package com.clementguillot.quarkifier.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link VersionChecker}. */
class VersionCheckerTest {

  @Test
  void matchingVersions_noWarnings() {
    var capture = new CapturedOutput();
    List<ExtensionInfo> mismatched =
        VersionChecker.check(
            List.of(ext("quarkus-arc", "3.20.6"), ext("quarkus-resteasy-reactive", "3.20.6")),
            "3.20.6",
            capture.stream);

    assertEquals(0, mismatched.size());
    assertEquals("", capture.text());
  }

  @Test
  void mismatchedVersions_producesWarnings() {
    var capture = new CapturedOutput();
    List<ExtensionInfo> mismatched =
        VersionChecker.check(
            List.of(ext("quarkus-arc", "3.18.0"), ext("quarkus-resteasy-reactive", "3.19.0")),
            "3.20.6",
            capture.stream);

    assertEquals(2, mismatched.size());
    assertTrue(capture.text().contains("quarkus-arc:3.18.0"));
    assertTrue(capture.text().contains("quarkus-resteasy-reactive:3.19.0"));
  }

  @Test
  void nullExpectedVersion_skipsCheck() {
    var capture = new CapturedOutput();
    List<ExtensionInfo> mismatched =
        VersionChecker.check(List.of(ext("quarkus-arc", "3.18.0")), null, capture.stream);

    assertEquals(0, mismatched.size());
    assertEquals("", capture.text());
  }

  @Test
  void mixedVersions_onlyMismatchesReported() {
    var capture = new CapturedOutput();
    List<ExtensionInfo> mismatched =
        VersionChecker.check(
            List.of(ext("quarkus-arc", "3.20.6"), ext("quarkus-resteasy-reactive", "3.18.0")),
            "3.20.6",
            capture.stream);

    assertEquals(1, mismatched.size());
    assertFalse(capture.text().contains("quarkus-arc"));
    assertTrue(capture.text().contains("quarkus-resteasy-reactive:3.18.0"));
  }

  @Test
  void emptyExtensionList() {
    var capture = new CapturedOutput();
    assertEquals(0, VersionChecker.check(List.of(), "3.20.6", capture.stream).size());
    assertEquals("", capture.text());
  }

  // --- helpers ---

  private static ExtensionInfo ext(String artifactId, String version) {
    return new ExtensionInfo(
        "io.quarkus", artifactId, version, Path.of(artifactId + "-" + version + ".jar"));
  }

  private static final class CapturedOutput {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final PrintStream stream = new PrintStream(baos);

    String text() {
      stream.flush();
      return baos.toString(StandardCharsets.UTF_8).trim();
    }
  }
}
