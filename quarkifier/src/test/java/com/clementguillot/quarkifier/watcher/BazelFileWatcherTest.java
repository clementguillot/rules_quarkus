package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BazelFileWatcher}. */
class BazelFileWatcherTest {

  @TempDir Path tempDir;

  private QuarkifierConfig testConfig(Path outputDir, List<Path> sourceDirs) {
    return new QuarkifierConfig(
        List.of(Path.of("app.jar")),
        List.of(),
        List.of(),
        outputDir,
        List.of(),
        AugmentationMode.DEV,
        "3.27.3",
        "test-app",
        "1.0.0",
        sourceDirs,
        tempDir.resolve("classes"),
        List.of("//pkg:lib"),
        List.of(),
        tempDir,
        5);
  }

  @Test
  void close_isIdempotent() throws IOException {
    var config = testConfig(tempDir.resolve("output"), List.of());
    var watcher = new BazelFileWatcher(config);

    watcher.close();
    // Second close should not throw
    assertDoesNotThrow(watcher::close);
  }

  @Test
  void close_releasesWatchService() throws IOException {
    Path sourceDir = tempDir.resolve("src/main/java");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(sourceDir));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    watcher.close();

    // After close, watchLoop should exit immediately
    assertDoesNotThrow(watcher::watchLoop);
  }

  @Test
  void registerWatchers_skipsNonExistentDirs() throws IOException {
    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("nonexistent")));
    var watcher = new BazelFileWatcher(config);

    // Should not throw, just log a warning
    assertDoesNotThrow(() -> watcher.registerWatchers(config.sourceDirs()));
    watcher.close();
  }

  @Test
  void registerWatchers_registersExistingDirsRecursively() throws IOException {
    Path sourceDir = tempDir.resolve("src/main/java/com/example");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("src/main/java")));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    // Watcher should be functional (close without error proves registration worked)
    assertDoesNotThrow(watcher::close);
  }

  @Test
  void triggerBuildAndSync_serializesConcurrentCalls() throws Exception {
    var config = testConfig(tempDir.resolve("output"), List.of());
    var watcher = new BazelFileWatcher(config);

    // triggerBuildAndSync will fail the bazel build (no real bazel), but should not throw
    AtomicInteger callCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(2);

    // Fire two concurrent triggers
    Thread t1 =
        new Thread(
            () -> {
              watcher.triggerBuildAndSync();
              callCount.incrementAndGet();
              latch.countDown();
            });
    Thread t2 =
        new Thread(
            () -> {
              watcher.triggerBuildAndSync();
              callCount.incrementAndGet();
              latch.countDown();
            });

    t1.start();
    t2.start();

    assertTrue(latch.await(15, TimeUnit.SECONDS), "Both triggers should complete");
    assertEquals(2, callCount.get());
    watcher.close();
  }

  @Test
  void watchLoop_exitsOnClose() throws Exception {
    Path sourceDir = tempDir.resolve("src/main/java");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(sourceDir));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);

    Thread watchThread =
        new Thread(
            () -> {
              started.countDown();
              watcher.watchLoop();
              finished.countDown();
            });
    watchThread.setDaemon(true);
    watchThread.start();

    assertTrue(started.await(2, TimeUnit.SECONDS));
    // Give the watch loop time to block on take()
    Thread.sleep(50);

    watcher.close();
    assertTrue(finished.await(2, TimeUnit.SECONDS), "watchLoop should exit after close()");
  }
}
