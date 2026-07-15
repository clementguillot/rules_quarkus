package com.clementguillot.quarkifier;

/**
 * Main entry point for Quarkifier CLI.
 *
 * <p>Thin shell that delegates to {@link QuarkifierCommand} via picocli. Kept as a separate class
 * so the Bazel {@code java_binary} main_class doesn't change.
 */
public final class QuarkifierLauncher {

  private static final String JUL_MANAGER_PROPERTY = "java.util.logging.manager";
  private static final String JBOSS_LOG_MANAGER = "org.jboss.logmanager.LogManager";

  private QuarkifierLauncher() {}

  public static void main(String... args) {
    System.setProperty(JUL_MANAGER_PROPERTY, JBOSS_LOG_MANAGER);
    int exitCode = QuarkifierCommand.createCommandLine().execute(args);
    System.exit(exitCode);
  }
}
