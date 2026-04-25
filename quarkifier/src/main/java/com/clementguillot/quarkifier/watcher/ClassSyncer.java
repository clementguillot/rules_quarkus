package com.clementguillot.quarkifier.watcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Utility for copying {@code .class} files between bazel-bin output paths and a mutable classes
 * directory that {@code RuntimeUpdatesProcessor} monitors.
 *
 * <p>Supports both directories (loose .class files) and jar files as input sources. Bazel's {@code
 * java_library} rule produces class jars (e.g., {@code liblib-class.jar}), so jar extraction is the
 * primary mode.
 */
public final class ClassSyncer {

  private ClassSyncer() {}

  /**
   * Initial population: extract/copy all {@code .class} files from bazel-bin output paths to {@code
   * classesDir} preserving package directory structure.
   *
   * <p>Each output path can be either a directory (walked recursively) or a jar file (entries
   * extracted).
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @param classesDir mutable target directory
   * @throws IOException if a file operation fails
   */
  public static void populateClassesDir(List<Path> classesOutputPaths, Path classesDir)
      throws IOException {
    for (Path outputPath : classesOutputPaths) {
      if (!Files.exists(outputPath)) {
        continue;
      }
      if (Files.isDirectory(outputPath)) {
        copyClassesFromDirectory(outputPath, classesDir, null);
      } else if (outputPath.toString().endsWith(".jar")) {
        extractClassesFromJar(outputPath, classesDir, null);
      }
    }
  }

  /**
   * Incremental sync: extract/copy {@code .class} files from bazel-bin output paths, track synced
   * relative paths, then walk {@code classesDir} and delete stale {@code .class} files not in the
   * synced set.
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @param classesDir mutable target directory
   * @throws IOException if a file operation fails
   */
  public static void syncClasses(List<Path> classesOutputPaths, Path classesDir)
      throws IOException {
    Set<Path> synced = new HashSet<>();

    for (Path outputPath : classesOutputPaths) {
      if (!Files.exists(outputPath)) {
        continue;
      }
      if (Files.isDirectory(outputPath)) {
        copyClassesFromDirectory(outputPath, classesDir, synced);
      } else if (outputPath.toString().endsWith(".jar")) {
        extractClassesFromJar(outputPath, classesDir, synced);
      }
    }

    // Remove stale .class files not present in latest build output
    if (Files.isDirectory(classesDir)) {
      Files.walkFileTree(
          classesDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (file.toString().endsWith(".class")) {
                Path relative = classesDir.relativize(file);
                if (!synced.contains(relative)) {
                  Files.delete(file);
                }
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  // ---- internal helpers ----

  private static void copyClassesFromDirectory(Path outputDir, Path classesDir, Set<Path> synced)
      throws IOException {
    Files.walkFileTree(
        outputDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.toString().endsWith(".class")) {
              Path relative = outputDir.relativize(file);
              Path target = classesDir.resolve(relative);
              Files.createDirectories(target.getParent());
              Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
              if (synced != null) {
                synced.add(relative);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void extractClassesFromJar(Path jarPath, Path classesDir, Set<Path> synced)
      throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
          continue;
        }
        Path relative = Path.of(entry.getName());
        Path target = classesDir.resolve(relative);
        Files.createDirectories(target.getParent());
        try (InputStream is = jar.getInputStream(entry)) {
          Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (synced != null) {
          synced.add(relative);
        }
      }
    }
  }
}
