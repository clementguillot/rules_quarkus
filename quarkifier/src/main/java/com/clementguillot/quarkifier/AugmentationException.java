package com.clementguillot.quarkifier;

/**
 * Thrown when Quarkus augmentation fails.
 *
 * <p>Wraps the underlying Quarkus build error so the caller can print the full stack trace to
 * stderr and exit with a non-zero code.
 */
public final class AugmentationException extends Exception {

  public AugmentationException(String message) {
    super(message);
  }

  public AugmentationException(String message, Throwable cause) {
    super(message, cause);
  }
}
