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
import java.util.ArrayList;
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
 * <p>This is the bridge between Bazel-managed jar classpaths and the Quarkus bootstrap API. It
 * scans jars for extension metadata, registers dependencies with the correct flags, and configures
 * parent-first classloading for infrastructure jars.
 */
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods", "PMD.NcssCount"})
public final class QuarkusAppModelBuilder {

  private static final String JAR_TYPE = "jar";

  private QuarkusAppModelBuilder() {}

  /**
   * Builds an {@link ApplicationModel} from classpath jars, detecting Quarkus extensions and
   * setting the appropriate dependency flags.
   *
   * @param localAppJars local workspace jars — the first element becomes the app artifact, and
   *     remaining jars are added as runtime dependencies (they are also indexed via the application
   *     root {@code PathCollection} mechanism in the caller)
   * @param runtimeJars remaining runtime dependency jars (external Maven jars)
   * @param deployClasspath deployment-only jars
   * @param appName optional application name override
   * @param appVersion optional application version override
   * @return a fully configured ApplicationModel
   * @throws IOException if jar scanning fails
   */
  public static ApplicationModel build(
      List<Path> localAppJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      String appName,
      String appVersion)
      throws IOException {

    var modelBuilder = new ApplicationModelBuilder();
    Set<Path> extensionJars = registerExtensions(modelBuilder, runtimeJars);

    // Also scan deployment jars for runtime extensions that were pulled in as
    // transitive deps of deployment artifacts (e.g., quarkus-devui is a transitive
    // dep of quarkus-devui-deployment). These are "conditional dev dependencies"
    // that Maven's resolver adds automatically in dev mode.
    Set<String> knownExtensionArtifactIds = new HashSet<>();
    for (Path jar : extensionJars) {
      knownExtensionArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }
    Set<Path> deployExtensionJars =
        registerExtensions(
            modelBuilder,
            deployClasspath.stream()
                .filter(
                    jar ->
                        !knownExtensionArtifactIds.contains(
                            MavenCoordinateParser.parse(jar).artifactId()))
                .toList());
    extensionJars.addAll(deployExtensionJars);

    Path appJar = localAppJars.get(0);
    setAppArtifact(modelBuilder, appJar, appName, appVersion);

    // Add additional local app jars (index 1+) as runtime dependencies so they
    // appear in the model. They are also indexed via the application root
    // PathCollection mechanism in AugmentationExecutor.
    if (localAppJars.size() > 1) {
      addLocalAppJarDependencies(modelBuilder, localAppJars.subList(1, localAppJars.size()));
    }

    Set<ArtifactKey> addedKeys = addRuntimeDependencies(modelBuilder, runtimeJars, extensionJars);
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys, extensionJars);
    markParentFirstArtifacts(modelBuilder);
    fixRunnerParentFirstFlags(modelBuilder);
    populateDependencyLinks(modelBuilder, runtimeJars);
    // ApplicationModel.asMap() (used by ApplicationModelSerializer for JSON serialization)
    // calls getPlatforms().asMap() without a null check. Set an empty PlatformImports so
    // serialization works when no Quarkus platform BOMs are imported (Bazel-managed deps).
    modelBuilder.setPlatformImports(new PlatformImportsImpl());

