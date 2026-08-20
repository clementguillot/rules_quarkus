package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildPropertiesTest {

  @TempDir Path temporaryDirectory;

  @Test
  void loadsDeclaredUtf8Properties() throws Exception {
    Path file = temporaryDirectory.resolve("build.properties");
    Files.writeString(file, "clé=été\nspaced=\\ leading\\ value\n");

    assertEquals(Map.of("clé", "été", "spaced", " leading value"), BuildProperties.load(file));
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
