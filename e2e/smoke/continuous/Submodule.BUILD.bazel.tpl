load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_test")

java_library(
    name = "lib",
    srcs = ["SubmoduleService.java"],
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:jakarta_enterprise_jakarta_enterprise_cdi_api",
    ],
)

java_library(
    name = "tests",
    testonly = True,
    srcs = glob(["src/test/java/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:jakarta_inject_jakarta_inject_api",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
    ],
)

quarkus_test(
    name = "test",
    deps = [":tests"],
    visibility = ["//visibility:public"],
)
