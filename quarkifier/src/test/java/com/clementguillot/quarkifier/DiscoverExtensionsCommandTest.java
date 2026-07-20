package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for descriptor-driven extension discovery. */
class DiscoverExtensionsCommandTest {

  @TempDir Path tempDir;

  @Test
  void discoversOnlyDescriptorsAndPreservesExactCoordinates() throws IOException {
    Path standard = extensionJar("standard.jar", "io.quarkus:quarkus-rest-deployment:3.33.2");
    Path custom = extensionJar("custom.jar", "custom.group:custom-build-steps:build:jar:9.1");
    Path duplicate = extensionJar("duplicate.jar", "io.quarkus:quarkus-rest-deployment:3.33.2");
    Path plain = plainJar("plain.jar");
    Path classpath = tempDir.resolve("runtime-classpath.txt");
    Files.write(
        classpath,
        java.util.List.of(standard, custom, duplicate, plain).stream()
            .map(Path::toString)
            .toList());
    Path output = tempDir.resolve("deployment-artifacts.txt");

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "discover-extensions",
                "--classpath-file",
                classpath.toString(),
                "--output",
                output.toString());

    assertEquals(0, exitCode);
    assertEquals(
        java.util.List.of(
            "custom.group:custom-build-steps:build:jar:9.1",
            "io.quarkus:quarkus-rest-deployment:3.33.2"),
        Files.readAllLines(output, StandardCharsets.UTF_8));
  }

  @Test
  void descriptorWithoutDeploymentArtifactFailsClosed() throws IOException {
    Path broken = extensionJar("broken.jar", null);
    Path classpath = tempDir.resolve("broken-classpath.txt");
    Files.writeString(classpath, broken.toString(), StandardCharsets.UTF_8);

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "discover-extensions",
                "--classpath-file",
                classpath.toString(),
                "--output",
                tempDir.resolve("unused.txt").toString());

    assertEquals(1, exitCode);
  }

  @Test
  void writesResolverIdentifiedDescriptorCatalog() throws IOException {
    Path runtime =
        extensionJar(
            "runtime.jar",
            "custom.group:unrelated-build-steps:special:jar:9.1",
            "conditional-dependencies=custom.group\\:feature\\:9.1\n"
                + "conditional-dev-dependencies=custom.group\\:dev-helper\\:9.1\n"
                + "dependency-condition=custom.group\\:trigger\n");
    Path artifacts = tempDir.resolve("artifacts.tsv");
    Files.writeString(
        artifacts,
        "custom.group:actual-runtime:tests:jar:9.1\t" + runtime + "\n",
        StandardCharsets.UTF_8);
    Path deployments = tempDir.resolve("deployments.txt");
    Path descriptors = tempDir.resolve("descriptors.json");

    int exitCode =
        QuarkifierCommand.createCommandLine()
            .execute(
                "discover-extensions",
                "--artifacts-file",
                artifacts.toString(),
                "--output",
                deployments.toString(),
                "--descriptor-output",
                descriptors.toString());

    assertEquals(0, exitCode);
    String json = Files.readString(descriptors, StandardCharsets.UTF_8);
    assertTrue(json.contains("\"runtimeArtifact\": \"custom.group:actual-runtime:tests:jar:9.1\""));
    assertTrue(json.contains("\"conditionalDependencies\": [\"custom.group:feature:9.1\"]"));
    assertTrue(json.contains("\"conditionalDevDependencies\": [\"custom.group:dev-helper:9.1\"]"));
    assertTrue(json.contains("\"dependencyConditions\": [\"custom.group:trigger\"]"));
  }

  private Path extensionJar(String name, String deploymentArtifact) throws IOException {
    return extensionJar(name, deploymentArtifact, "");
  }

  private Path extensionJar(String name, String deploymentArtifact, String extraProperties)
      throws IOException {
    Path jar = tempDir.resolve(name);
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
      if (deploymentArtifact != null) {
        output.write(
            ("deployment-artifact=" + deploymentArtifact.replace(":", "\\:") + "\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      output.write(extraProperties.getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return jar;
  }

  private Path plainJar(String name) throws IOException {
    Path jar = tempDir.resolve(name);
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("plain.txt"));
      output.closeEntry();
    }
    return jar;
  }
}
