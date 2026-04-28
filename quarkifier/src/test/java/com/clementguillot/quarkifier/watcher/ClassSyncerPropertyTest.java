package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property test for ClassSyncer sync idempotency.
 *
 * <p><b>Validates: Requirements 2.2, 3.3</b>
 */
class ClassSyncerPropertyTest {

  private static final Random RNG = new Random(42);
  private static final int TRIES = 50;

  @TempDir Path tempDir;

  /**
   * Property 3: Sync idempotency.
   *
   * <p>Running {@code syncClasses()} twice with the same input produces identical {@code
   * classesDir} state (same files, same content).
   */
  @ParameterizedTest
  @MethodSource("randomClassTrees")
  void syncIdempotency(List<String> classFiles) throws IOException {
    // Set up a fake bazel-bin output directory with the generated class files
    Path outputDir = tempDir.resolve("bazel-bin/pkg/lib");
    for (String classFile : classFiles) {
      Path file = outputDir.resolve(classFile);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "bytecode-" + classFile);
    }

    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir);

    // First sync
    ClassSyncer.syncClasses(List.of(outputDir), classesDir);
    Map<String, String> stateAfterFirst = snapshotDir(classesDir);

    // Second sync (same input, should be idempotent)
    ClassSyncer.syncClasses(List.of(outputDir), classesDir);
    Map<String, String> stateAfterSecond = snapshotDir(classesDir);

    assertEquals(stateAfterFirst, stateAfterSecond, "syncClasses should be idempotent");
  }

  static Stream<List<String>> randomClassTrees() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomClassFileList());
  }

  private static List<String> randomClassFileList() {
    int count = 1 + RNG.nextInt(8);
    List<String> files = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int depth = 1 + RNG.nextInt(3);
      var segments = new ArrayList<String>();
      for (int d = 0; d < depth; d++) {
        segments.add(randomAlpha(2, 8));
      }
      // Capitalize the class name to look like a real Java class
      String className =
          Character.toUpperCase(randomAlpha(3, 10).charAt(0)) + randomAlpha(2, 9) + ".class";
      segments.add(className);
      files.add(String.join("/", segments));
    }
    return files;
  }

  private static String randomAlpha(int minLen, int maxLen) {
    int len = minLen + RNG.nextInt(maxLen - minLen + 1);
    var sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) sb.append((char) ('a' + RNG.nextInt(26)));
    return sb.toString();
  }

  /** Walks a directory and returns a sorted map of relative-path → file-content. */
  private Map<String, String> snapshotDir(Path dir) throws IOException {
    Map<String, String> snapshot = new TreeMap<>();
    if (!Files.isDirectory(dir)) return snapshot;
    try (var stream = Files.walk(dir)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                try {
                  String relative = dir.relativize(file).toString();
                  snapshot.put(relative, Files.readString(file));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    return snapshot;
  }
}
