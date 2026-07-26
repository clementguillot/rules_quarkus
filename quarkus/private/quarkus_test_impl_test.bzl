"Unit tests for Quarkus JUnit ConsoleLauncher argument construction."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":quarkus_test_impl.bzl", "build_test_args_for_test", "integration_version_error_for_test")

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

def quarkus_test_impl_test_suite(name = "quarkus_test_impl_tests"):
    unittest.suite(
        name,
        integration_test_args_test,
        integration_version_test,
        unit_test_args_test,
    )
