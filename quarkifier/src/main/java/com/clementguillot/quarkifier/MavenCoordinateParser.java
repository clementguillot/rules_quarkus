package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.Set;

/**
 * Extracts Maven coordinates (groupId, artifactId, version) from jar file paths.
 *
 * <p>Handles Bazel maven repository paths like: {@code
 * .../io/quarkus/quarkus-arc/3.20.6/processed_quarkus-arc-3.20.6.jar}
 */
public final class MavenCoordinateParser {

  /** Path segments that mark the boundary before Maven groupId segments begin. */
  private static final Set<String> STOP_SEGMENTS =
      Set.of("external", "v1", "https", "file", "bin", "repo", "jars");

  /** Prefixes that mark non-groupId segments (Bazel/OS roots). */
  private static final String[] STOP_PREFIXES = {"maven", "bazel-", "darwin", "linux", "windows"};

  private MavenCoordinateParser() {}

  /** Parsed Maven coordinates. */
  public record Coordinates(String groupId, String artifactId, String version) {}

  /**
   * Attempts to parse Maven coordinates from a jar path.
   *
   * <p>Looks for the pattern {@code .../groupId-segments/artifactId/version/filename.jar} by
   * working backwards from the filename.
   *
   * @return parsed coordinates, or a fallback based on the filename
   */
  public static Coordinates parse(Path jarPath) {
    String[] parts = jarPath.toString().replace('\\', '/').split("/");

    if (parts.length < 4) {
      return fallback(jarPath);
    }

    String filename = parts[parts.length - 1];
    String version = parts[parts.length - 2];
    String artifactId = parts[parts.length - 3];

    String expectedSuffix = artifactId + "-" + version + ".jar";
    if (!filename.equals(expectedSuffix) && !filename.equals("processed_" + expectedSuffix)) {
      return fallback(jarPath);
    }

    int groupIdEnd = parts.length - 4;
    int groupIdStart = groupIdEnd;
    for (int i = groupIdEnd; i >= 0; i--) {
      if (isStopSegment(parts[i])) break;
      groupIdStart = i;
    }

    if (groupIdStart > groupIdEnd) {
      return fallback(jarPath);
    }

    return new Coordinates(String.join(".", java.util.Arrays.copyOfRange(parts, groupIdStart, groupIdEnd + 1)), artifactId, version);
  }

  private static boolean isStopSegment(String segment) {
    if (segment.isEmpty() || segment.contains("+") || segment.contains("=") || segment.contains(".")) {
      return true;
    }
    if (STOP_SEGMENTS.contains(segment)) return true;
    for (String prefix : STOP_PREFIXES) {
      if (segment.startsWith(prefix)) return true;
    }
    return false;
  }

  private static Coordinates fallback(Path jarPath) {
    String name = jarPath.getFileName().toString();
    if (name.startsWith("processed_")) {
      name = name.substring("processed_".length());
    }
    if (name.endsWith(".jar")) {
      name = name.substring(0, name.length() - 4);
    }

    // Try to extract artifactId and version from "artifactId-version" pattern.
    // Version typically starts with a digit, so find the last "-" followed by a digit.
    int versionSep = -1;
    for (int i = name.length() - 1; i > 0; i--) {
      if (name.charAt(i - 1) == '-' && Character.isDigit(name.charAt(i))) {
        versionSep = i - 1;
        break;
      }
    }

    if (versionSep > 0) {
      String artifactId = name.substring(0, versionSep);
      String version = name.substring(versionSep + 1);
      return new Coordinates("unknown", artifactId, version);
    }

    return new Coordinates("unknown", name, "0.0.0");
  }
}
