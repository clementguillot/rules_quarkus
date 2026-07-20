package com.clementguillot.quarkifier.extension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Enriches a {@code quarkus-extension.yaml} with build metadata.
 *
 * <p>Appends the Quarkus core version and extension-dependency list (other Quarkus extensions on
 * the compile classpath, discovered via their embedded {@code pom.properties}).
 */
@SuppressWarnings("PMD.GodClass") // single-purpose utility; cohesion is high despite metric
public final class ExtensionYamlEnricher {

  private ExtensionYamlEnricher() {}

  /**
   * Enriches the extension yaml from the given runtime jar and writes the result to output.
   *
   * @param runtimeJar jar containing the base {@code META-INF/quarkus-extension.yaml}
   * @param output path where the enriched yaml is written
   * @param quarkusVersion Quarkus core version string
   * @param classpathFile file listing the compile classpath (one jar per line)
   * @param project extension project metadata
   */
  public static void enrich(
      Path runtimeJar,
      Path output,
      String quarkusVersion,
      Path classpathFile,
      ExtensionProjectInfo project)
      throws IOException {
    Set<String> deps = discoverExtensionDependencies(classpathFile);
    String baseYaml = extractEntryText(runtimeJar, "META-INF/quarkus-extension.yaml");
    String enriched = enrichMetadata(baseYaml, project, quarkusVersion, List.copyOf(deps));
    Files.writeString(output, enriched, StandardCharsets.UTF_8);
  }

  // ---- extension dependency discovery ----

  /**
   * Scans jars on the classpath file for Quarkus extensions and returns their coordinates.
   *
   * @return sorted set of "groupId:artifactId" strings
   */
  static Set<String> discoverExtensionDependencies(Path cpFile) throws IOException {
    Set<String> deps = new TreeSet<>();
    for (String entry : Files.readAllLines(cpFile)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      Path jar = Path.of(trimmed);
      if (!Files.isRegularFile(jar) || !hasEntry(jar, "META-INF/quarkus-extension.properties")) {
        continue;
      }
      String coords = extractPomCoordinates(jar);
      if (coords != null) {
        deps.add(coords);
      }
    }
    return deps;
  }

  // ---- yaml enrichment ----

  @SuppressWarnings("PMD.CognitiveComplexity")
  static String enrichMetadata(
      String inputYaml, ExtensionProjectInfo project, String qVersion, List<String> deps)
      throws IOException {
    String yaml =
        (inputYaml == null || inputYaml.isBlank())
            ? "name: \"" + quoteYaml(project.name()) + "\"\nmetadata:\n"
            : inputYaml;

    String normalized = yaml.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }

    ExtensionArtifactCoordinates.ensure(lines, project);

    int metadataLine = findTopLevelMetadataLine(lines);
    if (metadataLine < 0) {
      if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
        lines.add("");
      }
      lines.add("metadata:");
      metadataLine = lines.size() - 1;
    } else if (!lines.get(metadataLine).matches("^metadata\\s*:\\s*(#.*)?$")) {
      throw new IOException("quarkus-extension.yaml metadata must be a block mapping");
    }

    int metadataEnd = findMetadataEnd(lines, metadataLine);
    stripGeneratedMetadata(lines, metadataLine + 1, metadataEnd);
    metadataEnd = findMetadataEnd(lines, metadataLine);

    List<String> generated = new ArrayList<>();
    generated.add("  built-with-quarkus-core: \"" + quoteYaml(qVersion) + "\"");
    generated.add("  extension-dependencies:");
    for (String coord : deps) {
      generated.add("    - \"" + quoteYaml(coord) + "\"");
    }
    lines.addAll(metadataEnd, generated);

    return String.join("\n", lines) + "\n";
  }

  private static int findTopLevelMetadataLine(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("metadata:")) {
        return i;
      }
    }
    return -1;
  }

  private static int findMetadataEnd(List<String> lines, int metadataLine) {
    for (int i = metadataLine + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      if (!Character.isWhitespace(line.charAt(0))) {
        return i;
      }
    }
    return lines.size();
  }

  @SuppressWarnings("PMD.AvoidReassigningParameters")
  private static void stripGeneratedMetadata(List<String> lines, int start, int end) {
    for (int i = start; i < end; ) {
      String line = lines.get(i);
      if (line.startsWith("  built-with-quarkus-core:")) {
        lines.remove(i);
        end--;
      } else if (line.startsWith("  extension-dependencies:")) {
        int dependencyIndent = line.length() - line.stripLeading().length();
        lines.remove(i);
        end--;
        while (i < end) {
          String nestedLine = lines.get(i);
          String stripped = nestedLine.stripLeading();
          int indent = nestedLine.length() - stripped.length();
          boolean belongs =
              nestedLine.isBlank()
                  || (stripped.startsWith("#") && indent >= dependencyIndent)
                  || indent > dependencyIndent
                  || (indent == dependencyIndent && stripped.startsWith("-"));
          if (!belongs) {
            break;
          }
          lines.remove(i);
          end--;
        }
      } else {
        i++;
      }
    }
  }

  private static String quoteYaml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // ---- jar utilities ----

  private static String extractEntryText(Path jar, String entryName) throws IOException {
    try (JarFile jf = new JarFile(jar.toFile())) {
      JarEntry entry = jf.getJarEntry(entryName);
      if (entry == null) {
        return null;
      }
      try (InputStream is = jf.getInputStream(entry)) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  private static boolean hasEntry(Path jar, String entryName) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      return jf.getJarEntry(entryName) != null;
    } catch (IOException ignored) {
      return false;
    }
  }

  /**
   * Extracts "groupId:artifactId" from pom.properties in the jar. Prefers the entry whose
   * artifactId matches the jar filename.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock") // best-effort: unreadable jar → skip
  static String extractPomCoordinates(Path jar) {
    String fileName = jar.getFileName().toString();
    String fallback = null;
    try (JarFile jf = new JarFile(jar.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties")) {
          continue;
        }
        Properties props = new Properties();
        try (InputStream is = jf.getInputStream(entry)) {
          props.load(is);
        }
        String groupId = props.getProperty("groupId");
        String artifactId = props.getProperty("artifactId");
        if (groupId != null && artifactId != null) {
          if (fileName.startsWith(artifactId + "-") || fileName.equals(artifactId + ".jar")) {
            return groupId + ":" + artifactId;
          }
          if (fallback == null) {
            fallback = groupId + ":" + artifactId;
          }
        }
      }
    } catch (IOException ignored) {
      // best-effort: unreadable jar → return fallback or null
    }
    return fallback;
  }
}
