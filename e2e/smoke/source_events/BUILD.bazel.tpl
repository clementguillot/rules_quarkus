load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")

java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "@maven//:io_quarkus_quarkus_rest",
        "@maven//:jakarta_ws_rs_jakarta_ws_rs_api",
    ],
)

quarkus_app(
    name = "ordinary",
    deps = [":lib"],
)

quarkus_test(
    name = "test",
    srcs = glob(["src/test/java/**/*.java"]),
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
    ],
)

quarkus_app(
    name = "continuous",
    continuous_test = ":test",
    deps = [":lib"],
)
