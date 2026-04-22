package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DevModeLauncher#buildDevModeContext}. */
class DevModeLauncherTest {

  private static QuarkifierConfig configWithSourceDirs(List<Path> sourceDirs) {
    return new QuarkifierConfig(
        List.of(Path.of("app.jar")),
        List.of(Path.of("deploy.jar")),
        List.of(),
        Path.of("/tmp/output"),
        List.of(),
        AugmentationMode.DEV,
        "3.27.3",
        "my-app",
        "1.0.0",
        sourceDirs);
  }

  @Test
  void buildDevModeContext_withSourceDirs_setsSourcePaths() {
    var sourceDirs = List.of(Path.of("src/main/java"), Path.of("lib/src/main/java"));
    var config = configWithSourceDirs(sourceDirs);

    var context = DevModeLauncher.buildDevModeContext(config);

    assertNotNull(context.getApplicationRoot());
    var actualPaths =
        context.getApplicationRoot().getMain().getSourcePaths().stream()
            .collect(Collectors.toSet());
    var expectedPaths =
        sourceDirs.stream().map(Path::toAbsolutePath).collect(Collectors.toSet());
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

    var context = DevModeLauncher.buildDevModeContext(config);

    assertNotNull(context.getApplicationRoot());
    var sourcePaths = context.getApplicationRoot().getMain().getSourcePaths();
    assertTrue(
        sourcePaths == null || !sourcePaths.iterator().hasNext(),
        "Expected empty source paths but got: " + sourcePaths);
    assertEquals(QuarkusBootstrap.Mode.DEV, context.getMode());
    assertNotNull(context.getProjectDir());
  }

  @Test
  void buildDevModeContext_abortOnFailedStart_alwaysTrue() {
    var config = configWithSourceDirs(List.of());

    var context = DevModeLauncher.buildDevModeContext(config);

    assertTrue(context.isAbortOnFailedStart());
  }

  @Test
  void buildDevModeContext_localProjectDiscovery_alwaysFalse() {
    var config = configWithSourceDirs(List.of(Path.of("src/main/java")));

    var context = DevModeLauncher.buildDevModeContext(config);

    assertFalse(context.isLocalProjectDiscovery());
  }

  @Test
  void buildDevModeContext_setsCorrectBaseNameProjectDirTargetDir() {
    var config =
        new QuarkifierConfig(
            List.of(Path.of("app.jar")),
            List.of(Path.of("deploy.jar")),
            List.of(),
            Path.of("/tmp/output"),
            List.of(),
            AugmentationMode.DEV,
            "3.27.3",
            "my-app",
            "1.0.0",
            List.of(Path.of("src/main/java")));

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals("my-app", context.getBaseName());
    assertEquals(
        config.outputDir().toAbsolutePath().toFile(),
        context.getProjectDir());
    assertEquals(
        config.outputDir().toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
  }
}
