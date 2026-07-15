package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.augmentation.AugmentationExecutor;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.extension.VersionChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.jboss.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Subcommand that performs Quarkus build-time augmentation.
 *
 * <p>Exit codes: 0 = success, 1 = augmentation failure, 2 = invalid arguments (picocli).
 */
@Command(
    name = "augmentation",
    description = "Run Quarkus build-time augmentation to produce a Fast_Jar.",
    mixinStandardHelpOptions = true)
@SuppressWarnings({"PMD.TooManyFields", "PMD.TooManyMethods"})
// picocli pattern — one field per CLI option; not worth splitting
public final class AugmentationCommand implements Callable<Integer> {

  @Spec private CommandSpec commandSpec;

  // ---- Classpath options (colon-separated, with file fallback) ----

  @Option(
      names = "--application-classpath",
      description = "Colon-separated list of runtime jars.",
      split = ":",
      defaultValue = "")
  private List<Path> applicationClasspath;

  @Option(
      names = "--application-classpath-file",
      description =
          "File containing the application classpath (alternative to --application-classpath).")
  private Path applicationClasspathFile;

  @Option(
      names = "--deployment-classpath",
      description = "Colon-separated list of deployment jars.",
      split = ":",
      defaultValue = "")
  private List<Path> deploymentClasspath;

  @Option(
      names = "--deployment-classpath-file",
      description =
          "File containing the deployment classpath (alternative to --deployment-classpath).")
  private Path deploymentClasspathFile;

  @Option(
      names = "--core-deployment-classpath",
      description = "Colon-separated list of core deployment jars (dev mode only).",
      split = ":",
      defaultValue = "")
  private List<Path> coreDeploymentClasspath;

  @Option(
      names = "--core-deployment-classpath-file",
      description =
          "File containing the core deployment classpath (alternative to"
              + " --core-deployment-classpath).")
  private Path coreDeploymentClasspathFile;

  @Option(
      names = "--local-app-jars",
      description = "Colon-separated local workspace jars to use as application roots.",
      split = ":",
      defaultValue = "")
  private List<Path> localAppJars;

  @Option(
      names = "--local-app-jars-file",
      description = "File containing local app jars (alternative to --local-app-jars).")
  private Path localAppJarsFile;

  // ---- Output ----

  @Option(
      names = "--output-dir",
      required = true,
      description = "Directory where Fast_Jar output is written.")
  private Path outputDir;

  // ---- Optional flags (comma-separated lists) ----

  @Option(names = "--resources", description = "Comma-separated resource file paths.", split = ",")
  private List<Path> resources;

  @Option(
      names = "--mode",
      description = "Augmentation mode: normal, test, dev, or native.",
      defaultValue = "normal")
  private String mode;

  @Option(
      names = "--expected-quarkus-version",
      description = "Expected Quarkus version for mismatch warnings.")
  private String expectedQuarkusVersion;

  @Option(names = "--app-name", description = "Application name for Quarkus startup banner.")
  private String appName;

  @Option(names = "--app-version", description = "Application version for Quarkus startup banner.")
  private String appVersion;

  @Option(
      names = "--main-class",
      description = "Fully-qualified custom main class annotated with @QuarkusMain.")
  private String mainClass;

  @Option(
      names = "--native-builder-image",
      description = "Native builder image for platform.quarkus.native.builder-image.")
  private String nativeBuilderImage;

  // ---- Dev-mode options ----

  @Option(
      names = "--source-dirs",
      description = "Comma-separated source directories for hot-reload in dev mode.",
      split = ",")
  private List<Path> sourceDirs;

  @Option(names = "--classes-dir", description = "Mutable directory for .class files in dev mode.")
  private Path classesDir;

  @Option(
      names = "--bazel-targets",
      description = "Comma-separated Bazel targets to rebuild on source changes.",
      split = ",")
  private List<String> bazelTargets;

  @Option(
      names = "--classes-output-dirs",
      description = "Comma-separated bazel-bin output directories containing .class files.",
      split = ",")
  private List<Path> classesOutputDirs;

  @Option(
      names = "--workspace-dir",
      description = "Bazel workspace root directory for running bazel build.")
  private Path workspaceDir;

  @Option(
      names = "--bazel-build-timeout-seconds",
      description = "Timeout in seconds for bazel build process.",
      defaultValue = "600")
  private long bazelBuildTimeoutSeconds;

