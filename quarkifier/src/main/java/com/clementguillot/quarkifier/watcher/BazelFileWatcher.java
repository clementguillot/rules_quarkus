package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Cross-platform file watcher for Bazel-driven hot-reload.
 *
 * <p>Uses {@link java.nio.file.WatchService} for native OS-level file watching:
 *
 * <p>Monitors the exact Bazel inputs captured during analysis. The {@link WatchService} still
 * registers directories, as required by its API, but sibling filesystem events are filtered against
 * that input set. Changes to a BUILD file require restarting dev mode because the running child
 * retains its initial application models and watch metadata.
 *
 * <p>Runs as a daemon thread inside the quarkifier process in DEV mode. Implements {@link
 * Closeable} to ensure the {@link WatchService} and executor are properly released.
 */
@SuppressWarnings("PMD.TooManyMethods") // cohesive watch/scope/sync lifecycle
public final class BazelFileWatcher implements Closeable {

  private static final Logger LOGGER = Logger.getLogger(BazelFileWatcher.class);
  private static final long DEFAULT_DEBOUNCE_MS = 100;

  private final QuarkifierConfig config;

  /** Continuous-testing settings, or {@code null} for ordinary dev mode. */
  private final QuarkifierConfig.ContinuousTesting continuousTesting;

  private final WatchService watchService;
  private final ScheduledExecutorService debounceExecutor;
  private final AtomicBoolean buildInProgress = new AtomicBoolean(false);
  private final AtomicBoolean pendingBuild = new AtomicBoolean(false);
  private final AtomicBoolean applicationReloadNeeded = new AtomicBoolean(false);
  private final AtomicBoolean testReloadNeeded = new AtomicBoolean(false);
  private final AtomicBoolean javaDeletionPending = new AtomicBoolean(false);
  private final AtomicBoolean buildFileChangeWarned = new AtomicBoolean(false);
  private volatile ScheduledFuture<?> debounceTask;
  private final Map<Path, WatchKey> watchKeys = new ConcurrentHashMap<>();
  private final BazelBuild bazelBuild;

  /**
   * Hot-reloadable class outputs, with locally-built Quarkus extension jars removed. An extension
   * is a dependency, not part of the reloadable application: syncing its classes into the mutable
   * classes directory would expose them to both the application and augment classloaders, breaking
   * build-time config-mapping lookup ({@code SRCFG00027}).
   */
  private final List<Path> reloadableClassesOutputDirs;

  private final List<Path> reloadableTestClassesOutputDirs;

  /** Path policy: which filesystem paths this watcher cares about. */
  private final WatchedPaths paths;

