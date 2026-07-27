package com.clementguillot.quarkifier.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.CodeGenerationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGenerationExecutorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void packagesJavaDeterministicallyAndSeparatesAuxiliaryOutputs() throws Exception {
    Path firstGenerated = temporaryDirectory.resolve("first-generated");
    Path secondGenerated = temporaryDirectory.resolve("second-generated");
    writeGeneratedTree(firstGenerated);
    writeGeneratedTree(secondGenerated);

    Path firstJar = temporaryDirectory.resolve("first.srcjar");
    Path secondJar = temporaryDirectory.resolve("second.srcjar");
    Path firstAux = Files.createDirectory(temporaryDirectory.resolve("first-aux"));
    Path secondAux = Files.createDirectory(temporaryDirectory.resolve("second-aux"));
    CodeGenerationExecutor.packageOutputs(firstGenerated, firstJar, firstAux);
    CodeGenerationExecutor.packageOutputs(secondGenerated, secondJar, secondAux);

    assertArrayEquals(Files.readAllBytes(firstJar), Files.readAllBytes(secondJar));
    assertEquals("descriptor", Files.readString(firstAux.resolve("META-INF/generated.txt")));
    try (JarFile jar = new JarFile(firstJar.toFile())) {
      assertEquals(0L, jar.getJarEntry("example/Alpha.java").getTime());
      assertTrue(jar.getJarEntry("example/Zulu.java") != null);
      assertTrue(jar.getJarEntry("META-INF/generated.txt") == null);
    }
  }

  @Test
  void rejectsKotlinAndScalaOutputs() throws IOException {
    Path generated = Files.createDirectory(temporaryDirectory.resolve("unsupported-generated"));
    Files.writeString(generated.resolve("Generated.kt"), "class Generated");

    CodeGenerationException exception =
        assertThrows(
            CodeGenerationException.class,
            () ->
                CodeGenerationExecutor.packageOutputs(
                    generated,
                    temporaryDirectory.resolve("unsupported.srcjar"),
                    Files.createDirectory(temporaryDirectory.resolve("unsupported-aux"))));

    assertTrue(exception.getMessage().contains("Kotlin/Scala"));
  }

  @Test
  void rejectsUnclaimedInputs() throws IOException {
    Path generated = Files.createDirectory(temporaryDirectory.resolve("empty-generated"));

    CodeGenerationException exception =
        assertThrows(
            CodeGenerationException.class,
            () ->
                CodeGenerationExecutor.packageOutputs(
                    generated,
                    temporaryDirectory.resolve("empty.srcjar"),
                    Files.createDirectory(temporaryDirectory.resolve("empty-aux"))));

    assertTrue(exception.getMessage().contains("No Java sources were generated"));
  }

  private static void writeGeneratedTree(Path root) throws IOException {
    Files.createDirectories(root.resolve("example"));
    Files.createDirectories(root.resolve("META-INF"));
    Files.writeString(root.resolve("example/Zulu.java"), "package example; class Zulu {}");
    Files.writeString(root.resolve("example/Alpha.java"), "package example; class Alpha {}");
    Files.writeString(root.resolve("META-INF/generated.txt"), "descriptor");
  }
}
