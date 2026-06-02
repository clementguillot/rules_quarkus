package com.clementguillot.quarkifier;

import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
    String appCp = null;
    String deployCp = null;
    String coreDeployCp = null;
    String outputDir = null;
    String resources = null;
    String mode = null;
    String expectedVersion = null;
    String appName = null;
    String appVersion = null;
    String mainClass = null;
    String nativeBuilderImage = null;
    String sourceDirs = null;
    String classesDir = null;
    String bazelTargets = null;
    String classesOutputDirs = null;
    String workspaceDir = null;
    String bazelBuildTimeoutSeconds = null;
    String localAppJars = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--application-classpath" -> appCp = requireValue(args, ++i, args[i - 1]);
        case "--application-classpath-file" -> appCp =
            readFileContent(requireValue(args, ++i, args[i - 1]));
        case "--deployment-classpath" -> deployCp = requireValue(args, ++i, args[i - 1]);
        case "--deployment-classpath-file" -> deployCp =
            readFileContent(requireValue(args, ++i, args[i - 1]));
        case "--core-deployment-classpath" -> coreDeployCp = requireValue(args, ++i, args[i - 1]);
        case "--output-dir" -> outputDir = requireValue(args, ++i, args[i - 1]);
        case "--resources" -> resources = requireValue(args, ++i, args[i - 1]);
        case "--mode" -> mode = requireValue(args, ++i, args[i - 1]);
        case "--expected-quarkus-version" -> expectedVersion = requireValue(args, ++i, args[i - 1]);
        case "--app-name" -> appName = requireValue(args, ++i, args[i - 1]);
        case "--app-version" -> appVersion = requireValue(args, ++i, args[i - 1]);
        case "--main-class" -> {
          mainClass = requireValue(args, ++i, args[i - 1]).trim();
          if (mainClass.isEmpty()) {
            throw new InvalidArgumentsException("Value for --main-class must not be empty");
          }
        }
        case "--native-builder-image" -> {
          nativeBuilderImage = requireValue(args, ++i, args[i - 1]).trim();
          if (nativeBuilderImage.isEmpty()) {
            throw new InvalidArgumentsException(
                "Value for --native-builder-image must not be empty");
          }
        }
        case "--source-dirs" -> sourceDirs = requireValue(args, ++i, args[i - 1]);
        case "--classes-dir" -> classesDir = requireValue(args, ++i, args[i - 1]);
        case "--bazel-targets" -> bazelTargets = requireValue(args, ++i, args[i - 1]);
        case "--classes-output-dirs" -> classesOutputDirs = requireValue(args, ++i, args[i - 1]);
        case "--workspace-dir" -> workspaceDir = requireValue(args, ++i, args[i - 1]);
        case "--bazel-build-timeout-seconds" -> bazelBuildTimeoutSeconds =
            requireValue(args, ++i, args[i - 1]);
        case "--local-app-jars" -> localAppJars = requireValue(args, ++i, args[i - 1]);
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

    AugmentationMode parsedMode = parseMode(mode);
    long parsedTimeout = parseTimeout(bazelBuildTimeoutSeconds);

    return new QuarkifierConfig(
        splitPaths(appCp, ":"),
        splitPaths(deployCp, ":"),
        coreDeployCp != null ? splitPaths(coreDeployCp, ":") : List.of(),
        Path.of(outputDir),
        resources != null ? splitPaths(resources, ",") : List.of(),
        parsedMode,
        expectedVersion,
        appName,
        appVersion,
        mainClass,
        nativeBuilderImage,
        sourceDirs != null ? splitPaths(sourceDirs, ",") : List.of(),
        classesDir != null ? Path.of(classesDir) : null,
        bazelTargets != null ? splitStrings(bazelTargets, ",") : List.of(),
        classesOutputDirs != null ? splitPaths(classesOutputDirs, ",") : List.of(),
        workspaceDir != null ? Path.of(workspaceDir) : null,
        parsedTimeout,
        localAppJars != null ? splitPaths(localAppJars, ":") : List.of());
  }

  /** Serializes this config back to a CLI argument array, suitable for round-trip testing. */
  public String[] toArgs() {
    var list = new java.util.ArrayList<String>();

    list.add("--application-classpath");
    list.add(joinPaths(applicationClasspath, ":"));

    list.add("--deployment-classpath");
    list.add(joinPaths(deploymentClasspath, ":"));

    if (!coreDeploymentClasspath.isEmpty()) {
      list.add("--core-deployment-classpath");
      list.add(joinPaths(coreDeploymentClasspath, ":"));
    }

    list.add("--output-dir");
    list.add(outputDir.toString());

    if (!resources.isEmpty()) {
      list.add("--resources");
      list.add(joinPaths(resources, ","));
    }

    list.add("--mode");
    list.add(mode.name().toLowerCase(java.util.Locale.ROOT));

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

    if (mainClass != null) {
      list.add("--main-class");
      list.add(mainClass);
    }

    if (nativeBuilderImage != null) {
      list.add("--native-builder-image");
      list.add(nativeBuilderImage);
    }

    if (!sourceDirs.isEmpty()) {
      list.add("--source-dirs");
      list.add(joinPaths(sourceDirs, ","));
    }

    if (classesDir != null) {
      list.add("--classes-dir");
      list.add(classesDir.toString());
    }

    if (!bazelTargets.isEmpty()) {
      list.add("--bazel-targets");
      list.add(String.join(",", bazelTargets));
    }

    if (!classesOutputDirs.isEmpty()) {
      list.add("--classes-output-dirs");
      list.add(joinPaths(classesOutputDirs, ","));
    }

    if (workspaceDir != null) {
      list.add("--workspace-dir");
      list.add(workspaceDir.toString());
    }

    // Always include timeout for round-trip consistency
    list.add("--bazel-build-timeout-seconds");
    list.add(String.valueOf(bazelBuildTimeoutSeconds));

    if (!localAppJars.isEmpty()) {
      list.add("--local-app-jars");
      list.add(joinPaths(localAppJars, ":"));
    }

    return list.toArray(String[]::new);
  }

  /** Returns the usage message for display on error. */
  public static String usage() {
    return USAGE;
  }

  // ---- internal helpers ----

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
