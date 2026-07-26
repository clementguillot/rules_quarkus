load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")
load("@rules_shell//shell:sh_test.bzl", "sh_test")
load(":assert_junit_xml.bzl", "assert_junit_xml_test")
load(":expect_test_failure.bzl", "expect_test_failure_test")

java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**/*"]),
    # //big reuses the app sources for the E2BIG regression targets.
    visibility = ["//big:__pkg__"],
    deps = [
        # Locally-built custom extension (runtime side); deployment is auto-wired.
        "//ext/runtime",
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:io_quarkus_quarkus_rest",
        "@maven//:jakarta_inject_jakarta_inject_api",
        "@maven//:jakarta_ws_rs_jakarta_ws_rs_api",
    ],
)

quarkus_app(
    name = "app",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

java_library(
    name = "test_lib",
    testonly = True,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/smoke/EmptyTest.java"],
    ),
    # //big reuses the test sources for the E2BIG regression targets.
    visibility = ["//big:__pkg__"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:io_rest_assured_rest_assured",
        "@maven//:org_hamcrest_hamcrest",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

quarkus_test(
    name = "test",
    deps = [
        ":test_lib",
    ],
)

assert_junit_xml_test(
    name = "junit_xml_report_test",
    expected_class = "smoke.GreetingResourceTest",
    expected_test = "testHelloEndpoint()",
    tags = ["exclusive"],
    target = ":test",
)

build_test(
    name = "smoke_test",
    targets = [
        ":app",
    ],
)

# Real production-mode conditional-dependency regression. quarkus-rest declares
# quarkus-rest-kotlin as conditional on quarkus-kotlin. The candidate must be selected by the
# ApplicationModel without becoming an explicit user dependency.
java_library(
    name = "conditional_lib",
    srcs = ["src/conditional/java/smoke/ConditionalMarker.java"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_kotlin",
    ],
)

quarkus_app(
    name = "conditional_app",
    version = "1.0.0-SNAPSHOT",
    deps = [":conditional_lib"],
)

build_test(
    name = "conditional_app_build_test",
    targets = [":conditional_app"],
)

filegroup(
    name = "conditional_app_model",
    srcs = [":conditional_app"],
    output_group = "quarkus_model",
)

sh_test(
    name = "conditional_dependency_model_test",
    srcs = ["conditional_dependency_model_test.sh"],
    args = ["$(rlocationpath :conditional_app_model)"],
    data = [":conditional_app_model"],
)

sh_test(
    name = "dev_ui_application_model_test",
    size = "large",
    srcs = ["dev_ui_application_model_test.sh"],
    args = ["$(rlocationpath :app_dev)"],
    data = [":app_dev"],
    # The test copies the launcher and points it at this test's merged runfiles tree.
    tags = ["no-sandbox"],
)

# --- fail_if_no_tests smoke tests (issue #125) ---
#
# EmptyTest.java is a @QuarkusTest with zero @Test methods.
java_library(
    name = "empty_test_lib",
    testonly = True,
    srcs = ["src/test/java/smoke/EmptyTest.java"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

# Zero tests + strict mode (default) — must fail.
quarkus_test(
    name = "empty_test_strict",
    tags = ["manual"],
    deps = [":empty_test_lib"],
)

# Wraps empty_test_strict and asserts it fails. This target runs in CI.
expect_test_failure_test(
    name = "empty_test_strict_fails",
    target = ":empty_test_strict",
)

# Zero tests + lenient mode — must pass. Runs in CI directly.
quarkus_test(
    name = "empty_test_lenient",
    fail_if_no_tests = False,
    deps = [":empty_test_lib"],
)

# Verifies the extension publish artifacts (jars + POMs) build for both halves.
build_test(
    name = "publish_test",
    targets = [
        "//ext/runtime:runtime_publish-project",
        "//ext/runtime:runtime_publish-pom",
        "//ext/deployment:deployment_publish-project",
        "//ext/deployment:deployment_publish-pom",
    ],
)
