package com.clementguillot.quarkifier.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Extracts Maven coordinates (groupId, artifactId, version) from jar file paths.
 *
 * <p>Handles Bazel maven repository paths like: {@code
 * .../io/quarkus/quarkus-arc/3.27.4/processed_quarkus-arc-3.27.4.jar}
 */
public final class MavenCoordinateParser {

  /** Path segments that mark the boundary before Maven groupId segments begin. */
  private static final Set<String> STOP_SEGMENTS =
      Set.of("external", "v1", "https", "file", "bin", "repo", "jars", "maven2");

  /** Prefixes that mark non-groupId segments (Bazel/OS roots). */
  private static final String[] STOP_PREFIXES = {"bazel-", "darwin", "linux", "windows"};

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
    // Try to resolve symlinks to get the original path (e.g., Coursier cache)
    // which has the full Maven directory structure for groupId extraction.
    Path resolvedPath = jarPath;
    try {
      resolvedPath = jarPath.toRealPath();
    } catch (IOException ignored) {
    }

    String[] parts = resolvedPath.toString().replace('\\', '/').split("/");

    if (parts.length < 4) {
      return fallbackWithJarIntrospection(jarPath);
    }

    String filename = parts[parts.length - 1];
    String version = parts[parts.length - 2];
    String artifactId = parts[parts.length - 3];

    String expectedSuffix = artifactId + "-" + version + ".jar";
    if (!filename.equals(expectedSuffix) && !filename.equals("processed_" + expectedSuffix)) {
      return fallbackWithJarIntrospection(jarPath);
    }

    int groupIdEnd = parts.length - 4;
    int groupIdStart = groupIdEnd;
    for (int i = groupIdEnd; i >= 0; i--) {
      if (isStopSegment(parts[i])) {
        break;
      }
      groupIdStart = i;
    }

    if (groupIdStart > groupIdEnd) {
      return fallback(jarPath);
    }

    return new Coordinates(
        String.join(".", Arrays.copyOfRange(parts, groupIdStart, groupIdEnd + 1)),
        artifactId,
        version);
  }

  /**
   * Checks whether a path segment is a boundary marker that precedes the Maven groupId. Segments
   * containing dots (e.g. "repo1.maven.org"), Bazel canonical repo markers, or known Bazel/OS
   * prefixes are not part of the groupId.
   */
  private static boolean isStopSegment(String segment) {
    if (segment.isEmpty()
        || segment.contains("+")
        || segment.contains("~")
        || segment.contains("=")
        || segment.contains(".")) {
      return true;
    }
    if (STOP_SEGMENTS.contains(segment)) {
      return true;
    }
    for (String prefix : STOP_PREFIXES) {
      if (segment.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Attempts to read Maven coordinates from pom.properties inside the jar before falling back to
   * filename-only parsing. This handles Bazel-built jars (e.g. libdeployment.jar) that carry
   * embedded pom.properties but have non-Maven-layout paths.
   */
  private static Coordinates fallbackWithJarIntrospection(Path jarPath) {
    if (jarPath.toString().endsWith(".jar")) {
      try (JarFile jf = new JarFile(jarPath.toFile())) {
        var entries = jf.entries();
        while (entries.hasMoreElements()) {
          var entry = entries.nextElement();
          if (!entry.getName().startsWith("META-INF/maven/")
              || !entry.getName().endsWith("/pom.properties")) {
            continue;
          }
          Properties props = new Properties();
          try (InputStream is = jf.getInputStream(entry)) {
            props.load(is);
          }
          String groupId = props.getProperty("groupId");
          String artifactId = props.getProperty("artifactId");
          String version = props.getProperty("version", "0.0.0");
          if (groupId != null && artifactId != null) {
            return new Coordinates(groupId, artifactId, version);
          }
        }
      } catch (IOException ignored) {
        // Fall through to filename-based fallback
      }
    }
    return fallback(jarPath);
  }

  /**
   * Fallback when the path doesn't match the standard Maven layout. Extracts artifactId and version
   * from the filename alone (e.g. {@code quarkus-arc-3.27.4.jar} → {@code quarkus-arc} / {@code
   * 3.27.4}). Returns {@code "unknown"} for groupId.
   */
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
