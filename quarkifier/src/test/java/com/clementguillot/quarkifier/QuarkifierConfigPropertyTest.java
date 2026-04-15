package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class QuarkifierConfigPropertyTest {

  @ParameterizedTest
  @MethodSource("com.clementguillot.quarkifier.TestDataGenerator#randomValidConfigs")
  void cliArgumentRoundTrip(QuarkifierConfig original)
      throws QuarkifierConfig.InvalidArgumentsException {

    String[] args = original.toArgs();
    QuarkifierConfig parsed = QuarkifierConfig.parse(args);

    assertEquals(original.applicationClasspath(), parsed.applicationClasspath());
    assertEquals(original.deploymentClasspath(), parsed.deploymentClasspath());
    assertEquals(original.outputDir(), parsed.outputDir());
    assertEquals(original.resources(), parsed.resources());
    assertEquals(original.mode(), parsed.mode());
    assertEquals(original.expectedQuarkusVersion(), parsed.expectedQuarkusVersion());
    assertEquals(original.appName(), parsed.appName());
    assertEquals(original.appVersion(), parsed.appVersion());
    assertEquals(original.sourceDirs(), parsed.sourceDirs());
  }
}
