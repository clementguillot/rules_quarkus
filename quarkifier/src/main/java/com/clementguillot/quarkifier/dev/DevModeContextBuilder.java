package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathList;
import java.nio.file.Path;
import org.jboss.logging.Logger;

/** Builds Quarkus dev-mode metadata from Bazel source and output directories. */
final class DevModeContextBuilder {

  private static final Logger LOGGER = Logger.getLogger(DevModeContextBuilder.class);

  private DevModeContextBuilder() {}

  static DevModeContext build(QuarkifierConfig config) {
    var context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName(config.appName() != null ? config.appName() : "quarkus-app");
    context.setArgs(new String[0]);

    Path projectRoot = config.workspaceDir() != null ? config.workspaceDir() : config.outputDir();
    if (config.workspaceDir() == null) {
      LOGGER.warn(
          "Workspace directory not set. Dev UI workspace tab will not show source files."
              + " Use 'bazel run' to launch dev mode.");
    }
    context.setProjectDir(projectRoot.toAbsolutePath().toFile());

    BuildProperties.defaults(
            config.buildProperties(), config.mainClass(), null, config.packageType())
        .forEach(
            (key, value) -> context.getBuildSystemProperties().put((String) key, (String) value));

    context.setApplicationRoot(buildAppModuleInfo(config, projectRoot));
    return context;
  }

  private static DevModeContext.ModuleInfo buildAppModuleInfo(
      QuarkifierConfig config, Path projectRoot) {
    Path appJar = config.applicationClasspath().get(0);
    var coords = MavenCoordinateParser.parse(appJar);
    Path classesPath = config.classesDir() != null ? config.classesDir() : appJar;
    Path targetDir = projectRoot.resolve("target");
    Path resourcesOutputPath = config.classesDir() != null ? config.classesDir() : targetDir;

    var builder =
        new DevModeContext.ModuleInfo.Builder()
            .setArtifactKey(ArtifactKey.ga(coords.groupId(), coords.artifactId()))
            .setName(config.appName() != null ? config.appName() : coords.artifactId())
            .setProjectDirectory(projectRoot.toAbsolutePath().toString())
            .setSourcePaths(PathList.from(config.sourceDirs()))
            .setClassesPath(classesPath.toAbsolutePath().toString())
            .setResourcePaths(PathList.from(config.resources()))
            .setResourcesOutputPath(
                config.resources().isEmpty()
                    ? null
                    : resourcesOutputPath.toAbsolutePath().toString())
            .setTargetDir(targetDir.toAbsolutePath().toString());

    if (config.testClassesDir() != null) {
      builder
          .setTestSourcePaths(PathList.from(config.testSourceDirs()))
          .setTestClassesPath(config.testClassesDir().toAbsolutePath().toString())
          .setTestResourcePaths(PathList.from(config.testResources()))
          .setTestResourcesOutputPath(config.testClassesDir().toAbsolutePath().toString());
    }
    return builder.build();
  }
}
