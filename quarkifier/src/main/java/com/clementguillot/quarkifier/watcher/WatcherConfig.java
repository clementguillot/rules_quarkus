package com.clementguillot.quarkifier.watcher;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable configuration for the Bazel file watcher process.
 *
 * @param sourceDirs source directories to watch for changes (comma-separated on CLI)
 * @param bazelTargets Bazel target labels to rebuild (comma-separated on CLI)
 * @param classesDir mutable directory where synced .class files are written
 * @param classesOutputDirs bazel-bin output directories containing .class files (comma-separated on
 *     CLI)
 * @param debounceMs debounce window in milliseconds before triggering a build (default 100)
 * @param workspaceDir Bazel workspace root directory for running bazel build (may be {@code null})
 */
public record WatcherConfig(
    List<Path> sourceDirs,
    List<String> bazelTargets,
    Path classesDir,
    List<Path> classesOutputDirs,
    long debounceMs,
    Path workspaceDir) {

  private static final long DEFAULT_DEBOUNCE_MS = 100;

  /**
   * Parses CLI arguments into a {@link WatcherConfig}.
   *
   * <p>Required flags: {@code --source-dirs}, {@code --bazel-targets}, {@code --classes-dir},
   * {@code --classes-output-dirs}.
   *
   * @return a validated config
   * @throws IllegalArgumentException on missing or invalid arguments
   */
  public static WatcherConfig parse(String... args) {
    String sourceDirs = null;
    String bazelTargets = null;
    String classesDir = null;
    String classesOutputDirs = null;
    String debounceMs = null;
    String workspaceDir = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--source-dirs" -> sourceDirs = requireValue(args, ++i, args[i - 1]);
        case "--bazel-targets" -> bazelTargets = requireValue(args, ++i, args[i - 1]);
        case "--classes-dir" -> classesDir = requireValue(args, ++i, args[i - 1]);
        case "--classes-output-dirs" -> classesOutputDirs = requireValue(args, ++i, args[i - 1]);
        case "--debounce-ms" -> debounceMs = requireValue(args, ++i, args[i - 1]);
        case "--workspace-dir" -> workspaceDir = requireValue(args, ++i, args[i - 1]);
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    if (sourceDirs == null) {
      throw new IllegalArgumentException("Missing required argument: --source-dirs");
    }
    if (bazelTargets == null) {
      throw new IllegalArgumentException("Missing required argument: --bazel-targets");
    }
    if (classesDir == null) {
      throw new IllegalArgumentException("Missing required argument: --classes-dir");
    }
    if (classesOutputDirs == null) {
      throw new IllegalArgumentException("Missing required argument: --classes-output-dirs");
    }

    long parsedDebounce = DEFAULT_DEBOUNCE_MS;
    if (debounceMs != null) {
      try {
        parsedDebounce = Long.parseLong(debounceMs);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid value for --debounce-ms: " + debounceMs, e);
      }
    }

    return new WatcherConfig(
        splitPaths(sourceDirs),
        splitStrings(bazelTargets),
        Path.of(classesDir),
        splitPaths(classesOutputDirs),
        parsedDebounce,
        workspaceDir != null ? Path.of(workspaceDir) : null);
  }

  /** Serializes this config back to a CLI argument array, suitable for round-trip testing. */
  public String[] toArgs() {
    var list = new java.util.ArrayList<String>();

    list.add("--source-dirs");
    list.add(joinPaths(sourceDirs));

    list.add("--bazel-targets");
    list.add(String.join(",", bazelTargets));

    list.add("--classes-dir");
    list.add(classesDir.toString());

    list.add("--classes-output-dirs");
    list.add(joinPaths(classesOutputDirs));

    list.add("--debounce-ms");
    list.add(Long.toString(debounceMs));

    if (workspaceDir != null) {
      list.add("--workspace-dir");
      list.add(workspaceDir.toString());
    }

    return list.toArray(String[]::new);
  }

  // ---- internal helpers ----

  private static String requireValue(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for " + flag);
    }
    return args[index];
  }

  private static List<Path> splitPaths(String value) {
    if (value.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(value.split(Pattern.quote(","))).map(Path::of).toList();
  }

  private static List<String> splitStrings(String value) {
    if (value.isEmpty()) {
      return List.of();
    }
    return Arrays.asList(value.split(Pattern.quote(",")));
  }

  private static String joinPaths(List<Path> paths) {
    return String.join(",", paths.stream().map(Path::toString).toList());
  }
}
