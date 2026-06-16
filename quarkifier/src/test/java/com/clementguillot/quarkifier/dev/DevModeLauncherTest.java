package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.QuarkifierConfig;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DevModeLauncher#buildDevModeContext}. */
class DevModeLauncherTest {

  /** Builds a dev-mode config from the baseline flags plus {@code extraArgs}. */
  private static QuarkifierConfig devConfig(String... extraArgs) {
    var args =
        new ArrayList<>(
            List.of(
                "--application-classpath", "app.jar",
                "--deployment-classpath", "deploy.jar",
                "--output-dir", "/tmp/output",
                "--mode", "dev",
                "--expected-quarkus-version", "3.27.4",
                "--app-name", "my-app",
                "--app-version", "1.0.0"));
    args.addAll(List.of(extraArgs));
    try {
      return QuarkifierConfig.parse(args.toArray(String[]::new));
    } catch (QuarkifierConfig.InvalidArgumentsException e) {
      throw new IllegalStateException("Invalid test config", e);
    }
  }

  @Test
  void buildDevModeContext_withSourceDirs_setsSourcePaths() {
    var sourceDirs = List.of(Path.of("src/main/java"), Path.of("lib/src/main/java"));
    var config = devConfig("--source-dirs", "src/main/java,lib/src/main/java");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertNotNull(context.getApplicationRoot());
    var actualPaths =
        context.getApplicationRoot().getMain().getSourcePaths().stream()
            .collect(Collectors.toSet());
    var expectedPaths = sourceDirs.stream().map(Path::toAbsolutePath).collect(Collectors.toSet());
    assertEquals(expectedPaths.size(), actualPaths.size());
    for (Path expected : expectedPaths) {
      assertTrue(
          actualPaths.stream().anyMatch(p -> p.toAbsolutePath().equals(expected)),
          "Expected source path " + expected + " not found in " + actualPaths);
    }
  }

  @Test
  void buildDevModeContext_withoutSourceDirs_emptySourcePaths() {
    var context = DevModeLauncher.buildDevModeContext(devConfig());

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
    assertTrue(DevModeLauncher.buildDevModeContext(devConfig()).isAbortOnFailedStart());
  }

  @Test
  void buildDevModeContext_localProjectDiscovery_alwaysFalse() {
    var context = DevModeLauncher.buildDevModeContext(devConfig("--source-dirs", "src/main/java"));

    assertFalse(context.isLocalProjectDiscovery());
  }

  @Test
  void buildDevModeContext_fallsBackToOutputDir_whenWorkspaceDirNull() {
    var config = devConfig("--source-dirs", "src/main/java");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals("my-app", context.getBaseName());
    // Falls back to outputDir when workspaceDir is null
    assertEquals(config.outputDir().toAbsolutePath().toFile(), context.getProjectDir());
    assertEquals(
        config.outputDir().resolve("target").toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
    assertEquals(
        config.outputDir().toAbsolutePath().toString(),
        context.getApplicationRoot().getProjectDirectory());
  }

  @Test
  void buildDevModeContext_setsProjectDir_fromWorkspaceDir() {
    var workspaceDir = Path.of("/home/user/project");
    var config =
        devConfig("--source-dirs", "src/main/java", "--workspace-dir", "/home/user/project");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(workspaceDir.toAbsolutePath().toFile(), context.getProjectDir());
    assertEquals(
        workspaceDir.toAbsolutePath().toString(),
        context.getApplicationRoot().getProjectDirectory());
  }

  @Test
  void buildDevModeContext_setsTargetDir_asSubdirOfWorkspaceDir() {
    var workspaceDir = Path.of("/home/user/project");
    var config =
        devConfig("--source-dirs", "src/main/java", "--workspace-dir", "/home/user/project");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(
        workspaceDir.resolve("target").toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
  }

  @Test
  void buildDevModeContext_withClassesDir_usesClassesDirForClassesPath() {
    var classesDir = Path.of("/tmp/mutable-classes");
    var config =
        devConfig(
            "--source-dirs", "src/main/java",
            "--classes-dir", "/tmp/mutable-classes",
            "--bazel-targets", "//pkg:lib");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(
        classesDir.toAbsolutePath().toString(),
        context.getApplicationRoot().getMain().getClassesPath());
  }

  @Test
  void buildDevModeContext_withoutClassesDir_usesAppJarForClassesPath() {
    var context = DevModeLauncher.buildDevModeContext(devConfig("--source-dirs", "src/main/java"));

    assertEquals(
        Path.of("app.jar").toAbsolutePath().toString(),
        context.getApplicationRoot().getMain().getClassesPath());
  }
}
