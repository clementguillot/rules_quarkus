load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_java_library", "quarkus_test")

quarkus_java_library(
    name = "lib",
    srcs = ["src/main/java/fixture/Main.java"],
    codegen_srcs = ["src/main/hello/main.hello"],
    resources = ["src/main/resources/app-resource.txt"],
    deps = [
        "//dep:value",
        "//ext/runtime",
        "@maven//:io_quarkus_quarkus_rest",
    ],
)

quarkus_java_library(
    name = "test_codegen",
    testonly = True,
    codegen_mode = "test",
    codegen_srcs = ["src/test/hello/test.hello"],
    deps = [":lib"],
)

quarkus_test(
    name = "test",
    srcs = [
        "tests/ExcludedTest.java",
        "tests/FlatTest.java",
        "tests/SelectedTest.java",
    ],
    build_properties = {"fixture.property": "round trip"},
    jvm_flags = ["-Dfixture.jvm=quote '$dollar `backtick` $(subshell)"],
    resources = [
        "src/test/resources/declared.tmp",
        "src/test/resources/input.txt",
    ],
    test_classes = ["fixture.FlatTest"],
    test_packages = ["selected"],
    deps = [
        ":lib",
        ":test_codegen",
        "//helper",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
    ],
)

quarkus_app(
    name = "app",
    continuous_test = ":test",
    dev_build_args = ["--define=continuous_fixture=true"],
    deps = [":lib"],
)

# The original positional signature must remain valid.
quarkus_test(
    "positional_test",
    ["tests/SelectedTest.java"],
    [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
    ],
)

quarkus_app(
    "without_tests",
    False,
    [],
    False,
    deps = [":lib"],
)
