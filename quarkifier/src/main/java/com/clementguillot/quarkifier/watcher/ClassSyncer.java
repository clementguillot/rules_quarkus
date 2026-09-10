package com.clementguillot.quarkifier.watcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
@SuppressWarnings("PMD.TooManyMethods") // cohesive class/resource synchronization lifecycle
public final class ClassSyncer {

  private static final int COMPARE_BUFFER_SIZE = 8192;

  private ClassSyncer() {}

  /**
   * Returns the class output paths that belong to the reloadable application, dropping
   * locally-built Quarkus extension jars.
   *
   * <p>A jar carrying {@code META-INF/quarkus-extension.properties} is an extension: a dependency,
   * not part of the reloadable application. Syncing its classes into the mutable classes directory
   * would expose them to both the application and augment classloaders, breaking build-time
   * config-mapping lookup ({@code SRCFG00027}). Best-effort: paths that cannot be inspected are
   * kept.
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @return the input paths with extension jars removed
   */
  public static List<Path> excludeExtensionJars(List<Path> classesOutputPaths) {
    List<Path> reloadable = new ArrayList<>(classesOutputPaths.size());
    for (Path path : classesOutputPaths) {
      boolean isExtension = false;
      if (path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
        try (JarFile jar = new JarFile(path.toFile())) {
          isExtension = jar.getEntry("META-INF/quarkus-extension.properties") != null;
        } catch (IOException e) {
          isExtension = false;
        }
      }
      if (!isExtension) {
        reloadable.add(path);
      }
    }
    return reloadable;
  }

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
    populateOutputs(classesOutputPaths, classesDir, false);
  }

  /**
   * Populates a mutable output tree with compiled classes <em>and</em> their packaged resources.
   *
   * <p>Used for both the application and the test tree under continuous testing: Quarkus is
   * configured with no workspace resource paths there, so Bazel is the only writer of resources.
   */
  public static void populateClassesAndResources(List<Path> outputPaths, Path outputDir)
      throws IOException {
    populateOutputs(outputPaths, outputDir, true);
  }

  private static void populateOutputs(
      List<Path> outputPaths, Path classesDir, boolean includeResources) throws IOException {
    copyOutputs(outputPaths, classesDir, includeResources);
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
    syncOutputs(classesOutputPaths, classesDir, false);
  }

  /**
   * Synchronizes compiled classes and their packaged resources, deleting anything the latest build
   * output no longer contains. The counterpart of {@link #populateClassesAndResources}.
   */
  public static void syncClassesAndResources(List<Path> outputPaths, Path outputDir)
      throws IOException {
    syncOutputs(outputPaths, outputDir, true);
  }

  /**
   * Advances every test class timestamp so Quarkus schedules a test run after a resource or
   * code-generation input changed without changing bytecode.
   *
   * <p>Quarkus continuous testing notices arbitrary resources but only runs tests automatically for
   * extension-declared restart resources or changed classes. Bazel owns compilation and resource
   * packaging here, so advancing the synchronized test classes feeds that rebuild back through
   * Quarkus's normal changed-class test selection.
   *
   * @return the number of test class files marked as changed
   */
  public static int markTestClassesChanged(Path testClassesDir) throws IOException {
    if (!Files.isDirectory(testClassesDir)) {
      return 0;
    }
    int changed = 0;
    long now = System.currentTimeMillis();
    // The tree also holds every packaged test resource; filter in the stream so the
    // walk stays lazy instead of materializing them all just to skip them.
    try (var paths = Files.walk(testClassesDir)) {
      for (Path path :
          paths
              .filter(path -> path.toString().endsWith(".class") && Files.isRegularFile(path))
              .toList()) {
        long current = Files.getLastModifiedTime(path).toMillis();
        Files.setLastModifiedTime(path, FileTime.fromMillis(Math.max(now, current + 1)));
        changed++;
      }
    }
    return changed;
  }

  private static void syncOutputs(List<Path> outputPaths, Path classesDir, boolean includeResources)
      throws IOException {
    Set<Path> synced = copyOutputs(outputPaths, classesDir, includeResources);

    // Remove stale synchronized files not present in the latest build output.
    if (Files.isDirectory(classesDir)) {
      Files.walkFileTree(
          classesDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Path relative = classesDir.relativize(file);
              if (shouldSync(relative, includeResources) && !synced.contains(relative)) {
                Files.delete(file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  // ---- internal helpers ----

  /**
   * Copies one flattened output tree while retaining Java classpath ordering semantics.
   *
   * <p>The first output containing an ordinary class or resource wins, just as it would on the
   * original classpath. Service-provider configuration is the exception: {@link
   * java.util.ServiceLoader} reads every {@code META-INF/services/*} resource, so those files are
   * merged in classpath order with duplicate providers removed. Keeping collision state across the
   * complete pass also prevents a lower-priority duplicate from rewriting the first entry on every
   * synchronization.
   */
  private static Set<Path> copyOutputs(
      List<Path> outputPaths, Path classesDir, boolean includeResources) throws IOException {
    Set<Path> synced = new HashSet<>();
    var serviceProviders = new ServiceProviders();

    for (Path outputPath : outputPaths) {
      if (!Files.exists(outputPath)) {
        continue;
      }
      if (Files.isDirectory(outputPath)) {
        copyOutputsFromDirectory(
            outputPath, classesDir, synced, serviceProviders, includeResources);
      } else if (outputPath.toString().endsWith(".jar")) {
        extractOutputsFromJar(outputPath, classesDir, synced, serviceProviders, includeResources);
      }
    }
    serviceProviders.writeTo(classesDir);
    return synced;
  }

  private static void copyOutputsFromDirectory(
      Path outputDir,
      Path classesDir,
      Set<Path> synced,
      ServiceProviders serviceProviders,
      boolean includeResources)
      throws IOException {
    Files.walkFileTree(
        outputDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path relative = outputDir.relativize(file).normalize();
            if (shouldSync(relative, includeResources)) {
              Path target = classesDir.resolve(relative);
              if (isServiceProviderConfiguration(relative)) {
                synced.add(relative);
                try (InputStream input = Files.newInputStream(file)) {
                  serviceProviders.collect(relative, input);
                }
                return FileVisitResult.CONTINUE;
              }
              if (!synced.add(relative)) {
                return FileVisitResult.CONTINUE;
              }
              Files.createDirectories(target.getParent());
              if (!Files.exists(target) || Files.mismatch(file, target) != -1) {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void extractOutputsFromJar(
      Path jarPath,
      Path classesDir,
      Set<Path> synced,
      ServiceProviders serviceProviders,
      boolean includeResources)
      throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        Path relative = Path.of(entry.getName());
        if (entry.isDirectory() || !shouldSync(relative, includeResources)) {
          continue;
        }
        Path target = classesDir.resolve(relative).normalize();
        if (!target.startsWith(classesDir)) {
          throw new IOException("Zip entry escapes target directory: " + entry.getName());
        }
        // Record the path in the same normal form the stale sweep derives from the written file;
        // an entry name with a redundant "." segment would otherwise never match.
        Path normalizedRelative = classesDir.relativize(target);
        if (isServiceProviderConfiguration(normalizedRelative)) {
          synced.add(normalizedRelative);
          try (InputStream input = jar.getInputStream(entry)) {
            serviceProviders.collect(normalizedRelative, input);
          }
          continue;
        }
        if (!synced.add(normalizedRelative)) {
          continue;
        }
        Files.createDirectories(target.getParent());
        if (!jarEntryMatches(jar, entry, target)) {
          try (InputStream is = jar.getInputStream(entry)) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }

  private static boolean isServiceProviderConfiguration(Path relative) {
    String name = relative.toString().replace('\\', '/');
    String prefix = "META-INF/services/";
    return name.startsWith(prefix)
        && name.length() > prefix.length()
        && name.indexOf('/', prefix.length()) < 0;
  }

  private static boolean shouldSync(Path relative, boolean includeResources) {
    String name = relative.toString().replace('\\', '/');
    return name.endsWith(".class")
        || (includeResources && !"META-INF/MANIFEST.MF".equalsIgnoreCase(name));
  }

  private static boolean jarEntryMatches(JarFile jar, JarEntry entry, Path target)
      throws IOException {
    if (!Files.isRegularFile(target)
        || (entry.getSize() >= 0 && entry.getSize() != Files.size(target))) {
      return false;
    }
    try (InputStream expected = jar.getInputStream(entry);
        InputStream actual = Files.newInputStream(target)) {
      byte[] expectedBuffer = new byte[COMPARE_BUFFER_SIZE];
      byte[] actualBuffer = new byte[COMPARE_BUFFER_SIZE];
      while (true) {
        // readNBytes fills the buffer unless the stream is exhausted. A plain
        // read() may return a short count with bytes still pending — the jar
        // inflater does exactly that once its compressed input buffer drains,
        // which would make identical content compare as different and rewrite
        // the target on every sync.
        int expectedRead = expected.readNBytes(expectedBuffer, 0, COMPARE_BUFFER_SIZE);
        int actualRead = actual.readNBytes(actualBuffer, 0, COMPARE_BUFFER_SIZE);
        if (expectedRead != actualRead
            || Arrays.mismatch(expectedBuffer, 0, expectedRead, actualBuffer, 0, actualRead) >= 0) {
          return false;
        }
        if (expectedRead < COMPARE_BUFFER_SIZE) {
          return true; // both streams reached the end on the same byte count
        }
      }
    }
  }

  /** Accumulates the logical union of ServiceLoader configuration files in classpath order. */
  private static final class ServiceProviders {
    private final Map<Path, Set<String>> providersByPath = new LinkedHashMap<>();

    private void collect(Path relative, InputStream input) throws IOException {
      Set<String> providers =
          providersByPath.computeIfAbsent(relative, ignored -> new LinkedHashSet<>());
      try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        while (line != null) {
          int comment = line.indexOf('#');
          String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
          if (!provider.isEmpty()) {
            providers.add(provider);
          }
          line = reader.readLine();
        }
      }
    }

    private void writeTo(Path classesDir) throws IOException {
      for (Map.Entry<Path, Set<String>> service : providersByPath.entrySet()) {
        Path target = classesDir.resolve(service.getKey());
        Files.createDirectories(target.getParent());
        String content =
            service.getValue().isEmpty() ? "" : String.join("\n", service.getValue()) + "\n";
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(target) || !Arrays.equals(expected, Files.readAllBytes(target))) {
          Files.write(target, expected);
        }
      }
    }
  }
}
