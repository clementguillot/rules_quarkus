package com.clementguillot.quarkifier.model;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultSourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

/**
 * Builds a Quarkus {@link ApplicationModel} from classpath jars, bypassing Maven/Gradle resolution.
 *
 * <p>Scans jars for extension metadata, registers dependencies with the correct flags, and
 * configures parent-first classloading. Parent-first flags come from:
 *
 * <ol>
 *   <li><b>Extension metadata</b>: {@code parent-first-artifacts} and {@code
 *       runner-parent-first-artifacts} from each extension's {@code quarkus-extension.properties}.
 *   <li><b>Bootstrap inference</b>: BFS walk of quarkus-core's transitive dep tree via embedded
 *       POMs — mirrors how Maven/Gradle implicitly parent-first these via the dev mode launcher
 *       classpath (see {@link #computeBootstrapParentFirstGAs}).
 * </ol>
 */
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods", "PMD.NcssCount", "PMD.ExcessiveImports"})
public final class QuarkusAppModelBuilder {

  private static final String JAR_TYPE = "jar";
  private static final String QUARKUS_CORE_GA = "io.quarkus:quarkus-core";

  /**
   * Artifacts excluded from bootstrap parent-first inference. These contain CDI beans whose
   * ArC-generated proxies cause {@code VerifyError} if the bean class is on the parent classloader.
   * Their transitive deps are still walked and may be marked parent-first.
   */
  private static final Set<String> BOOTSTRAP_PARENT_FIRST_EXCLUSIONS =
      Set.of(
          // smallrye-config contains CDI beans (ConfigProducer, ConfigExtension) whose ArC
          // proxies cause VerifyError when the bean class is on the parent classloader.
          "io.smallrye.config:smallrye-config");

  /** Result of extension scanning: extension jar paths plus parent-first metadata. */
  private record ExtensionScanResult(
      Set<Path> extensionJars, Set<String> parentFirstGAs, Set<String> runnerParentFirstGAs) {}

  private QuarkusAppModelBuilder() {}

  /**
   * Builds an {@link ApplicationModel} from classpath jars.
   *
   * @param localAppJars local workspace jars — first element is the app artifact
   * @param runtimeJars external Maven dependency jars
   * @param deployClasspath deployment-only jars
   * @param appName optional application name override
   * @param appVersion optional application version override
   */
  public static ApplicationModel build(
      List<Path> localAppJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      String appName,
      String appVersion)
      throws IOException {
    var modelBuilder = new ApplicationModelBuilder();
    var scanResult =
        registerAllExtensions(
            modelBuilder,
            localAppJars.subList(1, localAppJars.size()),
            runtimeJars,
            deployClasspath);

    setAppArtifact(modelBuilder, localAppJars.get(0), appName, appVersion);
    registerDependencies(modelBuilder, localAppJars, runtimeJars, deployClasspath, scanResult);
    populateDependencyLinks(modelBuilder, runtimeJars);
    return finishModel(modelBuilder);
  }

  /**
   * Builds an {@link ApplicationModel} for test mode, with a {@link WorkspaceModule} so that {@code
   * AppMakerHelper} can locate test classes.
   */
  public static ApplicationModel buildForTest(
      List<Path> localAppJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      String appName,
      String appVersion)
      throws IOException {
    var modelBuilder = new ApplicationModelBuilder();
    var scanResult =
        registerAllExtensions(
            modelBuilder,
            localAppJars.subList(1, localAppJars.size()),
            runtimeJars,
            deployClasspath);
    setAppArtifactForTest(modelBuilder, localAppJars.get(0), appName, appVersion);
    registerDependencies(modelBuilder, localAppJars, runtimeJars, deployClasspath, scanResult);
    return finishModel(modelBuilder);
  }