  @Option(
      names = "--bazel-command",
      description = "Bazel binary to invoke for hot-reload builds.",
      defaultValue = "bazel")
  private String bazelCommand;

  @Option(
      names = "--bazel-build-args",
      description = "Comma-separated extra flags for the hot-reload bazel build.",
      split = ",")
  private List<String> bazelBuildArgs;

  // ---- Execution ----

  @Override
  public Integer call() {
    QuarkifierConfig config = toConfig();
    try {
      var extensions = ExtensionScanner.scan(config.applicationClasspath());
      VersionChecker.check(extensions, config.expectedQuarkusVersion());
      AugmentationExecutor.execute(config);
    } catch (AugmentationException e) {
      logger().error("Augmentation failed", e);
      return 1;
    } catch (IOException e) {
      logger().errorf("Error scanning extensions: %s", e.getMessage());
      return 1;
    }
    return 0;
  }

  /**
   * Builds an immutable {@link QuarkifierConfig} from the parsed picocli options.
   *
   * @throws CommandLine.ParameterException on invalid values
   */
  public QuarkifierConfig toConfig() {
    List<Path> resolvedAppCp = resolveClasspath(applicationClasspath, applicationClasspathFile);
    List<Path> resolvedDeployCp = resolveClasspath(deploymentClasspath, deploymentClasspathFile);
    List<Path> resolvedCoreCp =
        resolveClasspath(coreDeploymentClasspath, coreDeploymentClasspathFile);
    List<Path> resolvedLocalJars = resolveClasspath(localAppJars, localAppJarsFile);

    if (resolvedAppCp.isEmpty()) {
      throw parameterException(
          "Either --application-classpath or --application-classpath-file must be provided");
    }
    if (resolvedDeployCp.isEmpty()) {
      throw parameterException(
          "Either --deployment-classpath or --deployment-classpath-file must be provided");
    }
    validateNonEmpty(mainClass, "--main-class");
    validateNonEmpty(nativeBuilderImage, "--native-builder-image");
    validateNonEmpty(bazelCommand, "--bazel-command");
    if (outputDir.toString().isEmpty()) {
      throw parameterException("Value for --output-dir must not be empty");
    }
    if (bazelBuildTimeoutSeconds <= 0) {
      throw parameterException("--bazel-build-timeout-seconds must be positive");
    }

    return new QuarkifierConfig(
        resolvedAppCp,
        resolvedDeployCp,
        resolvedCoreCp,
        outputDir,
        orEmpty(resources),
        parseMode(mode),
        expectedQuarkusVersion,
        appName,
        appVersion,
        mainClass,
        nativeBuilderImage,
        orEmpty(sourceDirs),
        classesDir,
        orEmpty(bazelTargets),
        orEmpty(classesOutputDirs),
        workspaceDir,
        bazelBuildTimeoutSeconds,
        bazelCommand,
        orEmpty(bazelBuildArgs),
        resolvedLocalJars);
  }

  // ---- internal helpers ----

  private List<Path> resolveClasspath(List<Path> inline, Path file) {
    if (file != null) {
      try {
        String content = Files.readString(file).strip();
        if (content.isEmpty()) {
          return List.of();
        }
        return Arrays.stream(content.split(":")).filter(s -> !s.isEmpty()).map(Path::of).toList();
      } catch (IOException e) {
        throw parameterException("Failed to read classpath file: " + file, e);
      }
    }
    return inline.stream().filter(p -> !p.toString().isEmpty()).toList();
  }

  private static <T> List<T> orEmpty(List<T> list) {
    if (list == null) {
      return List.of();
    }
    return list.stream().filter(e -> !e.toString().isBlank()).toList();
  }

  private CommandLine.ParameterException parameterException(String message) {
    return new CommandLine.ParameterException(commandSpec.commandLine(), message);
  }

  private CommandLine.ParameterException parameterException(String message, Throwable cause) {
    return new CommandLine.ParameterException(commandSpec.commandLine(), message, cause);
  }

  private static Logger logger() {
    return Logger.getLogger(AugmentationCommand.class);
  }

  private void validateNonEmpty(String value, String name) {
    if (value != null && value.isBlank()) {
      throw parameterException("Value for " + name + " must not be empty");
    }
  }

  private AugmentationMode parseMode(String modeStr) {
    try {
      return AugmentationMode.parse(modeStr);
    } catch (IllegalArgumentException e) {
      throw parameterException(e.getMessage(), e);
    }
  }
}
