package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuarkifierConfig} error paths and edge cases. */
class QuarkifierConfigTest {

  @Test
  void parse_missingApplicationClasspath() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {"--deployment-classpath", "d.jar", "--output-dir", "/out"}));
    assertTrue(ex.getMessage().contains("--application-classpath"));
  }

  @Test
  void parse_missingDeploymentClasspath() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {"--application-classpath", "a.jar", "--output-dir", "/out"}));
    assertTrue(ex.getMessage().contains("--deployment-classpath"));
  }

  @Test
  void parse_missingOutputDir() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar", "--deployment-classpath", "d.jar"
                    }));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_unknownArgument() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--bogus-flag"
                    }));
    assertTrue(ex.getMessage().contains("--bogus-flag"));
  }

  @Test
  void parse_missingValueForFlag() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir"
                    }));
    assertTrue(ex.getMessage().contains("--output-dir"));
  }

  @Test
  void parse_invalidMode() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--mode", "invalid"
                    }));
    assertTrue(ex.getMessage().contains("invalid"));
  }

  @Test
  void parse_defaultModeIsNormal() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out"
            });
    assertEquals(AugmentationMode.NORMAL, config.mode());
  }

  @Test
  void parse_emptyApplicationClasspath() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out"
            });
    assertTrue(config.applicationClasspath().isEmpty());
  }

  @Test
  void parse_testMode() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out",
              "--mode", "test"
            });
    assertEquals(AugmentationMode.TEST, config.mode());
  }

  @Test
  void parse_sourceDirs() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out",
              "--source-dirs", "src/main/java,lib/src/main/java"
            });
    assertEquals(
        List.of(Path.of("src/main/java"), Path.of("lib/src/main/java")), config.sourceDirs());
  }

  @Test
  void parse_sourceDirsMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--source-dirs"
                    }));
    assertTrue(ex.getMessage().contains("--source-dirs"));
  }

  @Test
  void parse_absentSourceDirsDefaultsToEmptyList()
      throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out"
            });
    assertTrue(config.sourceDirs().isEmpty());
  }

  @Test
  void parse_classesDir() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out",
              "--classes-dir", "/tmp/classes"
            });
    assertEquals(Path.of("/tmp/classes"), config.classesDir());
  }

  @Test
  void parse_absentClassesDirDefaultsToNull() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out"
            });
    assertNull(config.classesDir());
  }

  @Test
  void parse_bazelTargets() throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out",
              "--bazel-targets", "//pkg:lib,//pkg:other"
            });
    assertEquals(List.of("//pkg:lib", "//pkg:other"), config.bazelTargets());
  }

  @Test
  void parse_absentBazelTargetsDefaultsToEmptyList()
      throws QuarkifierConfig.InvalidArgumentsException {
    var config =
        QuarkifierConfig.parse(
            new String[] {
              "--application-classpath", "a.jar",
              "--deployment-classpath", "d.jar",
              "--output-dir", "/out"
            });
    assertTrue(config.bazelTargets().isEmpty());
  }

  @Test
  void parse_classesDirMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--classes-dir"
                    }));
    assertTrue(ex.getMessage().contains("--classes-dir"));
  }

  @Test
  void parse_bazelTargetsMissingValue() {
    var ex =
        assertThrows(
            QuarkifierConfig.InvalidArgumentsException.class,
            () ->
                QuarkifierConfig.parse(
                    new String[] {
                      "--application-classpath", "a.jar",
                      "--deployment-classpath", "d.jar",
                      "--output-dir", "/out",
                      "--bazel-targets"
                    }));
    assertTrue(ex.getMessage().contains("--bazel-targets"));
  }
}
