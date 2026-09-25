package com.clementguillot.quarkifier.watcher;

import com.clementguillot.quarkifier.QuarkifierConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Runs the hot-reload {@code bazel build} for the dev target.
 *
 * <p>Bazel output goes to a log file so it does not pollute the Quarkus dev console; its tail is
 * logged when a build fails or times out.
 */
final class BazelBuild {

  private static final Logger LOGGER = Logger.getLogger(BazelBuild.class);
  private static final String BAZEL_LOG_FILE = "bazel-hot-reload.log";

  private final QuarkifierConfig config;
  private final Path bazelLogPath;
  private final AtomicBoolean staleOutputsWarned = new AtomicBoolean(false);

  /** Class outputs the rebuild must refresh, for the stale-output freshness check. */
  private final List<Path> outputs;

  /**
   * Creates the build runner and opens its log file.
   *
   * @param config the quarkifier configuration
   * @param outputs class outputs the rebuild is expected to refresh
   * @throws IOException if the log file cannot be created
   */
  BazelBuild(QuarkifierConfig config, List<Path> outputs) throws IOException {
    this.config = config;
    this.outputs = List.copyOf(outputs);
    Path outputDir = config.outputDir();
    this.bazelLogPath =
        outputDir != null
            ? outputDir.resolve(BAZEL_LOG_FILE)
            : Path.of(System.getProperty("java.io.tmpdir", "/tmp"), BAZEL_LOG_FILE);
    Files.createDirectories(bazelLogPath.getParent());
    Files.writeString(
        bazelLogPath,
        "[hot-reload] Bazel build log\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /**
   * Rebuilds the configured targets.
   *
   * @return {@code true} if the build succeeded
   */
  boolean run() {
    long start = System.currentTimeMillis();
    boolean success = runBazel(config.bazelTargets());
    if (success) {
      warnIfOutputsWentStale(start);
    }
    return success;
  }

  /**
   * Invokes {@code bazel build} via {@link ProcessBuilder} with the given targets. Bazel output is
   * redirected to a log file to avoid polluting the Quarkus dev console. The build process has a
   * configurable timeout to prevent hanging.
   *
   * @param targets Bazel target labels to build
   * @return {@code true} if the build succeeded (exit code 0), {@code false} otherwise
   */
  private boolean runBazel(List<String> targets) {
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
    for (Path path : outputs) {
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
          outputs);
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
}
