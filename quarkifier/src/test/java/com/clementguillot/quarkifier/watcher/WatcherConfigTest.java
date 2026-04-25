package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WatcherConfig} parsing and serialization. */
class WatcherConfigTest {

  @Test
  void parse_allFlags() {
    var config =
        WatcherConfig.parse(
            "--source-dirs", "src/main/java,lib/src/main/java",
            "--bazel-targets", "//pkg:lib,//pkg:other",
            "--classes-dir", "/tmp/classes",
            "--classes-output-dirs", "bazel-bin/pkg/lib,bazel-bin/pkg/other",
            "--debounce-ms", "200");

    assertEquals(
        List.of(Path.of("src/main/java"), Path.of("lib/src/main/java")), config.sourceDirs());
    assertEquals(List.of("//pkg:lib", "//pkg:other"), config.bazelTargets());
    assertEquals(Path.of("/tmp/classes"), config.classesDir());
    assertEquals(
        List.of(Path.of("bazel-bin/pkg/lib"), Path.of("bazel-bin/pkg/other")),
        config.classesOutputDirs());
    assertEquals(200, config.debounceMs());
  }

  @Test
  void parse_defaultDebounceMs() {
    var config =
        WatcherConfig.parse(
            "--source-dirs", "src",
            "--bazel-targets", "//pkg:lib",
            "--classes-dir", "/tmp/classes",
            "--classes-output-dirs", "bazel-bin/pkg/lib");

    assertEquals(100, config.debounceMs());
  }

  @Test
  void parse_missingSourceDirs() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--bazel-targets", "//pkg:lib",
                    "--classes-dir", "/tmp/classes",
                    "--classes-output-dirs", "bazel-bin/pkg/lib"));
    assertTrue(ex.getMessage().contains("--source-dirs"));
  }

  @Test
  void parse_missingBazelTargets() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--source-dirs", "src",
                    "--classes-dir", "/tmp/classes",
                    "--classes-output-dirs", "bazel-bin/pkg/lib"));
    assertTrue(ex.getMessage().contains("--bazel-targets"));
  }

  @Test
  void parse_missingClassesDir() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--source-dirs", "src",
                    "--bazel-targets", "//pkg:lib",
                    "--classes-output-dirs", "bazel-bin/pkg/lib"));
    assertTrue(ex.getMessage().contains("--classes-dir"));
  }

  @Test
  void parse_missingClassesOutputDirs() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--source-dirs", "src",
                    "--bazel-targets", "//pkg:lib",
                    "--classes-dir", "/tmp/classes"));
    assertTrue(ex.getMessage().contains("--classes-output-dirs"));
  }

  @Test
  void parse_unknownArgument() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--source-dirs",
                    "src",
                    "--bazel-targets",
                    "//pkg:lib",
                    "--classes-dir",
                    "/tmp/classes",
                    "--classes-output-dirs",
                    "bazel-bin/pkg/lib",
                    "--bogus-flag"));
    assertTrue(ex.getMessage().contains("--bogus-flag"));
  }

  @Test
  void parse_missingValueForFlag() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WatcherConfig.parse(
                    "--source-dirs",
                    "src",
                    "--bazel-targets",
                    "//pkg:lib",
                    "--classes-dir",
                    "/tmp/classes",
                    "--classes-output-dirs"));
    assertTrue(ex.getMessage().contains("--classes-output-dirs"));
  }

  @Test
  void parse_commaSeparatedSourceDirs() {
    var config =
        WatcherConfig.parse(
            "--source-dirs", "a,b,c",
            "--bazel-targets", "//t",
            "--classes-dir", "/tmp/c",
            "--classes-output-dirs", "out");

    assertEquals(List.of(Path.of("a"), Path.of("b"), Path.of("c")), config.sourceDirs());
  }

  @Test
  void parse_commaSeparatedBazelTargets() {
    var config =
        WatcherConfig.parse(
            "--source-dirs", "src",
            "--bazel-targets", "//a:lib,//b:lib,//c:lib",
            "--classes-dir", "/tmp/c",
            "--classes-output-dirs", "out");

    assertEquals(List.of("//a:lib", "//b:lib", "//c:lib"), config.bazelTargets());
  }

  @Test
  void toArgs_roundTrip() {
    var original =
        WatcherConfig.parse(
            "--source-dirs", "src/main/java,lib/src",
            "--bazel-targets", "//pkg:lib,//pkg:other",
            "--classes-dir", "/tmp/classes",
            "--classes-output-dirs", "bazel-bin/pkg/lib,bazel-bin/pkg/other",
            "--debounce-ms", "250");

    var parsed = WatcherConfig.parse(original.toArgs());

    assertEquals(original.sourceDirs(), parsed.sourceDirs());
    assertEquals(original.bazelTargets(), parsed.bazelTargets());
    assertEquals(original.classesDir(), parsed.classesDir());
    assertEquals(original.classesOutputDirs(), parsed.classesOutputDirs());
    assertEquals(original.debounceMs(), parsed.debounceMs());
  }
}
