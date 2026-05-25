package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.model.QuarkusAppModelBuilder;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bug condition exploration test for the missing test ApplicationModel system property.
 *
 * <p><b>Validates: Requirements 1.3, 2.3</b>
 *
 * <p>Property 1: Bug Condition - Missing Test Model System Property in Dev Mode Child JVM
 *
 * <p>For any valid {@link QuarkifierConfig} in DEV mode, the child JVM command produced by {@link
 * DevModeLauncher#buildChildCommand(QuarkifierConfig, ApplicationModel)} SHOULD contain {@code
 * -Dquarkus-internal-test.serialized-app-model.path=<some-path>}.
 *
 * <p>This test is EXPECTED TO FAIL on unfixed code, confirming the bug exists: the child JVM
 * command does NOT contain the test model system property.
 */
class DevModeLauncherBugConditionTest {

  private static final String TEST_MODEL_PROPERTY_PREFIX =
      "-Dquarkus-internal-test.serialized-app-model.path=";

  @TempDir Path tempDir;

  /**
   * Generates multiple valid QuarkifierConfig instances with varying classpath sizes and app names
   * to demonstrate the bug exists across all configurations.
   */
  static Stream<Arguments> devModeConfigurations() {
    return Stream.of(
        Arguments.of("single-jar-app", 1, "my-app"),
        Arguments.of("multi-jar-app", 3, "hello-service"),
        Arguments.of("large-classpath-app", 5, "complex-app"),
        Arguments.of("null-app-name", 2, null));
  }

  /**
   * Asserts that the child JVM command contains the test model system property for any valid
   * QuarkifierConfig in DEV mode.
   *
   * <p>EXPECTED TO FAIL on unfixed code: the command does NOT contain {@code
   * -Dquarkus-internal-test.serialized-app-model.path}.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("devModeConfigurations")
  void childCommandContainsTestModelSystemProperty(
      String description, int classpathSize, String appName) throws Exception {
    // Arrange: create valid jars and build an ApplicationModel
    List<Path> appCp = createApplicationClasspath(classpathSize, appName);
    List<Path> deployCp = createDeploymentClasspath();
    List<Path> coreDeployCp = createCoreDeploymentClasspath();

    QuarkifierConfig config =
        new QuarkifierConfig(
            appCp,
            deployCp,
            coreDeployCp,
            tempDir.resolve("output"),
            List.of(),
            AugmentationMode.DEV,
            "3.33.1",
            appName,
            "1.0.0",
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            tempDir.resolve("workspace"),
            60,
            List.of());

    // Create output and workspace directories
    Files.createDirectories(config.outputDir());
    Files.createDirectories(config.workspaceDir());

    // Build an ApplicationModel from the classpath (same as production code does)
    ApplicationModel appModel =
        QuarkusAppModelBuilder.build(
            appCp.get(0), appCp.subList(1, appCp.size()), deployCp, appName, "1.0.0");

    // Act: build the child JVM command
    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    // Assert: the command SHOULD contain the test model system property
    boolean hasTestModelProperty =
        cmd.stream().anyMatch(arg -> arg.startsWith(TEST_MODEL_PROPERTY_PREFIX));

    assertTrue(
        hasTestModelProperty,
        "Bug condition confirmed: For config with appCp="
            + appCp.stream().map(p -> p.getFileName().toString()).toList()
            + " (appName="
            + appName
            + "), the child JVM command does NOT contain any"
            + " '-Dquarkus-internal-test.serialized-app-model.path' entry."
            + "\nFull command: "
            + cmd);

    // Additionally verify the file at the test model path exists and is non-empty
    if (hasTestModelProperty) {
      String testModelArg =
          cmd.stream()
              .filter(arg -> arg.startsWith(TEST_MODEL_PROPERTY_PREFIX))
              .findFirst()
              .orElseThrow();
      String testModelPath = testModelArg.substring(TEST_MODEL_PROPERTY_PREFIX.length());
      Path testModelFile = Path.of(testModelPath);
      assertTrue(Files.exists(testModelFile), "Test model file should exist at: " + testModelPath);
      assertTrue(
          Files.size(testModelFile) > 0,
          "Test model file should be non-empty at: " + testModelPath);
    }
  }

  // ---- Helpers ----

  private List<Path> createApplicationClasspath(int size, String appName) throws IOException {
    List<Path> jars = new ArrayList<>();
    String artifactId = appName != null ? appName : "myapp";
    jars.add(createJar("com.example", artifactId, "1.0.0"));
    for (int i = 1; i < size; i++) {
      jars.add(createJar("io.quarkus", "quarkus-dep-" + i, "3.33.1"));
    }
    return jars;
  }

  private List<Path> createDeploymentClasspath() throws IOException {
    return List.of(
        createJar("io.quarkus", "quarkus-core-deployment", "3.33.1"),
        createJar("io.quarkus", "quarkus-arc-deployment", "3.33.1"));
  }

  private List<Path> createCoreDeploymentClasspath() throws IOException {
    return List.of(createJar("io.quarkus", "quarkus-core-deployment", "3.33.1"));
  }

  private Path createJar(String groupId, String artifactId, String version) throws IOException {
    Path dir =
        tempDir.resolve("jars/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/"));
      jos.closeEntry();
    }
    return jar;
  }
}
