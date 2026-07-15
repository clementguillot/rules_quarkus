package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Unit tests for CLI parsing via {@link QuarkifierCommand} and {@link QuarkifierConfig} round-trip.
 */
class QuarkifierConfigTest {

  /** Parses args via picocli and returns the config, or throws on usage error. */
  private static QuarkifierConfig parse(String... args) {
    return QuarkifierConfig.parse(args);
  }

  @Test
  void parse_missingOutputDir() {
    // --output-dir is required by picocli
    var ex =
        assertThrows(
            CommandLine.MissingParameterException.class,
            () -> parse("--application-classpath", "a.jar", "--deployment-classpath", "d.jar"));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_missingApplicationClasspath() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () -> parse("--deployment-classpath", "d.jar", "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_missingDeploymentClasspath() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () -> parse("--application-classpath", "a.jar", "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--deployment-classpath"));
  }

  @Test
  void parse_unknownArgument() {
    assertThrows(
        CommandLine.UnmatchedArgumentException.class,
        () ->
            parse(
                "--application-classpath",
                "a.jar",
                "--deployment-classpath",
                "d.jar",
                "--output-dir",
                "/out",
                "--bogus-flag"));
  }

  @Test
  void parse_invalidMode() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--mode", "invalid"));
    assertTrue(ex.getMessage().contains("invalid"));
  }

