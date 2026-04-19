package com.clementguillot.quarkifier.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link MavenCoordinateParser} covering standard Maven repo paths, Bazel processed_
 * prefixed paths, and Coursier-style short paths (fallback).
 */
class MavenCoordinateParserTest {

  @Test
  void parseStandardMavenPath() {
    var path = Path.of("/repo/io/quarkus/quarkus-arc/3.20.6/quarkus-arc-3.20.6.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-arc", coords.artifactId());
    assertEquals("3.20.6", coords.version());
  }

  @Test
  void parseProcessedPrefixPath() {
    var path = Path.of("/repo/io/quarkus/quarkus-arc/3.20.6/processed_quarkus-arc-3.20.6.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-arc", coords.artifactId());
    assertEquals("3.20.6", coords.version());
  }

  @Test
  void parseBazelExternalMavenPath() {
    var path =
        Path.of(
            "/private/var/bazel/external/maven/v1/https/repo1.maven.org/maven2/"
                + "io/quarkus/quarkus-rest/3.20.6/processed_quarkus-rest-3.20.6.jar");
    var coords = MavenCoordinateParser.parse(path);
    assertEquals("io.quarkus", coords.groupId());
    assertEquals("quarkus-rest", coords.artifactId());
    assertEquals("3.20.6", coords.version());
  }

  @ParameterizedTest
  @CsvSource({
    "quarkus-arc-3.20.6.jar, quarkus-arc, 3.20.6",
    "processed_quarkus-arc-3.20.6.jar, quarkus-arc, 3.20.6",
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
    var path =
        Path.of("/repo/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");
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
    var mavenPath =
        Path.of("/repo/io/quarkus/quarkus-arc/3.20.6/processed_quarkus-arc-3.20.6.jar");
    var mavenCoords = MavenCoordinateParser.parse(mavenPath);

    var coursierPath = Path.of("jars/quarkus-arc-3.20.6.jar");
    var coursierCoords = MavenCoordinateParser.parse(coursierPath);

    assertEquals(mavenCoords.artifactId(), coursierCoords.artifactId());
    assertEquals(mavenCoords.version(), coursierCoords.version());
  }
}
