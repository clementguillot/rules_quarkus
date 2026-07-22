package com.clementguillot.quarkifier.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ExtensionYamlEnricher}. */
class ExtensionYamlEnricherTest {

  @TempDir Path tempDir;

  // ---- enrichMetadata ----

  @Test
  void enrichMetadata_nullInput_generatesNameAndMetadata() throws IOException {
    String result = enrichMetadata(null, "my-ext", "3.33.2", List.of());

    assertTrue(result.contains("name: \"my-ext\""));
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
    assertTrue(result.contains("extension-dependencies:"));
  }

  @Test
  void enrichMetadata_blankInput_generatesNameAndMetadata() throws IOException {
    String result = enrichMetadata("  ", "my-ext", "3.33.2", List.of());

    assertTrue(result.contains("name: \"my-ext\""));
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
  }

  @Test
  void enrichMetadata_existingYaml_appendsToMetadata() throws IOException {
    String input =
        "name: \"hello\"\ndescription: \"A greeting extension\"\nmetadata:\n  status: stable\n";
    String result = enrichMetadata(input, "hello", "3.27.4", List.of("io.quarkus:quarkus-arc"));

    assertTrue(result.contains("name: \"hello\""));
    assertTrue(result.contains("status: stable"));
    assertTrue(result.contains("built-with-quarkus-core: \"3.27.4\""));
    assertTrue(result.contains("    - \"io.quarkus:quarkus-arc\""));
  }

  @Test
  void enrichMetadata_noMetadataBlock_addsOne() throws IOException {
    String input = "name: \"no-meta\"\ndescription: \"No metadata block\"\n";
    String result = enrichMetadata(input, "no-meta", "3.33.2", List.of());

    assertTrue(result.contains("metadata:"));
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
  }

  @Test
  void enrichMetadata_replacesExistingGeneratedFields() throws IOException {
    String input =
        "name: \"re-enrich\"\n"
            + "metadata:\n"
            + "  built-with-quarkus-core: \"3.27.0\"\n"
            + "  extension-dependencies:\n"
            + "    - \"old:dep\"\n"
            + "  status: preview\n";
    String result = enrichMetadata(input, "re-enrich", "3.33.2", List.of("new:dep"));

    // Old values replaced
    assertFalse(result.contains("3.27.0"));
    assertFalse(result.contains("old:dep"));
    // New values present
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
    assertTrue(result.contains("    - \"new:dep\""));
    // Non-generated fields preserved
    assertTrue(result.contains("status: preview"));
  }

  @Test
  void enrichMetadata_removesEntireGeneratedDependencyBlockWithCommentsAndAlternateIndentation()
      throws IOException {
    String input =
        "name: \"re-enrich\"\n"
            + "metadata:\n"
            + "  extension-dependencies:\n"
            + "    # generated dependency\n"
            + "      - \"old:deeply-indented\"\n"
            + "  - \"old:indentless\"\n"
            + "  status: preview\n";

    String result = enrichMetadata(input, "re-enrich", "3.33.2", List.of("new:dep"));

    assertFalse(result.contains("generated dependency"));
    assertFalse(result.contains("old:deeply-indented"));
    assertFalse(result.contains("old:indentless"));
    assertTrue(result.contains("  status: preview"));
    assertTrue(result.contains("    - \"new:dep\""));
  }

  @Test
  void enrichMetadata_multipleDeps_allPresent() throws IOException {
    String result =
        enrichMetadata(null, "multi", "3.33.2", List.of("b:b-lib", "a:a-lib", "c:c-lib"));

    assertTrue(result.contains("    - \"a:a-lib\""));
    assertTrue(result.contains("    - \"b:b-lib\""));
    assertTrue(result.contains("    - \"c:c-lib\""));
  }

  @Test
  void enrichMetadata_specialCharsInVersion_areEscaped() throws IOException {
    String result = enrichMetadata(null, "esc\"test", "3.33\\2", List.of());

    assertTrue(result.contains("esc\\\"test"));
    assertTrue(result.contains("3.33\\\\2"));
  }