  /** Shared tail of both build pipelines. */
  private static ApplicationModel finishModel(ApplicationModelBuilder modelBuilder) {
    // ApplicationModel.asMap() (used by ApplicationModelSerializer for JSON serialization)
    // calls getPlatforms().asMap() without a null check. Set an empty PlatformImports so
    // serialization works when no Quarkus platform BOMs are imported (Bazel-managed deps).
    modelBuilder.setPlatformImports(new PlatformImportsImpl());
    return modelBuilder.build();
  }

  // ---- Extension registration ----

  /**
   * Registers extensions and collects parent-first metadata. Also scans deployment jars for runtime
   * extensions pulled in as transitive deps (conditional dev dependencies).
   */
  private static ExtensionScanResult registerAllExtensions(
      ApplicationModelBuilder modelBuilder,
      List<Path> localDependencyJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath)
      throws IOException {
    Set<String> parentFirstGAs = new HashSet<>();
    Set<String> runnerParentFirstGAs = new HashSet<>();

    List<Path> scanJars = new ArrayList<>(localDependencyJars);
    scanJars.addAll(runtimeJars);
    Set<Path> extensionJars =
        registerExtensions(modelBuilder, scanJars, parentFirstGAs, runnerParentFirstGAs);

    Set<String> knownExtensionGas = new HashSet<>();
    for (Path jar : extensionJars) {
      knownExtensionGas.add(groupArtifact(jar));
    }
    List<Path> unknownDeployJars =
        deployClasspath.stream()
            .filter(jar -> !knownExtensionGas.contains(groupArtifact(jar)))
            .toList();
    extensionJars.addAll(
        registerExtensions(modelBuilder, unknownDeployJars, parentFirstGAs, runnerParentFirstGAs));
    return new ExtensionScanResult(extensionJars, parentFirstGAs, runnerParentFirstGAs);
  }

  private static String groupArtifact(Path jar) {
    var coords = MavenCoordinateParser.parse(jar);
    return coords.groupId() + ":" + coords.artifactId();
  }

  /**
   * Scans jars for Quarkus extensions, registers their properties, and collects parent-first
   * metadata. We collect parent-first GAs ourselves (rather than relying on {@code
   * buildDependencies()}) because the GACT key type mismatch prevents the standard lookup.
   */
  private static Set<Path> registerExtensions(
      ApplicationModelBuilder modelBuilder,
      List<Path> runtimeJars,
      Set<String> parentFirstGAs,
      Set<String> runnerParentFirstGAs)
      throws IOException {
    Set<Path> extensionJars = new HashSet<>();
    for (ExtensionInfo ext : ExtensionScanner.scan(runtimeJars)) {
      extensionJars.add(ext.sourceJar());
      try (var jf = new JarFile(ext.sourceJar().toFile())) {
        var entry = jf.getEntry("META-INF/quarkus-extension.properties");
        if (entry != null) {
          var props = new Properties();
          try (var is = jf.getInputStream(entry)) {
            props.load(is);
          }
          modelBuilder.handleExtensionProperties(
              props, ArtifactKey.ga(ext.groupId(), ext.artifactId()));

          registerCapabilities(modelBuilder, props, ext);
          collectParentFirstMetadata(props, parentFirstGAs, runnerParentFirstGAs);
        }
      }
    }
    return extensionJars;
  }

  /**
   * Parses {@code parent-first-artifacts} and {@code runner-parent-first-artifacts} from extension
   * properties and adds them to the provided accumulator sets.
   */
  private static void collectParentFirstMetadata(
      Properties props, Set<String> parentFirstGAs, Set<String> runnerParentFirstGAs) {
    String parentFirst = props.getProperty("parent-first-artifacts");
    if (parentFirst != null && !parentFirst.isBlank()) {
      for (String ga : parentFirst.split(",")) {
        String trimmed = ga.trim();
        if (!trimmed.isEmpty()) {
          parentFirstGAs.add(trimmed);
        }
      }
    }
    String runnerParentFirst = props.getProperty("runner-parent-first-artifacts");
    if (runnerParentFirst != null && !runnerParentFirst.isBlank()) {
      for (String ga : runnerParentFirst.split(",")) {
        String trimmed = ga.trim();
        if (!trimmed.isEmpty()) {
          runnerParentFirstGAs.add(trimmed);
        }
      }
    }
  }

