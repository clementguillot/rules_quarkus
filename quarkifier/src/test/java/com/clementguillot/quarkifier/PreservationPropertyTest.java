package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.model.QuarkusAppModelBuilder;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Preservation property tests for the bugfix.
 *
 * <p>These tests capture BASELINE behavior that must be preserved after the fix. They should PASS
 * on the current unfixed code.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</b>
 */
class PreservationPropertyTest {

  private static final Random RNG = new Random(42);
  private static final int TRIES = 100;

  @TempDir Path tempDir;

  // ---- Property: External Maven jars are NEVER the appJar in QuarkusAppModelBuilder ----

  /**
   * Property: External Maven jars are passed as runtime dependencies (runtimeJars), not as appJar.
   *
   * <p>For any classpath configuration where the first jar is a local app jar and the remaining
   * jars are external Maven dependencies, the model's app artifact should always be the first jar
   * (the local one), and external jars should appear only as dependencies.
   *
   * <p><b>Validates: Requirements 3.1</b>
   */
  @ParameterizedTest
  @MethodSource("classpathConfigurations")
  void externalMavenJarsAreRuntimeDependenciesNotAppArtifact(ClasspathConfig config)
      throws IOException {
    // Create the app jar (local workspace jar)
    Path appJar = createJar(config.appGroupId(), config.appArtifactId(), config.appVersion());

    // Create external Maven jars (runtime dependencies)
    List<Path> runtimeJars = new ArrayList<>();
    for (MavenJarInfo mavenJar : config.mavenJars()) {
      runtimeJars.add(createJar(mavenJar.groupId(), mavenJar.artifactId(), mavenJar.version()));
    }

    // Build the model — localAppJars contains the app jar, runtimeJars are external deps
    ApplicationModel model =
        QuarkusAppModelBuilder.build(
            List.of(appJar), runtimeJars, List.of(), config.appName(), "1.0");

    // Assert: app artifact is the local jar, not any Maven jar
    assertEquals(
        config.appName() != null ? config.appName() : config.appArtifactId(),
        model.getAppArtifact().getArtifactId(),
        "App artifact should be the local jar");

    // Assert: all Maven jars appear as dependencies, not as the app artifact
    for (MavenJarInfo mavenJar : config.mavenJars()) {
      boolean foundAsDep =
          model.getDependencies().stream()
              .anyMatch(d -> mavenJar.artifactId().equals(d.getArtifactId()));
      assertTrue(
          foundAsDep, "Maven jar " + mavenJar.artifactId() + " should be a runtime dependency");
    }
  }

  /**
   * Property: Single-module project with one local jar correctly uses that jar as app artifact.
   *
   * <p>When there is exactly one local jar (the app jar) and zero or more Maven dependencies, the
   * app artifact in the model is always that single local jar.
   *
   * <p><b>Validates: Requirements 3.2</b>
   */
  @ParameterizedTest
  @MethodSource("singleModuleConfigurations")
  void singleLocalJarIsAlwaysAppArtifact(SingleModuleConfig config) throws IOException {
    Path appJar = createJar(config.groupId(), config.artifactId(), config.version());

    List<Path> runtimeJars = new ArrayList<>();
    for (MavenJarInfo mavenJar : config.mavenDeps()) {
      runtimeJars.add(createJar(mavenJar.groupId(), mavenJar.artifactId(), mavenJar.version()));
    }

    ApplicationModel model =
        QuarkusAppModelBuilder.build(
            List.of(appJar), runtimeJars, List.of(), config.appName(), config.version());

    // The app artifact must be the single local jar
    String expectedArtifactId = config.appName() != null ? config.appName() : config.artifactId();
    assertEquals(
        expectedArtifactId,
        model.getAppArtifact().getArtifactId(),
        "Single local jar must be the app artifact");
    assertEquals(
        config.groupId(),
        model.getAppArtifact().getGroupId(),
        "App artifact groupId must match the local jar");
    assertEquals(
        config.version(),
        model.getAppArtifact().getVersion(),
        "App artifact version must match the local jar");
  }

  /**
   * Property: QuarkifierConfig.parse() correctly parses --application-classpath and
   * --deployment-classpath flags for all valid configurations.
   *
   * <p><b>Validates: Requirements 3.4, 3.5</b>
   */
  @ParameterizedTest
  @MethodSource("com.clementguillot.quarkifier.TestDataGenerator#randomValidConfigs")
  void parseCorrectlyParsesClasspathFlags(QuarkifierConfig original)
      throws QuarkifierConfig.InvalidArgumentsException {
    String[] args = original.toArgs();
    QuarkifierConfig parsed = QuarkifierConfig.parse(args);

    // Application classpath must round-trip exactly
    assertEquals(
        original.applicationClasspath(),
        parsed.applicationClasspath(),
        "applicationClasspath must round-trip");

    // Deployment classpath must round-trip exactly
    assertEquals(
        original.deploymentClasspath(),
        parsed.deploymentClasspath(),
        "deploymentClasspath must round-trip");

    // Core deployment classpath must round-trip exactly
    assertEquals(
        original.coreDeploymentClasspath(),
        parsed.coreDeploymentClasspath(),
        "coreDeploymentClasspath must round-trip");
  }

