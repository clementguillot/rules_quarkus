package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for {@link EnrichExtensionCommand} via picocli. */
class EnrichExtensionCommandTest {

  @TempDir Path tempDir;

  @Test
  void execute_validArgs_producesEnrichedYaml() throws IOException {
    Path runtimeJar = createRuntimeJar("name: \"cli-ext\"\nmetadata:\n");
    Path depJar = createExtensionJar("io.quarkus", "quarkus-arc");
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, depJar.toString());
    Path output = tempDir.resolve("output.yaml");

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "enrich-extension",
                runtimeJar.toString(),
                output.toString(),
                "3.33.2",
                cpFile.toString(),
                "cli-ext");

    assertEquals(0, exitCode);
    String result = Files.readString(output);
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
    assertTrue(result.contains("    - \"io.quarkus:quarkus-arc\""));
  }

  @Test
  void execute_missingArgs_exitCodeTwo() {
    int exitCode = QuarkifierCommand.createCommandLine().execute("enrich-extension");

    assertEquals(2, exitCode);
  }

  @Test
  void execute_help_exitCodeZero() {
    int exitCode = QuarkifierCommand.createCommandLine().execute("enrich-extension", "--help");

    assertEquals(0, exitCode);
  }

  @Test
  void execute_missingRuntimeJar_exitCodeOne() throws IOException {
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, "");
    Path output = tempDir.resolve("output.yaml");

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "enrich-extension",
                "/nonexistent/runtime.jar",
                output.toString(),
                "3.33.2",
                cpFile.toString(),
                "broken");

    assertEquals(1, exitCode);
  }

  @Test
  void execute_noExtensionYamlInJar_producesDefaultOutput() throws IOException {
    // Jar without META-INF/quarkus-extension.yaml
    Path emptyJar = tempDir.resolve("empty-runtime.jar");
    try (var jos = new JarOutputStream(new FileOutputStream(emptyJar.toFile()))) {
      jos.putNextEntry(new JarEntry("com/example/Foo.class"));
      jos.closeEntry();
    }
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, "");
    Path output = tempDir.resolve("output.yaml");

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "enrich-extension",
                emptyJar.toString(),
                output.toString(),
                "3.33.2",
                cpFile.toString(),
                "fallback-name");

    assertEquals(0, exitCode);
    String result = Files.readString(output);
    assertTrue(result.contains("name: \"fallback-name\""));
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
  }

  // ---- helpers ----

  private Path createRuntimeJar(String yamlContent) throws IOException {
    Path jar = tempDir.resolve("runtime.jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
      jos.write(yamlContent.getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
    }
    return jar;
  }

  private Path createExtensionJar(String groupId, String artifactId) throws IOException {
    Path jar = tempDir.resolve(artifactId + "-3.33.2.jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      // Extension marker
      jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
      jos.write(
          ("deployment-artifact=" + groupId + ":" + artifactId + "-deployment:3.33.2\n")
              .getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
      // pom.properties
      String pomPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
      jos.putNextEntry(new JarEntry(pomPath));
      Properties props = new Properties();
      props.setProperty("groupId", groupId);
      props.setProperty("artifactId", artifactId);
      props.setProperty("version", "3.33.2");
      props.store(jos, null);
      jos.closeEntry();
    }
    return jar;
  }
}
