package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildPropertiesTest {

  @Test
  void scopesDeclaredSystemPropertiesAndRestoresPriorState() throws Exception {
    String existingName = "rules.quarkus.build-properties.existing";
    String newName = "rules.quarkus.build-properties.new";
    String previousExisting = System.getProperty(existingName);
    String previousNew = System.getProperty(newName);
    try {
      System.setProperty(existingName, "before");
      System.clearProperty(newName);
      var properties = new java.util.Properties();
      properties.setProperty(existingName, "during");
      properties.setProperty(newName, "declared");

      String result =
          BuildProperties.withSystemProperties(
              properties,
              () -> {
                assertEquals("during", System.getProperty(existingName));
                assertEquals("declared", System.getProperty(newName));
                return "done";
              });

      assertEquals("done", result);
      assertEquals("before", System.getProperty(existingName));
      assertNull(System.getProperty(newName));
    } finally {
      restoreSystemProperty(existingName, previousExisting);
      restoreSystemProperty(newName, previousNew);
    }
  }

  private static void restoreSystemProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  @TempDir Path temporaryDirectory;

  @Test
  void loadsDeclaredUtf8Properties() throws Exception {
    Path file = temporaryDirectory.resolve("build.properties");
    Files.writeString(file, "clé=été\nspaced=\\ leading\\ value\nkey\\:\\ with\\ spaces=value\n");

    assertEquals(
        Map.of("clé", "été", "spaced", " leading value", "key: with spaces", "value"),
        BuildProperties.load(file));
  }

  @Test
  void rejectsNamesThatCannotUseTheJvmPropertyChannel() throws Exception {
    Path emptyName = temporaryDirectory.resolve("empty-name.properties");
    Files.writeString(emptyName, "=value\n");
    IOException emptyException =
        assertThrows(IOException.class, () -> BuildProperties.load(emptyName));
    assertTrue(emptyException.getMessage().contains("empty property name"));

    Path equalsName = temporaryDirectory.resolve("equals-name.properties");
    Files.writeString(equalsName, "key\\=part=value\n");
    IOException equalsException =
        assertThrows(IOException.class, () -> BuildProperties.load(equalsName));
    assertTrue(equalsException.getMessage().contains("cannot be represented as a JVM -D flag"));
  }

  @Test
  void absentPropertiesFileIsAnEmptyDeclaredConfiguration() throws Exception {
    assertEquals(Map.of(), BuildProperties.load(null));
  }

  @Test
  void defaultsSelectsRequestedPackageTypeAndStableRunnerName() {
    var properties = BuildProperties.defaults(null, null, JarPackageType.UBER_JAR);

    assertEquals("uber-jar", properties.getProperty("quarkus.package.jar.type"));
    assertEquals("false", properties.getProperty("quarkus.package.jar.add-runner-suffix"));
  }

  @Test
  void defaultsPreserveDeclaredPropertiesAndRuleAttributesWin() {
    var properties =
        BuildProperties.defaults(
            Map.of(
                "custom.expression.input", "declared",
                "quarkus.package.jar.type", "legacy-jar",
                "quarkus.package.main-class", "wrong.Main"),
            "example.Main",
            "builder/image:fixed",
            JarPackageType.UBER_JAR);

    assertEquals("declared", properties.getProperty("custom.expression.input"));
    assertEquals("uber-jar", properties.getProperty("quarkus.package.jar.type"));
    assertEquals("example.Main", properties.getProperty("quarkus.package.main-class"));
    assertEquals(
        "builder/image:fixed", properties.getProperty("platform.quarkus.native.builder-image"));
  }

  @Test
  void nativeSourcesUseFastJarAsIntermediatePackage() {
    var properties =
        BuildProperties.nativeSourcesOnly(
            Map.of("quarkus.native.enabled", "false", "custom.native.input", "declared"),
            null,
            null);

    assertEquals("fast-jar", properties.getProperty("quarkus.package.jar.type"));
    assertEquals("true", properties.getProperty("quarkus.native.enabled"));
    assertEquals("true", properties.getProperty("quarkus.native.sources-only"));
    assertEquals("declared", properties.getProperty("custom.native.input"));
  }

  @Test
  void nativeSourcesKeepRunnerSuffix() {
    // NativeSourcesAssembler and the native-image.args rewrite in the native
    // rules both look for "<app>-runner"; dropping the suffix breaks the build.
    var properties = BuildProperties.nativeSourcesOnly(null, null);

    assertEquals("true", properties.getProperty("quarkus.package.jar.add-runner-suffix"));
  }
}
