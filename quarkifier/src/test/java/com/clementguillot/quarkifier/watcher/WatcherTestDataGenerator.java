package com.clementguillot.quarkifier.watcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Random data generators for WatcherConfig property-style parameterized tests. */
final class WatcherTestDataGenerator {

  private static final Random RNG = new Random(42);
  private static final int TRIES = 200;

  private WatcherTestDataGenerator() {}

  static Stream<WatcherConfig> randomValidConfigs() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomConfig());
  }

  private static WatcherConfig randomConfig() {
    return new WatcherConfig(
        randomPathList(1, 4),
        randomBazelTargetList(1, 4),
        randomSafePath(),
        randomPathList(1, 4),
        50 + RNG.nextInt(500),
        RNG.nextDouble() < 0.3 ? null : randomSafePath());
  }

  private static List<Path> randomPathList(int min, int max) {
    int size = min + RNG.nextInt(max - min + 1);
    return IntStream.range(0, size).mapToObj(i -> randomSafePath()).toList();
  }

  private static Path randomSafePath() {
    int segments = 1 + RNG.nextInt(3);
    var parts =
        IntStream.range(0, segments).mapToObj(i -> randomAlpha(2, 10)).toArray(String[]::new);
    return Path.of(String.join("/", parts));
  }

  private static List<String> randomBazelTargetList(int min, int max) {
    int size = min + RNG.nextInt(max - min + 1);
    return IntStream.range(0, size)
        .mapToObj(i -> "//" + randomAlpha(2, 8) + ":" + randomAlpha(2, 8))
        .toList();
  }

  private static String randomAlpha(int minLen, int maxLen) {
    int len = minLen + RNG.nextInt(maxLen - minLen + 1);
    var sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) sb.append((char) ('a' + RNG.nextInt(26)));
    return sb.toString();
  }
}
