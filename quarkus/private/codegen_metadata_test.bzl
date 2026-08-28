"Unit tests for code-generation metadata."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    ":quarkus_codegen_impl.bzl",
    "codegen_input_dirs_for_test",
    "normalize_source_roots_for_test",
    "resource_entry_for_test",
    "resource_watch_dir_for_test",
)

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
    asserts.equals(
        env,
        ["proto", "schema"],
        codegen_input_dirs_for_test(
            ["proto/v1/greeting.proto", "schema/message.avsc"],
            ["."],
        ),
    )
    return unittest.end(env)

codegen_input_dirs_test = unittest.make(_codegen_input_dirs_test_impl)

def _source_roots_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        ["pkg/src/main", "pkg"],
        normalize_source_roots_for_test("pkg", ["src/main/", "src/main", "."]),
    )
    asserts.equals(env, ["."], normalize_source_roots_for_test("", [".", "."]))
    return unittest.end(env)

source_roots_test = unittest.make(_source_roots_test_impl)

def _resource_paths_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        "application.properties",
        resource_entry_for_test(
            "pkg/src/integration/resources/application.properties",
            "",
            "pkg",
        ),
    )
    asserts.equals(
        env,
        "config/application.properties",
        resource_entry_for_test(
            "pkg/java/config/application.properties",
            "",
            "pkg",
        ),
    )
    asserts.equals(
        env,
        "pkg/src/integration/resources",
        resource_watch_dir_for_test(
            "pkg/src/integration/resources/application.properties",
            "application.properties",
        ),
    )
    asserts.equals(
        env,
        "pkg/assets",
        resource_watch_dir_for_test(
            "pkg/assets/codegen.yaml",
            "pkg/assets/codegen.yaml",
        ),
    )
    return unittest.end(env)

resource_paths_test = unittest.make(_resource_paths_test_impl)

def codegen_metadata_test_suite():
    unittest.suite(
        "codegen_metadata_tests",
        codegen_input_dirs_test,
        resource_paths_test,
        source_roots_test,
    )
