package com.clementguillot.rules_quarkus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
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

    // Start from the extension's own quarkus-extension.yaml (carried in the runtime jar).
    String baseYaml = extractEntryText(runtimeJar, "META-INF/quarkus-extension.yaml");
    if (baseYaml == null || baseYaml.isBlank()) {
      baseYaml = "name: \"" + extensionName + "\"\nmetadata:\n";
    }

    // Append build metadata.
    StringBuilder sb = new StringBuilder(baseYaml);
    sb.append("  built-with-quarkus-core: \"").append(quarkusVersion).append("\"\n");
    sb.append("  extension-dependencies:\n");

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

    for (String coord : deps) {
      sb.append("  - \"").append(coord).append("\"\n");
    }

    Files.writeString(output, sb.toString());
  }

  /** Extracts text content of a single jar entry, or null if absent/unreadable. */
  private static String extractEntryText(Path jar, String entryName) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      JarEntry entry = jf.getJarEntry(entryName);
      if (entry == null) {
        return null;
      }
      try (InputStream is = jf.getInputStream(entry)) {
        return new String(is.readAllBytes());
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
   * Extracts "groupId:artifactId" from the first pom.properties found under META-INF/maven/ in the
   * jar, or null if none.
   */
  private static String extractPomCoordinates(Path jar) {
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
          return groupId + ":" + artifactId;
        }
      }
    } catch (IOException e) {
      // Fall through
    }
    return null;
  }
}
