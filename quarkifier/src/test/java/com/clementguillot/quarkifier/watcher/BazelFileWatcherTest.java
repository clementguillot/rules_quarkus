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
import java.nio.file.StandardWatchEventKinds;
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
    Files.createDirectories(config.continuousTesting().reloadNotificationDir());
    try (var watcher = new BazelFileWatcher(config)) {
      assertTrue(watcher.syncClasses(true, true, false));
      assertEquals(
          "key=value", Files.readString(config.classesDir().resolve("application.properties")));
      assertEquals(
          "packaged",
          Files.readString(config.continuousTesting().classesDir().resolve("fixture.txt")));
      assertFalse(Files.exists(config.continuousTesting().classesDir().resolve("undeclared.txt")));
      assertTrue(
          Files.exists(
              config.continuousTesting().reloadNotificationDir().resolve("completed-build")));
      Files.writeString(config.continuousTesting().classesDir().resolve("Stale.class"), "stale");
      Files.delete(tests.resolve("fixture.txt"));
      assertTrue(watcher.syncClasses(true, true, false));
      assertFalse(Files.exists(config.continuousTesting().classesDir().resolve("fixture.txt")));
      assertFalse(Files.exists(config.continuousTesting().classesDir().resolve("Stale.class")));
    }
  }

  @Test
  void ordinarySyncPreservesDeletedClassAndAdvancesTimestampsAfterStructuralChange()
      throws Exception {
    Path compiled = Files.createDirectories(tempDir.resolve("compiled-main"));
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(tempDir.resolve("src/main/java")),
            "--classes-output-dirs",
            compiled.toString());
    assertNull(config.continuousTesting(), "only continuous testing needs a notification");
    try (var watcher = new BazelFileWatcher(config)) {
      Path compiledClass = compiled.resolve("fixture/Added.class");
      Files.createDirectories(compiledClass.getParent());
      Files.writeString(compiledClass, "added");
      Path survivingClass = compiled.resolve("fixture/Surviving.class");
      Files.writeString(survivingClass, "surviving");

      assertTrue(watcher.syncClasses(false, false, false));
      Path synchronizedSurviving = config.classesDir().resolve("fixture/Surviving.class");
      long survivingTimestamp = Files.getLastModifiedTime(synchronizedSurviving).toMillis();
      assertEquals("added", Files.readString(config.classesDir().resolve("fixture/Added.class")));

      Files.delete(compiledClass);
      assertTrue(watcher.syncClasses(true, false, true));
      assertTrue(
          Files.exists(config.classesDir().resolve("fixture/Added.class")),
          "Quarkus needs the stale class to associate it with the deleted source and remove it");
      assertTrue(Files.getLastModifiedTime(synchronizedSurviving).toMillis() > survivingTimestamp);
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
      assertEquals(
          "last-good",
          Files.readString(config.continuousTesting().classesDir().resolve("AppTest.class")));
      assertFalse(
          Files.exists(
              config.continuousTesting().reloadNotificationDir().resolve("completed-build")));
      Files.writeString(command, "#!/bin/sh\nexit 0\n");
      watcher.triggerBuildAndSync();
      assertEquals(
          "new",
          Files.readString(config.continuousTesting().classesDir().resolve("AppTest.class")));
      assertTrue(
          Files.exists(
              config.continuousTesting().reloadNotificationDir().resolve("completed-build")));
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
  void exactInputsAndGlobCandidatesHaveSeparateScopes() {
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
            codegenInput.toString(),
            "--watched-test-input",
            resourceInput.toString(),
            "--watched-build-file",
            buildFile.toString());
    var paths = new WatchedPaths(config);

    assertEquals(WatchedPaths.Scope.APPLICATION, paths.inputScope(javaInput));
    assertEquals(WatchedPaths.Scope.APPLICATION, paths.inputScope(codegenInput));
    assertEquals(
        WatchedPaths.Scope.TESTS,
        paths.inputScope(resourceInput),
        "declared .tmp files remain valid, test-only inputs");
    assertEquals(
        List.of(
            tempDir.resolve("src/main/java"),
            tempDir.resolve("src/main/proto"),
            tempDir.resolve("src/test/resources"),
            tempDir.resolve("src/test/java")),
        paths.candidateRoots());
    assertEquals(
        WatchedPaths.Scope.APPLICATION,
        paths.inputScope(tempDir.resolve("src/main/java/newpkg/Added.java")));
    assertEquals(
        WatchedPaths.Scope.APPLICATION,
        paths.inputScope(tempDir.resolve("src/main/proto/v2/added.proto")));
    assertEquals(
        WatchedPaths.Scope.NONE, paths.inputScope(tempDir.resolve("src/main/proto/notes.txt")));
    assertEquals(
        WatchedPaths.Scope.NONE, paths.inputScope(tempDir.resolve("src/other/java/Added.java")));
    assertEquals(
        WatchedPaths.Scope.TESTS,
        paths.inputScope(resourceInput.resolveSibling("added.tmp")),
        "an extension declared by an input is eligible even if editors also use it");
    assertEquals(
        WatchedPaths.Scope.NONE,
        paths.inputScope(tempDir.resolve("src/main/java/.#App.java")),
        "editor lock files are never candidates");
    assertEquals(
        WatchedPaths.Scope.NONE, paths.inputScope(resourceInput.resolveSibling("notes.txt")));
    assertTrue(paths.isBuildFile(buildFile));
    assertFalse(paths.isBuildFile(tempDir.resolve("other/BUILD.bazel")));
    assertTrue(paths.isExactWatchAncestor(javaInput.getParent()));
    assertFalse(paths.isExactWatchAncestor(tempDir.resolve("unrelated")));
    assertTrue(paths.isSourceDirectoryWatchPath(tempDir.resolve("src/main/java/pkg")));
    assertFalse(paths.isSourceDirectoryWatchPath(tempDir.resolve("src/main/proto/v1")));
    assertFalse(WatchedPaths.isNonJavaInput(javaInput));
    assertTrue(WatchedPaths.isNonJavaInput(resourceInput));
  }

  @Test
  void firstFileOfAnInitiallyEmptyTestGlobIsACandidate() {
    Path appInput = tempDir.resolve("app/src/main/java/App.java");
    Path moduleBuild = tempDir.resolve("module/BUILD.bazel");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--watched-input",
            appInput.toString(),
            "--watched-build-file",
            moduleBuild.toString());
    var paths = new WatchedPaths(config);

    Path firstTest = tempDir.resolve("module/src/test/java/pkg/FirstTest.java");
    assertEquals(WatchedPaths.Scope.TESTS, paths.inputScope(firstTest));
    assertEquals(
        WatchedPaths.Scope.APPLICATION,
        paths.inputScope(tempDir.resolve("module/src/main/java/pkg/First.java")));
    assertEquals(WatchedPaths.Scope.TESTS, paths.treeScope(tempDir.resolve("module/src/test")));
    assertEquals(WatchedPaths.Scope.APPLICATION, paths.treeScope(tempDir.resolve("module/src")));
    assertEquals(WatchedPaths.Scope.NONE, paths.treeScope(tempDir.resolve("module/docs")));
  }

  @Test
  void firstHandwrittenSourceOfACodegenOnlyLibraryIsAnApplicationCandidate() {
    Path codegenInput = tempDir.resolve("pkg/src/main/proto/schema.proto");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString(),
            "--watched-input",
            codegenInput.toString(),
            "--watched-build-file",
            tempDir.resolve("pkg/BUILD.bazel").toString());
    var paths = new WatchedPaths(config);

    // The library already builds from generated sources, so its empty handwritten glob is live.
    assertEquals(
        WatchedPaths.Scope.APPLICATION,
        paths.inputScope(tempDir.resolve("pkg/src/main/java/pkg/First.java")));
    assertEquals(WatchedPaths.Scope.APPLICATION, paths.treeScope(tempDir.resolve("pkg/src/main")));
  }

  @Test
  void ordinaryModeDiscoversGlobCandidatesButLeavesResourcesToQuarkus() throws IOException {
    Path codegenInput = tempDir.resolve("pkg/src/main/proto/schema.proto");
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--resources",
            tempDir.resolve("pkg/src/main/resources").toString(),
            "--watched-input",
            codegenInput.toString());
    var paths = new WatchedPaths(config);

    assertEquals(List.of(tempDir.resolve("pkg/src/main/proto")), paths.candidateRoots());
    assertEquals(
        WatchedPaths.Scope.APPLICATION,
        paths.inputScope(tempDir.resolve("pkg/src/main/proto/added.proto")));
    Path resource = tempDir.resolve("pkg/src/main/resources/index.html");
    assertEquals(WatchedPaths.Scope.NONE, paths.inputScope(resource));
    assertEquals(WatchedPaths.Scope.NONE, paths.treeScope(resource.getParent()));
    try (var watcher = new BazelFileWatcher(config)) {
      assertFalse(
          watcher.forcesReload(StandardWatchEventKinds.ENTRY_MODIFY, codegenInput),
          "ordinary mode lets changed bytecode, not a forced restart, reveal regenerated classes");
      assertTrue(watcher.forcesReload(StandardWatchEventKinds.ENTRY_CREATE, codegenInput));
    }
  }

  @Test
  void continuousTestingForcesReloadForNonJavaEdits() throws IOException {
    var config =
        testConfig(
            tempDir.resolve("output"),
            List.of(),
            "--test-application-model",
            tempDir.resolve("test-model.json").toString(),
            "--test-classes-dir",
            tempDir.resolve("mutable/test-classes").toString());
    try (var watcher = new BazelFileWatcher(config)) {
      assertTrue(watcher.forcesReload(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("input.txt")));
      assertFalse(watcher.forcesReload(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("App.java")));
    }
  }

  @Test
  void testOnlyReloadLeavesApplicationClassesUntouched() throws Exception {
    Path main = Files.createDirectories(tempDir.resolve("compiled-main"));
    Path tests = Files.createDirectories(tempDir.resolve("compiled-tests"));
    Files.writeString(main.resolve("App.class"), "main");
    Files.writeString(tests.resolve("AppTest.class"), "test");
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
    try (var watcher = new BazelFileWatcher(config)) {
      assertTrue(watcher.syncClasses(false, false, false));
      Path app = config.classesDir().resolve("App.class");
      Path test = config.continuousTesting().classesDir().resolve("AppTest.class");
      long appTimestamp = Files.getLastModifiedTime(app).toMillis();
      long testTimestamp = Files.getLastModifiedTime(test).toMillis();

      assertTrue(watcher.syncClasses(false, true, false));
      assertEquals(appTimestamp, Files.getLastModifiedTime(app).toMillis());
      assertTrue(Files.getLastModifiedTime(test).toMillis() > testTimestamp);
    }
  }

  @Test
  void registrationReplacesTheStaleKeyOfARecreatedDirectory() throws IOException {
    Path sourceDir = Files.createDirectories(tempDir.resolve("src/main/java/pkg"));
    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("src/main/java")));
    try (var watcher = new BazelFileWatcher(config)) {
      watcher.registerWatchers(config.sourceDirs());
      // Simulates a directory deleted and recreated before its invalidated key was drained.
      watcher.registeredKey(sourceDir).cancel();
      watcher.registerWatchers(config.sourceDirs());
      assertTrue(watcher.registeredKey(sourceDir).isValid());
    }
  }

  @Test
  void recursiveRegistrationSkipsVersionControlDirectories() throws IOException {
    Files.createDirectories(tempDir.resolve("root/.git/objects"));
    Files.createDirectories(tempDir.resolve("root/proto"));
    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("root")));
    try (var watcher = new BazelFileWatcher(config)) {
      watcher.registerWatchers(config.sourceDirs());
      assertNotNull(watcher.registeredKey(tempDir.resolve("root/proto")));
      assertNull(watcher.registeredKey(tempDir.resolve("root/.git")));
      assertNull(watcher.registeredKey(tempDir.resolve("root/.git/objects")));
    }
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.git")));
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.hg")));
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.svn")));
    assertFalse(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.schemas")));
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
