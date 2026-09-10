package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a single augmentation invocation.
 *
 * @param applicationClasspath runtime jars
 * @param coreDeploymentClasspath dev process infrastructure — Quarkus bootstrap resolvers and
 *     quarkus-core-deployment transitive closures (dev mode only)
 * @param outputDir directory where the selected package is written
 * @param resources additional resource paths
 * @param mode NORMAL, TEST, DEV, or NATIVE
 * @param packageType Quarkus JVM package layout (used in NORMAL mode)
 * @param appName application name for Quarkus startup banner (may be {@code null})
 * @param mainClass fully-qualified custom main class name annotated with {@code @QuarkusMain} (may
 *     be {@code null})
 * @param nativeBuilderImage the native builder image for {@code
 *     platform.quarkus.native.builder-image} (may be {@code null})
 * @param sourceDirs source directories for hot-reload in dev mode
 * @param classesDir mutable directory for .class files in dev mode (may be {@code null})
 * @param testClassesDir mutable directory for test .class files in dev mode (may be {@code null})
 * @param testClassesOutputDirs bazel-bin outputs containing compiled test classes
 * @param bazelTargets Bazel targets to rebuild on source changes
 * @param classesOutputDirs bazel-bin output directories containing .class files
 * @param workspaceDir Bazel workspace root directory for running bazel build (may be {@code null})
 * @param bazelBuildTimeoutSeconds timeout in seconds for bazel build process (default: 600)
 * @param bazelCommand bazel binary to invoke for hot-reload builds (default: {@code bazel})
 * @param bazelBuildArgs extra flags for the hot-reload {@code bazel build}
 * @param codegenInputFiles exact declared CodeGenProvider inputs
 * @param localAppJars local workspace jars to use as application roots
 * @param buildProperties declared hermetic build-time configuration
 * @param applicationModel explicit validated Bazel model JSON
 * @param testApplicationModel explicit validated TEST-mode Bazel model JSON (may be {@code null})
 * @param watchedInputs exact source, resource, and generator inputs for continuous testing
 * @param watchedBuildFiles BUILD files whose changes require a dev-mode restart
 * @param testJvmArgs JVM flags for the shared dev/test child process
 */
public record QuarkifierConfig(
    List<Path> applicationClasspath,
    List<Path> coreDeploymentClasspath,
    Path outputDir,
    List<Path> resources,
    AugmentationMode mode,
    JarPackageType packageType,
    String appName,
    String mainClass,
    String nativeBuilderImage,
    List<Path> sourceDirs,
    Path classesDir,
    Path testClassesDir,
    List<Path> testClassesOutputDirs,
    List<String> bazelTargets,
    List<Path> classesOutputDirs,
    Path workspaceDir,
    long bazelBuildTimeoutSeconds,
    String bazelCommand,
    List<String> bazelBuildArgs,
    List<Path> codegenInputFiles,
    List<Path> localAppJars,
    Map<String, String> buildProperties,
    Path applicationModel,
    Path testApplicationModel,
    List<Path> watchedInputs,
    List<Path> watchedBuildFiles,
    List<String> testJvmArgs) {

  /** Private, source-free directory used to notify Quarkus after a completed Bazel sync. */
  public Path reloadNotificationDir() {
    return testClassesDir == null
        ? null
        : testClassesDir.toAbsolutePath().getParent().resolve("reload-notifications");
  }
}