  /**
   * Creates a new file watcher. The {@link WatchService} is created eagerly; call {@link #close()}
   * to release it.
   *
   * @param config the quarkifier configuration
   * @throws IOException if the watch service cannot be created
   */
  BazelFileWatcher(QuarkifierConfig config) throws IOException {
    this.config = config;
    this.continuousTesting = config.continuousTesting();
    this.reloadableClassesOutputDirs = ClassSyncer.excludeExtensionJars(config.classesOutputDirs());
    this.reloadableTestClassesOutputDirs =
        continuousTesting == null
            ? List.of()
            : ClassSyncer.excludeExtensionJars(continuousTesting.classesOutputDirs());
    List<Path> allOutputs = new ArrayList<>(reloadableClassesOutputDirs);
    allOutputs.addAll(reloadableTestClassesOutputDirs);
    this.bazelBuild = new BazelBuild(config, allOutputs);
    this.paths = new WatchedPaths(config);
    this.watchService = FileSystems.getDefault().newWatchService();
    this.debounceExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "hot-reload-debounce");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Starts the file watcher in a daemon thread. Returns the watcher instance for shutdown. Initial
   * classes population and watcher registration happen before this method starts the watcher
   * thread, ensuring no file change events are missed during initialization.
   *
   * @param config the quarkifier configuration containing source dirs, classes dir, etc.
   * @return the watcher instance (call {@link #close()} to stop)
   * @throws IOException if the watch service cannot be created or initial population fails
   */
  public static BazelFileWatcher startInBackground(QuarkifierConfig config) throws IOException {
    BazelFileWatcher watcher = new BazelFileWatcher(config);
    try {
      // Step 1: Populate initial classes FIRST (can take time, must complete before watching)
      LOGGER.debug("[hot-reload] Populating initial classes...");
      watcher.syncOutputs(false);
      LOGGER.debug("[hot-reload] Initial classes populated");

      // Ordinary dev mode's Java source roots, plus candidate roots that reveal files added to
      // Bazel globs. Quarkus watches resource roots itself; Bazel decides what is an input.
      watcher.registerWatchers(config.sourceDirs());
      // Conventional roots of packages without such sources yet are registered once created.
      watcher.registerWatchers(
          watcher.paths.candidateRoots().stream().filter(Files::isDirectory).toList());
      watcher.registerExactWatchers(watcher.paths.exactWatchPaths());
      LOGGER.debug("[hot-reload] File watchers registered");

      // Step 3: Start watcher thread AFTER population is complete
      Thread watcherThread = new Thread(watcher::watchLoop, "hot-reload-watcher");
      watcherThread.setDaemon(true);
      watcherThread.start();
      LOGGER.debug("[hot-reload] Watcher thread started");

      return watcher;
    } catch (IOException e) {
      watcher.close();
      throw e;
    }
  }

  /**
   * Recursively registers all directories under each watched root with the {@link WatchService} for
   * {@code ENTRY_CREATE}, {@code ENTRY_MODIFY}, and {@code ENTRY_DELETE} events.
   *
   * @param roots source or resource roots to observe
   */
  void registerWatchers(List<Path> roots) {
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        LOGGER.warnf("Watched directory does not exist, skipping: %s", root);
        continue;
      }
      try {
        registerRecursive(root);
      } catch (IOException e) {
        LOGGER.errorv(e, "Failed to register watcher on %s: %s", root);
      }
    }
  }

  /**
   * Main event loop. Polls the {@link WatchService} for relevant source/resource events, registers
   * new subdirectories on {@code ENTRY_CREATE}, and debounces rapid changes before triggering a
   * build-and-sync cycle.
   *
   * <p>The loop exits on {@link Thread#interrupt()} or {@link ClosedWatchServiceException}.
   */
  void watchLoop() {
    LOGGER.debug("[hot-reload] Entering watch loop...");
    try {
      while (!Thread.currentThread().isInterrupted()) {
        WatchKey key = watchService.take(); // blocks until event
        boolean rebuildNeeded = processEvents(key);
        if (!key.reset()) {
          // Directory deleted or key invalidated: release the OS watch (inotify watches are bounded
          // per user). The entry itself is dropped when the parent reports the deletion, which can
          // arrive later: it is how that event is still recognized as a watched directory.
          key.cancel();
        }

        if (rebuildNeeded) {
          scheduleDebouncedBuild();
        }
      }
    } catch (ClosedWatchServiceException e) {
      LOGGER.debugv(e, "WatchService closed, exiting watch loop");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.debugv(e, "Watch loop interrupted, exiting");
    }
  }

  /**
   * Drains the key's events, registering newly created directories for recursive watching.
   *
   * @return true if a watched input changed or the OS event queue overflowed (in which case a full
   *     rebuild is the only safe response)
   */
  private boolean processEvents(WatchKey key) {
    boolean rebuildNeeded = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      WatchEvent.Kind<?> kind = event.kind();

      if (kind == StandardWatchEventKinds.OVERFLOW) {
        LOGGER.warn("WatchService overflow detected, triggering full rebuild");
        requestReload(WatchedPaths.Scope.APPLICATION);
        javaDeletionPending.set(true);
        rebuildNeeded = true;
        continue;
      }

      @SuppressWarnings("unchecked")
      WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
      Path changed = ((Path) key.watchable()).resolve(pathEvent.context());
      if (paths.isBuildFile(changed)) {
        warnRestartRequired(changed);
        continue;
      }
      Path absolute = changed.toAbsolutePath().normalize();
      if (kind == StandardWatchEventKinds.ENTRY_DELETE && watchKeys.containsKey(absolute)) {
        watchKeys.keySet().removeIf(directory -> directory.startsWith(absolute));
        rebuildNeeded |= onDirectoryDeleted(changed);
        continue;
      }
      if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
        rebuildNeeded |= onDirectoryCreated(changed);
        continue;
      }

