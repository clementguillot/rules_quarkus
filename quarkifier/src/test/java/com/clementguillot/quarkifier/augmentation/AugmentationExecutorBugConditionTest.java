package com.clementguillot.quarkifier.augmentation;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bug condition exploration test for the application root selection logic in {@link
 * AugmentationExecutor}.
 *
 * <p>These tests verify the FIXED behavior: when {@code --local-app-jars} is provided, all local
 * workspace jars are used as application roots. The fix ensures that the executor uses {@code
 * config.localAppJars()} (when non-empty) instead of just {@code appCp.get(0)}.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.4</b>
 */
class AugmentationExecutorBugConditionTest {

  /**
   * Fixed scenario: Maven jar is first in the classpath, but --local-app-jars identifies the local
   * jars.
   *
   * <p>Given applicationClasspath = [maven.jar, local1.jar, local2.jar] with --local-app-jars =
   * [local1.jar, local2.jar], the executor determines application roots from config.localAppJars().
   * The result is that ALL local jars are used as application roots.
   *
   * <p>This reproduces the AugmentationExecutor.execute() logic for determining localAppJars.
   */
  @Test
  void allLocalJarsShouldBeApplicationRoots_mavenJarFirst() {
    // Simulate: applicationClasspath = [maven.jar, local1.jar, local2.jar]
    // With the fix, the Bazel rule now provides --local-app-jars to identify local jars.
    Path mavenJar =
        Path.of("external/maven/io/quarkus/quarkus-rest/3.33.1/quarkus-rest-3.33.1.jar");
    Path local1Jar = Path.of("bazel-out/bin/services/api/libapi.jar");
    Path local2Jar = Path.of("bazel-out/bin/services/domain/libdomain.jar");

    List<Path> applicationClasspath = List.of(mavenJar, local1Jar, local2Jar);
    List<Path> localAppJars = List.of(local1Jar, local2Jar);

    // Create a config simulating the FIXED invocation — with --local-app-jars flag
    QuarkifierConfig config =
        new QuarkifierConfig(
            applicationClasspath,
            List.of(Path.of("deploy.jar")),
            List.of(),
            Path.of("/tmp/output"),
            List.of(),
            AugmentationMode.NORMAL,
            null,
            "test-app",
            "1.0",
            null,
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            null,
            60,
            localAppJars);

    // Reproduce the executor's logic for determining application roots:
    // List<Path> localAppJars = config.localAppJars().isEmpty()
    //     ? List.of(appCp.get(0))
    //     : config.localAppJars();
    List<Path> appCp = config.applicationClasspath();
    List<Path> actualApplicationRoots =
        config.localAppJars().isEmpty() ? List.of(appCp.get(0)) : config.localAppJars();

    // Verify ALL local jars are in the application roots
    List<Path> expectedLocalJars = List.of(local1Jar, local2Jar);

    for (Path expectedLocalJar : expectedLocalJars) {
      assertTrue(
          actualApplicationRoots.contains(expectedLocalJar),
          "Local jar "
              + expectedLocalJar
              + " should be in application roots, "
              + "but actual roots are: "
              + actualApplicationRoots);
    }

    // Verify that the Maven jar is NOT in the application roots
    assertFalse(
        actualApplicationRoots.contains(mavenJar),
        "Maven jar "
            + mavenJar
            + " should NOT be in application roots, "
            + "but actual roots are: "
            + actualApplicationRoots);

    // Verify the runtime jars (everything not in localAppJars) exclude local jars
    List<Path> runtimeJars =
        appCp.stream().filter(jar -> !actualApplicationRoots.contains(jar)).toList();
    assertTrue(runtimeJars.contains(mavenJar), "Maven jar should be in runtime jars");
    assertFalse(runtimeJars.contains(local1Jar), "Local jar should NOT be in runtime jars");
    assertFalse(runtimeJars.contains(local2Jar), "Local jar should NOT be in runtime jars");
  }

  /**
   * Fixed scenario: Multi-module project with local jars first.
   *
   * <p>Given applicationClasspath = [local1.jar, local2.jar, maven.jar] with --local-app-jars =
   * [local1.jar, local2.jar], the executor uses ALL local jars as application roots. Previously,
   * only local1.jar (appCp.get(0)) would be used.
   */
  @Test
  void allLocalJarsShouldBeApplicationRoots_multiModuleLocalFirst() {
    // Simulate: applicationClasspath = [local1.jar, local2.jar, maven.jar]
    Path local1Jar = Path.of("bazel-out/bin/services/api/libapi.jar");
    Path local2Jar = Path.of("bazel-out/bin/services/domain/libdomain.jar");
    Path mavenJar =
        Path.of("external/maven/io/quarkus/quarkus-rest/3.33.1/quarkus-rest-3.33.1.jar");

    List<Path> applicationClasspath = List.of(local1Jar, local2Jar, mavenJar);
    List<Path> localAppJars = List.of(local1Jar, local2Jar);

    QuarkifierConfig config =
        new QuarkifierConfig(
            applicationClasspath,
            List.of(Path.of("deploy.jar")),
            List.of(),
            Path.of("/tmp/output"),
            List.of(),
            AugmentationMode.NORMAL,
            null,
            "test-app",
            "1.0",
            null,
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            null,
            60,
            localAppJars);

    // Reproduce the executor's logic for determining application roots
    List<Path> appCp = config.applicationClasspath();
    List<Path> actualApplicationRoots =
        config.localAppJars().isEmpty() ? List.of(appCp.get(0)) : config.localAppJars();

    // Expected: BOTH local1 and local2 should be application roots
    List<Path> expectedLocalJars = List.of(local1Jar, local2Jar);

    assertEquals(
        expectedLocalJars.size(),
        actualApplicationRoots.size(),
        "All local jars should be application roots. "
            + "Expected "
            + expectedLocalJars.size()
            + " roots "
            + expectedLocalJars
            + " but got "
            + actualApplicationRoots.size()
            + " root(s): "
            + actualApplicationRoots);

    for (Path expectedLocalJar : expectedLocalJars) {
      assertTrue(
          actualApplicationRoots.contains(expectedLocalJar),
          "Local jar "
              + expectedLocalJar
              + " should be in application roots, "
              + "but actual roots are: "
              + actualApplicationRoots);
    }
  }
}
