package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable configuration for a single augmentation invocation.
 *
 * @param applicationClasspath runtime jars
 * @param deploymentClasspath deployment jars
 * @param coreDeploymentClasspath core deployment jars only — quarkus-core-deployment transitive
 *     closure (dev mode only)
 * @param outputDir directory where Fast_Jar output is written
 * @param resources additional resource paths
 * @param mode NORMAL, TEST, DEV, or NATIVE
 * @param expectedQuarkusVersion expected Quarkus version for mismatch warnings (may be {@code
 *     null})
 * @param appName application name for Quarkus startup banner (may be {@code null})
 * @param appVersion application version for Quarkus startup banner (may be {@code null})
 * @param mainClass fully-qualified custom main class name annotated with {@code @QuarkusMain} (may
 *     be {@code null})
 * @param nativeBuilderImage the native builder image for {@code
 *     platform.quarkus.native.builder-image} (may be {@code null})
 * @param sourceDirs source directories for hot-reload in dev mode
 * @param classesDir mutable directory for .class files in dev mode (may be {@code null})
 * @param bazelTargets Bazel targets to rebuild on source changes
 * @param classesOutputDirs bazel-bin output directories containing .class files
 * @param workspaceDir Bazel workspace root directory for running bazel build (may be {@code null})
 * @param bazelBuildTimeoutSeconds timeout in seconds for bazel build process (default: 600)
 * @param bazelCommand bazel binary to invoke for hot-reload builds (default: {@code bazel})
 * @param bazelBuildArgs extra flags for the hot-reload {@code bazel build}
 * @param localAppJars local workspace jars to use as application roots
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
    String bazelCommand,
    List<String> bazelBuildArgs,
    List<Path> localAppJars) {

  /**
   * Parses CLI arguments into a {@link QuarkifierConfig} via picocli.
   *
   * <p>Convenience factory used by tests and internal callers that already have an args array.
   * Automatically prepends the {@code augmentation} subcommand.
   *
   * @throws picocli.CommandLine.ParameterException on parse/validation error
   */
  public static QuarkifierConfig parse(String... args) {
    var commandLine = QuarkifierCommand.createCommandLine();
    // Prepend "augmentation" subcommand for callers that pass raw option args
    String[] fullArgs = new String[args.length + 1];
    fullArgs[0] = "augmentation";
    System.arraycopy(args, 0, fullArgs, 1, args.length);
    commandLine.parseArgs(fullArgs);
    var augCmd = commandLine.getSubcommands().get("augmentation").getCommand();
    return ((AugmentationCommand) augCmd).toConfig();
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
    addArg(list, "--bazel-build-timeout-seconds", String.valueOf(bazelBuildTimeoutSeconds));
    addArg(list, "--bazel-command", bazelCommand);
    addArgUnlessEmpty(list, "--bazel-build-args", String.join(",", bazelBuildArgs));
    addArgUnlessEmpty(list, "--local-app-jars", joinPaths(localAppJars, ":"));
    return list.toArray(String[]::new);
  }

  // ---- internal helpers for toArgs() ----

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

  private static String joinPaths(List<Path> paths, String separator) {
    return String.join(separator, paths.stream().map(Path::toString).toList());
  }
}