  @Test
  void enrichMetadata_missingArtifact_addsCanonicalProjectCoordinates() throws IOException {
    String result =
        ExtensionYamlEnricher.enrichMetadata(
            "name: local-extension\nmetadata:\n", project("local-extension"), "3.33.2", List.of());

    assertTrue(result.contains("artifact: \"org.acme:local-extension::jar:1.0.0\""));
  }

  @Test
  void enrichMetadata_completeArtifact_preservesUserCoordinates() throws IOException {
    String result =
        ExtensionYamlEnricher.enrichMetadata(
            "name: local-extension\nartifact: custom.group:custom-artifact:2.0\nmetadata:\n",
            project("local-extension"),
            "3.33.2",
            List.of());

    assertTrue(result.contains("artifact: custom.group:custom-artifact:2.0"));
    assertFalse(result.contains("org.acme:local-extension::jar:1.0.0"));
  }

  @Test
  void enrichMetadata_incompleteArtifact_completesWithProjectVersion() throws IOException {
    String result =
        ExtensionYamlEnricher.enrichMetadata(
            "name: local-extension\nartifact: custom.group:custom-artifact\nmetadata:\n",
            project("local-extension"),
            "3.33.2",
            List.of());

    assertTrue(result.contains("artifact: \"custom.group:custom-artifact::jar:1.0.0\""));
  }

  @Test
  void enrichMetadata_legacyCoordinateFields_areUsedAsFallbacks() throws IOException {
    String result =
        ExtensionYamlEnricher.enrichMetadata(
            "name: local-extension\n"
                + "groupId: custom.group\n"
                + "artifactId: custom-artifact\n"
                + "version: 2.0\n",
            project("local-extension"),
            "3.33.2",
            List.of());

    assertTrue(result.contains("artifact: \"custom.group:custom-artifact::jar:2.0\""));
  }

  @Test
  void enrichMetadata_projectPlaceholders_areResolved() throws IOException {
    String result =
        ExtensionYamlEnricher.enrichMetadata(
            "name: local-extension\n"
                + "artifact: ${project.groupId}:${project.artifactId}:${project.version}\n",
            project("local-extension"),
            "3.33.2",
            List.of());

    assertTrue(result.contains("artifact: \"org.acme:local-extension::jar:1.0.0\""));
  }