      WatchedPaths.Scope scope = paths.inputScope(changed);
      if (scope == WatchedPaths.Scope.NONE) {
        if (kind == StandardWatchEventKinds.ENTRY_DELETE && paths.isExactWatchAncestor(changed)) {
          warnRestartRequired(changed);
        }
        continue;
      }
      if (forcesReload(kind, changed)) {
        requestReload(scope);
      }
      if (kind == StandardWatchEventKinds.ENTRY_DELETE && changed.toString().endsWith(".java")) {
        javaDeletionPending.set(true);
      }
      rebuildNeeded = true;
      LOGGER.debugf("Change detected: %s (%s)", changed, kind.name());
    }
    return rebuildNeeded;
  }

  /**
   * Reports whether an input change must mark synchronized classes changed. Creation and deletion
   * change the class-tree topology, which Quarkus may not notice by itself. Continuous testing also
   * reruns tests for non-Java inputs whose bytecode may be unchanged; ordinary dev mode leaves
   * resource edits to Quarkus, and regenerated classes are detected when their bytecode changes.
   */
  boolean forcesReload(WatchEvent.Kind<?> kind, Path changed) {
    return kind != StandardWatchEventKinds.ENTRY_MODIFY
        || (continuousTesting != null && WatchedPaths.isNonJavaInput(changed));
  }

  /** Returns the key currently registered for {@code directory}, or {@code null}. */
  WatchKey registeredKey(Path directory) {
    return watchKeys.get(directory);
  }

  /**
   * Handles the removal of a registered directory. Recursive removal is not guaranteed to deliver a
   * delete event for every contained file, so this event alone requests one Bazel rebuild.
   *
   * @return whether a rebuild is needed
   */
  private boolean onDirectoryDeleted(Path directory) {
    WatchedPaths.Scope scope = paths.treeScope(directory);
    if (scope == WatchedPaths.Scope.NONE) {
      if (paths.isExactWatchAncestor(directory)) {
        warnRestartRequired(directory);
      }
      return false;
    }
    requestReload(scope);
    if (paths.isSourceDirectoryWatchPath(directory)) {
      javaDeletionPending.set(true);
    }
    return true;
  }

  /**
   * Registers a new directory below, or on the way to, a watch root, and requests a rebuild: files
   * created together with the directory may predate its registration.
   *
   * @return whether a rebuild is needed
   */
  private boolean onDirectoryCreated(Path directory) {
    try {
      WatchedPaths.Scope scope = paths.treeScope(directory);
      if (scope != WatchedPaths.Scope.NONE) {
        registerRecursive(directory);
        requestReload(scope);
        LOGGER.debugf("[hot-reload] Registered new directory: %s", directory);
        return true;
      }
      if (paths.isExactWatchAncestor(directory)) {
        registerExactWatchers(paths.exactWatchPaths());
      }
    } catch (IOException e) {
      LOGGER.errorv(e, "[hot-reload] Failed to register new directory %s", directory);
    }
    return false;
  }

  /**
   * Marks the synchronized trees a change affects as changed after the next successful rebuild.
   * Application changes rerun the tests that use the application too; test-only changes rerun tests
   * without restarting the running application.
   */
  private void requestReload(WatchedPaths.Scope scope) {
    if (scope == WatchedPaths.Scope.APPLICATION) {
      applicationReloadNeeded.set(true);
    }
    testReloadNeeded.set(true);
  }

  /** Warns once when watch metadata can no longer be updated safely in the running session. */
  @SuppressWarnings("PMD.SystemPrintln") // must survive Quarkus replacing the log handlers
  private void warnRestartRequired(Path changed) {
    if (buildFileChangeWarned.compareAndSet(false, true)) {
      String warning =
          "[hot-reload] "
              + changed
              + " changed. Restart dev mode so Bazel declarations, application models, and the"
              + " exact watch set are reloaded.";
      LOGGER.warn(warning);
      // Quarkus reconfigures the logging manager after the watcher starts. Keep this lifecycle
      // warning visible even when that removes the parent process's logger handler.
      System.err.println(warning);
    }
  }

  /** Cancels any pending scheduled build and schedules a new one after the debounce delay. */
  private void scheduleDebouncedBuild() {
    ScheduledFuture<?> currentTask = debounceTask;
    if (currentTask != null && !currentTask.isDone()) {
      currentTask.cancel(false);
    }
    debounceTask =
        debounceExecutor.schedule(
            this::triggerBuildAndSync, DEFAULT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Triggers a build-and-sync cycle. Serializes builds using {@link AtomicBoolean} flags: only one
   * {@code bazel build} runs at a time. If a build is already in progress, the request is queued
   * and drained after the current build completes.
   */
  void triggerBuildAndSync() {
    if (buildInProgress.compareAndSet(false, true)) {
      try {
        do {
          pendingBuild.set(false);
          boolean reloadApplication = applicationReloadNeeded.getAndSet(false);
          boolean reloadTests = testReloadNeeded.getAndSet(false);
          // Ordinary dev mode lets Quarkus correlate a deleted source with its previous class.
          // Continuous testing has no source roots, so retaining that class would keep it live.
          boolean preserveStaleClasses =
              javaDeletionPending.getAndSet(false) && continuousTesting == null;
          long start = System.currentTimeMillis();
          boolean success = bazelBuild.run();
          long elapsed = System.currentTimeMillis() - start;

          if (success) {
            if (!syncClasses(reloadApplication, reloadTests, preserveStaleClasses)) {
              restorePending(applicationReloadNeeded, reloadApplication);
              restorePending(testReloadNeeded, reloadTests);
              restorePending(javaDeletionPending, preserveStaleClasses);
            }
            LOGGER.debugf("[hot-reload] Build successful, classes synced (%dms)", elapsed);
          } else {
            restorePending(applicationReloadNeeded, reloadApplication);
            restorePending(testReloadNeeded, reloadTests);
            restorePending(javaDeletionPending, preserveStaleClasses);
            LOGGER.warn("[hot-reload] Build failed, skipping sync");
          }
        } while (pendingBuild.get()); // drain queued builds
      } finally {
        buildInProgress.set(false);
      }
    } else {
      // Build already in progress — mark pending
      pendingBuild.set(true);
      LOGGER.debug("[hot-reload] Build already in progress, queueing request");
    }
  }

  private static void restorePending(AtomicBoolean pending, boolean consumed) {
    if (consumed) {
      pending.set(true);
    }
  }

  /**
   * Copies changed {@code .class} files from the bazel-bin output paths to the mutable classes
   * directory, and — when continuous testing is configured — the compiled tests and their packaged
   * resources to the mutable test-classes directory.
   *
   * @param reloadApplication whether a non-Java input or topology change affected the application,
   *     in which case synchronized application classes are timestamped forward so Quarkus observes
   *     the rebuild even if no surviving bytecode changed
   * @param reloadTests the same for the synchronized test classes under continuous testing
   * @param preserveStaleClasses whether a Java deletion requires stale bytecode to remain long
   *     enough for Quarkus to associate it with the deleted source and remove it
   * @return {@code true} if everything synchronized; {@code false} lets the caller restore the
   *     pending change so the next successful build still schedules that reload or test run
   */
  boolean syncClasses(
      boolean reloadApplication, boolean reloadTests, boolean preserveStaleClasses) {
    try {
      syncOutputs(preserveStaleClasses);
      int changed = reloadApplication ? ClassSyncer.markClassesChanged(config.classesDir()) : 0;
      if (reloadTests && continuousTesting != null) {
        changed += ClassSyncer.markClassesChanged(continuousTesting.classesDir());
      }
      if (changed > 0) {
        LOGGER.debugf("[hot-reload] Marked %d classes changed after structural rebuild", changed);
      }
      // Continuous testing gives Quarkus no source paths, so on Linux its event-driven test
      // scanner would never wake up. This source-free marker is written only after every mutable
      // output is ready; Quarkus then rescans the synchronized trees (other hosts poll them).
      if (continuousTesting != null) {
        Path notificationDir = continuousTesting.reloadNotificationDir();
        Files.createDirectories(notificationDir);
        Files.writeString(
            notificationDir.resolve("completed-build"), Long.toString(System.nanoTime()));
      }
      LOGGER.debug("[hot-reload] Classes synced successfully");
      return true;
    } catch (IOException e) {
      LOGGER.errorf("[hot-reload] Failed to sync classes: %s", e.getMessage());
      return false;
    }
  }

  /**
   * Mirrors the Bazel outputs into the mutable trees. Under continuous testing Quarkus has no
   * workspace resource paths, so packaged resources are synchronized too.
   */
  private void syncOutputs(boolean preserveStaleClasses) throws IOException {
    if (continuousTesting == null) {
      ClassSyncer.syncClasses(
          reloadableClassesOutputDirs, config.classesDir(), preserveStaleClasses);
      return;
    }
    ClassSyncer.syncClassesAndResources(
        reloadableClassesOutputDirs, config.classesDir(), preserveStaleClasses);
    ClassSyncer.syncClassesAndResources(
        reloadableTestClassesOutputDirs, continuousTesting.classesDir(), preserveStaleClasses);
  }

  /** Closes the {@link WatchService} and shuts down the {@link ScheduledExecutorService}. */
  @Override
  public void close() {
    // Cancel all registered watch keys to release OS resources
    for (WatchKey key : watchKeys.values()) {
      key.cancel();
    }
    watchKeys.clear();

    // Cancel any pending debounce task
    ScheduledFuture<?> task = debounceTask;
    if (task != null && !task.isDone()) {
      task.cancel(false);
    }

    try {
      watchService.close();
    } catch (IOException e) {
      LOGGER.errorf("Error closing WatchService: %s", e.getMessage());
    }
    debounceExecutor.shutdownNow();
    LOGGER.debug("[hot-reload] BazelFileWatcher closed, all resources released");
  }

  // ---- internal helpers ----

  /**
   * Recursively registers all directories under rootDir with the WatchService and stores the
   * WatchKey references for proper cleanup.
   */
  private void registerRecursive(Path rootDir) throws IOException {
    Files.walkFileTree(
        rootDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (isVersionControlDirectory(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            // Registering again is harmless (a live key is returned as is) and replaces the stale
            // key of a directory that was deleted and recreated before its key was drained.
            WatchKey key =
                dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeys.put(dir, key);
            LOGGER.debugf("[hot-reload] Registered watcher on directory: %s", dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Registers existing parent directories for exact input and BUILD-file watches. */
  private void registerExactWatchers(List<Path> watchedPaths) throws IOException {
    Path boundary =
        config.workspaceDir() == null ? null : config.workspaceDir().toAbsolutePath().normalize();
    for (Path watchedPath : watchedPaths) {
      Path directory = watchedPath.toAbsolutePath().normalize().getParent();
      while (directory != null && (boundary == null || directory.startsWith(boundary))) {
        if (Files.isDirectory(directory)) {
          WatchKey key =
              directory.register(
                  watchService,
                  StandardWatchEventKinds.ENTRY_CREATE,
                  StandardWatchEventKinds.ENTRY_MODIFY,
                  StandardWatchEventKinds.ENTRY_DELETE);
          watchKeys.put(directory, key);
        }
        if (boundary == null || directory.equals(boundary)) {
          break;
        }
        directory = directory.getParent();
      }
    }
  }

  /**
   * Returns true for version-control metadata directories. A candidate root can be a package or the
   * workspace itself, and watching repository metadata would waste watches on files Bazel never
   * reads.
   */
  static boolean isVersionControlDirectory(Path dir) {
    Path name = dir.getFileName();
    if (name == null) {
      return false;
    }
    String value = name.toString();
    return ".git".equals(value) || ".hg".equals(value) || ".svn".equals(value);
  }
}
