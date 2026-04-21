package com.clementguillot.quarkifier.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ExtensionScanner}. */
class ExtensionScannerTest {

  @Test
  void parseDeploymentArtifact_standardGav() {
    var props = new Properties();
    props.setProperty(
        "deployment-artifact", "io.quarkus:quarkus-resteasy-reactive-deployment:3.27.3");
    Path jar = Path.of("dummy.jar");

    ExtensionInfo info = ExtensionScanner.parseDeploymentArtifact(props, jar);

    assertNotNull(info);
    assertEquals("io.quarkus", info.groupId());
    assertEquals("quarkus-resteasy-reactive", info.artifactId());
    assertEquals("3.27.3", info.version());
    assertEquals(jar, info.sourceJar());
  }

  @Test
  void parseDeploymentArtifact_missingProperty() {
    assertNull(ExtensionScanner.parseDeploymentArtifact(new Properties(), Path.of("x.jar")));
  }

  @Test
  void parseDeploymentArtifact_blankValue() {
    var props = new Properties();
    props.setProperty("deployment-artifact", "   ");
    assertNull(ExtensionScanner.parseDeploymentArtifact(props, Path.of("x.jar")));
  }

  @Test
  void parseDeploymentArtifact_malformedGav() {
    var props = new Properties();
    props.setProperty("deployment-artifact", "io.quarkus:only-two-parts");
    assertNull(ExtensionScanner.parseDeploymentArtifact(props, Path.of("x.jar")));
  }

  @Test
  void parseDeploymentArtifact_noDeploymentSuffix() {
    var props = new Properties();
    props.setProperty("deployment-artifact", "com.example:my-extension:1.0.0");

    ExtensionInfo info = ExtensionScanner.parseDeploymentArtifact(props, Path.of("x.jar"));

    assertNotNull(info);
    assertEquals("com.example", info.groupId());
    assertEquals("my-extension", info.artifactId());
    assertEquals("1.0.0", info.version());
  }

  @Test
  void parseDeploymentArtifact_extraGavParts() {
    var props = new Properties();
    props.setProperty("deployment-artifact", "io.quarkus:quarkus-arc-deployment:jar:3.27.3");

    ExtensionInfo info = ExtensionScanner.parseDeploymentArtifact(props, Path.of("x.jar"));

    assertNotNull(info);
    assertEquals("io.quarkus", info.groupId());
    assertEquals("quarkus-arc", info.artifactId());
    assertEquals("jar", info.version()); // 3rd part is version regardless of format
  }

  @Test
  void scan_withExtensionJar(@TempDir Path tempDir) throws IOException {
    Path jar = createExtensionJar(tempDir, "ext.jar", "io.quarkus:quarkus-arc-deployment:3.27.3");

    List<ExtensionInfo> results = ExtensionScanner.scan(List.of(jar));

    assertEquals(1, results.size());
    assertEquals("quarkus-arc", results.get(0).artifactId());
  }

  @Test
  void scan_withNonExtensionJar(@TempDir Path tempDir) throws IOException {
    Path jar = createPlainJar(tempDir, "plain.jar");
    assertEquals(0, ExtensionScanner.scan(List.of(jar)).size());
  }

  @Test
  void scan_emptyClasspath() throws IOException {
    assertEquals(0, ExtensionScanner.scan(List.of()).size());
  }

  @Test
  void scan_mixedJars(@TempDir Path tempDir) throws IOException {
    Path ext1 = createExtensionJar(tempDir, "ext1.jar", "io.quarkus:quarkus-arc-deployment:3.27.3");
    Path plain = createPlainJar(tempDir, "plain.jar");
    Path ext2 =
        createExtensionJar(
            tempDir, "ext2.jar", "io.quarkus:quarkus-resteasy-reactive-deployment:3.27.3");

    List<ExtensionInfo> results = ExtensionScanner.scan(List.of(ext1, plain, ext2));

    assertEquals(2, results.size());
    assertEquals("quarkus-arc", results.get(0).artifactId());
    assertEquals("quarkus-resteasy-reactive", results.get(1).artifactId());
  }

  // --- helpers ---

  private static Path createExtensionJar(Path dir, String name, String gav) throws IOException {
    Path jarPath = dir.resolve(name);
    try (var jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
      jos.write(("deployment-artifact=" + gav + "\n").getBytes());
      jos.closeEntry();
    }
    return jarPath;
  }

  private static Path createPlainJar(Path dir, String name) throws IOException {
    Path jarPath = dir.resolve(name);
    try (var jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
      jos.write("Manifest-Version: 1.0\n".getBytes());
      jos.closeEntry();
    }
    return jarPath;
  }
}
