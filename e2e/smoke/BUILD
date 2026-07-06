load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")

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
    srcs = glob(["src/test/java/**/*.java"]),
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

build_test(
    name = "smoke_test",
    targets = [
        ":app",
    ],
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
