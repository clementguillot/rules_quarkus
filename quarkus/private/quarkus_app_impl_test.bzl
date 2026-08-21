"Unit tests for package-type-specific quarkus_app behavior."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":quarkus_app_impl.bzl", "package_type_version_error_for_test", "runner_path_for_test")

def _runner_paths_test_impl(ctx):
    env = unittest.begin(ctx)
    for package_type in ("fast-jar", "mutable-jar", "aot-jar"):
        asserts.equals(env, "quarkus-app/quarkus-run.jar", runner_path_for_test(package_type))
    for package_type in ("uber-jar", "legacy-jar"):
        asserts.equals(env, "quarkus-run.jar", runner_path_for_test(package_type))
    return unittest.end(env)

runner_paths_test = unittest.make(_runner_paths_test_impl)

def _package_type_versions_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "", package_type_version_error_for_test("fast-jar", "3.27.4"))
    asserts.equals(env, "", package_type_version_error_for_test("aot-jar", "3.33.2"))
    asserts.equals(
        env,
        "package_type 'aot-jar' requires Quarkus 3.33; configured version is 3.27.4",
        package_type_version_error_for_test("aot-jar", "3.27.4"),
    )
    return unittest.end(env)

package_type_versions_test = unittest.make(_package_type_versions_test_impl)

def quarkus_app_impl_test_suite(name = "quarkus_app_impl_tests"):
    unittest.suite(
        name,
        package_type_versions_test,
        runner_paths_test,
    )
