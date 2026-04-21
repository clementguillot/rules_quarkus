package com.clementguillot.quarkifier.model;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Builds a Quarkus {@link ApplicationModel} from classpath jars, bypassing Maven/Gradle resolution.
 *
 * <p>This is the bridge between Bazel-managed jar classpaths and the Quarkus bootstrap API. It
 * scans jars for extension metadata, registers dependencies with the correct flags, and configures
 * parent-first classloading for infrastructure jars.
 */
public final class QuarkusAppModelBuilder {

  private QuarkusAppModelBuilder() {}

  /**
   * Builds an {@link ApplicationModel} from classpath jars, detecting Quarkus extensions and
   * setting the appropriate dependency flags.
   *
   * @param appJar the application's own jar (first entry on the application classpath)
   * @param runtimeJars remaining runtime dependency jars
   * @param deployClasspath deployment-only jars
   * @param appName optional application name override
   * @param appVersion optional application version override
   * @return a fully configured ApplicationModel
   * @throws IOException if jar scanning fails
   */
  public static ApplicationModel build(
      Path appJar,
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
    Set<Path> deployExtensionJars = registerExtensions(modelBuilder, deployClasspath.stream()
        .filter(jar -> !knownExtensionArtifactIds.contains(MavenCoordinateParser.parse(jar).artifactId()))
        .toList());
    extensionJars.addAll(deployExtensionJars);

    setAppArtifact(modelBuilder, appJar, appName, appVersion);
    Set<ArtifactKey> addedKeys = addRuntimeDependencies(modelBuilder, runtimeJars, extensionJars);
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys, extensionJars);
    markParentFirstArtifacts(modelBuilder);
    fixRunnerParentFirstFlags(modelBuilder);

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

  // ---- Artifact registration ----

  private static void setAppArtifact(
      ApplicationModelBuilder modelBuilder, Path appJar, String appName, String appVersion) {
    var coords = MavenCoordinateParser.parse(appJar);
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(coords.groupId())
            .setArtifactId(appName != null ? appName : coords.artifactId())
            .setVersion(appVersion != null ? appVersion : coords.version())
            .setResolvedPath(appJar)
            .setRuntimeCp()
            .setDeploymentCp());
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
              .setResolvedPath(jar)
              .setRuntimeCp()
              .setDeploymentCp()
              .setDirect(true);
      if (extensionJars.contains(jar)) dep.setRuntimeExtensionArtifact();
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
    for (var key : addedKeys) addedArtifactIds.add(key.getArtifactId());

    // Build a set of extension artifact IDs for path-independent matching
    // (deployment jars may come from Coursier cache, not the same path as scanned)
    Set<String> extensionArtifactIds = new HashSet<>();
    for (Path jar : extensionJars) {
      extensionArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }

    for (Path jar : deployClasspath) {
      var coords = MavenCoordinateParser.parse(jar);
      if (addedArtifactIds.contains(coords.artifactId())) continue;

      var dep =
          ResolvedDependencyBuilder.newInstance()
              .setGroupId(coords.groupId())
              .setArtifactId(coords.artifactId())
              .setVersion(coords.version())
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
            // Core (needed by IsolatedDevModeMain)
            "quarkus-core",
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
   * dependencies have type "jar". The keys don't match, so the
   * {@code CLASSLOADER_RUNNER_PARENT_FIRST} flag is never set by {@code buildDependencies()}. We
   * fix this by manually setting the flag based on artifactId matching.
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
}
