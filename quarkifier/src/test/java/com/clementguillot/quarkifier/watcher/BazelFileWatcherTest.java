package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.TestQuarkifierConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BazelFileWatcher}. */
class BazelFileWatcherTest {

  @TempDir Path tempDir;

  private QuarkifierConfig testConfig(Path outputDir, List<Path> sourceDirs, String... extra) {
    var args =
        new java.util.ArrayList<>(
            List.of(
                "--application-classpath", "app.jar",
                "--application-model", "model.json",
                "--output-dir", outputDir.toString(),
                "--mode", "dev",
                "--app-name", "test-app",
                "--classes-dir", tempDir.resolve("classes").toString(),
                "--bazel-targets", "//pkg:lib",
                "--workspace-dir", tempDir.toString(),
                "--bazel-build-timeout-seconds", "5"));
    if (!sourceDirs.isEmpty()) {
      args.add("--source-dirs");
      args.add(
          sourceDirs.stream()
              .map(Path::toString)
              .collect(java.util.stream.Collectors.joining(",")));
    }
    args.addAll(List.of(extra));
    return TestQuarkifierConfig.parse(args.toArray(String[]::new));
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
  void continuousSyncCopiesOnlyBazelOutputsAndNotifiesAfterSuccess() throws Exception {
    Path main = Files.createDirectories(tempDir.resolve("compiled-main"));
    Path tests = Files.createDirectories(tempDir.resolve("compiled-tests"));
    Files.writeString(main.resolve("App.class"), "main");
    Files.writeString(main.resolve("application.properties"), "key=value");
    Files.writeString(tests.resolve("AppTest.class"), "test");
    Files.writeString(tests.resolve("fixture.txt"), "packaged");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--classes-output-dirs",
            main.toString(),
            "--test-classes-output-dirs",
            tests.toString());
    Files.createDirectories(config.reloadNotificationDir());
    try (var watcher = new BazelFileWatcher(config)) {
      assertTrue(watcher.syncClasses(true));
      assertEquals(
          "key=value", Files.readString(config.classesDir().resolve("application.properties")));
      assertEquals("packaged", Files.readString(config.testClassesDir().resolve("fixture.txt")));
      assertFalse(Files.exists(config.testClassesDir().resolve("undeclared.txt")));
      assertTrue(Files.exists(config.reloadNotificationDir().resolve("completed-build")));
      Files.delete(tests.resolve("fixture.txt"));
      assertTrue(watcher.syncClasses(true));
      assertFalse(Files.exists(config.testClassesDir().resolve("fixture.txt")));
    }
  }

  @Test
  void failedBuildDoesNotPublishOrNotifyAndNextSuccessRecovers() throws Exception {
    Path command = tempDir.resolve("fake-bazel");
    Files.writeString(command, "#!/bin/sh\nexit 1\n");
    assertTrue(command.toFile().setExecutable(true));
    Path tests = Files.createDirectories(tempDir.resolve("compiled-tests"));
    Files.writeString(tests.resolve("AppTest.class"), "last-good");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--test-classes-output-dirs",
            tests.toString(),
            "--bazel-command",
            command.toString());
    try (var watcher = BazelFileWatcher.startInBackground(config)) {
      Files.writeString(tests.resolve("AppTest.class"), "new");
      watcher.triggerBuildAndSync();
      assertEquals("last-good", Files.readString(config.testClassesDir().resolve("AppTest.class")));
      assertFalse(Files.exists(config.reloadNotificationDir().resolve("completed-build")));
      Files.writeString(command, "#!/bin/sh\nexit 0\n");
      watcher.triggerBuildAndSync();
      assertEquals("new", Files.readString(config.testClassesDir().resolve("AppTest.class")));
      assertTrue(Files.exists(config.reloadNotificationDir().resolve("completed-build")));
    }
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
  void exactInputsDoNotMatchUndeclaredSiblings() {
    Path javaInput = tempDir.resolve("src/main/java/App.java");
    Path resourceInput = tempDir.resolve("src/test/resources/fixture.tmp");
    Path codegenInput = tempDir.resolve("src/main/proto/schema.proto");
    Path buildFile = tempDir.resolve("BUILD.bazel");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--watched-input",
            javaInput.toString(),
            "--watched-input",
            resourceInput.toString(),
            "--codegen-input-file",
            codegenInput.toString(),
            "--watched-build-file",
            buildFile.toString());
    var paths = new WatchedPaths(config);

    assertTrue(paths.isExactInput(javaInput));
    assertTrue(paths.isExactInput(resourceInput), "declared .tmp files must remain valid inputs");
    assertTrue(paths.isExactInput(codegenInput));
    assertFalse(paths.isExactInput(javaInput.resolveSibling("Undeclared.java")));
    assertFalse(paths.isExactInput(resourceInput.resolveSibling("undeclared.txt")));
    assertTrue(paths.isBuildFile(buildFile));
    assertFalse(paths.isBuildFile(tempDir.resolve("other/BUILD.bazel")));
    assertTrue(paths.isExactWatchAncestor(javaInput.getParent()));
    assertFalse(paths.isExactWatchAncestor(tempDir.resolve("unrelated")));
    assertFalse(paths.isNonJavaInput(javaInput));
    assertTrue(paths.isNonJavaInput(resourceInput));
    assertTrue(paths.isNonJavaInput(codegenInput));
  }

  @Test
  void buildFileChangePrintsRestartWarningWithoutRebuilding() throws Exception {
    Path buildFile = tempDir.resolve("helper/BUILD.bazel");
    Files.createDirectories(buildFile.getParent());
    Files.writeString(buildFile, "# initial\n");
    Path outputDir = tempDir.resolve("output");
    var config =
        testConfig(
            outputDir,
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--watched-build-file",
            buildFile.toString());
    PrintStream originalError = System.err;
    var warningOutput = new ByteArrayOutputStream();
    try (var capturedError = new PrintStream(warningOutput, true, StandardCharsets.UTF_8)) {
      System.setErr(capturedError);
      try (var watcher = BazelFileWatcher.startInBackground(config)) {
        Files.writeString(
            buildFile, "# changed\n", StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!warningOutput.toString(StandardCharsets.UTF_8).contains("Restart dev mode")
            && System.nanoTime() < deadline) {
          Thread.sleep(25);
        }
        assertTrue(
            warningOutput.toString(StandardCharsets.UTF_8).contains("Restart dev mode"),
            warningOutput.toString(StandardCharsets.UTF_8));
        assertEquals(
            "[hot-reload] Bazel build log\n",
            Files.readString(outputDir.resolve("bazel-hot-reload.log")));
      }
    } finally {
      System.setErr(originalError);
    }
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
