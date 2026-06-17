package com.clementguillot.quarkifier;

import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Immutable configuration for a single augmentation invocation.
 *
 * @param applicationClasspath runtime jars (colon-separated on CLI)
 * @param deploymentClasspath deployment jars (colon-separated on CLI)
 * @param coreDeploymentClasspath core deployment jars only — quarkus-core-deployment transitive
 *     closure (colon-separated on CLI, dev mode only)
 * @param outputDir directory where Fast_Jar output is written
 * @param resources additional resource paths (comma-separated on CLI)
 * @param mode NORMAL, TEST, or DEV
 * @param expectedQuarkusVersion expected Quarkus version for mismatch warnings (may be {@code
 *     null})
 * @param appName application name for Quarkus startup banner (may be {@code null})
 * @param appVersion application version for Quarkus startup banner (may be {@code null})
 * @param mainClass fully-qualified custom main class name annotated with {@code @QuarkusMain} (may
 *     be {@code null})
 * @param nativeBuilderImage the native builder image for {@code
 *     platform.quarkus.native.builder-image} (e.g. {@code
 *     quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25}). May be {@code null} to use the
 *     default.
 * @param sourceDirs source directories for hot-reload in dev mode (comma-separated on CLI)
 * @param classesDir mutable directory for .class files in dev mode (may be {@code null})
 * @param bazelTargets Bazel targets to rebuild on source changes (comma-separated on CLI)
 * @param classesOutputDirs bazel-bin output directories containing .class files (comma-separated on
 *     CLI)
 * @param workspaceDir Bazel workspace root directory for running bazel build (may be {@code null})
 * @param bazelBuildTimeoutSeconds timeout in seconds for bazel build process (default: 60)
 * @param localAppJars local workspace jars to use as application roots (colon-separated on CLI).
 *     When empty, the caller handles fallback (e.g. using {@code applicationClasspath.get(0)}).
 */
