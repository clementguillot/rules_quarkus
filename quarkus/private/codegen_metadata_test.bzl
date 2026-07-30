"Unit tests for code-generation metadata and property serialization."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":quarkus_codegen_impl.bzl", "codegen_input_dirs_for_test", "escape_property_for_test")

def _codegen_input_dirs_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        [
            "pkg/src/main/hello",
            "pkg/src/main/proto",
        ],
        codegen_input_dirs_for_test(
            [
                "pkg/src/main/proto/v1/alpha.proto",
                "pkg/src/main/proto/v2/beta.proto",
                "pkg/src/main/hello/greeting.hello",
            ],
            ["pkg/src/main"],
        ),
    )
    asserts.equals(
        env,
        ["pkg/src/main/proto"],
        codegen_input_dirs_for_test(
            ["pkg/src/main/proto/v1/greeting.proto"],
            [
                "pkg/src/main",
                "pkg/src/main/proto",
            ],
        ),
    )
    asserts.equals(
        env,
        ["pkg/schema"],
        codegen_input_dirs_for_test(
            ["pkg/schema/message.avsc"],
            ["pkg/schema"],
        ),
    )
    return unittest.end(env)

codegen_input_dirs_test = unittest.make(_codegen_input_dirs_test_impl)

def _escape_property_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "\\#\\ key\\!\\=\\:", escape_property_for_test("# key!=:"))
    return unittest.end(env)

escape_property_test = unittest.make(_escape_property_test_impl)

def codegen_metadata_test_suite():
    unittest.suite(
        "codegen_metadata_tests",
        codegen_input_dirs_test,
        escape_property_test,
    )
