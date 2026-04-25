package com.clementguillot.quarkifier.watcher;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>Bazel target: {@code //quarkifier:bazel_file_watcher}
 */
public final class BazelFileWatcher {

  private final WatcherConfig config;
  private final WatchService watchService;
  private final ScheduledExecutorService debounceExecutor;
  private final AtomicBoolean buildInProgress = new AtomicBoolean(false);
  private final AtomicBoolean pendingBuild = new AtomicBoolean(false);
  private volatile ScheduledFuture<?> debounceTask;

  BazelFileWatcher(WatcherConfig config, WatchService watchService) {
    this.config = config;
    this.watchService = watchService;
    this.debounceExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "hot-reload-debounce");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Entry point. Parses CLI arguments into a {@link WatcherConfig}, registers watchers on all
   * source directories, populates the classes directory from the initial build output, and enters
   * the watch loop.
   */
  public static void main(String[] args) {
    WatcherConfig config = WatcherConfig.parse(args);

    log("Starting Java file watcher (WatchService)");
    log("Watching: " + config.sourceDirs());
    log("Bazel targets: " + config.bazelTargets());
    log("Classes dir: " + config.classesDir());
    log("Debounce: " + config.debounceMs() + "ms");

    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      BazelFileWatcher watcher = new BazelFileWatcher(config, watchService);

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log("Shutting down file watcher...");
                    watcher.shutdown();
                  },
                  "hot-reload-shutdown"));

      watcher.registerWatchers(config.sourceDirs());

      log("Populating initial classes...");
      ClassSyncer.populateClassesDir(config.classesOutputDirs(), config.classesDir());
      log("Initial classes populated");

      watcher.watchLoop();
    } catch (IOException e) {
      log("Fatal error: " + e.getMessage());
      System.exit(1);
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
        log("Source directory does not exist, skipping: " + sourceDir);
        continue;
      }
      try {
        registerRecursive(sourceDir);
      } catch (IOException e) {
        log("Failed to register watcher on " + sourceDir + ": " + e.getMessage());
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
    log("Entering watch loop...");
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
            log("WatchService overflow detected, triggering full rebuild");
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
              log("Registered new directory: " + changed);
            } catch (IOException e) {
              log("Failed to register new directory " + changed + ": " + e.getMessage());
            }
          }

          if (changed.toString().endsWith(".java")) {
            javaFileChanged = true;
            log("Change detected: " + changed + " (" + kind.name() + ")");
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
                  this::triggerBuildAndSync, config.debounceMs(), TimeUnit.MILLISECONDS);
        }
      }
    } catch (ClosedWatchServiceException e) {
      log("WatchService closed, exiting watch loop");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log("Watch loop interrupted, exiting");
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
            log("Build successful, classes synced (" + elapsed + "ms)");
          } else {
            log("Build failed, skipping sync");
          }
        } while (pendingBuild.get()); // drain queued builds
      } finally {
        buildInProgress.set(false);
      }
    } else {
      // Build already in progress — mark pending
      pendingBuild.set(true);
    }
  }

  /**
   * Invokes {@code bazel build} via {@link ProcessBuilder} with the given targets.
   *
   * @param targets Bazel target labels to build
   * @return {@code true} if the build succeeded (exit code 0), {@code false} otherwise
   */
  boolean runBazelBuild(List<String> targets) {
    List<String> command = new ArrayList<>();
    command.add("bazel");
    command.add("build");
    command.addAll(targets);

    log("Rebuilding " + String.join(" ", targets) + "...");

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      if (config.workspaceDir() != null) {
        pb.directory(config.workspaceDir().toFile());
      }
      pb.inheritIO();
      Process process = pb.start();
      int exitCode = process.waitFor();
      return exitCode == 0;
    } catch (IOException e) {
      log("Failed to start bazel build: " + e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log("Build interrupted");
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
    } catch (IOException e) {
      log("Failed to sync classes: " + e.getMessage());
    }
  }

  /** Closes the {@link WatchService} and shuts down the {@link ScheduledExecutorService}. */
  void shutdown() {
    try {
      watchService.close();
    } catch (IOException e) {
      log("Error closing WatchService: " + e.getMessage());
    }
    debounceExecutor.shutdownNow();
  }

  // ---- internal helpers ----

  private void registerRecursive(Path rootDir) throws IOException {
    Files.walkFileTree(
        rootDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void log(String message) {
    System.out.println("[hot-reload] " + message);
  }
}
