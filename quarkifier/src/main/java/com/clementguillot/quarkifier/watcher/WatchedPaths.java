package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which filesystem paths the hot-reload watcher cares about.
 *
 * <p>Bazel exposes the files a {@code glob()} matched, not the glob itself. To notice files added
 * to a glob, each declared input also yields a candidate root: its {@code src/main/<dir>} or {@code
 * src/test/<dir>} directory, or else its parent. A new file under that root with the extension of a
 * declared input there triggers a rebuild, and Bazel decides whether it is actually an input.
 */
@SuppressWarnings("PMD.TooManyMethods") // cohesive path policy of small predicates
final class WatchedPaths {

  private static final String JAVA_EXTENSION = ".java";

  private final List<Path> sourceDirs;
  private final Map<Path, Set<String>> extensionsByRoot;
  private final Set<Path> exactInputs;
  private final Set<Path> buildFiles;
  private final List<Path> exactWatches;

  WatchedPaths(QuarkifierConfig config) {
    this.sourceDirs = normalizeAll(config.sourceDirs());
    this.exactInputs = new LinkedHashSet<>(normalizeAll(config.watchedInputs()));
    this.extensionsByRoot = inferCandidateRoots(exactInputs, config.workspaceDir());
    this.buildFiles = Set.copyOf(normalizeAll(config.watchedBuildFiles()));
    var paths = new LinkedHashSet<>(exactInputs);
    paths.addAll(buildFiles);
    this.exactWatches = List.copyOf(paths);
  }

  /** Reports whether {@code changed} is one of the exact Bazel inputs captured at analysis time. */
  boolean isExactInput(Path changed) {
    return exactInputs.contains(normalize(changed));
  }

  /** Reports whether {@code changed} is a Java file below an ordinary dev-mode source root. */
  boolean isDirectoryInput(Path changed) {
    return changed.toString().endsWith(JAVA_EXTENSION) && isUnder(changed, sourceDirs);
  }

  /** Reports whether {@code changed} is below a source or glob-discovery watch root. */
  boolean isDirectoryWatchPath(Path changed) {
    return isUnder(changed, sourceDirs) || isUnderCandidateRoot(changed, extensions -> true);
  }

  /** Reports whether {@code changed} is below a root that may hold Java sources. */
  boolean isSourceDirectoryWatchPath(Path changed) {
    return isUnder(changed, sourceDirs)
        || isUnderCandidateRoot(changed, extensions -> extensions.contains(JAVA_EXTENSION));
  }

  /** Files like a declared input, below its root, may become inputs once a glob re-evaluates. */
  boolean isCandidateInput(Path changed) {
    String extension = extension(changed);
    return !isEditorScratchFile(changed.getFileName())
        && isUnderCandidateRoot(changed, extensions -> extensions.contains(extension));
  }

  List<Path> candidateRoots() {
    return List.copyOf(extensionsByRoot.keySet());
  }

  private boolean isUnderCandidateRoot(Path changed, Predicate<Set<String>> extensions) {
    Path absolute = normalize(changed);
    return extensionsByRoot.entrySet().stream()
        .anyMatch(root -> absolute.startsWith(root.getKey()) && extensions.test(root.getValue()));
  }

  /** Reports whether {@code changed} is an exact BUILD file from the configured target graph. */
  boolean isBuildFile(Path changed) {
    return buildFiles.contains(normalize(changed));
  }

  /** Reports whether {@code changed} is a directory containing a configured exact watch path. */
  boolean isExactWatchAncestor(Path changed) {
    Path absolute = normalize(changed);
    for (Path path : exactWatches) {
      if (!path.equals(absolute) && path.startsWith(absolute)) {
        return true;
      }
    }
    return false;
  }

  /** Returns exact inputs and BUILD files whose parent directories must be registered. */
  List<Path> exactWatchPaths() {
    return exactWatches;
  }

  /** Reports whether an input change needs the conservative non-Java test notification. */
  static boolean isNonJavaInput(Path changed) {
    return !changed.toString().endsWith(JAVA_EXTENSION);
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

  private static Map<Path, Set<String>> inferCandidateRoots(Set<Path> inputs, Path workspaceDir) {
    Path workspace = workspaceDir == null ? null : normalize(workspaceDir);
    var roots = new LinkedHashMap<Path, Set<String>>();
    for (Path input : inputs) {
      if (workspace != null && !input.startsWith(workspace)) {
        continue;
      }
      Path root = input.getParent();
      // Only look for the Maven layout inside the workspace, never in the checkout's own path.
      for (int i = workspace == null ? 0 : workspace.getNameCount();
          i + 3 < input.getNameCount();
          i++) {
        if ("src".equals(input.getName(i).toString())
            && ("main".equals(input.getName(i + 1).toString())
                || "test".equals(input.getName(i + 1).toString()))) {
          root = input.getRoot().resolve(input.subpath(0, i + 3));
          break;
        }
      }
      roots.computeIfAbsent(root, ignored -> new LinkedHashSet<>()).add(extension(input));
    }
    return roots;
  }

  /** Returns the file-name extension including its dot, or the whole name when it has none. */
  private static String extension(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot) : name;
  }

  private static List<Path> normalizeAll(List<Path> paths) {
    List<Path> normalized = new ArrayList<>(paths.size());
    for (Path path : paths) {
      normalized.add(normalize(path));
    }
    return List.copyOf(normalized);
  }
}
