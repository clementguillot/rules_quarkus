"Unit tests for Quarkus JUnit ConsoleLauncher argument construction."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":quarkus_dev_impl.bzl", "continuous_build_properties_for_test", "continuous_test_application_error_for_test")
load(":quarkus_test_impl.bzl", "build_property_jvm_flags_for_test", "build_test_args_for_test", "integration_version_error_for_test", "quarkus_jacoco_present_for_test", "test_resources_without_sources_error")

def _continuous_configuration_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, {"app": "unchanged"}, continuous_build_properties_for_test({"app": "unchanged"}, None))
    actual = continuous_build_properties_for_test(
        {"app": "value", "shared": "same"},
        struct(build_properties = {"shared": "same", "test": "round trip"}, test_classes = ["fixture.Outer$NestedTest"], test_packages = ["selected"]),
    )
    asserts.equals(env, "value", actual["app"])
    asserts.equals(env, "round trip", actual["test"])
    asserts.equals(env, "(^fixture\\.Outer\\$NestedTest$|^selected\\..*$)", actual["quarkus.test.include-pattern"])
    asserts.equals(env, "(^$|.*IT$)", actual["quarkus.test.exclude-pattern"])
    filtered = continuous_build_properties_for_test(
        {"quarkus.test.include-pattern": ".*SelectedTest"},
        struct(build_properties = {}, test_classes = [], test_packages = ["selected"]),
    )
    asserts.equals(env, "(?=(?:.*SelectedTest)$)(^selected\\..*$)", filtered["quarkus.test.include-pattern"])
    return unittest.end(env)

continuous_configuration_test = unittest.make(_continuous_configuration_test_impl)

def _continuous_application_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "",
        continuous_test_application_error_for_test(
            ["//app:main"],
            ["//test:helper", "//app:main"],
        ),
    )
    mismatch = continuous_test_application_error_for_test(
        ["//app:main"],
        ["//other:main"],
    )
    asserts.true(env, "//app:main" in mismatch)
    asserts.true(env, "//other:main" in mismatch)
    asserts.true(env, "direct TEST model dependencies" in mismatch)
    return unittest.end(env)

continuous_application_test = unittest.make(_continuous_application_test_impl)

def _test_resources_validation_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "", test_resources_without_sources_error(["Test.java"], ["test.properties"]))
    asserts.equals(env, "", test_resources_without_sources_error(None, []))
    error = test_resources_without_sources_error(None, ["test.properties"])
    asserts.true(env, "require inline srcs" in error)
    asserts.true(env, "precompiled java_library" in error)
    return unittest.end(env)

test_resources_validation_test = unittest.make(_test_resources_validation_test_impl)

def _build_property_jvm_flags_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        ["'-Da=two words'", "'-Dkey: with whitespace=value'", "'-Dz=last'"],
        build_property_jvm_flags_for_test({
            "z": "last",
            "a": "two words",
            "key: with whitespace": "value",
        }),
    )
    return unittest.end(env)

build_property_jvm_flags_test = unittest.make(_build_property_jvm_flags_test_impl)

def _unit_test_args_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "execute --fail-if-no-tests --select-package=smoke --select-class=smoke.GreetingResourceTest --exclude-classname=.*IT$",
        build_test_args_for_test(
            ["smoke"],
            ["smoke.GreetingResourceTest"],
            True,
        ),
    )
    return unittest.end(env)

unit_test_args_test = unittest.make(_unit_test_args_test_impl)

def _integration_test_args_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "execute --fail-if-no-tests --select-package=smoke --select-class=smoke.ExplicitIntegrationTest --include-classname=(.*IT$|^smoke\\.ExplicitIntegrationTest$)",
        build_test_args_for_test(
            ["smoke"],
            ["smoke.ExplicitIntegrationTest"],
            True,
            integration = True,
        ),
    )
    return unittest.end(env)

integration_test_args_test = unittest.make(_integration_test_args_test_impl)

def _integration_version_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "",
        integration_version_error_for_test("integration_test", "3.33.2", "//:app", "3.33.2"),
    )
    asserts.equals(
        env,
        "quarkus_integration_test rule 'integration_test' uses Quarkus 3.33.2, but app '//:app' was built with Quarkus 3.27.4",
        integration_version_error_for_test("integration_test", "3.33.2", "//:app", "3.27.4"),
    )
    return unittest.end(env)

integration_version_test = unittest.make(_integration_version_test_impl)

def _quarkus_jacoco_present_test_impl(ctx):
    env = unittest.begin(ctx)

    # Unit tests keep quarkus-jacoco's own report; the launcher, not this flag,
    # is what turns it off while Bazel coverage collects.
    asserts.true(env, quarkus_jacoco_present_for_test(False, True))
    asserts.false(env, quarkus_jacoco_present_for_test(False, False))

    # Integration tests run the app out of process, so quarkus-jacoco cannot report.
    asserts.false(env, quarkus_jacoco_present_for_test(True, True))
    return unittest.end(env)

quarkus_jacoco_present_test = unittest.make(_quarkus_jacoco_present_test_impl)

def quarkus_test_impl_test_suite(name = "quarkus_test_impl_tests"):
    unittest.suite(
        name,
        build_property_jvm_flags_test,
        continuous_application_test,
        continuous_configuration_test,
        integration_test_args_test,
        integration_version_test,
        quarkus_jacoco_present_test,
        test_resources_validation_test,
        unit_test_args_test,
    )
