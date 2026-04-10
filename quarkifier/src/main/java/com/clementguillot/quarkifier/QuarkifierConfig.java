package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable configuration for a single augmentation invocation.
 *
 * @param applicationClasspath runtime jars (colon-separated on CLI)
 * @param deploymentClasspath deployment jars (colon-separated on CLI)
 * @param outputDir directory where Fast_Jar output is written
 * @param applicationProperties path to application.properties (may be {@code null})
 * @param resources additional resource paths (comma-separated on CLI)
 * @param mode NORMAL or TEST
 * @param expectedQuarkusVersion expected Quarkus version for mismatch warnings (may be {@code
 *     null})
 */
public record QuarkifierConfig(
    List<Path> applicationClasspath,
    List<Path> deploymentClasspath,
    Path outputDir,
    Path applicationProperties,
    List<Path> resources,
    AugmentationMode mode,
    String expectedQuarkusVersion,
    String appName,
    String appVersion) {

  private static final String USAGE =
      """
            Usage: quarkifier \\
              --application-classpath <jar:jar:...> \\
              --deployment-classpath <jar:jar:...> \\
              --output-dir <path> \\
              [--application-properties <path>] \\
              [--resources <path,path,...>] \\
              [--mode normal|test] \\
              [--expected-quarkus-version <version>]""";

  /**
   * Parses CLI arguments into an {@link QuarkifierConfig}.
   *
   * <p>Required flags: {@code --application-classpath}, {@code --deployment-classpath}, {@code
   * --output-dir}.
   *
   * @return a validated config
   * @throws InvalidArgumentsException on missing/invalid arguments (caller should exit 2)
   */
  public static QuarkifierConfig parse(String[] args) throws InvalidArgumentsException {
    String appCp = null;
    String deployCp = null;
    String outputDir = null;
    String appProps = null;
    String resources = null;
    String mode = null;
    String expectedVersion = null;
    String appName = null;
    String appVersion = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--application-classpath" -> appCp = requireValue(args, ++i, args[i - 1]);
        case "--deployment-classpath" -> deployCp = requireValue(args, ++i, args[i - 1]);
        case "--output-dir" -> outputDir = requireValue(args, ++i, args[i - 1]);
        case "--application-properties" -> appProps = requireValue(args, ++i, args[i - 1]);
        case "--resources" -> resources = requireValue(args, ++i, args[i - 1]);
        case "--mode" -> mode = requireValue(args, ++i, args[i - 1]);
        case "--expected-quarkus-version" -> expectedVersion = requireValue(args, ++i, args[i - 1]);
        case "--app-name" -> appName = requireValue(args, ++i, args[i - 1]);
        case "--app-version" -> appVersion = requireValue(args, ++i, args[i - 1]);
        default -> throw new InvalidArgumentsException("Unknown argument: " + args[i]);
      }
    }

    if (appCp == null) {
      throw new InvalidArgumentsException("Missing required argument: --application-classpath");
    }
    if (deployCp == null) {
      throw new InvalidArgumentsException("Missing required argument: --deployment-classpath");
    }
    if (outputDir == null) {
      throw new InvalidArgumentsException("Missing required argument: --output-dir");
    }

    AugmentationMode parsedMode;
    try {
      parsedMode = mode != null ? AugmentationMode.parse(mode) : AugmentationMode.NORMAL;
    } catch (IllegalArgumentException e) {
      throw new InvalidArgumentsException(e.getMessage());
    }

    return new QuarkifierConfig(
        splitPaths(appCp, ":"),
        splitPaths(deployCp, ":"),
        Path.of(outputDir),
        appProps != null ? Path.of(appProps) : null,
        resources != null ? splitPaths(resources, ",") : List.of(),
        parsedMode,
        expectedVersion,
        appName,
        appVersion);
  }

  /**
   * Serializes this config back to a CLI argument array. Useful for round-trip testing (Property
   * 9).
   */
  public String[] toArgs() {
    var list = new java.util.ArrayList<String>();

    list.add("--application-classpath");
    list.add(joinPaths(applicationClasspath, ":"));

    list.add("--deployment-classpath");
    list.add(joinPaths(deploymentClasspath, ":"));

    list.add("--output-dir");
    list.add(outputDir.toString());

    if (applicationProperties != null) {
      list.add("--application-properties");
      list.add(applicationProperties.toString());
    }

    if (!resources.isEmpty()) {
      list.add("--resources");
      list.add(joinPaths(resources, ","));
    }

    list.add("--mode");
    list.add(mode.name().toLowerCase());

    if (expectedQuarkusVersion != null) {
      list.add("--expected-quarkus-version");
      list.add(expectedQuarkusVersion);
    }

    if (appName != null) {
      list.add("--app-name");
      list.add(appName);
    }

    if (appVersion != null) {
      list.add("--app-version");
      list.add(appVersion);
    }

    return list.toArray(String[]::new);
  }

  /** Returns the usage message for display on error. */
  public static String usage() {
    return USAGE;
  }

  // ---- internal helpers ----

  private static String requireValue(String[] args, int index, String flag)
      throws InvalidArgumentsException {
    if (index >= args.length) {
      throw new InvalidArgumentsException("Missing value for " + flag);
    }
    return args[index];
  }

  private static List<Path> splitPaths(String value, String separator) {
    if (value.isEmpty()) return List.of();
    return Arrays.stream(value.split(Pattern.quote(separator)))
        .map(Path::of)
        .toList();
  }

  private static String joinPaths(List<Path> paths, String separator) {
    return String.join(separator, paths.stream().map(Path::toString).toList());
  }

  /** Thrown when CLI arguments are invalid. The caller should exit with code 2. */
  public static final class InvalidArgumentsException extends Exception {
    public InvalidArgumentsException(String message) {
      super(message);
    }
  }
}
