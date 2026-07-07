package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link QuarkifierConfig} error paths and edge cases. */
class QuarkifierConfigTest {

  @Test
  void parse_missingApplicationClasspath() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {"--deployment-classpath", "d.jar", "--output-dir", "/out"}));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_missingDeploymentClasspath() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {"--application-classpath", "a.jar", "--output-dir", "/out"}));
    assertTrue(ex.getMessage().contains("--deployment-classpath"));
  }

  @Test
  void parse_missingOutputDir() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar", "--deployment-classpath", "d.jar"
                    }));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_unknownArgument() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--bogus-flag"
                    }));
    assertTrue(ex.getMessage().contains("--bogus-flag"));
  }

  @Test
  void parse_missingValueForFlag() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir"
                    }));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_invalidMode() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--mode", "invalid"
                    }));
    assertTrue(ex.getMessage().contains("invalid"));
  }

  @Test
  void parse_defaultModeIsNormal() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals(AugmentationMode.NORMAL, config.mode());
  }

  @Test
  void parse_emptyApplicationClasspath() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.applicationClasspath().isEmpty());
  }

  @Test
  void parse_testMode() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--mode", "test");
    assertEquals(AugmentationMode.TEST, config.mode());
  }

  @Test
  void parse_sourceDirs() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--source-dirs", "src/main/java,lib/src/main/java");
    assertEquals(
        List.of(Path.of("src/main/java"), Path.of("lib/src/main/java")), config.sourceDirs());
  }

  @Test
  void parse_sourceDirsMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--source-dirs"
                    }));
    assertTrue(ex.getMessage().contains("--source-dirs"));
  }

  @Test
  void parse_absentSourceDirsDefaultsToEmptyList()
      throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.sourceDirs().isEmpty());
  }

  @Test
  void parse_classesDir() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--classes-dir", "/tmp/classes");
    assertEquals(Path.of("/tmp/classes"), config.classesDir());
  }

  @Test
  void parse_absentClassesDirDefaultsToNull() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertNull(config.classesDir());
  }

  @Test
  void parse_bazelTargets() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--bazel-targets", "//pkg:lib,//pkg:other");
    assertEquals(List.of("//pkg:lib", "//pkg:other"), config.bazelTargets());
  }

  @Test
  void parse_absentBazelTargetsDefaultsToEmptyList()
      throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.bazelTargets().isEmpty());
  }

  @Test
  void parse_classesDirMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--classes-dir"
                    }));
    assertTrue(ex.getMessage().contains("--classes-dir"));
  }

  @Test
  void parse_bazelTargetsMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--bazel-targets"
                    }));
    assertTrue(ex.getMessage().contains("--bazel-targets"));
  }

  @Test
  void parse_classesOutputDirs() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--classes-output-dirs", "bazel-bin/pkg/lib,bazel-bin/pkg/other");
    assertEquals(
        List.of(Path.of("bazel-bin/pkg/lib"), Path.of("bazel-bin/pkg/other")),
        config.classesOutputDirs());
  }

  @Test
  void parse_absentClassesOutputDirsDefaultsToEmptyList()
      throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.classesOutputDirs().isEmpty());
  }

  @Test
  void parse_classesOutputDirsMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--classes-output-dirs"
                    }));
    assertTrue(ex.getMessage().contains("--classes-output-dirs"));
  }

  @Test
  void parse_workspaceDir() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--workspace-dir", "/home/user/project");
    assertEquals(Path.of("/home/user/project"), config.workspaceDir());
  }

  @Test
  void parse_absentWorkspaceDirDefaultsToNull() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertNull(config.workspaceDir());
  }

  @Test
  void parse_workspaceDirMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--workspace-dir"
                    }));
    assertTrue(ex.getMessage().contains("--workspace-dir"));
  }

  // ---- toArgs() round-trip ----
  //
  // Two deliberate cases instead of randomized configs: a minimal config
  // (defaults and empty collections) and a maximal one (every field set).
  // Together they cover every branch in toArgs()/parse() symmetry.

  @Test
  void roundTrip_minimalConfig() throws QuarkifierConfig.InvalidArgumentsException {
    var original =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");

    assertEquals(original, QuarkifierConfig.parse(original.toArgs()));
  }

  @Test
  void roundTrip_fullConfig() throws QuarkifierConfig.InvalidArgumentsException {
    var original =
        QuarkifierConfig.parse(
            "--application-classpath",
            "a.jar:b.jar",
            "--deployment-classpath",
            "d.jar:e.jar",
            "--core-deployment-classpath",
            "core.jar",
            "--output-dir",
            "/out",
            "--resources",
            "src/main/resources,extra/resources",
            "--mode",
            "dev",
            "--expected-quarkus-version",
            "3.33.2",
            "--app-name",
            "my-app",
            "--app-version",
            "1.2.3",
            "--main-class",
            "com.example.Main",
            "--native-builder-image",
            "quay.io/quarkus/builder:jdk-25",
            "--source-dirs",
            "src/main/java,lib/src/main/java",
            "--classes-dir",
            "/tmp/classes",
            "--bazel-targets",
            "//pkg:lib,//pkg:other",
            "--classes-output-dirs",
            "bazel-bin/pkg/lib",
            "--workspace-dir",
            "/home/user/project",
            "--bazel-build-timeout-seconds",
            "120",
            "--bazel-command",
            "/usr/local/bin/bazelisk",
            "--bazel-build-args",
            "--config=dev,-c,opt",
            "--local-app-jars",
            "a.jar");

    assertEquals(original, QuarkifierConfig.parse(original.toArgs()));
  }

  // ---- classpath file flags ----

  @Test
  void parse_applicationClasspathFromFile(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("app_cp.txt");
    Files.writeString(cpFile, "a.jar:b.jar\n");

    var config =
        QuarkifierConfig.parse(
            "--application-classpath-file", cpFile.toString(),
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("a.jar"), Path.of("b.jar")), config.applicationClasspath());
  }

  @Test
  void parse_deploymentClasspathFromFile(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("deploy_cp.txt");
    Files.writeString(cpFile, "d.jar:e.jar");

    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath-file", cpFile.toString(),
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("d.jar"), Path.of("e.jar")), config.deploymentClasspath());
  }

  @Test
  void parse_classpathFileMissing_throws() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    "--application-classpath-file", "/nonexistent/cp.txt",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("/nonexistent/cp.txt"));
  }

  @Test
  void parse_coreDeploymentClasspathFromFile(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("core_deploy_cp.txt");
    Files.writeString(cpFile, "core.jar:core2.jar");

    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--core-deployment-classpath-file", cpFile.toString());

    assertEquals(
        List.of(Path.of("core.jar"), Path.of("core2.jar")), config.coreDeploymentClasspath());
  }

  @Test
  void parse_localAppJarsFromFile(@TempDir Path tempDir) throws Exception {
    Path jarsFile = tempDir.resolve("local_jars.txt");
    Files.writeString(jarsFile, "app.jar:lib.jar:util.jar");

    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--local-app-jars-file", jarsFile.toString());

    assertEquals(
        List.of(Path.of("app.jar"), Path.of("lib.jar"), Path.of("util.jar")),
        config.localAppJars());
  }

  // ---- value validation ----

  @Test
  void parse_emptyMainClass_throws() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--main-class", "  "));
    assertTrue(ex.getMessage().contains("--main-class"));
  }

  @Test
  void parse_emptyNativeBuilderImage_throws() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--native-builder-image", ""));
    assertTrue(ex.getMessage().contains("--native-builder-image"));
  }

  @Test
  void parse_nonNumericTimeout_throws() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--bazel-build-timeout-seconds", "soon"));
    assertTrue(ex.getMessage().contains("--bazel-build-timeout-seconds"));
  }

  @Test
  void parse_negativeTimeout_throws() {
    assertThrows(
        QuarkifierConfig.InvalidArgumentsException.class,
        () ->
            QuarkifierConfig.parse(
                "--application-classpath", "a.jar",
                "--deployment-classpath", "d.jar",
                "--output-dir", "/out",
                "--bazel-build-timeout-seconds", "-5"));
  }

  @Test
  void parse_defaultTimeoutIs600() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals(600, config.bazelBuildTimeoutSeconds());
  }

  @Test
  void parse_defaultBazelCommandAndBuildArgs() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals("bazel", config.bazelCommand());
    assertEquals(List.of(), config.bazelBuildArgs());
  }

  @Test
  void parse_bazelCommandAndBuildArgs() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--bazel-command", "/opt/bazelisk",
            "--bazel-build-args", "--config=dev,-c,opt");
    assertEquals("/opt/bazelisk", config.bazelCommand());
    assertEquals(List.of("--config=dev", "-c", "opt"), config.bazelBuildArgs());
  }

  @Test
  void parse_emptyBazelCommand_throws() {
    assertThrows(
        QuarkifierConfig.InvalidArgumentsException.class,
        () ->
            QuarkifierConfig.parse(
                "--application-classpath", "a.jar",
                "--deployment-classpath", "d.jar",
                "--output-dir", "/out",
                "--bazel-command", ""));
  }

  @Test
  void parse_emptyOutputDir_throws() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", ""));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }
}