  @Test
  void extensionProjectInfo_blankProjectCoordinate_failsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExtensionProjectInfo("local-extension", "org.acme", "local-extension", " "));
  }

  // ---- discoverExtensionDependencies ----

  @Test
  void discoverExtensionDependencies_emptyFile_returnsEmpty() throws IOException {
    Path cpFile = tempDir.resolve("empty_cp.txt");
    Files.writeString(cpFile, "");

    Set<String> deps = ExtensionYamlEnricher.discoverExtensionDependencies(cpFile);

    assertTrue(deps.isEmpty());
  }

  @Test
  void discoverExtensionDependencies_nonExtensionJar_skipped(@TempDir Path jarDir)
      throws IOException {
    Path jar = createJarWithPom(jarDir, "plain-lib-1.0.jar", "org.example", "plain-lib", false);
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, jar.toString());

    Set<String> deps = ExtensionYamlEnricher.discoverExtensionDependencies(cpFile);

    assertTrue(deps.isEmpty());
  }

  @Test
  void discoverExtensionDependencies_extensionJar_discovered(@TempDir Path jarDir)
      throws IOException {
    Path jar =
        createJarWithPom(jarDir, "quarkus-rest-3.33.2.jar", "io.quarkus", "quarkus-rest", true);
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, jar.toString());

    Set<String> deps = ExtensionYamlEnricher.discoverExtensionDependencies(cpFile);

    assertEquals(Set.of("io.quarkus:quarkus-rest"), deps);
  }

  @Test
  void discoverExtensionDependencies_nonexistentJar_skipped() throws IOException {
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, "/nonexistent/path.jar");

    Set<String> deps = ExtensionYamlEnricher.discoverExtensionDependencies(cpFile);

    assertTrue(deps.isEmpty());
  }

  @Test
  void discoverExtensionDependencies_blankLines_ignored(@TempDir Path jarDir) throws IOException {
    Path jar =
        createJarWithPom(jarDir, "quarkus-arc-3.33.2.jar", "io.quarkus", "quarkus-arc", true);
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, "\n  \n" + jar + "\n\n");

    Set<String> deps = ExtensionYamlEnricher.discoverExtensionDependencies(cpFile);

    assertEquals(Set.of("io.quarkus:quarkus-arc"), deps);
  }

  // ---- extractPomCoordinates ----

  @Test
  void extractPomCoordinates_matchingFilename(@TempDir Path jarDir) throws IOException {
    Path jar = createJarWithPom(jarDir, "my-lib-2.0.jar", "com.example", "my-lib", false);

    assertEquals("com.example:my-lib", ExtensionYamlEnricher.extractPomCoordinates(jar));
  }

  @Test
  void extractPomCoordinates_noPomProperties(@TempDir Path jarDir) throws IOException {
    Path jar = jarDir.resolve("empty.jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("com/example/Foo.class"));
      jos.closeEntry();
    }

    assertNull(ExtensionYamlEnricher.extractPomCoordinates(jar));
  }

  // ---- enrich (end-to-end) ----

  @Test
  void enrich_endToEnd(@TempDir Path jarDir) throws IOException {
    Path runtimeJar = createRuntimeJar(jarDir, "name: \"test-ext\"\nmetadata:\n  status: beta\n");
    Path depJar =
        createJarWithPom(jarDir, "quarkus-core-3.33.2.jar", "io.quarkus", "quarkus-core", true);
    Path cpFile = tempDir.resolve("cp.txt");
    Files.writeString(cpFile, depJar.toString());
    Path output = tempDir.resolve("enriched.yaml");

    ExtensionYamlEnricher.enrich(runtimeJar, output, "3.33.2", cpFile, project("test-ext"));

    String result = Files.readString(output);
    assertTrue(result.contains("name: \"test-ext\""));
    assertTrue(result.contains("status: beta"));
    assertTrue(result.contains("built-with-quarkus-core: \"3.33.2\""));
    assertTrue(result.contains("    - \"io.quarkus:quarkus-core\""));
  }

  @Test
  void enrich_missingRuntimeJar_throwsIOException() {
    Path cpFile = tempDir.resolve("cp.txt");
    assertDoesNotThrow(() -> Files.writeString(cpFile, ""));
    Path output = tempDir.resolve("out.yaml");

    assertThrows(
        IOException.class,
        () ->
            ExtensionYamlEnricher.enrich(
                Path.of("/nonexistent.jar"), output, "3.33.2", cpFile, project("x")));
  }

  // ---- helpers ----

  private static String enrichMetadata(
      String inputYaml, String extensionName, String quarkusVersion, List<String> dependencies)
      throws IOException {
    return ExtensionYamlEnricher.enrichMetadata(
        inputYaml, project(extensionName), quarkusVersion, dependencies);
  }

  private static ExtensionProjectInfo project(String extensionName) {
    return new ExtensionProjectInfo(extensionName, "org.acme", extensionName, "1.0.0");
  }

  private Path createJarWithPom(
      Path dir, String fileName, String groupId, String artifactId, boolean isExtension)
      throws IOException {
    Path jar = dir.resolve(fileName);
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      // pom.properties
      String pomPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
      jos.putNextEntry(new JarEntry(pomPath));
      Properties props = new Properties();
      props.setProperty("groupId", groupId);
      props.setProperty("artifactId", artifactId);
      props.setProperty("version", "1.0");
      props.store(jos, null);
      jos.closeEntry();

      // Extension marker
      if (isExtension) {
        jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
        jos.write(
            ("deployment-artifact=" + groupId + ":" + artifactId + "-deployment:1.0\n")
                .getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
      }
    }
    return jar;
  }

  private Path createRuntimeJar(Path dir, String yamlContent) throws IOException {
    Path jar = dir.resolve("runtime.jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
      jos.write(yamlContent.getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
    }
    return jar;
  }
}
