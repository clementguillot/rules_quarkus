package com.clementguillot.quarkifier;

/** Augmentation mode: NORMAL for production builds, TEST for test-scoped builds. */
public enum AugmentationMode {
  NORMAL,
  TEST;

  /**
   * Parses a mode string (case-insensitive).
   *
   * @throws IllegalArgumentException if the string is not "normal" or "test"
   */
  public static AugmentationMode parse(String value) {
    return switch (value.toLowerCase()) {
      case "normal" -> NORMAL;
      case "test" -> TEST;
      default -> throw new IllegalArgumentException(
          "Invalid mode: '%s'. Must be 'normal' or 'test'.".formatted(value));
    };
  }
}
