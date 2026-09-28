package com.clementguillot.quarkifier;

import java.util.Locale;

/** Augmentation mode for production, one-shot tests, dev mode, or console continuous testing. */
public enum AugmentationMode {
  NORMAL,
  TEST,
  DEV,
  CONTINUOUS_TEST,
  NATIVE;

  /**
   * Parses a mode string (case-insensitive).
   *
   * @throws IllegalArgumentException if the string is not a supported augmentation mode
   */
  public static AugmentationMode parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException(
          "Invalid mode: null. Must be 'normal', 'test', 'dev', 'continuous-test', or 'native'.");
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "normal" -> NORMAL;
      case "test" -> TEST;
      case "dev" -> DEV;
      case "continuous-test" -> CONTINUOUS_TEST;
      case "native" -> NATIVE;
      default -> throw new IllegalArgumentException(
          "Invalid mode: '%s'. Must be 'normal', 'test', 'dev', 'continuous-test', or 'native'."
              .formatted(value));
    };
  }
}