  /**
   * Property: QuarkifierConfig.parse() round-trips correctly via toArgs() for ALL fields.
   *
   * <p><b>Validates: Requirements 3.3, 3.4, 3.5</b>
   */
  @ParameterizedTest
  @MethodSource("com.clementguillot.quarkifier.TestDataGenerator#randomValidConfigs")
  void configRoundTripPreservesAllFields(QuarkifierConfig original)
      throws QuarkifierConfig.InvalidArgumentsException {
    String[] args = original.toArgs();
    QuarkifierConfig parsed = QuarkifierConfig.parse(args);

    assertEquals(original.applicationClasspath(), parsed.applicationClasspath());
    assertEquals(original.deploymentClasspath(), parsed.deploymentClasspath());
    assertEquals(original.coreDeploymentClasspath(), parsed.coreDeploymentClasspath());
    assertEquals(original.outputDir(), parsed.outputDir());
    assertEquals(original.resources(), parsed.resources());
    assertEquals(original.mode(), parsed.mode());
    assertEquals(original.expectedQuarkusVersion(), parsed.expectedQuarkusVersion());
    assertEquals(original.appName(), parsed.appName());
    assertEquals(original.appVersion(), parsed.appVersion());
    assertEquals(original.mainClass(), parsed.mainClass());
    assertEquals(original.nativeBuilderImage(), parsed.nativeBuilderImage());
    assertEquals(original.sourceDirs(), parsed.sourceDirs());
    assertEquals(original.classesDir(), parsed.classesDir());
    assertEquals(original.bazelTargets(), parsed.bazelTargets());
    assertEquals(original.classesOutputDirs(), parsed.classesOutputDirs());
    assertEquals(original.workspaceDir(), parsed.workspaceDir());
    assertEquals(original.bazelBuildTimeoutSeconds(), parsed.bazelBuildTimeoutSeconds());
    assertEquals(original.localAppJars(), parsed.localAppJars());
  }

  // ---- Data generators ----

  static Stream<ClasspathConfig> classpathConfigurations() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomClasspathConfig());
  }

  static Stream<SingleModuleConfig> singleModuleConfigurations() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomSingleModuleConfig());
  }

  private static ClasspathConfig randomClasspathConfig() {
    String appGroupId = "com." + randomAlpha(3, 8);
    String appArtifactId = randomAlpha(4, 12);
    String appVersion = randomVersion();
    String appName = RNG.nextDouble() < 0.3 ? null : randomAlpha(3, 10);

    int mavenJarCount = 1 + RNG.nextInt(5);
    List<MavenJarInfo> mavenJars = new ArrayList<>();
    for (int i = 0; i < mavenJarCount; i++) {
      mavenJars.add(
          new MavenJarInfo(
              "io." + randomAlpha(3, 8),
              randomAlpha(5, 15) + "-" + randomAlpha(3, 6),
              randomVersion()));
    }

    return new ClasspathConfig(appGroupId, appArtifactId, appVersion, appName, mavenJars);
  }

  private static SingleModuleConfig randomSingleModuleConfig() {
    String groupId = "com." + randomAlpha(3, 8);
    String artifactId = randomAlpha(4, 12);
    String version = randomVersion();
    String appName = RNG.nextDouble() < 0.3 ? null : randomAlpha(3, 10);

    int mavenDepCount = RNG.nextInt(6); // 0 to 5 Maven deps
    List<MavenJarInfo> mavenDeps = new ArrayList<>();
    for (int i = 0; i < mavenDepCount; i++) {
      mavenDeps.add(
          new MavenJarInfo("org." + randomAlpha(3, 8), randomAlpha(5, 15), randomVersion()));
    }

    return new SingleModuleConfig(groupId, artifactId, version, appName, mavenDeps);
  }

  // ---- Helpers ----

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

  private static String randomAlpha(int minLen, int maxLen) {
    int len = minLen + RNG.nextInt(maxLen - minLen + 1);
    var sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) sb.append((char) ('a' + RNG.nextInt(26)));
    return sb.toString();
  }

  private static String randomVersion() {
    return RNG.nextInt(10) + "." + RNG.nextInt(30) + "." + RNG.nextInt(20);
  }

  // ---- Data records ----

  record MavenJarInfo(String groupId, String artifactId, String version) {}

  record ClasspathConfig(
      String appGroupId,
      String appArtifactId,
      String appVersion,
      String appName,
      List<MavenJarInfo> mavenJars) {}

  record SingleModuleConfig(
      String groupId,
      String artifactId,
      String version,
      String appName,
      List<MavenJarInfo> mavenDeps) {}
}
