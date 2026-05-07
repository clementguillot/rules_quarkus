load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")

java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:io_quarkus_quarkus_rest",
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
