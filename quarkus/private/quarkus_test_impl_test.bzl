"Unit tests for Quarkus JUnit ConsoleLauncher argument construction."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":continuous_test.bzl", "continuous_build_properties", "continuous_selection_error", "merge_continuous_build_properties", "merge_continuous_jvm_flags", "merge_continuous_mappings")
load(":quarkus_test_impl.bzl", "build_property_jvm_flags_for_test", "build_test_args_for_test", "integration_version_error_for_test", "quarkus_jacoco_present_for_test", "test_resources_without_sources_error")

def _continuous_configuration_test_impl(ctx):
    env = unittest.begin(ctx)
    actual = continuous_build_properties(
        {"app": "value", "shared": "same"},
        struct(build_properties = {"shared": "same", "test": "round trip"}, test_classes = ["fixture.Outer$NestedTest"], test_packages = ["selected"]),
    )
    asserts.equals(env, "value", actual["app"])
    asserts.equals(env, "round trip", actual["test"])

    # Quarkus ignores exclude-pattern once include-pattern is set: *IT exclusion lives in the include.
    asserts.equals(env, "(?!.*IT$)(^fixture\\.Outer\\$NestedTest$|^selected\\..*$)", actual["quarkus.test.include-pattern"])
    asserts.false(env, "quarkus.test.exclude-pattern" in actual)
    unselected = continuous_build_properties(
        {},
        struct(build_properties = {}, test_classes = [], test_packages = []),
    )

    # Without selectors, application.properties and Quarkus' default patterns stay in charge.
    asserts.equals(env, {}, unselected)
    filtered = continuous_build_properties(
        {"quarkus.test.include-pattern": ".*SelectedTest"},
        struct(build_properties = {}, test_classes = [], test_packages = ["selected"]),
    )
    asserts.equals(env, "(?!.*IT$)(?=(?:.*SelectedTest)$)(^selected\\..*$)", filtered["quarkus.test.include-pattern"])
    return unittest.end(env)

continuous_configuration_test = unittest.make(_continuous_configuration_test_impl)

def _continuous_merge_test_impl(ctx):
    env = unittest.begin(ctx)

    # Two-token flags survive: de-duplicating "--add-opens" would orphan its second value.
    asserts.equals(
        env,
        ["--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-opens", "java.base/java.util=ALL-UNNAMED"],
        merge_continuous_jvm_flags([
            ["--add-opens", "java.base/java.lang=ALL-UNNAMED"],
            ["--add-opens", "java.base/java.util=ALL-UNNAMED"],
        ]),
    )
    merged = merge_continuous_build_properties(["//:a", "//m:b"], [{"k": "v", "a": "1"}, {"k": "v", "b": "2"}])
    asserts.equals(env, "", merged.error)
    asserts.equals(env, {"k": "v", "a": "1", "b": "2"}, merged.properties)
    conflict = merge_continuous_build_properties(["//:a", "//m:b"], [{"k": "one"}, {"k": "two"}])
    asserts.true(env, "'//:a' and '//m:b'" in conflict.error)
    asserts.true(env, "'k'" in conflict.error)
    mappings = merge_continuous_mappings(
        [[{"coordinate": "g:a", "targetId": "//ext:a"}], [{"coordinate": "g:a", "targetId": "//ext:a"}]],
        "coordinate",
        "targetId",
        "local deployment coordinate",
    )
    asserts.equals(env, "", mappings.error)
    asserts.equals(env, [{"coordinate": "g:a", "targetId": "//ext:a"}], mappings.items)
    clash = merge_continuous_mappings(
        [[{"coordinate": "g:a", "targetId": "//ext:a"}], [{"coordinate": "g:a", "targetId": "//ext:other"}]],
        "coordinate",
        "targetId",
        "local deployment coordinate",
    )
    asserts.true(env, "local deployment coordinate 'g:a' maps to multiple targets" in clash.error)
    return unittest.end(env)

continuous_merge_test = unittest.make(_continuous_merge_test_impl)

def _continuous_selection_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "", continuous_selection_error(["//:a", "//m:b"], [0, 0]))
    asserts.equals(env, "", continuous_selection_error(["//:a", "//m:b"], [2, 1]))
    mixed = continuous_selection_error(["//:a", "//m:b"], [2, 0])
    asserts.true(env, "//m:b" in mixed)
    asserts.false(env, "//:a" in mixed)
    asserts.true(env, "single test selection" in mixed)
    return unittest.end(env)

continuous_selection_test = unittest.make(_continuous_selection_test_impl)

def _test_resources_validation_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "", test_resources_without_sources_error(["Test.java"], ["test.properties"]))
    asserts.equals(env, "", test_resources_without_sources_error(None, []))

    # An initially empty glob still creates the test library that packages the resources.
    asserts.equals(env, "", test_resources_without_sources_error([], ["test.properties"]))
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
        continuous_configuration_test,
        continuous_merge_test,
        continuous_selection_test,
        integration_test_args_test,
        integration_version_test,
        quarkus_jacoco_present_test,
        test_resources_validation_test,
        unit_test_args_test,
    )