  /** Registers extension capabilities so build steps can query them. */
  private static void registerCapabilities(
      ApplicationModelBuilder modelBuilder, Properties props, ExtensionInfo ext) {
    String providesCapabilities = props.getProperty("provides-capabilities");
    String requiresCapabilities = props.getProperty("requires-capabilities");
    if (providesCapabilities != null || requiresCapabilities != null) {
      String compactCoords = ext.groupId() + ":" + ext.artifactId();
      modelBuilder.addExtensionCapabilities(
          CapabilityContract.of(compactCoords, providesCapabilities, requiresCapabilities));
    }
  }

  // ---- Artifact registration ----

  /** Registers all non-app-artifact dependencies and configures classloading flags. */
  private static void registerDependencies(
      ApplicationModelBuilder modelBuilder,
      List<Path> localAppJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      ExtensionScanResult scanResult) {
    if (localAppJars.size() > 1) {
      addLocalAppJarDependencies(
          modelBuilder, localAppJars.subList(1, localAppJars.size()), scanResult.extensionJars());
    }
    Set<ArtifactKey> addedKeys =
        addRuntimeDependencies(modelBuilder, runtimeJars, scanResult.extensionJars());
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys, scanResult.extensionJars());
    applyParentFirstFromMetadata(
        modelBuilder, scanResult.parentFirstGAs(), scanResult.runnerParentFirstGAs());
  }

  private static void setAppArtifact(
      ApplicationModelBuilder modelBuilder, Path appJar, String appName, String appVersion) {
    var coords = MavenCoordinateParser.parse(appJar);
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(coords.groupId())
            .setArtifactId(appName != null ? appName : coords.artifactId())
            .setVersion(appVersion != null ? appVersion : coords.version())
            .setType(JAR_TYPE)
            .setResolvedPath(appJar)
            .setRuntimeCp()
            .setDeploymentCp());
  }

  /**
   * Sets the app artifact with a WorkspaceModule for test mode. Required because {@code
   * AppMakerHelper} calls {@code getWorkspaceModule().getTestSources()}.
   */
  private static void setAppArtifactForTest(
      ApplicationModelBuilder modelBuilder, Path appJar, String appName, String appVersion) {
    var coords = MavenCoordinateParser.parse(appJar);
    Path jarDir = appJar.getParent() != null ? appJar.getParent() : appJar;

    String artifactId = appName != null ? appName : coords.artifactId();
    String version = appVersion != null ? appVersion : coords.version();
    WorkspaceModule module =
        WorkspaceModule.builder()
            .setModuleId(WorkspaceModuleId.of(coords.groupId(), artifactId, version))
            .setModuleDir(jarDir)
            .setBuildDir(jarDir)
            .setBuildFile(jarDir.resolve("BUILD.bazel"))
            .addArtifactSources(
                new DefaultArtifactSources(
                    ArtifactSources.MAIN,
                    List.of(new DefaultSourceDir(jarDir, jarDir, null)),
                    List.of()))
            .build();

    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(coords.groupId())
            .setArtifactId(artifactId)
            .setVersion(version)
            .setType(JAR_TYPE)
            .setResolvedPath(appJar)
            .setWorkspaceModule(module)
            .setRuntimeCp()
            .setDeploymentCp());
  }

  /** Adds additional local app jars (beyond the app artifact) as runtime dependencies. */
  private static void addLocalAppJarDependencies(
      ApplicationModelBuilder modelBuilder,
      List<Path> additionalLocalJars,
      Set<Path> extensionJars) {
    for (Path jar : additionalLocalJars) {
      addDependencyToModelBuilder(modelBuilder, extensionJars, jar);
    }
  }

  /** Adds runtime jars as dependencies, marking extension jars appropriately. */
  private static Set<ArtifactKey> addRuntimeDependencies(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars, Set<Path> extensionJars) {
    Set<ArtifactKey> addedKeys = new HashSet<>();
    for (Path jar : runtimeJars) {
      var dep = addDependencyToModelBuilder(modelBuilder, extensionJars, jar);
      addedKeys.add(dep.getKey());
    }
    return addedKeys;
  }

  private static ResolvedDependencyBuilder addDependencyToModelBuilder(
      ApplicationModelBuilder modelBuilder, Set<Path> extensionJars, Path jar) {
    var coords = MavenCoordinateParser.parse(jar);
    var dep =
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(coords.groupId())
            .setArtifactId(coords.artifactId())
            .setVersion(coords.version())
            .setType(JAR_TYPE)
            .setResolvedPath(jar)
            .setRuntimeCp()
            .setDeploymentCp()
            .setDirect(true);
    if (extensionJars.contains(jar)) {
      dep.setRuntimeExtensionArtifact();
    }
    modelBuilder.addDependency(dep);
    return dep;
  }

  /**
   * Adds deployment-only jars, deduplicating by GA. Extension jars found here are marked as runtime
   * too (conditional dev dependencies).
   */
  private static void addDeploymentDependencies(
      ApplicationModelBuilder modelBuilder,
      List<Path> deployClasspath,
      Set<ArtifactKey> addedKeys,
      Set<Path> extensionJars) {
    Set<String> addedGas = new HashSet<>();
    for (var key : addedKeys) {
      addedGas.add(key.getGroupId() + ":" + key.getArtifactId());
    }

    // Extension artifact IDs for path-independent matching (deployment jars may
    // come from the Coursier cache, not the same path as the scanned jar).
    Set<String> extensionGas = new HashSet<>();
    for (Path jar : extensionJars) {
      extensionGas.add(groupArtifact(jar));
    }

    for (Path jar : deployClasspath) {
      var coords = MavenCoordinateParser.parse(jar);
      String ga = coords.groupId() + ":" + coords.artifactId();
      if (addedGas.contains(ga)) {
        continue;
      }

      var dep =
          ResolvedDependencyBuilder.newInstance()
              .setGroupId(coords.groupId())
              .setArtifactId(coords.artifactId())
              .setVersion(coords.version())
              .setType(JAR_TYPE)
              .setResolvedPath(jar)
              .setDeploymentCp()
              .setDirect(true);
      applyDeploymentRuntimeFlags(dep, ga, coords.artifactId(), extensionGas);

      if (!addedKeys.contains(dep.getKey())) {
        modelBuilder.addDependency(dep);
        addedKeys.add(dep.getKey());
        addedGas.add(ga);
      }
    }
  }

  /** Puts deployment-classpath jars that contain runtime classes onto the runtime classpath too. */
  private static void applyDeploymentRuntimeFlags(
      ResolvedDependencyBuilder dep, String ga, String artifactId, Set<String> extensionGas) {
    // Runtime extensions found in the deployment classpath (conditional dev deps)
    // need to be on both the runtime and deployment classpaths.
    if (extensionGas.contains(ga)) {
      dep.setRuntimeCp().setRuntimeExtensionArtifact();
    }

    // Conditional dev runtime jars (e.g., quarkus-arc-dev, quarkus-rest-dev)
    // contain runtime classes referenced by generated code.
    if (artifactId.endsWith("-dev")) {
      dep.setRuntimeCp();
    }

    // quarkus-devui-spi contains SPI classes (e.g., McpServerConfiguration) referenced
    // by generated ArC beans. It has no extension metadata so it won't be
    // auto-detected as a runtime extension, but it must be on the runtime
    // classpath so the generated code can load them.
    if ("quarkus-devui-spi".equals(artifactId)) {
      dep.setRuntimeCp();
    }
  }

  // ---- Classloader configuration ----

  /**
   * Applies parent-first and runner-parent-first flags using GA-based matching (avoids the GACT key
   * type mismatch in {@code buildDependencies()}).
   */
  private static void applyParentFirstFromMetadata(
      ApplicationModelBuilder modelBuilder,
      Set<String> metadataParentFirstGAs,
      Set<String> metadataRunnerParentFirstGAs) {
    // Build a GA → dependency index for type-agnostic lookup
    Map<String, ResolvedDependencyBuilder> depsByGa = new HashMap<>();
    for (var dep : modelBuilder.getDependencies()) {
      depsByGa.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
    }

    // Compute bootstrap parent-first GAs by walking quarkus-core's transitive dep tree
    Set<String> bootstrapParentFirstGAs = computeBootstrapParentFirstGAs(depsByGa);

    // Merge metadata-declared parent-first with bootstrap-inferred set
    Set<String> allParentFirstGAs = new HashSet<>(metadataParentFirstGAs);
    allParentFirstGAs.addAll(bootstrapParentFirstGAs);

    // Apply CLASSLOADER_PARENT_FIRST
    for (String ga : allParentFirstGAs) {
      var dep = depsByGa.get(ga);
      if (dep != null) {
        dep.setFlags(DependencyFlags.CLASSLOADER_PARENT_FIRST);
        modelBuilder.addParentFirstArtifact(dep.getKey());
      }
    }

    // Apply CLASSLOADER_RUNNER_PARENT_FIRST from metadata only (no supplementary needed)
    for (String ga : metadataRunnerParentFirstGAs) {
      var dep = depsByGa.get(ga);
      if (dep != null) {
        dep.setFlags(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST);
      }
    }
  }

  /**
   * BFS walk of quarkus-core's transitive dep tree to find bootstrap parent-first artifacts.
   *
   * <p>In Maven/Gradle, these are implicitly parent-first (on the launcher classpath before the
   * classloader split). In Bazel we must flag them explicitly.
   *
   * <p>Excludes runtime extensions (own metadata) and {@link #BOOTSTRAP_PARENT_FIRST_EXCLUSIONS}
   * (CDI bean jars). Excluded jars' sub-deps are still walked.
   */
  private static Set<String> computeBootstrapParentFirstGAs(
      Map<String, ResolvedDependencyBuilder> depsByGa) {
    var quarkusCore = depsByGa.get(QUARKUS_CORE_GA);
    if (quarkusCore == null) {
      return Set.of();
    }

    Set<String> bootstrapGAs = new HashSet<>();
    bootstrapGAs.add(QUARKUS_CORE_GA);

    Set<String> visited = new HashSet<>();
    visited.add(QUARKUS_CORE_GA);
    Deque<String> queue = new ArrayDeque<>();
    queue.add(QUARKUS_CORE_GA);

    while (!queue.isEmpty()) {
      String ga = queue.poll();
      var dep = depsByGa.get(ga);
      if (dep == null) {
        continue;
      }

      // Read this jar's embedded POM to discover its compile/runtime deps
      var resolvedPaths = dep.getResolvedPaths();
      if (resolvedPaths == null || resolvedPaths.isEmpty()) {
        continue;
      }
      Path jarPath = resolvedPaths.iterator().next();
      if (!jarPath.toString().endsWith(".jar")) {
        continue;
      }

      List<ArtifactCoords> subDeps =
          readDependenciesFromJar(jarPath, dep.getGroupId(), dep.getArtifactId());
      for (ArtifactCoords subDep : subDeps) {
        String subGa = subDep.getGroupId() + ":" + subDep.getArtifactId();
        if (visited.contains(subGa)) {
          continue;
        }
        visited.add(subGa);

        // Always enqueue for further walking (even excluded jars' sub-deps may be needed)
        queue.add(subGa);

        // Skip runtime extensions — their own metadata handles classloading
        var modelDep = depsByGa.get(subGa);
        if (modelDep != null && modelDep.isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)) {
          continue;
        }

        // Skip exclusions (CDI bean jars that cause VerifyError when parent-first)
        if (BOOTSTRAP_PARENT_FIRST_EXCLUSIONS.contains(subGa)) {
          continue;
        }

        // Mark as bootstrap parent-first if it's in our model
        if (modelDep != null) {
          bootstrapGAs.add(subGa);
        }
      }
    }

    return bootstrapGAs;
  }

  // ---- Dependency graph links ----

  /**
   * Populates sub-dependency links by reading embedded POMs. Enables the Dev UI dependency graph.
   */
  private static void populateDependencyLinks(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars) {
    Map<String, ResolvedDependencyBuilder> depsByGa = buildGaIndex(modelBuilder);
    List<ArtifactCoords> appDeps = computeRootEdges(modelBuilder, runtimeJars, depsByGa);
    wireSubDependencies(modelBuilder, depsByGa);
    adoptOrphans(modelBuilder, depsByGa, appDeps);
    modelBuilder.getApplicationArtifact().setDependencies(appDeps);
  }

  /** Builds a lookup of all model dependencies keyed by "groupId:artifactId". */
  private static Map<String, ResolvedDependencyBuilder> buildGaIndex(
      ApplicationModelBuilder modelBuilder) {
    Map<String, ResolvedDependencyBuilder> depsByGa = new HashMap<>();
    for (var dep : modelBuilder.getDependencies()) {
      depsByGa.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
    }
    return depsByGa;
  }

  private static ArtifactCoords toCoords(ResolvedDependencyBuilder dep) {
    return ArtifactCoords.of(
        dep.getGroupId(),
        dep.getArtifactId(),
        dep.getClassifier(),
        dep.getType(),
        dep.getVersion());
  }

  /**
   * Computes root-node edges: runtime extensions and their deployment counterparts become direct
   * dependencies of the application artifact.
   */
  private static List<ArtifactCoords> computeRootEdges(
      ApplicationModelBuilder modelBuilder,
      List<Path> runtimeJars,
      Map<String, ResolvedDependencyBuilder> depsByGa) {
    Set<String> runtimeExtensionGas = new HashSet<>();
    for (Path jar : runtimeJars) {
      String ga = groupArtifact(jar);
      var dep = depsByGa.get(ga);
      if (dep != null && dep.isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)) {
        runtimeExtensionGas.add(ga);
      }
    }
    List<ArtifactCoords> appDeps = new ArrayList<>();
    for (var dep : modelBuilder.getDependencies()) {
      String ga = dep.getGroupId() + ":" + dep.getArtifactId();
      String aid = dep.getArtifactId();
      if (runtimeExtensionGas.contains(ga)
          || (aid.endsWith("-deployment")
              && runtimeExtensionGas.contains(
                  dep.getGroupId()
                      + ":"
                      + aid.substring(0, aid.length() - "-deployment".length())))) {
        appDeps.add(toCoords(dep));
      }
    }
    return appDeps;
  }

  /** Reads embedded POMs and wires sub-dependency links on each model dependency. */
  private static void wireSubDependencies(
      ApplicationModelBuilder modelBuilder, Map<String, ResolvedDependencyBuilder> depsByGa) {
    for (var dep : modelBuilder.getDependencies()) {
      var resolvedPaths = dep.getResolvedPaths();
      if (resolvedPaths == null || resolvedPaths.isEmpty()) {
        continue;
      }
      Path jarPath = resolvedPaths.iterator().next();
      if (!jarPath.toString().endsWith(".jar")) {
        continue;
      }
      List<ArtifactCoords> subDeps =
          readDependenciesFromJar(jarPath, dep.getGroupId(), dep.getArtifactId());
      List<ArtifactCoords> filtered = new ArrayList<>();
      for (ArtifactCoords subDep : subDeps) {
        var modelDep = depsByGa.get(subDep.getGroupId() + ":" + subDep.getArtifactId());
        if (modelDep != null && modelDep != dep) {
          filtered.add(toCoords(modelDep));
        }
      }
      if (!filtered.isEmpty()) {
        dep.setDependencies(filtered);
      }
    }
  }

  /**
   * Finds orphan nodes (no incoming edges) and attaches them. Runtime orphans become direct deps of
   * the root; deployment-only orphans are adopted by quarkus-core-deployment.
   */
  private static void adoptOrphans(
      ApplicationModelBuilder modelBuilder,
      Map<String, ResolvedDependencyBuilder> depsByGa,
      List<ArtifactCoords> appDeps) {
    Set<String> allTargets = new HashSet<>();
    for (var dep : modelBuilder.getDependencies()) {
      for (ArtifactCoords subDep : dep.getDependencies()) {
        allTargets.add(subDep.getGroupId() + ":" + subDep.getArtifactId());
      }
    }
    for (ArtifactCoords c : appDeps) {
      allTargets.add(c.getGroupId() + ":" + c.getArtifactId());
    }

    List<String> deploymentOrphans = new ArrayList<>();
    for (var dep : modelBuilder.getDependencies()) {
      String ga = dep.getGroupId() + ":" + dep.getArtifactId();
      if (!allTargets.contains(ga)) {
        if (dep.isFlagSet(DependencyFlags.RUNTIME_CP)) {
          appDeps.add(toCoords(dep));
        } else {
          deploymentOrphans.add(ga);
        }
      }
    }
    adoptDeploymentOrphans(depsByGa, deploymentOrphans);
  }

  /** Attaches deployment-only orphan nodes under quarkus-core-deployment. */
  private static void adoptDeploymentOrphans(
      Map<String, ResolvedDependencyBuilder> depsByGa, List<String> deploymentOrphans) {
    if (deploymentOrphans.isEmpty()) {
      return;
    }
    var coreDeployment = depsByGa.get("io.quarkus:quarkus-core-deployment");
    if (coreDeployment == null) {
      return;
    }
    List<ArtifactCoords> coreDeps = new ArrayList<>(coreDeployment.getDependencies());
    for (String orphanGa : deploymentOrphans) {
      if ("io.quarkus:quarkus-core-deployment".equals(orphanGa)) {
        continue;
      }
      var orphan = depsByGa.get(orphanGa);
      if (orphan != null) {
        coreDeps.add(toCoords(orphan));
      }
    }
    coreDeployment.setDependencies(coreDeps);
  }

  /**
   * Reads the {@code <dependencies>} section from a jar's embedded POM using StAX streaming.
   * Returns an empty list if the POM is missing or cannot be parsed — the dependency graph is
   * best-effort Dev UI metadata.
   */
  private static List<ArtifactCoords> readDependenciesFromJar(
      Path jarPath, String groupId, String artifactId) {
    String pomPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml";
    try (var jf = new JarFile(jarPath.toFile())) {
      var entry = jf.getEntry(pomPath);
      if (entry == null) {
        return List.of();
      }
      // Read version from pom.properties for accurate ${project.version} resolution
      String version = "0";
      var propsEntry =
          jf.getEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
      if (propsEntry != null) {
        var props = new Properties();
        try (InputStream pis = jf.getInputStream(propsEntry)) {
          props.load(pis);
        }
        version = props.getProperty("version", version);
      }
      try (InputStream is = jf.getInputStream(entry)) {
        return parsePomDependencies(is, groupId, version);
      }
    } catch (Exception ignored) {
      return List.of();
    }
  }

  /** Parses compile/runtime dependencies from a POM input stream using StAX. */
  private static List<ArtifactCoords> parsePomDependencies(
      InputStream is, String parentGroupId, String parentVersion) throws Exception {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    XMLStreamReader reader = factory.createXMLStreamReader(is);
    var parser = new PomDependenciesParser(parentGroupId, parentVersion);
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamReader.START_ELEMENT) {
        parser.onStartElement(reader.getLocalName());
      } else if (event == XMLStreamReader.END_ELEMENT) {
        parser.onEndElement(reader.getLocalName());
      } else if (event == XMLStreamReader.CHARACTERS) {
        parser.onText(reader.getText());
      }
    }
    reader.close();
    return parser.dependencies();
  }

  private static String resolveProperty(String value, String groupId, String version) {
    if (value.isEmpty() || !value.contains("$")) {
      return value;
    }
    return value.replace("${project.groupId}", groupId).replace("${project.version}", version);
  }

  /**
   * StAX state machine collecting {@code <dependency>} elements directly under {@code
   * <dependencies>}. Skips {@code <dependencyManagement>}, {@code <exclusions>} (whose
   * groupId/artifactId children must not overwrite the dependency's own coordinates), and
   * test/provided/system scopes.
   */
  private static final class PomDependenciesParser {
    private final String parentGroupId;
    private final String parentVersion;
    private final List<ArtifactCoords> collected = new ArrayList<>();

    // Short-lived per-POM accumulator; the parser never outlives a single parse call.
    @SuppressWarnings("PMD.AvoidStringBufferField")
    private final StringBuilder text = new StringBuilder();

    private boolean inDependencyManagement;
    private boolean inDependencies;
    private boolean inDependency;
    private boolean inExclusions;
    private String currentElement = "";
    private String groupId = "";
    private String artifactId = "";
    private String version = "";
    private String scope = "";

    PomDependenciesParser(String parentGroupId, String parentVersion) {
      this.parentGroupId = parentGroupId;
      this.parentVersion = parentVersion;
    }

    List<ArtifactCoords> dependencies() {
      return collected;
    }

    void onStartElement(String name) {
      if ("dependencyManagement".equals(name)) {
        inDependencyManagement = true;
      } else if ("dependencies".equals(name) && !inDependencyManagement) {
        inDependencies = true;
      } else if ("dependency".equals(name) && inDependencies && !inDependencyManagement) {
        inDependency = true;
        groupId = "";
        artifactId = "";
        version = "";
        scope = "";
      } else if ("exclusions".equals(name)) {
        inExclusions = true;
      }
      currentElement = name;
      text.setLength(0);
    }

    void onText(String chars) {
      if (inDependency && !inExclusions) {
        text.append(chars);
      }
    }

    void onEndElement(String name) {
      if (inDependency && !inExclusions && !currentElement.isEmpty()) {
        recordField(text.toString().trim());
      }
      if ("dependencyManagement".equals(name)) {
        inDependencyManagement = false;
      } else if ("exclusions".equals(name)) {
        inExclusions = false;
      } else if ("dependencies".equals(name) && !inDependencyManagement) {
        inDependencies = false;
      } else if ("dependency".equals(name) && inDependency && !inExclusions) {
        inDependency = false;
        addDependencyIfRelevant();
      }
      currentElement = "";
      text.setLength(0);
    }

    private void recordField(String value) {
      if (value.isEmpty()) {
        return;
      }
      switch (currentElement) {
        case "groupId" -> groupId = value;
        case "artifactId" -> artifactId = value;
        case "version" -> version = value;
        case "scope" -> scope = value;
        default -> {
          /* ignore */
        }
      }
    }

    private void addDependencyIfRelevant() {
      // Only compile and runtime scopes (exclude test, provided, system).
      boolean relevantScope = scope.isEmpty() || "compile".equals(scope) || "runtime".equals(scope);
      if (groupId.isEmpty() || artifactId.isEmpty() || !relevantScope) {
        return;
      }
      String gid = resolveProperty(groupId, parentGroupId, parentVersion);
      String resolvedVersion =
          resolveProperty(version.isEmpty() ? "0" : version, parentGroupId, parentVersion);
      if (!gid.contains("$")) {
        collected.add(ArtifactCoords.jar(gid, artifactId, resolvedVersion));
      }
    }
  }
}
