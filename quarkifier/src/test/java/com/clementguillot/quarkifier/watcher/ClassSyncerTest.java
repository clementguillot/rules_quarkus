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
}
