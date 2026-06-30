package com.clementguillot.quarkifier.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link MavenCoordinateParser} covering standard Maven repo paths, Bazel processed_
 * prefixed paths, and Coursier-style short paths (fallback).
 */
class MavenCoordinateParserTest {

  @Test
  void parseStandardMavenPath() {
    var path = Path.of("/repo/io/quarkus/quarkus-arc/3.27.4/quarkus-arc-3.27.4.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-arc", coords.artifactId());
    assertEquals("3.27.4", coords.version());
  }

  @Test
  void parseProcessedPrefixPath() {
    var path = Path.of("/repo/io/quarkus/quarkus-arc/3.27.4/processed_quarkus-arc-3.27.4.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-arc", coords.artifactId());
    assertEquals("3.27.4", coords.version());
  }

  @Test
  void parseBazelExternalMavenPath() {
    var path =
        Path.of(
            "/private/var/bazel/external/maven/v1/https/repo1.maven.org/maven2/"
                + "io/quarkus/quarkus-rest/3.27.4/processed_quarkus-rest-3.27.4.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-rest", coords.artifactId());
    assertEquals("3.27.4", coords.version());
  }

  @Test
  void parseBazelCanonicalExternalRepoPath() {
    var path =
        Path.of(
            "/private/var/bazel/external/rules_jvm_external~~maven~maven/"
                + "io/smallrye/reactive/mutiny/3.1.1/processed_mutiny-3.1.1.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.smallrye.reactive", coords.groupId());
    assertEquals("mutiny", coords.artifactId());
    assertEquals("3.1.1", coords.version());
  }

  @Test
  void parseBazelGeneratedExtensionRuntimePath() {
    var path =
        Path.of(
            "/private/var/tmp/_bazel/user/execroot/_main/bazel-out/darwin-fastbuild/bin/"
                + "greeting-extension/runtime/maven2/com/example/greeting-extension/"
                + "1.0.0-SNAPSHOT/greeting-extension-1.0.0-SNAPSHOT.jar");

    var coords = MavenCoordinateParser.parse(path);

    assertEquals("com.example", coords.groupId());
    assertEquals("greeting-extension", coords.artifactId());
    assertEquals("1.0.0-SNAPSHOT", coords.version());
  }

  @Test
  void parseJarIntrospectionFallback(@TempDir Path tmp) throws IOException {
    // Build a jar with embedded pom.properties but a non-Maven-layout path
    Path jar = tmp.resolve("libdeployment.jar");
    try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
      jos.putNextEntry(new JarEntry("META-INF/maven/io.quarkus/quarkus-core/pom.properties"));
      jos.write("groupId=io.quarkus\nartifactId=quarkus-core\nversion=3.33.2\n".getBytes());
      jos.closeEntry();
    }

    var coords = MavenCoordinateParser.parse(jar);

    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-core", coords.artifactId());
    assertEquals("3.33.2", coords.version());
  }

  @ParameterizedTest
  @CsvSource({
    "quarkus-arc-3.27.4.jar, quarkus-arc, 3.27.4",
    "processed_quarkus-arc-3.27.4.jar, quarkus-arc, 3.27.4",
    "jakarta.ws.rs-api-4.0.0.jar, jakarta.ws.rs-api, 4.0.0",
    "smallrye-common-annotation-2.5.0.jar, smallrye-common-annotation, 2.5.0",
  })
  void fallbackExtractsArtifactIdAndVersion(
      String filename, String expectedArtifact, String expectedVersion) {
    var path = Path.of("jars/" + filename);
    var coords = MavenCoordinateParser.parse(path);
    assertEquals(expectedArtifact, coords.artifactId());
    assertEquals(expectedVersion, coords.version());
  }

  @Test
  void fallbackWithNoVersionReturnsZero() {
    var path = Path.of("jars/some-library.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("unknown", coords.groupId());
    assertEquals("some-library", coords.artifactId());
    assertEquals("0.0.0", coords.version());
  }

  @Test
  void parseDeepGroupId() {
    var path = Path.of("/repo/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("org.apache.commons", coords.groupId());
    assertEquals("commons-lang3", coords.artifactId());
    assertEquals("3.14.0", coords.version());
  }

  @Test
  void parseSingleSegmentGroupId() {
    var path = Path.of("/repo/single/my-lib/1.0.0/my-lib-1.0.0.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("single", coords.groupId());
    assertEquals("my-lib", coords.artifactId());
    assertEquals("1.0.0", coords.version());
  }

  @Test
  void sameArtifactFromDifferentSourcesProducesSameArtifactIdAndVersion() {
    var mavenPath = Path.of("/repo/io/quarkus/quarkus-arc/3.27.4/processed_quarkus-arc-3.27.4.jar");
    var mavenCoords = MavenCoordinateParser.parse(mavenPath);

    var coursierPath = Path.of("jars/quarkus-arc-3.27.4.jar");
    var coursierCoords = MavenCoordinateParser.parse(coursierPath);

    assertEquals(mavenCoords.artifactId(), coursierCoords.artifactId());
    assertEquals(mavenCoords.version(), coursierCoords.version());
  }
}
