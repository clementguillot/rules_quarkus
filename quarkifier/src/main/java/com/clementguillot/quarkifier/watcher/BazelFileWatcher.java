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
import java.nio.file.StandardOpenOption;
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
 * <p>Monitors Java sources, test resources, code-generation inputs and — for continuous testing —
 * whole Bazel package directories, so that files added to a previously empty glob are noticed.
 * Debounces rapid edits, invokes {@code bazel build} via {@link ProcessBuilder}, and syncs the
 * resulting {@code .class} files to the mutable classes directory that {@code
 * RuntimeUpdatesProcessor} monitors. Because a package root matches on location rather than on an
 * extension, any non-Java edit below one conservatively reruns the selected test suite.
 *
 * <p>Runs as a daemon thread inside the quarkifier process in DEV mode. Implements {@link
 * Closeable} to ensure the {@link WatchService} and executor are properly released.
 */
@SuppressWarnings("PMD.TooManyMethods") // cohesive watch/build/sync lifecycle
public final class BazelFileWatcher implements Closeable {

  private static final Logger LOGGER = Logger.getLogger(BazelFileWatcher.class);
  private static final long DEFAULT_DEBOUNCE_MS = 100;
  private static final String BAZEL_LOG_FILE = "bazel-hot-reload.log";

  private final QuarkifierConfig config;
  private final WatchService watchService;
  private final ScheduledExecutorService debounceExecutor;
  private final AtomicBoolean buildInProgress = new AtomicBoolean(false);
  private final AtomicBoolean pendingBuild = new AtomicBoolean(false);
  private final AtomicBoolean staleOutputsWarned = new AtomicBoolean(false);
  private final AtomicBoolean nonJavaInputChanged = new AtomicBoolean(false);
  private volatile ScheduledFuture<?> debounceTask;
  private final Map<Path, WatchKey> watchKeys = new ConcurrentHashMap<>();
  private final Path bazelLogPath;

  /**
   * Hot-reloadable class outputs, with locally-built Quarkus extension jars removed. An extension
   * is a dependency, not part of the reloadable application: syncing its classes into the mutable
   * classes directory would expose them to both the application and augment classloaders, breaking
   * build-time config-mapping lookup ({@code SRCFG00027}).
   */
  private final List<Path> reloadableClassesOutputDirs;

  private final List<Path> reloadableTestClassesOutputDirs;

