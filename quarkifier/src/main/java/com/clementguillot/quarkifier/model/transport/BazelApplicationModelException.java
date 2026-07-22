package com.clementguillot.quarkifier.model.transport;

/** Raised when a Bazel application-model document is malformed or internally inconsistent. */
public final class BazelApplicationModelException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public BazelApplicationModelException(String message) {
    super(message);
  }

  public BazelApplicationModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