  @Test
  void parse_defaultModeIsNormal() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals(AugmentationMode.NORMAL, config.mode());
  }

  @Test
  void parse_emptyApplicationClasspath() {
    // Empty inline + no file = validation error
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_testMode() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--mode", "test");
    assertEquals(AugmentationMode.TEST, config.mode());
  }

  @Test
  void parse_sourceDirs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--source-dirs", "src/main/java,lib/src/main/java");
    assertEquals(
        List.of(Path.of("src/main/java"), Path.of("lib/src/main/java")), config.sourceDirs());
  }

  @Test
  void parse_absentSourceDirsDefaultsToEmptyList() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.sourceDirs().isEmpty());
  }

  @Test
  void parse_classesDir() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--classes-dir", "/tmp/classes");
    assertEquals(Path.of("/tmp/classes"), config.classesDir());
  }

  @Test
  void parse_absentClassesDirDefaultsToNull() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertNull(config.classesDir());
  }

  @Test
  void parse_bazelTargets() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--bazel-targets", "//pkg:lib,//pkg:other");
    assertEquals(List.of("//pkg:lib", "//pkg:other"), config.bazelTargets());
  }

  @Test
  void parse_absentBazelTargetsDefaultsToEmptyList() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.bazelTargets().isEmpty());
  }

  @Test
  void parse_classesOutputDirs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--classes-output-dirs", "bazel-bin/pkg/lib,bazel-bin/pkg/other");
    assertEquals(
        List.of(Path.of("bazel-bin/pkg/lib"), Path.of("bazel-bin/pkg/other")),
        config.classesOutputDirs());
  }

  @Test
  void parse_absentClassesOutputDirsDefaultsToEmptyList() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertTrue(config.classesOutputDirs().isEmpty());
  }

  @Test
  void parse_workspaceDir() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--workspace-dir", "/home/user/project");
    assertEquals(Path.of("/home/user/project"), config.workspaceDir());
  }

  @Test
  void parse_absentWorkspaceDirDefaultsToNull() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertNull(config.workspaceDir());
  }

  // ---- toArgs() round-trip ----

  @Test
  void roundTrip_minimalConfig() {
    var original =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");

    assertEquals(original, parse(original.toArgs()));
  }

  @Test
  void roundTrip_fullConfig() {
    var original =
        parse(
            "--application-classpath", "a.jar:b.jar",
            "--deployment-classpath", "d.jar:e.jar",
            "--core-deployment-classpath", "core.jar",
            "--output-dir", "/out",
            "--resources", "src/main/resources,extra/resources",
            "--mode", "dev",
            "--expected-quarkus-version", "3.33.2",
            "--app-name", "my-app",
            "--app-version", "1.2.3",
            "--main-class", "com.example.Main",
            "--native-builder-image", "quay.io/quarkus/builder:jdk-25",
            "--source-dirs", "src/main/java,lib/src/main/java",
            "--classes-dir", "/tmp/classes",
            "--bazel-targets", "//pkg:lib,//pkg:other",
            "--classes-output-dirs", "bazel-bin/pkg/lib",
            "--workspace-dir", "/home/user/project",
            "--bazel-build-timeout-seconds", "120",
            "--bazel-command", "/usr/local/bin/bazelisk",
            "--bazel-build-args", "--config=dev,-c,opt",
            "--local-app-jars", "a.jar");

    assertEquals(original, parse(original.toArgs()));
  }

  // ---- classpath file flags ----

  @Test
  void parse_applicationClasspathFromFile(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("app_cp.txt");
    Files.writeString(cpFile, "a.jar:b.jar\n");

    var config =
        parse(
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
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath-file", cpFile.toString(),
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("d.jar"), Path.of("e.jar")), config.deploymentClasspath());
  }

  @Test
  void parse_classpathFileMissing_throws() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
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
        parse(
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
        parse(
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
            CommandLine.ParameterException.class,
            () ->
                parse(
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
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--native-builder-image", ""));
    assertTrue(ex.getMessage().contains("--native-builder-image"));
  }

  @Test
  void parse_nonNumericTimeout_throws() {
    // picocli rejects non-numeric for a long field
    assertThrows(
        CommandLine.ParameterException.class,
        () ->
            parse(
                "--application-classpath", "a.jar",
                "--deployment-classpath", "d.jar",
                "--output-dir", "/out",
                "--bazel-build-timeout-seconds", "soon"));
  }

  @Test
  void parse_negativeTimeout_throws() {
    assertThrows(
        CommandLine.ParameterException.class,
        () ->
            parse(
                "--application-classpath", "a.jar",
                "--deployment-classpath", "d.jar",
                "--output-dir", "/out",
                "--bazel-build-timeout-seconds", "-5"));
  }

  @Test
  void parse_defaultTimeoutIs600() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals(600, config.bazelBuildTimeoutSeconds());
  }

  @Test
  void parse_defaultBazelCommandAndBuildArgs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");
    assertEquals("bazel", config.bazelCommand());
    assertEquals(List.of(), config.bazelBuildArgs());
  }

  @Test
  void parse_bazelCommandAndBuildArgs() {
    var config =
        parse(
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
    // picocli still accepts empty string for --bazel-command, but
    // the default is "bazel" so we only get empty if explicitly passed
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--deployment-classpath", "d.jar",
                    "--output-dir", "/out",
                    "--bazel-command", ""));
    assertTrue(ex.getMessage().contains("--bazel-command"));
  }

  @Test
  void parse_emptyOutputDir_throws() {
    // picocli converts "" to a Path, but the path is not null so we test validation here
    // The required flag triggers MissingParameterException when omitted entirely
    assertThrows(
        CommandLine.ParameterException.class,
        () ->
            parse(
                "--application-classpath", "a.jar",
                "--deployment-classpath", "d.jar",
                "--output-dir", ""));
  }

  // ---- picocli integration: help, exit codes, overwrite behavior ----

  @Test
  void help_exitCodeIsZero() {
    int exitCode = QuarkifierCommand.createCommandLine().execute("--help");
    assertEquals(0, exitCode);
  }

  @Test
  void noSubcommand_printsUsageExitZero() {
    int exitCode = QuarkifierCommand.createCommandLine().execute();
    assertEquals(0, exitCode);
  }

  @Test
  void version_exitCodeIsZero() {
    int exitCode = QuarkifierCommand.createCommandLine().execute("--version");
    assertEquals(0, exitCode);
  }

  @Test
  void usageError_exitCodeIsTwo() {
    // augmentation without required --output-dir → exit 2
    int exitCode = QuarkifierCommand.createCommandLine().execute("augmentation");
    assertEquals(2, exitCode);
  }

  @Test
  void validationError_usesConfiguredErrorWriter() {
    var commandLine = QuarkifierCommand.createCommandLine();
    var errorOutput = new StringWriter();
    commandLine.setErr(new PrintWriter(errorOutput, true));

    int exitCode =
        commandLine.execute(
            "augmentation", "--deployment-classpath", "d.jar", "--output-dir", "/out");

    assertEquals(2, exitCode);
    assertTrue(errorOutput.toString().contains("--application-classpath"));
  }

  @Test
  void classpathFile_overridesInlineValue(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("app_cp.txt");
    Files.writeString(cpFile, "from-file.jar");

    // Both inline and file provided — file wins (overwrite allowed, file resolved last)
    var config =
        parse(
            "--application-classpath", "inline.jar",
            "--application-classpath-file", cpFile.toString(),
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("from-file.jar")), config.applicationClasspath());
  }

  @Test
  void classpathFile_overridesInlineValue_reverseOrder(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("app_cp.txt");
    Files.writeString(cpFile, "from-file.jar");

    // File before inline — file still wins (precedence is not order-dependent)
    var config =
        parse(
            "--application-classpath-file", cpFile.toString(),
            "--application-classpath", "inline.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("from-file.jar")), config.applicationClasspath());
  }

  @Test
  void duplicateOption_lastValueWins() {
    // overwrittenOptionsAllowed: second --mode overwrites first
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--deployment-classpath", "d.jar",
            "--output-dir", "/out",
            "--mode", "test",
            "--mode", "dev");
    assertEquals(AugmentationMode.DEV, config.mode());
  }
}
