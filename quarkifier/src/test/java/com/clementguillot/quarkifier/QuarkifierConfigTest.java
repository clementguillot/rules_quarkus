package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Unit tests for CLI parsing via {@link QuarkifierCommand} and {@link QuarkifierConfig}. */
class QuarkifierConfigTest {

  /** Parses args via picocli and returns the config, or throws on usage error. */
  private static QuarkifierConfig parse(String... args) {
    var arguments = new ArrayList<>(List.of(args));
    if (!arguments.contains("--application-model")) {
      arguments.add("--application-model");
      arguments.add("model.json");
    }
    return TestQuarkifierConfig.parse(arguments.toArray(String[]::new));
  }

  @Test
  void parse_missingOutputDir() {
    // --output-dir is required by picocli
    var ex =
        assertThrows(
            CommandLine.MissingParameterException.class,
            () -> parse("--application-classpath", "a.jar"));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_missingApplicationClasspath() {
    var ex =
        assertThrows(CommandLine.ParameterException.class, () -> parse("--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_missingApplicationModel() {
    var ex =
        assertThrows(
            CommandLine.MissingParameterException.class,
            () ->
                TestQuarkifierConfig.parse(
                    "--application-classpath", "a.jar", "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--application-model"));
  }

  @Test
  void parse_unknownArgument() {
    assertThrows(
        CommandLine.UnmatchedArgumentException.class,
        () -> parse("--application-classpath", "a.jar", "--output-dir", "/out", "--bogus-flag"));
  }

  @Test
  void parse_invalidMode() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--output-dir", "/out",
                    "--mode", "invalid"));
    assertTrue(ex.getMessage().contains("invalid"));
  }

