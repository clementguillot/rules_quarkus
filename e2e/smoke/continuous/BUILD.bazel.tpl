load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_java_library", "quarkus_test")

quarkus_java_library(
    name = "lib",
    srcs = ["src/main/java/fixture/Main.java"],
    codegen_srcs = ["src/main/hello/main.hello"],
    deps = ["//dep:value", "//ext/runtime", "@maven//:io_quarkus_quarkus_rest"],
)

quarkus_java_library(
    name = "test_lib",
    testonly = True,
    srcs = glob(["tests/*.java"]),
    resources = glob(["testdata/**"]),
    resource_strip_prefix = "testdata",
    codegen_mode = "test",
    codegen_srcs = ["src/test/hello/test.hello"],
    deps = [":lib", "//helper:helper", "@maven//:JUNIT_LABEL", "@maven//:org_junit_jupiter_junit_jupiter_api"],
)

quarkus_test(
    name = "test",
    deps = [":test_lib"],
    build_properties = {"fixture.property": "round trip"},
    jvm_flags = ["-Dfixture.jvm=quote '$dollar `backtick` $(subshell)"],
    test_classes = ["fixture.FlatTest"],
    test_packages = ["selected"],
)

quarkus_app(
    name = "app",
    continuous_test = ":test",
    dev_build_args = ["--define=continuous_fixture=true"],
    deps = [":lib"],
)

# The original positional signature must remain valid.
quarkus_test("positional_test", ["tests/SelectedTest.java"], [":lib", "@maven//:JUNIT_LABEL", "@maven//:org_junit_jupiter_junit_jupiter_api"])
quarkus_app("without_tests", False, [], False, deps = [":lib"])
