package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which filesystem paths the hot-reload watcher cares about.
 *
 * <p>Split out of {@link BazelFileWatcher} so the watcher owns the watch/build/sync lifecycle and
 * this type owns path policy. Roots are normalized once here: prefix matching runs on every
 * filesystem event, so doing it per event would allocate a {@link Path} per configured root.
 */
final class WatchedPaths {

  private final Path workspaceRoot;
  private final List<Path> codegenInputDirs;
  private final List<Path> testResourceDirs;
  private final List<Path> packageDirs;

  WatchedPaths(QuarkifierConfig config) {
    this.workspaceRoot = normalizeRoot(config.workspaceDir());
    this.codegenInputDirs = normalize(config.codegenInputDirs());
    this.testResourceDirs = normalize(config.testResources());
    this.packageDirs = normalize(config.watchedPackageDirs());
  }

  /**
   * Reports whether {@code changed} is a code-generation input.
   *
   * <p>Matching is scoped to the generator input directories (for example {@code src/main/proto}),
   * not to the enclosing source parent: a source parent is the whole {@code src/main} tree, so
   * matching on it would trigger a full Bazel rebuild whenever any resource or other non-Java file
   * below it is saved.
   */
  boolean isCodegenInput(Path changed) {
    return isUnder(changed, codegenInputDirs);
  }

  /** Reports whether {@code changed} belongs to a declared test resource directory. */
  boolean isTestResource(Path changed) {
    return isUnder(changed, testResourceDirs);
  }

  /** Reports whether {@code changed} belongs to a watched Bazel package directory. */
  boolean isPackageInput(Path changed) {
    return isUnder(changed, packageDirs);
  }

  /**
   * Reports whether {@code path} is build-tool state that must never trigger a rebuild.
   *
   * <p>A watched Bazel package root can widen to the whole workspace — a root-level {@code BUILD}
   * file collapses to {@code "."} — so the outputs of the very build the watcher runs would
   * otherwise feed back into it.
   */
  boolean isIgnored(Path path) {
    if (isVersionControlDirectory(path)) {
      return true;
    }
    if (workspaceRoot == null) {
      return false;
    }
    Path absolute = path.toAbsolutePath().normalize();
    if (!absolute.startsWith(workspaceRoot) || absolute.equals(workspaceRoot)) {
      return false;
    }
    String first = workspaceRoot.relativize(absolute).getName(0).toString();
    return first.startsWith("bazel-")
        || "target".equals(first)
        || "MODULE.bazel.lock".equals(first);
  }

  /**
   * Reports whether {@code dir} is version-control metadata that must never be watched.
   *
   * <p>A watched root can legitimately widen to the whole workspace: a source root of {@code "."},
   * or a resource declared directly at the workspace root, both collapse to it. Registering every
   * directory below such a root would put a watch on {@code .git}, whose constant churn during any
   * ordinary git operation would then queue a full rebuild, and on Linux would burn the per-user
   * inotify watch budget.
   *
   * <p>Below an explicitly declared root other dot-directories remain eligible, because a declared
   * generator input may live in one. Below a bare Bazel package root they do not — see {@link
   * #isHidden}.
   */
  static boolean isVersionControlDirectory(Path dir) {
    Path name = dir.getFileName();
    if (name == null) {
      return false;
    }
    String value = name.toString();
    return ".git".equals(value) || ".hg".equals(value) || ".svn".equals(value);
  }

  /**
   * Reports whether {@code dir} is hidden (dot-prefixed).
   *
   * <p>A Bazel package root covers a whole checkout, which is full of tool state the build never
   * reads — {@code .idea}, {@code .venv}, {@code .gradle}. Watching those would rebuild on every
   * IDE save and, on Linux, burn the inotify budget.
   */
  static boolean isHidden(Path dir) {
    Path name = dir.getFileName();
    return name != null && name.toString().startsWith(".");
  }

  /**
   * Reports whether {@code fileName} is an editor temporary, backup, or probe file.
   *
   * <p>Watched roots match on location alone rather than on an extension, so without this a single
   * {@code vim} save would queue rebuilds for the {@code 4913} probe file, the {@code .swp} file,
   * and the {@code ~} backup as well.
   */
  static boolean isEditorScratchFile(Path fileName) {
    if (fileName == null) {
      return true;
    }
    String name = fileName.toString();
    return name.isEmpty()
        || name.startsWith(".")
        || name.endsWith("~")
        || name.endsWith(".swp")
        || name.endsWith(".swx")
        || name.endsWith(".tmp");
  }

  /** Reports whether {@code changed} sits below one of the already-normalized {@code roots}. */
  private static boolean isUnder(Path changed, List<Path> roots) {
    if (roots.isEmpty()) {
      return false;
    }
    Path absolute = changed.toAbsolutePath().normalize();
    for (Path root : roots) {
      if (absolute.startsWith(root)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the workspace root in absolute, normalized form, or {@code null} when dev mode was not
   * launched through {@code bazel run} and no workspace directory is known.
   */
  private static Path normalizeRoot(Path root) {
    if (root == null) {
      return null;
    }
    return root.toAbsolutePath().normalize();
  }

  /** Returns {@code dirs} in absolute, normalized form. */
  private static List<Path> normalize(List<Path> dirs) {
    List<Path> normalized = new ArrayList<>(dirs.size());
    for (Path dir : dirs) {
      normalized.add(dir.toAbsolutePath().normalize());
    }
    return List.copyOf(normalized);
  }
}
