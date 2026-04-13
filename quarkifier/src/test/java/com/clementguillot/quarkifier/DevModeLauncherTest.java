package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DevModeLauncher#buildDevModeContext}. */
class DevModeLauncherTest {

  private static ApplicationModel minimalAppModel() {
    var modelBuilder = new ApplicationModelBuilder();
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("com.example")
            .setArtifactId("test-app")
            .setVersion("1.0.0")
            .setResolvedPath(Path.of("test-app.jar"))
            .setRuntimeCp()
            .setDeploymentCp());
    return modelBuilder.build();
  }

  private static QuarkifierConfig configWithSourceDirs(List<Path> sourceDirs) {
    return new QuarkifierConfig(
        List.of(Path.of("app.jar")),
        List.of(Path.of("deploy.jar")),
        Path.of("/tmp/output"),
        null,
        List.of(),
        AugmentationMode.DEV,
        "3.20.6",
        "my-app",
        "1.0.0",
        sourceDirs);
  }

  @Test
  void buildDevModeContext_withSourceDirs_setsSourcePaths() {
    var sourceDirs = List.of(Path.of("src/main/java"), Path.of("lib/src/main/java"));
    var config = configWithSourceDirs(sourceDirs);
    var appModel = minimalAppModel();

    var context = DevModeLauncher.buildDevModeContext(config, appModel);

    assertNotNull(context.getApplicationRoot());
    var actualPaths =
        context.getApplicationRoot().getMain().getSourcePaths().stream()
            .collect(Collectors.toSet());
    var expectedPaths =
        sourceDirs.stream().map(Path::toAbsolutePath).collect(Collectors.toSet());
    // PathList.from() converts paths; verify the source dirs are present
    assertEquals(expectedPaths.size(), actualPaths.size());
    for (Path expected : expectedPaths) {
      assertTrue(
          actualPaths.stream().anyMatch(p -> p.toAbsolutePath().equals(expected)),
          "Expected source path " + expected + " not found in " + actualPaths);
    }
  }

  @Test
  void buildDevModeContext_withoutSourceDirs_emptySourcePaths() {
    var config = configWithSourceDirs(List.of());
    var appModel = minimalAppModel();

    var context = DevModeLauncher.buildDevModeContext(config, appModel);

    assertNotNull(context.getApplicationRoot());
    // Empty source dirs → empty source paths in ModuleInfo
    var sourcePaths = context.getApplicationRoot().getMain().getSourcePaths();
    assertTrue(
        sourcePaths == null || !sourcePaths.iterator().hasNext(),
        "Expected empty source paths but got: " + sourcePaths);
    // Context should still be valid
    assertEquals(QuarkusBootstrap.Mode.DEV, context.getMode());
    assertNotNull(context.getProjectDir());
  }

  @Test
  void buildDevModeContext_abortOnFailedStart_alwaysTrue() {
    var config = configWithSourceDirs(List.of());
    var appModel = minimalAppModel();

    var context = DevModeLauncher.buildDevModeContext(config, appModel);

    assertTrue(context.isAbortOnFailedStart());
  }

  @Test
  void buildDevModeContext_localProjectDiscovery_alwaysFalse() {
    var config = configWithSourceDirs(List.of(Path.of("src/main/java")));
    var appModel = minimalAppModel();

    var context = DevModeLauncher.buildDevModeContext(config, appModel);

    assertFalse(context.isLocalProjectDiscovery());
  }

  @Test
  void buildDevModeContext_setsCorrectBaseNameProjectDirTargetDir() {
    var config =
        new QuarkifierConfig(
            List.of(Path.of("app.jar")),
            List.of(Path.of("deploy.jar")),
            Path.of("/tmp/output"),
            null,
            List.of(),
            AugmentationMode.DEV,
            "3.20.6",
            "my-app",
            "1.0.0",
            List.of(Path.of("src/main/java")));
    var appModel = minimalAppModel();

    var context = DevModeLauncher.buildDevModeContext(config, appModel);

    assertEquals("my-app", context.getBaseName());
    assertEquals(
        config.outputDir().toAbsolutePath().toFile(),
        context.getProjectDir());
    assertEquals(
        config.outputDir().toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
  }
}
