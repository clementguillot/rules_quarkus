package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds an {@link ApplicationModel} from classpath jars, detecting Quarkus extensions and setting
 * the appropriate dependency flags.
 *
 * <p>Bypasses Maven/Gradle resolution — coordinates are inferred from jar paths via {@link
 * MavenCoordinateParser}.
 */
public final class ApplicationModelFactory {

  // @formatter:off
  private static final Set<String> PARENT_FIRST_ARTIFACT_IDS = Set.of(
      "quarkus-bootstrap-core", "quarkus-bootstrap-app-model",
      "quarkus-bootstrap-runner", "quarkus-classloader-commons",
      "quarkus-development-mode-spi", "quarkus-fs-util",
      "quarkus-bootstrap-maven-resolver",
      "quarkus-core",
      "smallrye-config", "smallrye-config-core", "smallrye-config-common",
      "microprofile-config-api",
      "jboss-logmanager", "jboss-logging", "slf4j-api",
      "slf4j-jboss-logmanager", "jboss-logging-annotations",
      "jboss-threads", "wildfly-common",
      "smallrye-common-constraint", "smallrye-common-expression",
      "smallrye-common-function", "smallrye-common-classloader",
      "smallrye-common-io", "smallrye-common-annotation",
      "smallrye-common-cpu", "smallrye-common-net",
      "smallrye-common-os", "smallrye-common-ref",
      "jakarta.json-api", "parsson",
      "jakarta.enterprise.cdi-api", "jakarta.enterprise.lang-model",
      "jakarta.inject-api", "jakarta.interceptor-api",
      "jakarta.annotation-api", "jakarta.el-api",
      "jakarta.transaction-api",
      "quarkus-ide-launcher", "org-crac",
      "commons-logging-jboss-logging",
      "maven-resolver-api", "maven-resolver-spi", "maven-resolver-impl",
      "maven-resolver-util", "maven-resolver-connector-basic",
      "maven-resolver-transport-file", "maven-resolver-transport-http",
      "maven-resolver-named-locks", "maven-resolver-supplier",
      "maven-model", "maven-model-builder", "maven-repository-metadata",
      "maven-builder-support", "maven-artifact",
      "maven-settings", "maven-settings-builder"
  );

  private static final Set<String> RUNNER_PARENT_FIRST_ARTIFACT_IDS = Set.of(
      "quarkus-bootstrap-runner", "quarkus-classloader-commons",
      "quarkus-development-mode-spi",
      "jboss-logmanager", "jboss-logging",
      "slf4j-jboss-logmanager", "slf4j-api",
      "smallrye-common-constraint", "smallrye-common-cpu",
      "smallrye-common-expression", "smallrye-common-function",
      "smallrye-common-io", "smallrye-common-net",
      "smallrye-common-os", "smallrye-common-ref",
      "org-crac"
  );
  // @formatter:on

  private ApplicationModelFactory() {}

  /**
   * Builds an {@link ApplicationModel} from classpath jars.
   *
   * @param appJar the application jar (first entry on the application classpath)
   * @param runtimeJars remaining runtime jars
   * @param deployClasspath deployment-only jars
   * @param extensions pre-scanned extension list (avoids duplicate scanning)
   * @param appName application name override (may be {@code null})
   * @param appVersion application version override (may be {@code null})
   */
  public static ApplicationModel build(
      Path appJar,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      List<ExtensionInfo> extensions,
      String appName,
      String appVersion)
      throws IOException {

    var modelBuilder = new ApplicationModelBuilder();
    Set<Path> extensionJars = registerExtensions(modelBuilder, runtimeJars, extensions);
    setAppArtifact(modelBuilder, appJar, appName, appVersion);
    Set<ArtifactKey> addedKeys = addRuntimeDependencies(modelBuilder, runtimeJars, extensionJars);
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys);

    for (var dep : modelBuilder.getDependencies()) {
      if (PARENT_FIRST_ARTIFACT_IDS.contains(dep.getArtifactId())) {
        modelBuilder.addParentFirstArtifact(dep.getKey());
      }
    }

    // Workaround: handleExtensionProperties processes runner-parent-first-artifacts
    // from quarkus-extension.properties, but the GACT keys it creates have empty type
    // while our dependencies have type "jar". The keys don't match, so the
    // CLASSLOADER_RUNNER_PARENT_FIRST flag is never set by buildDependencies().
    for (var dep : modelBuilder.getDependencies()) {
      if (RUNNER_PARENT_FIRST_ARTIFACT_IDS.contains(dep.getArtifactId())) {
        dep.setFlags(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST);
      }
    }

    return modelBuilder.build();
  }

  private static Set<Path> registerExtensions(
      ApplicationModelBuilder modelBuilder,
      List<Path> runtimeJars,
      List<ExtensionInfo> extensions)
      throws IOException {
    Set<Path> extensionJars = new HashSet<>();
    for (ExtensionInfo ext : extensions) {
      extensionJars.add(ext.sourceJar());
      try (var jf = new java.util.jar.JarFile(ext.sourceJar().toFile())) {
        var entry = jf.getEntry("META-INF/quarkus-extension.properties");
        if (entry != null) {
          var props = new java.util.Properties();
          try (var is = jf.getInputStream(entry)) {
            props.load(is);
          }
          modelBuilder.handleExtensionProperties(
              props, ArtifactKey.ga(ext.groupId(), ext.artifactId()));

          String providesCapabilities = props.getProperty("provides-capabilities");
          String requiresCapabilities = props.getProperty("requires-capabilities");
          if (providesCapabilities != null || requiresCapabilities != null) {
            String compactCoords = ext.groupId() + ":" + ext.artifactId();
            modelBuilder.addExtensionCapabilities(
                io.quarkus.bootstrap.model.CapabilityContract.of(
                    compactCoords, providesCapabilities, requiresCapabilities));
          }
        }
      }
    }
    return extensionJars;
  }

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

  private static void addDeploymentDependencies(
      ApplicationModelBuilder modelBuilder,
      List<Path> deployClasspath,
      Set<ArtifactKey> addedKeys) {
    Set<String> addedArtifactIds = new HashSet<>();
    for (var key : addedKeys) addedArtifactIds.add(key.getArtifactId());

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

      if (!addedKeys.contains(dep.getKey())) {
        modelBuilder.addDependency(dep);
        addedKeys.add(dep.getKey());
        addedArtifactIds.add(coords.artifactId());
      }
    }
  }
}
