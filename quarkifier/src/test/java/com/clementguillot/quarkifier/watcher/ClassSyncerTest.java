package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ClassSyncer}. */
class ClassSyncerTest {

  @TempDir Path tempDir;

  @Test
  void populateClassesDir_copiesClassFilesWithCorrectStructure() throws IOException {
    // Set up a fake bazel-bin output directory
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    Path classFile = outputDir.resolve("com/example/Foo.class");
    Files.createDirectories(classFile.getParent());
    Files.writeString(classFile, "fake-bytecode-foo");

    Path nestedClass = outputDir.resolve("com/example/inner/Bar.class");
    Files.createDirectories(nestedClass.getParent());
    Files.writeString(nestedClass, "fake-bytecode-bar");

    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);

    ClassSyncer.populateClassesDir(List.of(outputDir), classesDir);

    assertEquals(
        "fake-bytecode-foo", Files.readString(classesDir.resolve("com/example/Foo.class")));
    assertEquals(
        "fake-bytecode-bar", Files.readString(classesDir.resolve("com/example/inner/Bar.class")));
  }

  @Test
  void populateClassesDir_ignoresNonClassFiles() throws IOException {
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    Path classFile = outputDir.resolve("com/example/Foo.class");
    Files.createDirectories(classFile.getParent());
    Files.writeString(classFile, "bytecode");

    Path javaFile = outputDir.resolve("com/example/Foo.java");
    Files.writeString(javaFile, "source");

    Path txtFile = outputDir.resolve("com/example/readme.txt");
    Files.writeString(txtFile, "text");

    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);

    ClassSyncer.populateClassesDir(List.of(outputDir), classesDir);

    assertTrue(Files.exists(classesDir.resolve("com/example/Foo.class")));
    assertFalse(Files.exists(classesDir.resolve("com/example/Foo.java")));
    assertFalse(Files.exists(classesDir.resolve("com/example/readme.txt")));
  }

  @Test
  void populateClassesDir_skipsNonExistentOutputDir() throws IOException {
    Path nonExistent = tempDir.resolve("does-not-exist");
    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);

    // Should not throw
    ClassSyncer.populateClassesDir(List.of(nonExistent), classesDir);

    // classesDir should remain empty (no files copied)
    try (var stream = Files.walk(classesDir)) {
      long fileCount = stream.filter(Files::isRegularFile).count();
      assertEquals(0, fileCount);
    }
  }

  @Test
  void syncClasses_copiesChangedFilesAndRemovesStale() throws IOException {
    // Initial state: classesDir has Foo.class and Old.class
    Path classesDir = tempDir.resolve("classes");
    Path oldClass = classesDir.resolve("com/example/Old.class");
    Files.createDirectories(oldClass.getParent());
    Files.writeString(oldClass, "old-bytecode");

    Path fooClass = classesDir.resolve("com/example/Foo.class");
    Files.writeString(fooClass, "original-foo");

    // Build output: Foo.class (updated) and New.class (added), no Old.class
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    Path outputFoo = outputDir.resolve("com/example/Foo.class");
    Files.createDirectories(outputFoo.getParent());
    Files.writeString(outputFoo, "updated-foo");

    Path outputNew = outputDir.resolve("com/example/New.class");
    Files.writeString(outputNew, "new-bytecode");

    ClassSyncer.syncClasses(List.of(outputDir), classesDir);

    // Foo.class should be updated
    assertEquals("updated-foo", Files.readString(classesDir.resolve("com/example/Foo.class")));
    // New.class should be added
    assertEquals("new-bytecode", Files.readString(classesDir.resolve("com/example/New.class")));
    // Old.class should be removed (stale)
    assertFalse(Files.exists(classesDir.resolve("com/example/Old.class")));
  }

  @Test
  void syncClasses_handlesEmptyOutputDirectories() throws IOException {
    // classesDir has a stale file
    Path classesDir = tempDir.resolve("classes");
    Path staleClass = classesDir.resolve("com/example/Stale.class");
    Files.createDirectories(staleClass.getParent());
    Files.writeString(staleClass, "stale");

    // Output dir exists but is empty
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    Files.createDirectories(outputDir);

    ClassSyncer.syncClasses(List.of(outputDir), classesDir);

    // Stale file should be removed
    assertFalse(Files.exists(staleClass));
  }

  @Test
  void syncClasses_multipleOutputDirs() throws IOException {
    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);

    // First output dir
    Path outputDir1 = tempDir.resolve("bazel-bin/pkg/lib1");
    Path class1 = outputDir1.resolve("com/example/A.class");
    Files.createDirectories(class1.getParent());
    Files.writeString(class1, "bytecode-a");

    // Second output dir
    Path outputDir2 = tempDir.resolve("bazel-bin/pkg/lib2");
    Path class2 = outputDir2.resolve("com/other/B.class");
    Files.createDirectories(class2.getParent());
    Files.writeString(class2, "bytecode-b");

    ClassSyncer.syncClasses(List.of(outputDir1, outputDir2), classesDir);

    assertEquals("bytecode-a", Files.readString(classesDir.resolve("com/example/A.class")));
    assertEquals("bytecode-b", Files.readString(classesDir.resolve("com/other/B.class")));
  }

  @Test
  void syncClasses_isIdempotent() throws IOException {
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    for (String classFile :
        List.of("com/example/Foo.class", "com/example/deep/nested/Bar.class", "Root.class")) {
      Path file = outputDir.resolve(classFile);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "bytecode-" + classFile);
    }
    Path classesDir = Files.createDirectories(tempDir.resolve("classes"));

    ClassSyncer.syncClasses(List.of(outputDir), classesDir);
    java.util.Map<String, String> first = snapshot(classesDir);
    Path unchangedClass = classesDir.resolve("com/example/Foo.class");
    var sentinelTime = java.nio.file.attribute.FileTime.fromMillis(1_234_000);
    Files.setLastModifiedTime(unchangedClass, sentinelTime);
    ClassSyncer.syncClasses(List.of(outputDir), classesDir);

    assertEquals(3, first.size());
    assertEquals(first, snapshot(classesDir), "second sync must not change the directory");
    assertEquals(
        sentinelTime,
        Files.getLastModifiedTime(unchangedClass),
        "unchanged class files must not be rewritten and trigger another reload");
  }

  @Test
  void syncClasses_unchangedJarEntryKeepsTimestamp() throws IOException {
    Path jar = tempDir.resolve("libtests.jar");
    writeJar(jar, java.util.Map.of("org/acme/GreetingResourceTest.class", "bytecode"));
    Path classesDir = Files.createDirectories(tempDir.resolve("test-classes"));

    ClassSyncer.syncClasses(List.of(jar), classesDir);
    Path testClass = classesDir.resolve("org/acme/GreetingResourceTest.class");
    var sentinelTime = java.nio.file.attribute.FileTime.fromMillis(1_234_000);
    Files.setLastModifiedTime(testClass, sentinelTime);
    ClassSyncer.syncClasses(List.of(jar), classesDir);

    assertEquals(sentinelTime, Files.getLastModifiedTime(testClass));
  }

  @Test
  void syncTestClasses_copiesUpdatesAndRemovesResources() throws IOException {
    Path jar = tempDir.resolve("libtests.jar");
    writeJar(
        jar,
        java.util.Map.of(
            "org/acme/GreetingResourceTest.class", "bytecode",
            "continuous-test.txt", "resource-v1",
            "obsolete.txt", "obsolete",
            "META-INF/MANIFEST.MF", "Manifest-Version: 1.0"));
    Path classesDir = Files.createDirectories(tempDir.resolve("test-classes"));

    ClassSyncer.populateTestClassesDir(List.of(jar), classesDir);

    assertEquals(
        "bytecode", Files.readString(classesDir.resolve("org/acme/GreetingResourceTest.class")));
    assertEquals("resource-v1", Files.readString(classesDir.resolve("continuous-test.txt")));
    assertFalse(Files.exists(classesDir.resolve("META-INF/MANIFEST.MF")));

    writeJar(
        jar,
        java.util.Map.of(
            "org/acme/GreetingResourceTest.class", "bytecode",
            "continuous-test.txt", "resource-v2"));
    ClassSyncer.syncTestClasses(List.of(jar), classesDir);

    assertEquals("resource-v2", Files.readString(classesDir.resolve("continuous-test.txt")));
    assertFalse(Files.exists(classesDir.resolve("obsolete.txt")));
  }

  @Test
  void markTestClassesChanged_advancesOnlyClassTimestamps() throws IOException {
    Path classesDir = Files.createDirectories(tempDir.resolve("test-classes"));
    Path testClass = classesDir.resolve("org/acme/GreetingResourceTest.class");
    Path resource = classesDir.resolve("continuous-test.txt");
    Files.createDirectories(testClass.getParent());
    Files.writeString(testClass, "bytecode");
    Files.writeString(resource, "resource");
    var sentinelTime = java.nio.file.attribute.FileTime.fromMillis(1_234_000);
    Files.setLastModifiedTime(testClass, sentinelTime);
    Files.setLastModifiedTime(resource, sentinelTime);

    assertEquals(1, ClassSyncer.markTestClassesChanged(classesDir));

    assertTrue(Files.getLastModifiedTime(testClass).compareTo(sentinelTime) > 0);
    assertEquals(sentinelTime, Files.getLastModifiedTime(resource));
  }

  @Test
  void excludeExtensionJars_dropsExtensionJarsKeepsApplicationOutputs() throws IOException {
    Path extensionJar = tempDir.resolve("libgreeting-extension.jar");
    writeJar(
        extensionJar,
        java.util.Map.of(
            "META-INF/quarkus-extension.properties",
            "deployment-artifact=com.example:greeting-extension-deployment:1.0.0",
            "com/example/greeting/runtime/GreetingService.class",
            "bytecode"));

    Path appJar = tempDir.resolve("liblib-class.jar");
    writeJar(appJar, java.util.Map.of("com/example/app/GreetingResource.class", "bytecode"));

    Path classesDir = Files.createDirectories(tempDir.resolve("bazel-bin/pkg/lib"));

    List<Path> reloadable =
        ClassSyncer.excludeExtensionJars(List.of(appJar, extensionJar, classesDir));

    assertEquals(
        List.of(appJar, classesDir),
        reloadable,
        "Extension jars must be excluded; application class jars and directories kept");
  }

  @Test
  void excludeExtensionJars_keepsUnreadableOrMissingPaths() throws IOException {
    Path missingJar = tempDir.resolve("missing.jar");
    Path notAJar = tempDir.resolve("notes.txt");
    Files.writeString(notAJar, "not a jar");

    List<Path> reloadable = ClassSyncer.excludeExtensionJars(List.of(missingJar, notAJar));

    assertEquals(List.of(missingJar, notAJar), reloadable);
  }

  private static void writeJar(Path jar, java.util.Map<String, String> entries) throws IOException {
    try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
      for (var entry : entries.entrySet()) {
        out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
  }

  private static java.util.Map<String, String> snapshot(Path dir) throws IOException {
    var state = new java.util.TreeMap<String, String>();
    try (var stream = Files.walk(dir)) {
      for (Path p : stream.filter(Files::isRegularFile).toList()) {
        state.put(dir.relativize(p).toString(), Files.readString(p));
      }
    }
    return state;
  }
}
