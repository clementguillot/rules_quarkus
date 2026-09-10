package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Decides which filesystem paths the hot-reload watcher cares about. */
final class WatchedPaths {

  private final List<Path> sourceDirs;
  private final List<Path> resourceDirs;
  private final Set<Path> exactInputs;
  private final Set<Path> buildFiles;
  private final List<Path> exactWatchPaths;

  WatchedPaths(QuarkifierConfig config) {
    this.sourceDirs = normalize(config.sourceDirs());
    this.resourceDirs = normalize(config.resources());
    this.exactInputs = new LinkedHashSet<>(normalize(config.codegenInputFiles()));
    this.exactInputs.addAll(normalize(config.watchedInputs()));
    this.buildFiles = Set.copyOf(normalize(config.watchedBuildFiles()));
    var paths = new LinkedHashSet<>(exactInputs);
    paths.addAll(buildFiles);
    this.exactWatchPaths = List.copyOf(paths);
  }

  /** Reports whether {@code changed} is one of the exact Bazel inputs captured at analysis time. */
  boolean isExactInput(Path changed) {
    return exactInputs.contains(normalize(changed));
  }

  /** Reports whether {@code changed} belongs to an ordinary directory-based dev-mode input. */
  boolean isDirectoryInput(Path changed) {
    if (isUnder(changed, resourceDirs)) {
      return true;
    }
    return changed.toString().endsWith(".java") && isUnder(changed, sourceDirs);
  }

  /** Reports whether {@code changed} is below a legacy directory-based watch root. */
  boolean isDirectoryWatchPath(Path changed) {
    return isUnder(changed, sourceDirs) || isUnder(changed, resourceDirs);
  }

  /** Reports whether {@code changed} is an exact BUILD file from the configured target graph. */
  boolean isBuildFile(Path changed) {
    return buildFiles.contains(normalize(changed));
  }

  /** Reports whether {@code changed} is a directory containing a configured exact watch path. */
  boolean isExactWatchAncestor(Path changed) {
    Path absolute = normalize(changed);
    for (Path path : exactWatchPaths) {
      if (!path.equals(absolute) && path.startsWith(absolute)) {
        return true;
      }
    }
    return false;
  }

  /** Returns exact inputs and BUILD files whose parent directories must be registered. */
  List<Path> exactWatchPaths() {
    return exactWatchPaths;
  }

  /** Reports whether an input change needs the conservative non-Java test notification. */
  boolean isNonJavaInput(Path changed) {
    return isExactInput(changed)
        ? !changed.toString().endsWith(".java")
        : isUnder(changed, resourceDirs);
  }

  /** Filters editor files only from legacy directory-based roots; exact Bazel inputs always win. */
  boolean isIncidentalScratchFile(Path changed) {
    return !isExactInput(changed)
        && isDirectoryInput(changed)
        && isEditorScratchFile(changed.getFileName());
  }

  /** Reports whether {@code fileName} is an editor temporary, backup, or probe file. */
  static boolean isEditorScratchFile(Path fileName) {
    if (fileName == null) {
      return true;
    }
    String name = fileName.toString();
    return name.isEmpty()
        || name.startsWith(".#")
        || name.endsWith("~")
        || name.endsWith(".swp")
        || name.endsWith(".swx")
        || name.endsWith(".tmp");
  }

  private static boolean isUnder(Path changed, List<Path> roots) {
    Path absolute = normalize(changed);
    for (Path root : roots) {
      if (absolute.startsWith(root)) {
        return true;
      }
    }
    return false;
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static List<Path> normalize(List<Path> paths) {
    List<Path> normalized = new ArrayList<>(paths.size());
    for (Path path : paths) {
      normalized.add(normalize(path));
    }
    return List.copyOf(normalized);
  }
}