    return modelBuilder.build();
  }

  // ---- Extension registration ----

  /**
   * Scans runtime jars for Quarkus extensions and registers their properties on the model.
   *
   * <p>This re-opens each extension jar after {@link ExtensionScanner} has identified it, because
   * the full {@code quarkus-extension.properties} content is needed by {@link
   * ApplicationModelBuilder#handleExtensionProperties} and for capability registration — the
   * scanner only extracts the {@code deployment-artifact} GAV.
   *
   * @return the set of jar paths that are Quarkus extensions (used to flag them later)
   */
  private static Set<Path> registerExtensions(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars) throws IOException {
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
        }
      }
    }
    return extensionJars;
  }

  /**
   * Registers extension capabilities (provides-capabilities / requires-capabilities).
   *
   * <p>The Maven/Gradle resolvers do this automatically, but since we bypass them and build the
   * ApplicationModel manually, we must do it ourselves. Without this, capabilities like VERTX_HTTP
   * won't be available to build steps.
   */
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

  /**
   * Builds an {@link ApplicationModel} for test mode, including a {@link WorkspaceModule} with test
   * sources so that {@code AppMakerHelper} can locate test classes.
   *
   * @param localAppJars local workspace jars — the first element becomes the app artifact, and
   *     remaining jars are added as runtime dependencies (they are also indexed via the application
   *     root {@code PathCollection} mechanism in the caller)
   * @param runtimeJars remaining runtime dependency jars (external Maven jars)
   * @param deployClasspath deployment-only jars
   * @param appName optional application name override
   * @param appVersion optional application version override
   * @return a fully configured ApplicationModel with workspace module for test mode
   */
  public static ApplicationModel buildForTest(
      List<Path> localAppJars,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      String appName,
      String appVersion)
      throws IOException {
    var modelBuilder = new ApplicationModelBuilder();

    // Scan extensions and register their metadata so the augmentation classloader
    // can correctly map runtime extensions to their deployment counterparts.
    // Without this, deployment processors (e.g., NarayanaJtaProcessor) won't be
    // discovered and critical beans (e.g., TransactionSessions) won't be registered.
    Set<Path> extensionJars = registerExtensions(modelBuilder, runtimeJars);

    // Also scan deployment jars for runtime extensions pulled in as transitive
    // deps of deployment artifacts (conditional dev dependencies).
    Set<String> knownExtensionGas = new HashSet<>();
    for (Path jar : extensionJars) {
      var coords = MavenCoordinateParser.parse(jar);
      knownExtensionGas.add(coords.groupId() + ":" + coords.artifactId());
    }
    Set<Path> deployExtensionJars =
        registerExtensions(
            modelBuilder,
            deployClasspath.stream()
                .filter(
                    jar -> {
                      var coords = MavenCoordinateParser.parse(jar);
                      return !knownExtensionGas.contains(
                          coords.groupId() + ":" + coords.artifactId());
                    })
                .toList());
    extensionJars.addAll(deployExtensionJars);

    Path appJar = localAppJars.get(0);
    setAppArtifactForTest(modelBuilder, appJar, appName, appVersion);

    // Add additional local app jars (index 1+) as runtime dependencies so they
    // appear in the model. They are also indexed via the application root
    // PathCollection mechanism in AugmentationExecutor.
    if (localAppJars.size() > 1) {
      addLocalAppJarDependencies(modelBuilder, localAppJars.subList(1, localAppJars.size()));
    }

    Set<ArtifactKey> addedKeys = addRuntimeDependencies(modelBuilder, runtimeJars, extensionJars);
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys, extensionJars);
    markParentFirstArtifacts(modelBuilder);
    fixRunnerParentFirstFlags(modelBuilder);
    // ApplicationModel.asMap() (used by ApplicationModelSerializer for JSON serialization)
    // calls getPlatforms().asMap() without a null check. Test-mode models are serialized too.
    modelBuilder.setPlatformImports(new PlatformImportsImpl());

    return modelBuilder.build();
  }

  // ---- Artifact registration ----

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
   * Sets the app artifact with a minimal WorkspaceModule for test mode.
   *
   * <p>The module is required (non-null) because {@code AppMakerHelper} calls {@code
   * getWorkspaceModule().getTestSources()}. Source dirs point to the jar's parent directory (a real
   * directory) rather than the jar itself, avoiding {@code NotDirectoryException}. Test sources are
   * left empty so the fallback path in {@code PathTestHelper} is used, which locates test classes
   * via the classloader (works with Bazel's jar-based classpath).
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

  /**
   * Adds additional local app jars (beyond the first one which is the app artifact) as runtime
   * dependencies in the model. These jars are local workspace modules that need to be on the
   * runtime and deployment classpaths so the augmentation classloader can find them. They are also
   * indexed via the application root {@code PathCollection} mechanism.
   */
  private static void addLocalAppJarDependencies(
      ApplicationModelBuilder modelBuilder, List<Path> additionalLocalJars) {
    for (Path jar : additionalLocalJars) {
      var coords = MavenCoordinateParser.parse(jar);
      modelBuilder.addDependency(
          ResolvedDependencyBuilder.newInstance()
              .setGroupId(coords.groupId())
              .setArtifactId(coords.artifactId())
              .setVersion(coords.version())
              .setType(JAR_TYPE)
              .setResolvedPath(jar)
              .setRuntimeCp()
              .setDeploymentCp()
              .setDirect(true));
    }
  }

  /** Adds runtime jars as dependencies, marking extension jars appropriately. */
  private static Set<ArtifactKey> addRuntimeDependencies(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars, Set<Path> extensionJars) {
    Set<ArtifactKey> addedKeys = new HashSet<>();
    for (Path jar : runtimeJars) {
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
      addedKeys.add(dep.getKey());
    }
    return addedKeys;
  }

  /**
   * Adds deployment-only jars, deduplicating by both ArtifactKey and artifactId to handle the same
   * jar resolved from different sources (e.g., @maven vs Coursier cache).
   *
   * <p>Jars that are Quarkus runtime extensions (identified by {@code extensionJars}) are marked
   * with both runtime and deployment classpath flags, since they are conditional dev dependencies
   * that need to be on the runtime classpath for the augment classloader.
   */
  private static void addDeploymentDependencies(
      ApplicationModelBuilder modelBuilder,
      List<Path> deployClasspath,
      Set<ArtifactKey> addedKeys,
      Set<Path> extensionJars) {
    Set<String> addedArtifactIds = new HashSet<>();
    for (var key : addedKeys) {
      addedArtifactIds.add(key.getArtifactId());
    }

    // Build a set of extension artifact IDs for path-independent matching
    // (deployment jars may come from Coursier cache, not the same path as scanned)
    Set<String> extensionArtifactIds = new HashSet<>();
    for (Path jar : extensionJars) {
      extensionArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }

    for (Path jar : deployClasspath) {
      var coords = MavenCoordinateParser.parse(jar);
      if (addedArtifactIds.contains(coords.artifactId())) {
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

      // Runtime extensions found in the deployment classpath (conditional dev deps)
      // need to be on both the runtime and deployment classpaths.
      if (extensionArtifactIds.contains(coords.artifactId())) {
        dep.setRuntimeCp().setRuntimeExtensionArtifact();
      }

      // Conditional dev runtime jars (e.g., quarkus-arc-dev, quarkus-rest-dev)
      // contain runtime classes referenced by generated code. They need to be
      // on the runtime classpath. We identify them by the "-dev" suffix pattern.
      if (coords.artifactId().endsWith("-dev")) {
        dep.setRuntimeCp();
      }

      // quarkus-devui-spi contains SPI classes (e.g., McpServerConfiguration) referenced
      // by generated ArC beans. It has no extension metadata so it won't be
      // auto-detected as a runtime extension, but it must be on the runtime
      // classpath so the generated code can load them.
      if ("quarkus-devui-spi".equals(coords.artifactId())) {
        dep.setRuntimeCp();
      }

      if (!addedKeys.contains(dep.getKey())) {
        modelBuilder.addDependency(dep);
        addedKeys.add(dep.getKey());
        addedArtifactIds.add(coords.artifactId());
      }
    }
  }

  // ---- Classloader configuration ----

  /**
   * Marks bootstrap and infrastructure jars as parent-first so the augment classloader delegates to
   * the parent for them. This prevents LinkageError and ClassCastException.
   *
   * <p>Jars containing CDI beans (e.g., {@code smallrye-config} which has {@code ConfigProducer})
   * must NOT be parent-first — ArC-generated proxies cause VerifyError across classloader
   * boundaries.
   */
  private static void markParentFirstArtifacts(ApplicationModelBuilder modelBuilder) {
    Set<String> parentFirstArtifactIds =
        Set.of(
            // Bootstrap infrastructure
            "quarkus-bootstrap-core",
            "quarkus-bootstrap-app-model",
            "quarkus-bootstrap-runner",
            "quarkus-classloader-commons",
            "quarkus-development-mode-spi",
            "quarkus-fs-util",
            "quarkus-bootstrap-maven-resolver",
            // Core (needed by IsolatedDevModeMain and test mode classloader delegation)
            "quarkus-core",
            // Value registry — must be parent-first to avoid LinkageError in dev mode.
            // ExtensionLoader (in augment classloader) calls ValueRegistryImpl$Builder.build()
            // which returns ValueRegistry; if ValueRegistry is loaded by two different
            // classloaders the JVM throws a loader constraint violation.
            "quarkus-value-registry",
            // Config infrastructure. Note: smallrye-config itself is NOT parent-first
            // because it contains CDI beans (ConfigProducer) whose ArC proxies cause
            // VerifyError across classloader boundaries. Only the core/common/api jars
            // (which don't contain CDI beans) are parent-first.
            "smallrye-config-core",
            "smallrye-config-common",
            "microprofile-config-api",
            // Logging
            "jboss-logmanager",
            "jboss-logging",
            "slf4j-api",
            "slf4j-jboss-logmanager",
            "jboss-logging-annotations",
            // Threading
            "jboss-threads",
            "wildfly-common",
            // SmallRye common (used by config)
            "smallrye-common-constraint",
            "smallrye-common-expression",
            "smallrye-common-function",
            "smallrye-common-classloader",
            "smallrye-common-io",
            "smallrye-common-annotation",
            "smallrye-common-cpu",
            "smallrye-common-net",
            "smallrye-common-os",
            "smallrye-common-ref",
            // JSON (used by config)
            "jakarta.json-api",
            "parsson",
            // Jakarta APIs (needed parent-first so that classes like
            // smallrye-common-annotation's Identifier$Literal, which extends
            // jakarta.enterprise.util.AnnotationLiteral, are loaded by the same
            // classloader — prevents VerifyError in dev mode)
            "jakarta.enterprise.cdi-api",
            "jakarta.enterprise.lang-model",
            "jakarta.inject-api",
            "jakarta.interceptor-api",
            "jakarta.annotation-api",
            "jakarta.el-api",
            "jakarta.transaction-api",
            // JUnit Platform + Jupiter API — must be parent-first so that
            // @Test and other JUnit annotations loaded by the QuarkusClassLoader
            // are the same class instances as those used by JUnit's engine
            // (which is loaded by the app classloader in ConsoleLauncher mode).
            "junit-jupiter-api",
            "junit-jupiter-engine",
            "junit-jupiter-params",
            "junit-platform-commons",
            "junit-platform-engine",
            "junit-platform-launcher",
            "opentest4j",
            // Other infrastructure
            "quarkus-ide-launcher",
            "crac",
            "commons-logging-jboss-logging");
    for (var dep : modelBuilder.getDependencies()) {
      if (parentFirstArtifactIds.contains(dep.getArtifactId())) {
        modelBuilder.addParentFirstArtifact(dep.getKey());
      }
    }
  }

  /**
   * Workaround for the GACT key mismatch bug.
   *
   * <p>{@code handleExtensionProperties} processes runner-parent-first-artifacts from
   * quarkus-extension.properties, but the GACT keys it creates have empty type while our
   * dependencies have type "jar". The keys don't match, so the {@code
   * CLASSLOADER_RUNNER_PARENT_FIRST} flag is never set by {@code buildDependencies()}. We fix this
   * by manually setting the flag based on artifactId matching.
   */
  private static void fixRunnerParentFirstFlags(ApplicationModelBuilder modelBuilder) {
    Set<String> runnerParentFirstArtifactIds =
        Set.of(
            "quarkus-bootstrap-runner",
            "quarkus-classloader-commons",
            "quarkus-development-mode-spi",
            "jboss-logmanager",
            "jboss-logging",
            "slf4j-jboss-logmanager",
            "slf4j-api",
            "smallrye-common-constraint",
            "smallrye-common-cpu",
            "smallrye-common-expression",
            "smallrye-common-function",
            "smallrye-common-io",
            "smallrye-common-net",
            "smallrye-common-os",
            "smallrye-common-ref",
            "crac");

    for (var dep : modelBuilder.getDependencies()) {
      if (runnerParentFirstArtifactIds.contains(dep.getArtifactId())) {
        dep.setFlags(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST);
      }
    }
  }

  // ---- Dependency graph links ----

  /**
   * Populates sub-dependency links on each {@link ResolvedDependencyBuilder} by reading the
   * embedded POM from each jar. This enables the Dev UI "Application Dependencies" graph to show
   * edges between nodes.
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
      var coords = MavenCoordinateParser.parse(jar);
      var dep = depsByGa.get(coords.groupId() + ":" + coords.artifactId());
      if (dep != null && dep.isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)) {
        runtimeExtensionGas.add(coords.groupId() + ":" + coords.artifactId());
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
        appDeps.add(
            ArtifactCoords.of(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier(),
                dep.getType(),
                dep.getVersion()));
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
          filtered.add(
              ArtifactCoords.of(
                  modelDep.getGroupId(),
                  modelDep.getArtifactId(),
                  modelDep.getClassifier(),
                  modelDep.getType(),
                  modelDep.getVersion()));
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
          appDeps.add(
              ArtifactCoords.of(
                  dep.getGroupId(),
                  dep.getArtifactId(),
                  dep.getClassifier(),
                  dep.getType(),
                  dep.getVersion()));
        } else {
          deploymentOrphans.add(ga);
        }
      }
    }
    if (!deploymentOrphans.isEmpty()) {
      var coreDeployment = depsByGa.get("io.quarkus:quarkus-core-deployment");
      if (coreDeployment != null) {
        List<ArtifactCoords> coreDeps = new ArrayList<>(coreDeployment.getDependencies());
        for (String orphanGa : deploymentOrphans) {
          if ("io.quarkus:quarkus-core-deployment".equals(orphanGa)) {
            continue;
          }
          var orphan = depsByGa.get(orphanGa);
          if (orphan != null) {
            coreDeps.add(
                ArtifactCoords.of(
                    orphan.getGroupId(),
                    orphan.getArtifactId(),
                    orphan.getClassifier(),
                    orphan.getType(),
                    orphan.getVersion()));
          }
        }
        coreDeployment.setDependencies(coreDeps);
      }
    }
  }

  /**
   * Reads the {@code <dependencies>} section from a jar's embedded POM using StAX streaming.
   * Returns an empty list if the POM is missing or cannot be parsed.
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
    List<ArtifactCoords> result = new ArrayList<>();
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    XMLStreamReader reader = factory.createXMLStreamReader(is);

    // Track nesting: only parse <dependency> elements directly under <dependencies>
    // that are NOT inside <dependencyManagement>
    boolean inDependencyManagement = false;
    boolean inDependencies = false;
    boolean inDependency = false;
    String depGroupId = "";
    String depArtifactId = "";
    String depVersion = "";
    String depScope = "";
    String currentElement = "";
    StringBuilder textBuffer = new StringBuilder();

    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamReader.START_ELEMENT) {
        String name = reader.getLocalName();
        if ("dependencyManagement".equals(name)) {
          inDependencyManagement = true;
        } else if ("dependencies".equals(name) && !inDependencyManagement) {
          inDependencies = true;
        } else if ("dependency".equals(name) && inDependencies && !inDependencyManagement) {
          inDependency = true;
          depGroupId = "";
          depArtifactId = "";
          depVersion = "";
          depScope = "";
        }
        currentElement = name;
        textBuffer.setLength(0);
      } else if (event == XMLStreamReader.END_ELEMENT) {
        String name = reader.getLocalName();
        if (inDependency && !currentElement.isEmpty()) {
          String text = textBuffer.toString().trim();
          if (!text.isEmpty()) {
            switch (currentElement) {
              case "groupId" -> depGroupId = text;
              case "artifactId" -> depArtifactId = text;
              case "version" -> depVersion = text;
              case "scope" -> depScope = text;
              default -> {
                /* ignore */
              }
            }
          }
        }
        if ("dependencyManagement".equals(name)) {
          inDependencyManagement = false;
        } else if ("dependencies".equals(name) && !inDependencyManagement) {
          inDependencies = false;
        } else if ("dependency".equals(name) && inDependency) {
          inDependency = false;
          // Only include compile and runtime scope (exclude test, provided, system)
          if (!depGroupId.isEmpty()
              && !depArtifactId.isEmpty()
              && (depScope.isEmpty() || "compile".equals(depScope) || "runtime".equals(depScope))) {
            String gid = resolveProperty(depGroupId, parentGroupId, parentVersion);
            String version = depVersion.isEmpty() ? "0" : depVersion;
            version = resolveProperty(version, parentGroupId, parentVersion);
            if (!gid.contains("$")) {
              result.add(ArtifactCoords.jar(gid, depArtifactId, version));
            }
          }
        }
        currentElement = "";
        textBuffer.setLength(0);
      } else if (event == XMLStreamReader.CHARACTERS && inDependency) {
        textBuffer.append(reader.getText());
      }
    }
    reader.close();
    return result;
  }

  private static String resolveProperty(String value, String groupId, String version) {
    if (value.isEmpty() || !value.contains("$")) {
      return value;
    }
    return value.replace("${project.groupId}", groupId).replace("${project.version}", version);
  }
}
