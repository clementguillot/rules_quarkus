"""Unit tests for deterministic Quarkus build-property serialization helpers."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":build_properties.bzl", "escape_property_for_test")

def _escape_property_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "\\#\\ key\\!\\=\\:\\t\\n\\r\\f\\\\",
        escape_property_for_test("# key!=:\t\n\r\f\\"),
    )
    return unittest.end(env)

escape_property_test = unittest.make(_escape_property_test_impl)

def build_properties_test_suite():
    unittest.suite(
        "build_properties_tests",
        escape_property_test,
    )
