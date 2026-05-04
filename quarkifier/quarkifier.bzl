"""Macro for declaring per-minor quarkifier targets.

Reduces duplication in BUILD.bazel when adding new Quarkus minor versions.
Each call creates: java_library, java_binary, java_test, and pmd_test targets
suffixed with the sanitized minor version (e.g., _3_27).
"""

load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_test.bzl", "java_test")
load("//tools/lint:linters.bzl", "pmd_test")

def quarkifier_targets(minor, maven_repo):
    """Declares quarkifier library, binary, test, and lint targets for a Quarkus minor version.

    Args:
        minor: Sanitized minor version string (e.g., "3_27", "3_33").
        maven_repo: Maven repository name (e.g., "@maven_3_27", "@maven_3_33").
    """
    lib_name = "quarkifier_lib_" + minor
    bin_name = "quarkifier_" + minor
    test_name = "quarkifier_test_" + minor
    pmd_name = "pmd_test_" + minor

    java_library(
        name = lib_name,
        srcs = native.glob(["src/main/java/**/*.java"]) + native.glob(["src/main/java_{minor}/**/*.java".format(minor = minor)]),
        resources = native.glob(["src/main/resources/**/*"]),
        visibility = ["//quarkifier:__pkg__"],
        deps = [
            # Quarkus Bootstrap
            maven_repo + "//:io_quarkus_quarkus_bootstrap_app_model",
            maven_repo + "//:io_quarkus_quarkus_bootstrap_core",
            maven_repo + "//:io_quarkus_quarkus_bootstrap_runner",
            # Quarkus Core + deployment (contains AugmentActionImpl)
            maven_repo + "//:io_quarkus_quarkus_core",
            maven_repo + "//:io_quarkus_quarkus_core_deployment",
            # ArC runtime — needed in the deploy jar so ASM's ClassWriter can resolve
            # types like io.quarkus.arc.impl.ParameterizedTypeImpl during augmentation.
            maven_repo + "//:io_quarkus_arc_arc",
            # Logging
            maven_repo + "//:org_jboss_logging_jboss_logging",
        ],
    )

    java_binary(
        name = bin_name,
        main_class = "com.clementguillot.quarkifier.QuarkifierLauncher",
        visibility = ["//visibility:public"],
        runtime_deps = [":" + lib_name],
    )

    java_test(
        name = test_name,
        srcs = native.glob(["src/test/java/**/*.java"]),
        args = [
            "execute",
            "--select-package=com.clementguillot.quarkifier",
            "--fail-if-no-tests",
        ],
        main_class = "org.junit.platform.console.ConsoleLauncher",
        use_testrunner = False,
        deps = [
            ":" + lib_name,
            maven_repo + "//:io_quarkus_quarkus_bootstrap_app_model",
            maven_repo + "//:io_quarkus_quarkus_bootstrap_core",
            maven_repo + "//:org_junit_jupiter_junit_jupiter",
            maven_repo + "//:org_junit_platform_junit_platform_console_standalone",
            maven_repo + "//:org_junit_platform_junit_platform_launcher",
        ],
    )

    pmd_test(
        name = pmd_name,
        srcs = [":" + lib_name],
    )