public record QuarkifierConfig(
    List<Path> applicationClasspath,
    List<Path> deploymentClasspath,
    List<Path> coreDeploymentClasspath,
    Path outputDir,
    List<Path> resources,
    AugmentationMode mode,
    String expectedQuarkusVersion,
    String appName,
    String appVersion,
    String mainClass,
    String nativeBuilderImage,
    List<Path> sourceDirs,
    Path classesDir,
    List<String> bazelTargets,
    List<Path> classesOutputDirs,
    Path workspaceDir,
    long bazelBuildTimeoutSeconds,
    List<Path> localAppJars) {

  private static final String USAGE =
      """
            Usage: quarkifier \\
              --application-classpath <jar:jar:...> \\
              [--application-classpath-file <path>] \\
              --deployment-classpath <jar:jar:...> \\
              [--deployment-classpath-file <path>] \\
              [--core-deployment-classpath <jar:jar:...>] \\
              --output-dir <path> \\
              [--resources <path,path,...>] \\
              [--mode normal|test|dev] \\
              [--expected-quarkus-version <version>] \\
              [--app-name <name>] \\
              [--app-version <version>] \\
              [--main-class <class>] \\
              [--native-builder-image <image>] \\
              [--source-dirs <dir,dir,...>] \\
              [--classes-dir <path>] \\
              [--bazel-targets <label,label,...>] \\
              [--classes-output-dirs <dir,dir,...>] \\
              [--workspace-dir <path>] \\
              [--bazel-build-timeout-seconds <seconds>] \\
              [--local-app-jars <jar:jar:...>]""";

  /**
   * Parses CLI arguments into an {@link QuarkifierConfig}.
   *
   * <p>Required flags: {@code --application-classpath}, {@code --deployment-classpath}, {@code
   * --output-dir}.
   *
   * @return a validated config
   * @throws InvalidArgumentsException on missing/invalid arguments (caller should exit 2)
   */
  public static QuarkifierConfig parse(String... args) throws InvalidArgumentsException {
    RawArgs raw = parseFlags(args);

    if (raw.appCp == null) {
      throw new InvalidArgumentsException("Missing required argument: --application-classpath");
    }
    if (raw.deployCp == null) {
      throw new InvalidArgumentsException("Missing required argument: --deployment-classpath");
    }
    if (raw.outputDir == null) {
      throw new InvalidArgumentsException("Missing required argument: --output-dir");
    }

    return new QuarkifierConfig(
        splitPaths(raw.appCp, ":"),
        splitPaths(raw.deployCp, ":"),
        raw.coreDeployCp != null ? splitPaths(raw.coreDeployCp, ":") : List.of(),
        Path.of(raw.outputDir),
        raw.resources != null ? splitPaths(raw.resources, ",") : List.of(),
        parseMode(raw.mode),
        raw.expectedVersion,
        raw.appName,
        raw.appVersion,
        raw.mainClass,
        raw.nativeBuilderImage,
        raw.sourceDirs != null ? splitPaths(raw.sourceDirs, ",") : List.of(),
        raw.classesDir != null ? Path.of(raw.classesDir) : null,
        raw.bazelTargets != null ? splitStrings(raw.bazelTargets, ",") : List.of(),
        raw.classesOutputDirs != null ? splitPaths(raw.classesOutputDirs, ",") : List.of(),
        raw.workspaceDir != null ? Path.of(raw.workspaceDir) : null,
        parseTimeout(raw.bazelBuildTimeoutSeconds),
        raw.localAppJars != null ? splitPaths(raw.localAppJars, ":") : List.of());
  }

  /** Raw flag values as given on the CLI, before validation and conversion. One field per flag. */
  @SuppressWarnings("PMD.TooManyFields")
  private static final class RawArgs {
    String appCp;
    String deployCp;
    String coreDeployCp;
    String outputDir;
    String resources;
    String mode;
    String expectedVersion;
    String appName;
    String appVersion;
    String mainClass;
    String nativeBuilderImage;
    String sourceDirs;
    String classesDir;
    String bazelTargets;
    String classesOutputDirs;
    String workspaceDir;
    String bazelBuildTimeoutSeconds;
    String localAppJars;
  }

  private static RawArgs parseFlags(String... args) throws InvalidArgumentsException {
    RawArgs raw = new RawArgs();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--application-classpath" -> raw.appCp = requireValue(args, ++i, args[i - 1]);
        case "--application-classpath-file" -> raw.appCp =
            readFileContent(requireValue(args, ++i, args[i - 1]));
        case "--deployment-classpath" -> raw.deployCp = requireValue(args, ++i, args[i - 1]);
        case "--deployment-classpath-file" -> raw.deployCp =
            readFileContent(requireValue(args, ++i, args[i - 1]));
        case "--core-deployment-classpath" -> raw.coreDeployCp =
            requireValue(args, ++i, args[i - 1]);
        case "--output-dir" -> raw.outputDir =
            requireNonEmptyValue(args, ++i, args[i - 1], "--output-dir");
        case "--resources" -> raw.resources = requireValue(args, ++i, args[i - 1]);
        case "--mode" -> raw.mode = requireValue(args, ++i, args[i - 1]);
        case "--expected-quarkus-version" -> raw.expectedVersion =
            requireValue(args, ++i, args[i - 1]);
        case "--app-name" -> raw.appName = requireValue(args, ++i, args[i - 1]);
        case "--app-version" -> raw.appVersion = requireValue(args, ++i, args[i - 1]);
        case "--main-class" -> raw.mainClass =
            requireNonEmptyValue(args, ++i, args[i - 1], "--main-class");
        case "--native-builder-image" -> raw.nativeBuilderImage =
            requireNonEmptyValue(args, ++i, args[i - 1], "--native-builder-image");
        case "--source-dirs" -> raw.sourceDirs = requireValue(args, ++i, args[i - 1]);
        case "--classes-dir" -> raw.classesDir = requireValue(args, ++i, args[i - 1]);
        case "--bazel-targets" -> raw.bazelTargets = requireValue(args, ++i, args[i - 1]);
        case "--classes-output-dirs" -> raw.classesOutputDirs =
            requireValue(args, ++i, args[i - 1]);
        case "--workspace-dir" -> raw.workspaceDir = requireValue(args, ++i, args[i - 1]);
        case "--bazel-build-timeout-seconds" -> raw.bazelBuildTimeoutSeconds =
            requireValue(args, ++i, args[i - 1]);
        case "--local-app-jars" -> raw.localAppJars = requireValue(args, ++i, args[i - 1]);
        default -> throw new InvalidArgumentsException("Unknown argument: " + args[i]);
      }
    }
    return raw;
  }

  /** Serializes this config back to a CLI argument array, suitable for round-trip testing. */
  public String[] toArgs() {
    var list = new ArrayList<String>();
    addArg(list, "--application-classpath", joinPaths(applicationClasspath, ":"));
    addArg(list, "--deployment-classpath", joinPaths(deploymentClasspath, ":"));
    addArgUnlessEmpty(list, "--core-deployment-classpath", joinPaths(coreDeploymentClasspath, ":"));
    addArg(list, "--output-dir", outputDir.toString());
    addArgUnlessEmpty(list, "--resources", joinPaths(resources, ","));
    addArg(list, "--mode", mode.name().toLowerCase(Locale.ROOT));
    addArgIfPresent(list, "--expected-quarkus-version", expectedQuarkusVersion);
    addArgIfPresent(list, "--app-name", appName);
    addArgIfPresent(list, "--app-version", appVersion);
    addArgIfPresent(list, "--main-class", mainClass);
    addArgIfPresent(list, "--native-builder-image", nativeBuilderImage);
    addArgUnlessEmpty(list, "--source-dirs", joinPaths(sourceDirs, ","));
    addArgIfPresent(list, "--classes-dir", classesDir != null ? classesDir.toString() : null);
    addArgUnlessEmpty(list, "--bazel-targets", String.join(",", bazelTargets));
    addArgUnlessEmpty(list, "--classes-output-dirs", joinPaths(classesOutputDirs, ","));
    addArgIfPresent(list, "--workspace-dir", workspaceDir != null ? workspaceDir.toString() : null);
    // Always include timeout for round-trip consistency
    addArg(list, "--bazel-build-timeout-seconds", String.valueOf(bazelBuildTimeoutSeconds));
    addArgUnlessEmpty(list, "--local-app-jars", joinPaths(localAppJars, ":"));
    return list.toArray(String[]::new);
  }

  /** Returns the usage message for display on error. */
  public static String usage() {
    return USAGE;
  }

  // ---- internal helpers ----

  private static void addArg(List<String> list, String flag, String value) {
    list.add(flag);
    list.add(value);
  }

  private static void addArgIfPresent(List<String> list, String flag, String value) {
    if (value != null) {
      addArg(list, flag, value);
    }
  }

  private static void addArgUnlessEmpty(List<String> list, String flag, String value) {
    if (!value.isEmpty()) {
      addArg(list, flag, value);
    }
  }

  private static AugmentationMode parseMode(String mode) throws InvalidArgumentsException {
    try {
      return mode != null ? AugmentationMode.parse(mode) : AugmentationMode.NORMAL;
    } catch (IllegalArgumentException e) {
      throw new InvalidArgumentsException(e.getMessage(), e);
    }
  }

  private static long parseTimeout(String bazelBuildTimeoutSeconds)
      throws InvalidArgumentsException {
    if (bazelBuildTimeoutSeconds == null) {
      return 60;
    }
    try {
      long timeout = Long.parseLong(bazelBuildTimeoutSeconds);
      if (timeout <= 0) {
        throw new InvalidArgumentsException("--bazel-build-timeout-seconds must be positive");
      }
      return timeout;
    } catch (NumberFormatException e) {
      throw new InvalidArgumentsException(
          "--bazel-build-timeout-seconds must be a valid integer", e);
    }
  }

  private static String requireValue(String[] args, int index, String flag)
      throws InvalidArgumentsException {
    if (index >= args.length) {
      throw new InvalidArgumentsException("Missing value for " + flag);
    }
    return args[index];
  }

  private static String requireNonEmptyValue(String[] args, int index, String flag, String name)
      throws InvalidArgumentsException {
    String value = requireValue(args, index, flag).trim();
    if (value.isEmpty()) {
      throw new InvalidArgumentsException("Value for " + name + " must not be empty");
    }
    return value;
  }

  private static String readFileContent(String filePath) throws InvalidArgumentsException {
    try {
      return Files.readString(Path.of(filePath)).strip();
    } catch (java.io.IOException e) {
      throw new InvalidArgumentsException("Failed to read classpath file: " + filePath, e);
    }
  }

  private static List<Path> splitPaths(String value, String separator) {
    if (value.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(value.split(Pattern.quote(separator))).map(Path::of).toList();
  }

  private static List<String> splitStrings(String value, String separator) {
    if (value.isEmpty()) {
      return List.of();
    }
    return Arrays.asList(value.split(Pattern.quote(separator)));
  }

  private static String joinPaths(List<Path> paths, String separator) {
    return String.join(separator, paths.stream().map(Path::toString).toList());
  }

  /** Thrown when CLI arguments are invalid. The caller should exit with code 2. */
  public static final class InvalidArgumentsException extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    public InvalidArgumentsException(String message) {
      super(message);
    }

    public InvalidArgumentsException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
