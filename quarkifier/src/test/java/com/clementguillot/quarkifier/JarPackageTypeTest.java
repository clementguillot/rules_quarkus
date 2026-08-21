package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JarPackageTypeTest {

  @Test
  void parsesEveryCanonicalTypeCaseInsensitively() {
    for (JarPackageType type : JarPackageType.values()) {
      assertEquals(type, JarPackageType.parse(type.configValue()));
      assertEquals(type, JarPackageType.parse(type.configValue().toUpperCase()));
    }
  }

  @Test
  void rejectsUnknownTypeWithAllowedValues() {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> JarPackageType.parse("thin-jar"));
    assertTrue(exception.getMessage().contains("fast-jar"));
    assertTrue(exception.getMessage().contains("aot-jar"));
  }

  @Test
  void exposesStableRunnerPaths() {
    assertEquals("quarkus-app/quarkus-run.jar", JarPackageType.FAST_JAR.runnerPath());
    assertEquals("quarkus-app/quarkus-run.jar", JarPackageType.MUTABLE_JAR.runnerPath());
    assertEquals("quarkus-app/quarkus-run.jar", JarPackageType.AOT_JAR.runnerPath());
    assertEquals("quarkus-run.jar", JarPackageType.UBER_JAR.runnerPath());
    assertEquals("quarkus-run.jar", JarPackageType.LEGACY_JAR.runnerPath());
  }

  @Test
  void aotRequiresQuarkus333() {
    assertFalse(JarPackageType.AOT_JAR.supports("3.27.4"));
    assertTrue(JarPackageType.AOT_JAR.supports("3.33.2"));
    assertTrue(JarPackageType.UBER_JAR.supports("3.27.4"));
  }
}
