package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which filesystem paths the hot-reload watcher cares about, and what a change affects.
 *
 * <p>Bazel exposes the files a {@code glob()} matched, not the glob itself. To notice files added
 * to a glob, each declared input also yields a candidate root: its {@code src/main/<dir>} or {@code
 * src/test/<dir>} directory, or else its parent. A new file under that root with the extension of a
 * declared input there triggers a rebuild, and Bazel decides whether it is actually an input. Under
 * continuous testing every watched package also gets conventional {@code src/main/java} and {@code
 * src/test/java} roots, so the first file of an initially empty glob is noticed too.
 */
// One cohesive path policy: small predicates over the same root and input sets.
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods"})
final class WatchedPaths {

  /** What a change can affect, from the narrowest to the widest. */
  enum Scope {
    /** Not a watched input. */
    NONE,
    /** Only the continuous-test graph: rerun tests, keep the running application. */
    TESTS,
    /** The application graph (and, under continuous testing, the tests that use it). */
    APPLICATION
  }

  private static final String JAVA_EXTENSION = ".java";

  private final List<Path> sourceDirs;
  private final Set<Path> applicationInputs;
  private final Set<Path> testInputs;
  private final Map<Path, Set<String>> applicationRoots;
  private final Map<Path, Set<String>> testRoots;
  private final Set<Path> buildFiles;
  private final List<Path> exactWatches;

  WatchedPaths(QuarkifierConfig config) {
    Path workspace = config.workspaceDir() == null ? null : normalize(config.workspaceDir());
    this.sourceDirs = normalizeAll(config.sourceDirs());
    this.applicationInputs = new LinkedHashSet<>(normalizeAll(config.watchedInputs()));
    QuarkifierConfig.ContinuousTesting continuousTesting = config.continuousTesting();
    this.testInputs =
        new LinkedHashSet<>(
            continuousTesting == null
                ? List.of()
                : normalizeAll(continuousTesting.watchedInputs()));
    this.buildFiles =
        continuousTesting == null
            ? Set.of()
            : Set.copyOf(normalizeAll(continuousTesting.watchedBuildFiles()));
    this.applicationRoots = inferCandidateRoots(applicationInputs, workspace);
    this.testRoots = inferCandidateRoots(testInputs, workspace);
    for (Path buildFile : buildFiles) {
      Path pkg = buildFile.getParent();
      addRoot(applicationRoots, pkg.resolve("src/main/java"), JAVA_EXTENSION);
      addRoot(testRoots, pkg.resolve("src/test/java"), JAVA_EXTENSION);
    }
    var paths = new LinkedHashSet<>(applicationInputs);
    paths.addAll(testInputs);
    paths.addAll(buildFiles);
    this.exactWatches = List.copyOf(paths);
  }

  /** Reports what a file-level change at {@code changed} affects. */
  Scope inputScope(Path changed) {
    Path absolute = normalize(changed);
    if (applicationInputs.contains(absolute)) {
      return Scope.APPLICATION;
    }
    if (testInputs.contains(absolute)) {
      return Scope.TESTS;
    }
    if (isEditorArtifact(absolute.getFileName())) {
      return Scope.NONE;
    }
    String extension = extension(absolute);
    if ((JAVA_EXTENSION.equals(extension) && isUnder(absolute, sourceDirs))
        || isCandidate(absolute, extension, applicationRoots)) {
      return Scope.APPLICATION;
    }
    return isCandidate(absolute, extension, testRoots) ? Scope.TESTS : Scope.NONE;
  }

  /** Reports what adding or removing the directory {@code changed} under a watch root affects. */
  Scope directoryScope(Path changed) {
    Path absolute = normalize(changed);
    if (isUnder(absolute, sourceDirs) || isUnderRoot(absolute, applicationRoots)) {
      return Scope.APPLICATION;
    }
    return isUnderRoot(absolute, testRoots) ? Scope.TESTS : Scope.NONE;
  }

  /** Reports whether {@code changed} is below a root that may hold Java sources. */
  boolean isSourceDirectoryWatchPath(Path changed) {
    Path absolute = normalize(changed);
    return isUnder(absolute, sourceDirs)
        || isUnderJavaRoot(absolute, applicationRoots)
        || isUnderJavaRoot(absolute, testRoots);
  }

  /**
   * Reports what adding or removing the directory {@code changed} affects: the scope of the root it
   * lies under, or else of the roots it contains (such as a newly created {@code src/test}).
   */
  Scope treeScope(Path changed) {
    Scope scope = directoryScope(changed);
    if (scope != Scope.NONE) {
      return scope;
    }
    Path absolute = normalize(changed);
    if (applicationRoots.keySet().stream().anyMatch(root -> root.startsWith(absolute))) {
      return Scope.APPLICATION;
    }
    return testRoots.keySet().stream().anyMatch(root -> root.startsWith(absolute))
        ? Scope.TESTS
        : Scope.NONE;
  }

  /** Returns every candidate root; roots that do not exist yet are picked up once created. */
  List<Path> candidateRoots() {
    var roots = new LinkedHashSet<>(applicationRoots.keySet());
    roots.addAll(testRoots.keySet());
    return List.copyOf(roots);
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

  /**
   * Reports whether {@code fileName} is an editor lock or backup file that is never an input.
   * Temporary suffixes such as {@code .swp} or {@code .tmp} need no filter: a candidate must share
   * the extension of a declared input, so they only count when a project really declares them.
   */
  static boolean isEditorArtifact(Path fileName) {
    if (fileName == null) {
      return true;
    }
    String name = fileName.toString();
    return name.isEmpty() || name.startsWith(".#") || name.endsWith("~");
  }

  private static boolean isCandidate(
      Path absolute, String extension, Map<Path, Set<String>> roots) {
    return roots.entrySet().stream()
        .anyMatch(
            root -> absolute.startsWith(root.getKey()) && root.getValue().contains(extension));
  }

  private static boolean isUnderRoot(Path absolute, Map<Path, Set<String>> roots) {
    return roots.keySet().stream().anyMatch(absolute::startsWith);
  }

  private static boolean isUnderJavaRoot(Path absolute, Map<Path, Set<String>> roots) {
    return roots.entrySet().stream()
        .anyMatch(
            root -> absolute.startsWith(root.getKey()) && root.getValue().contains(JAVA_EXTENSION));
  }

  private static boolean isUnder(Path absolute, List<Path> roots) {
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

  private static Map<Path, Set<String>> inferCandidateRoots(Set<Path> inputs, Path workspace) {
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
      addRoot(roots, root, extension(input));
    }
    return roots;
  }

  private static void addRoot(Map<Path, Set<String>> roots, Path root, String extension) {
    roots.computeIfAbsent(normalize(root), ignored -> new LinkedHashSet<>()).add(extension);
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
