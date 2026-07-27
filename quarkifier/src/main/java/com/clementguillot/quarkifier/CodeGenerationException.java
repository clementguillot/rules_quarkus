package com.clementguillot.quarkifier;

import java.io.Serial;

/** Raised when extension-provided source generation cannot complete. */
public final class CodeGenerationException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  public CodeGenerationException(String message) {
    super(message);
  }

  public CodeGenerationException(String message, Throwable cause) {
    super(message, cause);
  }
}
