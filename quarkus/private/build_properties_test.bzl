"""Unit tests for deterministic Quarkus build-property serialization helpers."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":build_properties.bzl", "build_property_key_error_for_test", "escape_property_for_test", "serialize_build_properties_for_test")

def _escape_property_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "\\#\\ key\\!\\=\\:\\t\\n\\r\\f\\\\",
        escape_property_for_test("# key!=:\t\n\r\f\\"),
    )
    return unittest.end(env)

escape_property_test = unittest.make(_escape_property_test_impl)

def _serialize_build_properties_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "a=first\nm=middle\nz=last\n",
        serialize_build_properties_for_test({
            "z": "last",
            "a": "first",
            "m": "middle",
        }),
        "serialization is sorted and byte-stable regardless of dict insertion order",
    )
    asserts.equals(env, "", serialize_build_properties_for_test({}))
    return unittest.end(env)

serialize_build_properties_test = unittest.make(_serialize_build_properties_test_impl)

def _build_property_key_error_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "",
        build_property_key_error_for_test({
            "key: with whitespace": "still representable",
            "quarkus.package.jar.type": "legacy-jar",
            "smoke.ext.prefix": "Declared:=\\ spaced",
        }),
        "spaces and colons survive both the properties file and quoted JVM argv channels",
    )
    asserts.equals(
        env,
        "build_properties contains an empty key; declared build-time configuration names must be non-empty",
        build_property_key_error_for_test({"": "value"}),
    )

    # A key holding '=' round-trips through the .properties file but is split
    # on its first '=' when passed as a `-Dkey=value` JVM flag.
    asserts.equals(
        env,
        "build_properties key 'a=b' contains '='; declared names must not contain '=' because JVM -D flags split on the first '='",
        build_property_key_error_for_test({"a=b": "c"}),
    )
    asserts.equals(
        env,
        "",
        build_property_key_error_for_test({"a b": "c"}),
    )
    asserts.equals(
        env,
        "",
        build_property_key_error_for_test({"a:b": "c"}),
    )
    return unittest.end(env)

build_property_key_error_test = unittest.make(_build_property_key_error_test_impl)

def build_properties_test_suite():
    unittest.suite(
        "build_properties_tests",
        build_property_key_error_test,
        escape_property_test,
        serialize_build_properties_test,
    )
