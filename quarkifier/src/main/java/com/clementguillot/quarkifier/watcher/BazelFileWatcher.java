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
 * <ul>
 *   <li>macOS: kqueue
 *   <li>Linux: inotify
 *   <li>Windows: ReadDirectoryChangesW
 * </ul>
 *
 * <p>Monitors source directories for {@code .java} file changes, debounces rapid edits, invokes
 * {@code bazel build} via {@link ProcessBuilder}, and syncs resulting {@code .class} files to the
 * mutable classes directory that {@code RuntimeUpdatesProcessor} monitors.
 *
 * <p>Runs as a daemon thread inside the quarkifier process in DEV mode. Implements {@link
 * Closeable} to ensure the {@link WatchService} and executor are properly released.
 */
public final class BazelFileWatcher implements Closeable {

  private static final Logger LOGGER = Logger.getLogger(BazelFileWatcher.class);
  private static final long DEFAULT_DEBOUNCE_MS = 100;
  private static final String BAZEL_LOG_FILE = "bazel-hot-reload.log";

  private final QuarkifierConfig config;
  private final WatchService watchService;
  private final ScheduledExecutorService debounceExecutor;
  private final AtomicBoolean buildInProgress = new AtomicBoolean(false);
  private final AtomicBoolean pendingBuild = new AtomicBoolean(false);
  private volatile ScheduledFuture<?> debounceTask;
  private final Map<Path, WatchKey> watchKeys = new ConcurrentHashMap<>();
  private final Path bazelLogPath;

  /**
   * Creates a new file watcher. The {@link WatchService} is created eagerly; call {@link #close()}
   * to release it.
   *
   * @param config the quarkifier configuration
   * @throws IOException if the watch service cannot be created
   */
  BazelFileWatcher(QuarkifierConfig config) throws IOException {
    this.config = config;
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
      ClassSyncer.populateClassesDir(config.classesOutputDirs(), config.classesDir());
      LOGGER.debug("[hot-reload] Initial classes populated");

      // Step 2: Register watchers on all source directories
      watcher.registerWatchers(config.sourceDirs());
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
   * Recursively registers all directories under each source directory with the {@link WatchService}
   * for {@code ENTRY_CREATE}, {@code ENTRY_MODIFY}, and {@code ENTRY_DELETE} events.
   *
   * @param sourceDirs source directories to watch
   */
  void registerWatchers(List<Path> sourceDirs) {
    for (Path sourceDir : sourceDirs) {
      if (!Files.isDirectory(sourceDir)) {
        LOGGER.warnf("Source directory does not exist, skipping: %s", sourceDir);
        continue;
      }
      try {
        registerRecursive(sourceDir);
      } catch (IOException e) {
        LOGGER.errorv(e, "Failed to register watcher on %s: %s", sourceDir);
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
        List<WatchEvent<?>> events = key.pollEvents();

        boolean javaFileChanged = false;
        boolean overflow = false;

        for (WatchEvent<?> event : events) {
          WatchEvent.Kind<?> kind = event.kind();

          // Handle OVERFLOW: trigger a full rebuild
          if (kind == StandardWatchEventKinds.OVERFLOW) {
            LOGGER.warn("WatchService overflow detected, triggering full rebuild");
            overflow = true;
            continue;
          }

          @SuppressWarnings("unchecked")
          WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
          Path changed = ((Path) key.watchable()).resolve(pathEvent.context());

          // Register new subdirectories for recursive watching
          if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
            try {
              registerRecursive(changed);
              LOGGER.debugf("[hot-reload] Registered new directory: %s", changed);
            } catch (IOException e) {
              LOGGER.errorv(e, "[hot-reload] Failed to register new directory %s", changed);
            }
          }

          if (changed.toString().endsWith(".java")) {
            javaFileChanged = true;
            LOGGER.debugf("Change detected: %s (%s)", changed, kind.name());
          }
        }

        key.reset();

        if (javaFileChanged || overflow) {
          // Debounce: cancel previous scheduled build, schedule new one
          ScheduledFuture<?> currentTask = debounceTask;
          if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(false);
          }
          debounceTask =
              debounceExecutor.schedule(
                  this::triggerBuildAndSync, DEFAULT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
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
   * Triggers a build-and-sync cycle. Serializes builds using {@link AtomicBoolean} flags: only one
   * {@code bazel build} runs at a time. If a build is already in progress, the request is queued
   * and drained after the current build completes.
   */
  void triggerBuildAndSync() {
    if (buildInProgress.compareAndSet(false, true)) {
      try {
        do {
          pendingBuild.set(false);
          long start = System.currentTimeMillis();
          boolean success = runBazelBuild(config.bazelTargets());
          long elapsed = System.currentTimeMillis() - start;

          if (success) {
            syncClasses();
            LOGGER.debugf("[hot-reload] Build successful, classes synced (%dms)", elapsed);
          } else {
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
    command.add("bazel");
    command.add("build");
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
            "[hot-reload] Bazel build timed out after %d seconds, destroying process", timeout);
        process.destroyForcibly();
        return false;
      }

      int exitCode = process.exitValue();
      if (exitCode != 0) {
        LOGGER.debugf(
            "[hot-reload] Bazel build failed with exit code %d (see %s)", exitCode, bazelLogPath);
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
   * Delegates to {@link ClassSyncer#syncClasses(List, Path)} to copy changed {@code .class} files
   * from bazel-bin output directories to the mutable classes directory.
   */
  void syncClasses() {
    try {
      ClassSyncer.syncClasses(config.classesOutputDirs(), config.classesDir());
      LOGGER.debug("[hot-reload] Classes synced successfully");
    } catch (IOException e) {
      LOGGER.errorf("[hot-reload] Failed to sync classes: %s", e.getMessage());
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
  private void registerRecursive(Path rootDir) throws IOException {
    Files.walkFileTree(
        rootDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
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
