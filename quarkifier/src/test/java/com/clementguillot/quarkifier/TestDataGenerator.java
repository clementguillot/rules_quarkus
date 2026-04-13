package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Random data generators for property-style parameterized tests. */
final class TestDataGenerator {

  private static final Random RNG = new Random(42);
  private static final int TRIES = 200;

  private TestDataGenerator() {}

  static Stream<QuarkifierConfig> randomValidConfigs() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomConfig());
  }

  private static QuarkifierConfig randomConfig() {
    return new QuarkifierConfig(
        randomPathList(1, 5),
        randomPathList(1, 5),
        randomSafePath(),
        RNG.nextDouble() < 0.3 ? null : randomSafePath(),
        randomPathList(0, 3),
        RNG.nextBoolean() ? AugmentationMode.NORMAL : AugmentationMode.TEST,
        RNG.nextDouble() < 0.3 ? null : randomVersion(),
        RNG.nextDouble() < 0.3 ? null : randomAlpha(3, 10),
        RNG.nextDouble() < 0.3 ? null : randomVersion(),
        randomPathList(0, 3));
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

  private static String randomVersion() {
    return RNG.nextInt(10) + "." + RNG.nextInt(30) + "." + RNG.nextInt(20);
  }

  private static String randomAlpha(int minLen, int maxLen) {
    int len = minLen + RNG.nextInt(maxLen - minLen + 1);
    var sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) sb.append((char) ('a' + RNG.nextInt(26)));
    return sb.toString();
  }
}
