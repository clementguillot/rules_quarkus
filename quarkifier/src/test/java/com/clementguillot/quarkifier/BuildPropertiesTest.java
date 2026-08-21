package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BuildPropertiesTest {

  @Test
  void defaultsSelectsRequestedPackageTypeAndStableRunnerName() {
    var properties = BuildProperties.defaults(null, null, JarPackageType.UBER_JAR);

    assertEquals("uber-jar", properties.getProperty("quarkus.package.jar.type"));
    assertEquals("false", properties.getProperty("quarkus.package.jar.add-runner-suffix"));
  }

  @Test
  void nativeSourcesUseFastJarAsIntermediatePackage() {
    var properties = BuildProperties.nativeSourcesOnly(null, null);

    assertEquals("fast-jar", properties.getProperty("quarkus.package.jar.type"));
    assertEquals("true", properties.getProperty("quarkus.native.enabled"));
    assertEquals("true", properties.getProperty("quarkus.native.sources-only"));
  }

  @Test
  void nativeSourcesKeepRunnerSuffix() {
    // NativeSourcesAssembler and the native-image.args rewrite in the native
    // rules both look for "<app>-runner"; dropping the suffix breaks the build.
    var properties = BuildProperties.nativeSourcesOnly(null, null);

    assertEquals("true", properties.getProperty("quarkus.package.jar.add-runner-suffix"));
  }
}
