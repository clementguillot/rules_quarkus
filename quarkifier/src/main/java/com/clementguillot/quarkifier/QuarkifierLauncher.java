package com.clementguillot.quarkifier;

/**
 * Main entry point for Quarkifier CLI.
 *
 * <p>Thin shell that delegates to {@link QuarkifierCommand} via picocli. Kept as a separate class
 * so the Bazel {@code java_binary} main_class doesn't change.
 */
public final class QuarkifierLauncher {

  private QuarkifierLauncher() {}

  public static void main(String... args) {
    int exitCode = QuarkifierCommand.createCommandLine().execute(args);
    System.exit(exitCode);
  }
}
