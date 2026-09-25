"Unit tests for code-generation metadata."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    ":quarkus_codegen_impl.bzl",
    "normalize_source_roots_for_test",
    "resource_entry_for_test",
)

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
    return unittest.end(env)

resource_paths_test = unittest.make(_resource_paths_test_impl)

def codegen_metadata_test_suite():
    unittest.suite(
        "codegen_metadata_tests",
        resource_paths_test,
        source_roots_test,
    )
