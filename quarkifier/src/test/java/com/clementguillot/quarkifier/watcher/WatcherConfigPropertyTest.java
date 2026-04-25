package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property test for WatcherConfig round-trip consistency.
 *
 * <p><b>Validates: Requirements 1.1, 2.1</b>
 */
class WatcherConfigPropertyTest {

  /**
   * Property 2: WatcherConfig round-trip consistency.
   *
   * <p>For any valid WatcherConfig, serializing to CLI args via {@code toArgs()} and re-parsing
   * produces an equivalent config.
   */
  @ParameterizedTest
  @MethodSource("com.clementguillot.quarkifier.watcher.WatcherTestDataGenerator#randomValidConfigs")
  void cliArgumentRoundTrip(WatcherConfig original) {
    String[] args = original.toArgs();
    WatcherConfig parsed = WatcherConfig.parse(args);

    assertEquals(original.sourceDirs(), parsed.sourceDirs());
    assertEquals(original.bazelTargets(), parsed.bazelTargets());
    assertEquals(original.classesDir(), parsed.classesDir());
    assertEquals(original.classesOutputDirs(), parsed.classesOutputDirs());
    assertEquals(original.debounceMs(), parsed.debounceMs());
    assertEquals(original.workspaceDir(), parsed.workspaceDir());
  }
}
