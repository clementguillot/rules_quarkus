package com.clementguillot.quarkifier;

import java.util.Locale;

/**
 * Augmentation mode: NORMAL for production builds, DEV for dev mode, TEST for serializing a test
 * ApplicationModel without running augmentation.
 */
public enum AugmentationMode {
  NORMAL,
  TEST,
  DEV,
  NATIVE;

  /**
   * Parses a mode string (case-insensitive).
   *
   * @throws IllegalArgumentException if the string is not "normal", "test", "dev", or "native"
   */
  public static AugmentationMode parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException(
          "Invalid mode: null. Must be 'normal', 'test', 'dev', or 'native'.");
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "normal" -> NORMAL;
      case "test" -> TEST;
      case "dev" -> DEV;
      case "native" -> NATIVE;
      default -> throw new IllegalArgumentException(
          "Invalid mode: '%s'. Must be 'normal', 'test', 'dev', or 'native'.".formatted(value));
    };
  }
}
