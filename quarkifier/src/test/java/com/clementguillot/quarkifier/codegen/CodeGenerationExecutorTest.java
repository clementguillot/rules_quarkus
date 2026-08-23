package com.clementguillot.quarkifier.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.CodeGenerationException;
import io.quarkus.deployment.CodeGenerator;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGenerationExecutorTest {

  @TempDir Path temporaryDirectory;

  /**
   * Pins the reflective entry point against the quarkus-core-deployment this minor compiles
   * against. At runtime the class is loaded from the isolated deployment classloader while the
   * parameter types come from the quarkifier's own, so a renamed method, a changed signature, or a
   * bootstrap type that stops being parent-first would otherwise only surface as a
   * NoSuchMethodException once the Bazel action already runs.
   */
  @Test
  void resolvesQuarkusCodeGeneratorEntryPoint() throws NoSuchMethodException {
    Method initAndRun = CodeGenerationExecutor.codeGeneratorEntryPoint(CodeGenerator.class);
    Method getConfig = CodeGenerationProperties.codeGeneratorConfigEntryPoint(CodeGenerator.class);

    assertEquals("initAndRun", initAndRun.getName());
    assertTrue(Modifier.isStatic(initAndRun.getModifiers()));
    assertEquals("getConfig", getConfig.getName());
    assertTrue(Modifier.isStatic(getConfig.getModifiers()));
  }

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
    CodeGenerationOutputs.packageOutputs(firstGenerated, firstJar, firstAux);
    CodeGenerationOutputs.packageOutputs(secondGenerated, secondJar, secondAux);

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
                CodeGenerationOutputs.packageOutputs(
                    generated,
                    temporaryDirectory.resolve("unsupported.srcjar"),
                    Files.createDirectory(temporaryDirectory.resolve("unsupported-aux"))));

    assertTrue(exception.getMessage().contains("Kotlin/Scala"));
  }

  @Test
  void packagesAnEmptySourceJarWhenProvidersDoNotGenerateJava() throws Exception {
    Path generated = Files.createDirectory(temporaryDirectory.resolve("empty-generated"));
    Path firstJar = temporaryDirectory.resolve("empty-first.srcjar");
    Path secondJar = temporaryDirectory.resolve("empty-second.srcjar");
    Path firstAux = Files.createDirectory(temporaryDirectory.resolve("empty-first-aux"));
    Path secondAux = Files.createDirectory(temporaryDirectory.resolve("empty-second-aux"));

    CodeGenerationOutputs.packageOutputs(generated, firstJar, firstAux);
    CodeGenerationOutputs.packageOutputs(generated, secondJar, secondAux);

    assertArrayEquals(Files.readAllBytes(firstJar), Files.readAllBytes(secondJar));
    try (JarFile jar = new JarFile(firstJar.toFile())) {
      assertEquals(0, jar.size());
    }
  }

  @Test
  void retainsStableWorkProductsAndDropsTransientExecutables() throws IOException {
    Path work = Files.createDirectories(temporaryDirectory.resolve("work/reports"));
    Path descriptor = work.resolve("descriptor.dsc");
    Files.writeString(descriptor, "descriptor");
    Path executable = temporaryDirectory.resolve("work/quarkus-grpc-random.sh");
    Files.writeString(executable, "#!/bin/sh\n");
    assertTrue(executable.toFile().setExecutable(true));
    Path output = temporaryDirectory.resolve("work-output");

    CodeGenerationOutputs.copyStableWorkOutputs(temporaryDirectory.resolve("work"), output);

    assertEquals("descriptor", Files.readString(output.resolve("reports/descriptor.dsc")));
    assertTrue(Files.notExists(output.resolve("quarkus-grpc-random.sh")));
  }

  @Test
  void loadsUtf8BuildPropertiesWithoutLosingEscapedSpaces() throws IOException {
    Path propertiesFile = temporaryDirectory.resolve("codegen.properties");
    Files.writeString(propertiesFile, "clé=été\nspaced=\\ leading\\ value\n");

    var properties = BuildProperties.load(propertiesFile);

    assertEquals("été", properties.get("clé"));
    assertEquals(" leading value", properties.get("spaced"));
  }

  @Test
  void rejectsAmbientPropertyReferencedByDeclaredGeneratedValue() {
    Properties declared = new Properties();
    declared.setProperty("generated.value", "${ambient.only}");
    Map<String, CodeGenerationProperties.ConfigProperty> resolved =
        Map.of(
            "ambient.only",
            new CodeGenerationProperties.ConfigProperty(
                "not-an-input", "not-an-input", "SysPropConfigSource"));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CodeGenerationProperties.validateDeclaredExpressionInputs(declared, resolved::get));

    assertTrue(exception.getMessage().contains("generated.value"));
    assertTrue(exception.getMessage().contains("ambient.only"));
  }

  @Test
  void rejectsTransitiveAmbientReferenceFromActionInputProperty() {
    Properties declared = new Properties();
    declared.setProperty("generated.value", "${application.value}");
    Map<String, CodeGenerationProperties.ConfigProperty> resolved =
        Map.of(
            "application.value",
            new CodeGenerationProperties.ConfigProperty(
                "${ambient.only}", "not-an-input", "application.properties"),
            "ambient.only",
            new CodeGenerationProperties.ConfigProperty(
                "not-an-input", "not-an-input", "EnvConfigSource"));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CodeGenerationProperties.validateDeclaredExpressionInputs(declared, resolved::get));

    assertTrue(exception.getMessage().contains("ambient.only"));
  }

  @Test
  void allowsExpressionDefaultWhenAmbientPropertyIsAbsent() {
    Properties declared = new Properties();
    declared.setProperty("generated.value", "${ambient.only:fallback}");

    CodeGenerationProperties.validateDeclaredExpressionInputs(declared, ignored -> null);
  }

  private static void writeGeneratedTree(Path root) throws IOException {
    Files.createDirectories(root.resolve("example"));
    Files.createDirectories(root.resolve("META-INF"));
    Files.writeString(root.resolve("example/Zulu.java"), "package example; class Zulu {}");
    Files.writeString(root.resolve("example/Alpha.java"), "package example; class Alpha {}");
    Files.writeString(root.resolve("META-INF/generated.txt"), "descriptor");
  }
}
