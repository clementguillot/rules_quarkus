package com.clementguillot.rules_quarkus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Hermetic replacement for the shell-based quarkus-extension.yaml enrichment.
 *
 * <p>Reproduces what the Maven/Gradle Quarkus extension plugin appends: the Quarkus core version
 * and the extension-dependencies list (other Quarkus extensions on the compile classpath,
 * discovered via their embedded pom.properties).
 *
 * <p>Usage: EnrichExtensionYaml &lt;runtime.jar&gt; &lt;output.yaml&gt; &lt;quarkusVersion&gt;
 * &lt;classpath.params&gt; &lt;extensionName&gt;
 */
public final class EnrichExtensionYaml {

  public static void main(String[] args) throws IOException {
    if (args.length != 5) {
      System.err.println(
          "Usage: EnrichExtensionYaml <runtime.jar> <output.yaml>"
              + " <quarkusVersion> <classpath.params> <extensionName>");
      System.exit(1);
    }

    Path runtimeJar = Path.of(args[0]);
    Path output = Path.of(args[1]);
    String quarkusVersion = args[2];
    Path classpathFile = Path.of(args[3]);
    String extensionName = args[4];

    // Discover extension dependencies from the compile classpath.
    TreeSet<String> deps = new TreeSet<>();
    try (BufferedReader reader = Files.newBufferedReader(classpathFile)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        Path jar = Path.of(line);
        if (!Files.isRegularFile(jar)) {
          continue;
        }
        if (!hasEntry(jar, "META-INF/quarkus-extension.properties")) {
          continue;
        }
        String coords = extractPomCoordinates(jar);
        if (coords != null) {
          deps.add(coords);
        }
      }
    }

    String baseYaml = extractEntryText(runtimeJar, "META-INF/quarkus-extension.yaml");
    Files.writeString(
        output,
        enrichMetadata(baseYaml, extensionName, quarkusVersion, List.copyOf(deps)),
        StandardCharsets.UTF_8);
  }

  private static String enrichMetadata(
      String baseYaml, String extensionName, String quarkusVersion, List<String> deps)
      throws IOException {
    if (baseYaml == null || baseYaml.isBlank()) {
      baseYaml = "name: \"" + quoteYaml(extensionName) + "\"\nmetadata:\n";
    }

    String normalized = baseYaml.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }

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
    generated.add("  built-with-quarkus-core: \"" + quoteYaml(quarkusVersion) + "\"");
    generated.add("  extension-dependencies:");
    for (String coord : deps) {
      generated.add("    - \"" + quoteYaml(coord) + "\"");
    }
    lines.addAll(metadataEnd, generated);

    return String.join("\n", lines) + "\n";
  }

  private static int findTopLevelMetadataLine(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("metadata:")) {
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

  private static void stripGeneratedMetadata(List<String> lines, int start, int end) {
    for (int i = start; i < end; ) {
      String line = lines.get(i);
      if (line.startsWith("  built-with-quarkus-core:")) {
        lines.remove(i);
        end--;
        continue;
      }
      if (line.startsWith("  extension-dependencies:")) {
        lines.remove(i);
        end--;
        while (i < end && isExtensionDependencyListLine(lines.get(i))) {
          lines.remove(i);
          end--;
        }
        continue;
      }
      i++;
    }
  }

  private static boolean isExtensionDependencyListLine(String line) {
    return line.startsWith("  - ") || line.startsWith("    - ") || line.isBlank();
  }

  private static String quoteYaml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Extracts text content of a single jar entry, or null if absent/unreadable. */
  private static String extractEntryText(Path jar, String entryName) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      JarEntry entry = jf.getJarEntry(entryName);
      if (entry == null) {
        return null;
      }
      try (InputStream is = jf.getInputStream(entry)) {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      return null;
    }
  }

  /** Checks whether a jar contains a given entry path. */
  private static boolean hasEntry(Path jar, String entryName) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      return jf.getJarEntry(entryName) != null;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Extracts "groupId:artifactId" from pom.properties under META-INF/maven/ in the jar, or null if
   * none.
   *
   * <p>Shaded jars can carry several pom.properties (their own plus relocated dependencies'), and
   * jar entry order is arbitrary — so prefer the entry whose artifactId matches the jar's file name
   * (Maven convention: {@code artifactId-version.jar}) over whichever happens to come first.
   */
  private static String extractPomCoordinates(Path jar) {
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
    } catch (IOException e) {
      // Fall through
    }
    return fallback;
  }
}
