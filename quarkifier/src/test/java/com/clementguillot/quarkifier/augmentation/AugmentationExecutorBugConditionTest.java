package com.clementguillot.quarkifier.augmentation;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the classpath partitioning logic in {@link AugmentationExecutor}.
 *
 * <p>These tests drive assertions through the production {@code partitionClasspath()} helper to
 * ensure that the actual executor logic correctly separates local workspace jars from external
 * Maven jars.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4</b>
 */
class AugmentationExecutorBugConditionTest {

  /**
   * When --local-app-jars identifies local jars and a Maven jar is first in the classpath, the
   * production partitioning logic places all local jars in {@code localAppJars} and the Maven jar
   * in {@code runtimeJars}.
   */
  @Test
  void partitionClasspath_localAppJarsProvided_mavenJarFirst() throws AugmentationException {
    Path mavenJar =
        Path.of("external/maven/io/quarkus/quarkus-rest/3.33.2/quarkus-rest-3.33.2.jar");
    Path local1Jar = Path.of("bazel-out/bin/services/api/libapi.jar");
    Path local2Jar = Path.of("bazel-out/bin/services/domain/libdomain.jar");

    QuarkifierConfig config =
        configWith(List.of(mavenJar, local1Jar, local2Jar), List.of(local1Jar, local2Jar));

    AugmentationExecutor.ClasspathPartition partition =
        AugmentationExecutor.partitionClasspath(config);

    assertEquals(
        List.of(local1Jar, local2Jar),
        partition.localAppJars(),
        "All local jars should be application roots");
    assertEquals(
        List.of(mavenJar), partition.runtimeJars(), "Maven jar should be a runtime dependency");
  }

  /**
   * Multi-module project with local jars first in the classpath. The production partitioning must
   * include ALL local jars as application roots, not just the first one.
   */
  @Test
  void partitionClasspath_localAppJarsProvided_multiModuleLocalFirst()
      throws AugmentationException {
    Path local1Jar = Path.of("bazel-out/bin/services/api/libapi.jar");
    Path local2Jar = Path.of("bazel-out/bin/services/domain/libdomain.jar");
    Path mavenJar =
        Path.of("external/maven/io/quarkus/quarkus-rest/3.33.2/quarkus-rest-3.33.2.jar");

    QuarkifierConfig config =
        configWith(List.of(local1Jar, local2Jar, mavenJar), List.of(local1Jar, local2Jar));

    AugmentationExecutor.ClasspathPartition partition =
        AugmentationExecutor.partitionClasspath(config);

    assertEquals(2, partition.localAppJars().size(), "Both local jars should be application roots");
    assertTrue(partition.localAppJars().contains(local1Jar));
    assertTrue(partition.localAppJars().contains(local2Jar));
    assertFalse(
        partition.localAppJars().contains(mavenJar), "Maven jar must not be an application root");
    assertEquals(List.of(mavenJar), partition.runtimeJars());
  }

  /**
   * When --local-app-jars is empty (backward compat), the production partitioning falls back to
   * appCp.get(0) as the sole application root.
   */
  @Test
  void partitionClasspath_noLocalAppJars_fallsBackToFirstEntry() throws AugmentationException {
    Path firstJar = Path.of("bazel-out/bin/lib/liblib.jar");
    Path secondJar =
        Path.of("external/maven/io/quarkus/quarkus-core/3.33.2/quarkus-core-3.33.2.jar");

    QuarkifierConfig config =
        configWith(List.of(firstJar, secondJar), List.of()); // no --local-app-jars

    AugmentationExecutor.ClasspathPartition partition =
        AugmentationExecutor.partitionClasspath(config);

    assertEquals(List.of(firstJar), partition.localAppJars(), "Should fall back to appCp.get(0)");
    assertEquals(List.of(secondJar), partition.runtimeJars());
  }

  /**
   * When --local-app-jars contains a jar not in the application classpath, the production code
   * should throw.
   */
  @Test
  void partitionClasspath_localAppJarNotInClasspath_throws() {
    Path appJar = Path.of("bazel-out/bin/lib/liblib.jar");
    Path unknownJar = Path.of("bazel-out/bin/other/libother.jar");

    QuarkifierConfig config =
        configWith(
            List.of(appJar), List.of(unknownJar)); // unknownJar is not in applicationClasspath

    AugmentationException ex =
        assertThrows(
            AugmentationException.class, () -> AugmentationExecutor.partitionClasspath(config));
    assertTrue(ex.getMessage().contains("must be a subset"));
  }

  // ---- Helper ----

  private static QuarkifierConfig configWith(
      List<Path> applicationClasspath, List<Path> localAppJars) {
    return new QuarkifierConfig(
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
  }
}
