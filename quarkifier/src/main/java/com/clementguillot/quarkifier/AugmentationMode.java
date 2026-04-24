package com.clementguillot.quarkifier;

import java.util.Locale;

/**
 * Augmentation mode: NORMAL for production builds, DEV for dev mode, TEST for serializing a test
 * ApplicationModel without running augmentation.
 */
public enum AugmentationMode {
  NORMAL,
  TEST,
  DEV;

  /**
   * Parses a mode string (case-insensitive).
   *
   * @throws IllegalArgumentException if the string is not "normal", "test", or "dev"
   */
  public static AugmentationMode parse(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "normal" -> NORMAL;
      case "test" -> TEST;
      case "dev" -> DEV;
      default -> throw new IllegalArgumentException(
          "Invalid mode: '%s'. Must be 'normal', 'test', or 'dev'.".formatted(value));
    };
  }
}