  /** Application and test class outputs combined, for the stale-output freshness check. */
  private final List<Path> allReloadableOutputDirs;

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
    this.reloadableClassesOutputDirs = ClassSyncer.excludeExtensionJars(config.classesOutputDirs());
    this.reloadableTestClassesOutputDirs =
        ClassSyncer.excludeExtensionJars(config.testClassesOutputDirs());
    List<Path> allOutputs = new ArrayList<>(reloadableClassesOutputDirs);
    allOutputs.addAll(reloadableTestClassesOutputDirs);
    this.allReloadableOutputDirs = List.copyOf(allOutputs);
    this.paths = new WatchedPaths(config);
    this.watchService = FileSystems.getDefault().newWatchService();
    this.debounceExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "hot-reload-debounce");
              t.setDaemon(true);
              return t;
            });
    // Create bazel log file in output dir or temp dir
    Path outputDir = config.outputDir();
    if (outputDir != null) {
      this.bazelLogPath = outputDir.resolve(BAZEL_LOG_FILE);
    } else {
      this.bazelLogPath = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), BAZEL_LOG_FILE);
    }
    // Initialize log file
    Files.createDirectories(bazelLogPath.getParent());
    Files.writeString(
        bazelLogPath,
        "[hot-reload] Bazel build log\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
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
      if (config.testClassesDir() != null) {
        Files.createDirectories(config.reloadNotificationDir());
        ClassSyncer.populateClassesAndResources(
            watcher.reloadableClassesOutputDirs, config.classesDir());
        ClassSyncer.populateClassesAndResources(
            watcher.reloadableTestClassesOutputDirs, config.testClassesDir());
      } else {
        ClassSyncer.populateClassesDir(watcher.reloadableClassesOutputDirs, config.classesDir());
      }
      LOGGER.debug("[hot-reload] Initial classes populated");

      // Step 2: Register watchers on all watched roots. Declared roots come first so
      // their subtrees are fully registered before the enclosing Bazel packages are
      // walked; registerRecursive then skips what is already covered.
      watcher.registerWatchers(config.sourceDirs(), false);
      watcher.registerWatchers(config.testSourceDirs(), false);
      watcher.registerWatchers(config.testResources(), false);
      watcher.registerWatchers(config.codegenInputDirs(), false);
      watcher.registerWatchers(config.watchedPackageDirs(), true);
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
   * @param roots watched roots (source, resource, generator-input, or Bazel package directories)
   * @param pruneHidden skip hidden subdirectories below each root. A Bazel package root covers a
   *     whole checkout, which is full of tool state the build never reads — {@code .idea}, {@code
   *     .venv}, {@code .gradle}. Registering those would churn rebuilds on every IDE save and, on
   *     Linux, burn the per-user inotify budget. Declared roots pass {@code false}: an explicitly
   *     declared generator input may legitimately live under a dot-directory.
   */
  void registerWatchers(List<Path> roots, boolean pruneHidden) {
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        LOGGER.warnf("Watched directory does not exist, skipping: %s", root);
        continue;
      }
      try {
        registerRecursive(root, pruneHidden);
      } catch (IOException e) {
        LOGGER.errorv(e, "Failed to register watcher on %s: %s", root);
      }
    }
  }

  /**
   * Main event loop. Polls the {@link WatchService} for file system events, filters for {@code
   * .java} file changes, registers new subdirectories on {@code ENTRY_CREATE}, and debounces rapid
   * changes before triggering a build-and-sync cycle.
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
          // Directory deleted or key invalidated: drop it so the OS watch
          // resource is released (inotify watches are bounded per user) and
          // the map does not grow across branch switches in long sessions.
          key.cancel();
          watchKeys.values().remove(key);
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
   * @return true if a {@code .java} file changed or the OS event queue overflowed (in which case a
   *     full rebuild is the only safe response)
   */
  private boolean processEvents(WatchKey key) {
    boolean rebuildNeeded = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      WatchEvent.Kind<?> kind = event.kind();

      if (kind == StandardWatchEventKinds.OVERFLOW) {
        LOGGER.warn("WatchService overflow detected, triggering full rebuild");
        nonJavaInputChanged.set(true);
        rebuildNeeded = true;
        continue;
      }

      @SuppressWarnings("unchecked")
      WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
      Path changed = ((Path) key.watchable()).resolve(pathEvent.context());
      // Editor temporaries, backups, and probe files are filtered once here: a watched
      // Bazel package root matches on location alone, so without this a single vim save
      // of one file would queue rebuilds for its 4913 probe, its .swp, and its ~ backup.
      if (paths.isIgnored(changed) || WatchedPaths.isEditorScratchFile(changed.getFileName())) {
        continue;
      }

      boolean testResource = paths.isTestResource(changed);
      boolean codegenInput = paths.isCodegenInput(changed);
      boolean packageInput = paths.isPackageInput(changed);

      if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
        try {
          // Prune hidden subtrees only below a bare package root; a declared root may
          // legitimately hold generator inputs under a dot-directory.
          registerRecursive(changed, packageInput && !testResource && !codegenInput);
          LOGGER.debugf("[hot-reload] Registered new directory: %s", changed);
        } catch (IOException e) {
          LOGGER.errorv(e, "[hot-reload] Failed to register new directory %s", changed);
        }
      }

      if (changed.toString().endsWith(".java") || testResource || codegenInput || packageInput) {
        if (testResource
            || codegenInput
            || (packageInput && !changed.toString().endsWith(".java"))
            || kind == StandardWatchEventKinds.ENTRY_DELETE) {
          nonJavaInputChanged.set(true);
        }
        rebuildNeeded = true;
        LOGGER.debugf("Change detected: %s (%s)", changed, kind.name());
      }
    }
    return rebuildNeeded;
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
          boolean markTestsChanged = nonJavaInputChanged.getAndSet(false);
          long start = System.currentTimeMillis();
          boolean success = runBazelBuild(config.bazelTargets());
          long elapsed = System.currentTimeMillis() - start;

          if (success) {
            warnIfOutputsWentStale(start);
            if (!syncClasses(markTestsChanged)) {
              nonJavaInputChanged.compareAndSet(false, markTestsChanged);
            }
            LOGGER.debugf("[hot-reload] Build successful, classes synced (%dms)", elapsed);
          } else {
            nonJavaInputChanged.compareAndSet(false, markTestsChanged);
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

  /**
   * Invokes {@code bazel build} via {@link ProcessBuilder} with the given targets. Bazel output is
   * redirected to a log file to avoid polluting the Quarkus dev console. The build process has a
   * configurable timeout to prevent hanging.
   *
   * @param targets Bazel target labels to build
   * @return {@code true} if the build succeeded (exit code 0), {@code false} otherwise
   */
  boolean runBazelBuild(List<String> targets) {
    List<String> command = new ArrayList<>();
    command.add(config.bazelCommand());
    command.add("build");
    // Extra flags so the rebuild uses the same configuration as the original
    // `bazel run` — otherwise outputs land in a different bazel-out tree than
    // the recorded classes output paths.
    command.addAll(config.bazelBuildArgs());
    command.addAll(targets);

    LOGGER.debugf("[hot-reload] Rebuilding %s...", String.join(" ", targets));

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      if (config.workspaceDir() != null) {
        pb.directory(config.workspaceDir().toFile());
      }
      // Redirect bazel output to log file to avoid polluting Quarkus console
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(bazelLogPath.toFile()));
      pb.redirectError(ProcessBuilder.Redirect.appendTo(bazelLogPath.toFile()));

      Process process = pb.start();

      // Wait with timeout to prevent hanging if bazel gets stuck
      long timeout = config.bazelBuildTimeoutSeconds();
      boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

      if (!finished) {
        LOGGER.warnf(
            "[hot-reload] Bazel build timed out after %d seconds, destroying process."
                + " Killing the client may leave the server-side build running; the next"
                + " rebuild can then block on the workspace lock until it finishes. Raise"
                + " --bazel-build-timeout-seconds if your builds are legitimately slow.",
            timeout);
        process.destroyForcibly();
        logBazelLogTail();
        return false;
      }

      int exitCode = process.exitValue();
      if (exitCode != 0) {
        LOGGER.warnf(
            "[hot-reload] Bazel build failed with exit code %d (see %s)", exitCode, bazelLogPath);
        logBazelLogTail();
      }
      return exitCode == 0;
    } catch (IOException e) {
      LOGGER.errorf("[hot-reload] Failed to start bazel build: %s", e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warn("[hot-reload] Build interrupted");
      return false;
    }
  }

  /**
   * Warns (once per session) when a successful rebuild updated none of the recorded class output
   * paths — the telltale sign that the rebuild ran in a different configuration than the original
   * {@code bazel run} (e.g. the user launched dev mode with {@code --config} or {@code -c opt}), so
   * hot-reload would silently sync stale classes.
   *
   * <p>Directories are skipped (their mtime does not reflect nested changes); a fully-incremental
   * no-op rebuild could in theory trigger a false warning, which is why this warns instead of
   * failing and only fires once.
   *
   * @param buildStartMillis wall-clock time at which the rebuild was started
   */
  private void warnIfOutputsWentStale(long buildStartMillis) {
    if (staleOutputsWarned.get()) {
      return;
    }
    long threshold = buildStartMillis - 2000; // slack for coarse mtime granularity
    for (Path path : allReloadableOutputDirs) {
      try {
        if (Files.isDirectory(path)) {
          return; // cannot cheaply track directory freshness; assume OK
        }
        if (Files.exists(path) && Files.getLastModifiedTime(path).toMillis() >= threshold) {
          return; // at least one output was rewritten by this build
        }
      } catch (IOException ignored) {
        // Unreadable path — let syncClasses surface the real error.
      }
    }
    if (staleOutputsWarned.compareAndSet(false, true)) {
      LOGGER.warnf(
          "[hot-reload] Build succeeded but none of the class output paths were updated: %s."
              + " If you launch dev mode with extra Bazel flags (e.g. --config, -c opt), set"
              + " dev_build_args on quarkus_app/quarkus_dev so hot-reload rebuilds use the same"
              + " configuration — otherwise code changes will not be picked up.",
          allReloadableOutputDirs);
    }
  }

  /** Logs the tail of the bazel build log so failures are visible without digging for the file. */
  private void logBazelLogTail() {
    try {
      List<String> lines = Files.readAllLines(bazelLogPath);
      int from = Math.max(0, lines.size() - 20);
      LOGGER.warnf(
          "[hot-reload] Last %d lines of %s:%n%s",
          lines.size() - from,
          bazelLogPath,
          String.join(System.lineSeparator(), lines.subList(from, lines.size())));
    } catch (IOException e) {
      LOGGER.debugf("[hot-reload] Could not read bazel log %s: %s", bazelLogPath, e.getMessage());
    }
  }

  /**
   * Copies changed {@code .class} files from the bazel-bin output paths to the mutable classes
   * directory, and — when continuous testing is configured — the compiled tests and their packaged
   * resources to the mutable test-classes directory.
   *
   * @param markTestsChanged whether the rebuild was triggered by a non-Java input, in which case
   *     the synchronized test classes are timestamped forward so Quarkus schedules a test run
   * @return {@code true} if everything synchronized; {@code false} lets the caller restore the
   *     pending non-Java change so the next successful build still schedules that test run
   */
  boolean syncClasses(boolean markTestsChanged) {
    try {
      if (config.testClassesDir() != null) {
        ClassSyncer.syncClassesAndResources(reloadableClassesOutputDirs, config.classesDir());
        ClassSyncer.syncClassesAndResources(
            reloadableTestClassesOutputDirs, config.testClassesDir());
        if (markTestsChanged) {
          int changed = ClassSyncer.markTestClassesChanged(config.testClassesDir());
          LOGGER.debugf(
              "[hot-reload] Marked %d test classes changed after non-Java input rebuild", changed);
        }
        // The notification contains no compilable source or application resource.
        // Linux's event-driven test scanner sees it only after both trees are ready.
        Path notificationDir = config.reloadNotificationDir();
        Files.createDirectories(notificationDir);
        Files.writeString(
            notificationDir.resolve("completed-build"), Long.toString(System.nanoTime()));
      } else {
        ClassSyncer.syncClasses(reloadableClassesOutputDirs, config.classesDir());
      }
      LOGGER.debug("[hot-reload] Classes synced successfully");
      return true;
    } catch (IOException e) {
      LOGGER.errorf("[hot-reload] Failed to sync classes: %s", e.getMessage());
      return false;
    }
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
  private void registerRecursive(Path rootDir, boolean pruneHidden) throws IOException {
    Files.walkFileTree(
        rootDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (paths.isIgnored(dir)
                || (pruneHidden && !dir.equals(rootDir) && WatchedPaths.isHidden(dir))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (watchKeys.containsKey(dir)) {
              // An earlier, narrower root already registered this whole subtree.
              return FileVisitResult.SKIP_SUBTREE;
            }
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
}
