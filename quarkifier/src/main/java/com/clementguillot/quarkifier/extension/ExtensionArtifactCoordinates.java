package com.clementguillot.quarkifier.extension;

import java.io.IOException;
import java.util.List;

/** Completes the top-level artifact coordinates in a Quarkus extension descriptor. */
final class ExtensionArtifactCoordinates {

  private ExtensionArtifactCoordinates() {}

  /** Mirrors the Maven/Gradle extension plugins' artifact-coordinate completion semantics. */
  static void ensure(List<String> lines, ExtensionProjectInfo project) throws IOException {
    int artifactLine = findTopLevelKey(lines, "artifact");
    String artifact = artifactLine < 0 ? null : scalarValue(lines.get(artifactLine));
    String[] coordinates = artifact == null ? new String[0] : artifact.split(":", -1);

    String groupId = coordinateOrNull(coordinates, 0, "${project.groupId}");
    String artifactId = coordinateOrNull(coordinates, 1, "${project.artifactId}");
    String version = coordinateOrNull(coordinates, 2, "${project.version}");
    if (artifactLine < 0) {
      groupId = topLevelScalarOrNull(lines, "groupId", "${project.groupId}");
      artifactId = topLevelScalarOrNull(lines, "artifactId", "${project.artifactId}");
      version = topLevelScalarOrNull(lines, "version", "${project.version}");
    }

    if (artifactLine >= 0 && groupId != null && artifactId != null && version != null) {
      return;
    }

    String completed =
        (groupId == null ? project.groupId() : groupId)
            + ":"
            + (artifactId == null ? project.artifactId() : artifactId)
            + "::jar:"
            + (version == null ? project.version() : version);
    String completedLine = "artifact: \"" + quoteYaml(completed) + "\"";
    if (artifactLine >= 0) {
      lines.set(artifactLine, completedLine);
    } else {
      int metadataLine = findTopLevelKey(lines, "metadata");
      lines.add(metadataLine < 0 ? lines.size() : metadataLine, completedLine);
    }
  }

  private static String coordinateOrNull(String[] coordinates, int index, String propertyExpr) {
    return index < coordinates.length
        ? realValueOrNull(coordinates[index].trim(), propertyExpr)
        : null;
  }

  private static String topLevelScalarOrNull(List<String> lines, String key, String propertyExpr)
      throws IOException {
    int line = findTopLevelKey(lines, key);
    return line < 0 ? null : realValueOrNull(scalarValue(lines.get(line)), propertyExpr);
  }

  private static String realValueOrNull(String value, String propertyExpr) {
    return value == null || value.isBlank() || value.equals(propertyExpr) ? null : value;
  }

  private static int findTopLevelKey(List<String> lines, String key) {
    String prefix = key + ":";
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private static String scalarValue(String line) throws IOException {
    int separator = line.indexOf(':');
    String value = line.substring(separator + 1).trim();
    if (value.isEmpty()) {
      return null;
    }
    if (value.charAt(0) == '\'' || value.charAt(0) == '"') {
      char quote = value.charAt(0);
      int end = value.indexOf(quote, 1);
      if (end < 0) {
        throw new IOException("Unterminated quoted YAML scalar: " + line);
      }
      return value.substring(1, end);
    }
    int comment = value.indexOf(" #");
    return comment < 0 ? value : value.substring(0, comment).trim();
  }

  private static String quoteYaml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