  @Test
  void parse_defaultModeIsNormal() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertEquals(AugmentationMode.NORMAL, config.mode());
  }

  @Test
  void parse_defaultPackageTypeIsFastJar() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertEquals(JarPackageType.FAST_JAR, config.packageType());
  }

  @Test
  void parse_everyPackageType() {
    for (JarPackageType type : JarPackageType.values()) {
      var config =
          parse(
              "--application-classpath", "a.jar",
              "--output-dir", "/out",
              "--package-type", type.configValue());
      assertEquals(type, config.packageType());
    }
  }

  @Test
  void parse_invalidPackageType() {
    var exception =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--output-dir", "/out",
                    "--package-type", "thin-jar"));
    assertTrue(exception.getMessage().contains("Invalid package type"));
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
                    "--output-dir", "/out"));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_testMode() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--mode", "test");
    assertEquals(AugmentationMode.TEST, config.mode());
  }

  @Test
  void parse_sourceDirs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
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
            "--output-dir", "/out");
    assertTrue(config.sourceDirs().isEmpty());
  }

  @Test
  void parse_continuousTestingOptions() {
    var config =
        parse(
            "--application-classpath",
            "a.jar",
            "--output-dir",
            "/out",
            "--test-application-model",
            "test-model.json",
            "--test-source-dirs",
            "src/test/java,lib/src/test/java",
            "--test-classes-dir",
            "/tmp/test-classes",
            "--test-classes-output-dirs",
            "bazel-bin/test.jar,bazel-bin/other-tests.jar",
            "--test-resources",
            "src/test/resources,lib/src/test/resources");

    assertEquals(Path.of("test-model.json"), config.testApplicationModel());
    assertEquals(
        List.of(Path.of("src/test/java"), Path.of("lib/src/test/java")), config.testSourceDirs());
    assertEquals(Path.of("/tmp/test-classes"), config.testClassesDir());
    assertEquals(
        List.of(Path.of("bazel-bin/test.jar"), Path.of("bazel-bin/other-tests.jar")),
        config.testClassesOutputDirs());
    assertEquals(
        List.of(Path.of("src/test/resources"), Path.of("lib/src/test/resources")),
        config.testResources());
  }

  @Test
  void parse_absentContinuousTestingOptionsDefaultToEmpty() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");

    assertNull(config.testApplicationModel());
    assertTrue(config.testSourceDirs().isEmpty());
    assertNull(config.testClassesDir());
    assertTrue(config.testClassesOutputDirs().isEmpty());
    assertTrue(config.testResources().isEmpty());
  }

  @Test
  void parse_codegenInputDirs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--codegen-input-dirs", "src/main,schemas/src/main");
    assertEquals(
        List.of(Path.of("src/main"), Path.of("schemas/src/main")), config.codegenInputDirs());
  }

  @Test
  void parse_classesDir() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--classes-dir", "/tmp/classes");
    assertEquals(Path.of("/tmp/classes"), config.classesDir());
  }

  @Test
  void parse_absentClassesDirDefaultsToNull() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertNull(config.classesDir());
  }

  @Test
  void parse_bazelTargets() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--bazel-targets", "//pkg:lib,//pkg:other");
    assertEquals(List.of("//pkg:lib", "//pkg:other"), config.bazelTargets());
  }

  @Test
  void parse_absentBazelTargetsDefaultsToEmptyList() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertTrue(config.bazelTargets().isEmpty());
  }

  @Test
  void parse_classesOutputDirs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
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
            "--output-dir", "/out");
    assertTrue(config.classesOutputDirs().isEmpty());
  }

  @Test
  void parse_workspaceDir() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--workspace-dir", "/home/user/project");
    assertEquals(Path.of("/home/user/project"), config.workspaceDir());
  }

  @Test
  void parse_absentWorkspaceDirDefaultsToNull() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertNull(config.workspaceDir());
  }

  @Test
  void parse_buildPropertiesFromUtf8File(@TempDir Path tempDir) throws Exception {
    Path propertiesFile = tempDir.resolve("build.properties");
    Files.writeString(propertiesFile, "quarkus.banner.enabled=false\nclé=été\n");

    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--build-properties-file", propertiesFile.toString());

    assertEquals(
        java.util.Map.of("quarkus.banner.enabled", "false", "clé", "été"),
        config.buildProperties());
  }

  @Test
  void parse_absentBuildPropertiesDefaultsToEmptyMap() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");

    assertEquals(java.util.Map.of(), config.buildProperties());
  }

  @Test
  void parse_missingBuildPropertiesFile_throws() {
    var exception =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--output-dir", "/out",
                    "--build-properties-file", "/nonexistent/build.properties"));

    assertTrue(exception.getMessage().contains("build properties file"));
  }

  @Test
  void parse_testModeRejectsInertBuildPropertiesFile(@TempDir Path tempDir) throws Exception {
    Path propertiesFile = tempDir.resolve("build.properties");
    Files.writeString(propertiesFile, "quarkus.banner.enabled=false\n");

    var exception =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath", "a.jar",
                    "--output-dir", "/out",
                    "--mode", "test",
                    "--build-properties-file", propertiesFile.toString()));

    assertTrue(exception.getMessage().contains("not supported in TEST mode"));
  }

  // ---- classpath file flags ----

  @Test
  void parse_applicationClasspathFromFile(@TempDir Path tempDir) throws Exception {
    Path cpFile = tempDir.resolve("app_cp.txt");
    Files.writeString(cpFile, "a.jar:b.jar\n");

    var config = parse("--application-classpath-file", cpFile.toString(), "--output-dir", "/out");

    assertEquals(List.of(Path.of("a.jar"), Path.of("b.jar")), config.applicationClasspath());
  }

  @Test
  void parse_removedLegacyOptionsAreRejected() {
    for (String option :
        List.of(
            "--deployment-classpath",
            "--deployment-classpath-file",
            "--expected-quarkus-version",
            "--app-version")) {
      assertThrows(
          CommandLine.UnmatchedArgumentException.class,
          () ->
              parse(
                  "--application-classpath", "a.jar", "--output-dir", "/out", option, "obsolete"));
    }
  }

  @Test
  void parse_classpathFileMissing_throws() {
    var ex =
        assertThrows(
            CommandLine.ParameterException.class,
            () ->
                parse(
                    "--application-classpath-file", "/nonexistent/cp.txt",
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
                "--output-dir", "/out",
                "--bazel-build-timeout-seconds", "-5"));
  }

  @Test
  void parse_defaultTimeoutIs600() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertEquals(600, config.bazelBuildTimeoutSeconds());
  }

  @Test
  void parse_defaultBazelCommandAndBuildArgs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out");
    assertEquals("bazel", config.bazelCommand());
    assertEquals(List.of(), config.bazelBuildArgs());
  }

  @Test
  void parse_bazelCommandAndBuildArgs() {
    var config =
        parse(
            "--application-classpath", "a.jar",
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
  void packagedToolDeclaresTargetedQuarkusVersion() {
    assertNotEquals("unknown", QuarkifierVersionProvider.targetedQuarkusVersion());
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
            "augmentation", "--application-model", "model.json", "--output-dir", "/out");

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
            "--output-dir", "/out");

    assertEquals(List.of(Path.of("from-file.jar")), config.applicationClasspath());
  }

  @Test
  void duplicateOption_lastValueWins() {
    // overwrittenOptionsAllowed: second --mode overwrites first
    var config =
        parse(
            "--application-classpath", "a.jar",
            "--output-dir", "/out",
            "--mode", "test",
            "--mode", "dev");
    assertEquals(AugmentationMode.DEV, config.mode());
  }
}
