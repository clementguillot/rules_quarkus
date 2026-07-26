"""Implementation of the quarkus_test and quarkus_integration_test rules.

Runs Quarkus JUnit 5 tests under Bazel by:
1. Assembling the runtime and deployment classpaths
2. At test time, invoking the quarkifier in test mode to serialize an
   ApplicationModel from the actual runfiles jar paths
3. Launching JUnit ConsoleLauncher either with @QuarkusTest's in-process
   bootstrap or with metadata for an @QuarkusIntegrationTest artifact

The two-phase approach (model generation at test time, not build time) ensures
that jar paths in the ApplicationModel match the actual runfiles locations.
"""

load("@bazel_skylib//lib:shell.bzl", "shell")
load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusAppInfo", "QuarkusNativeInfo")
load("//quarkus/private:application_model_aspect.bzl", "has_maven_artifact", "quarkus_application_model_aspect")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_extension_runtime_jars", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

def _regex_escape_class_name(class_name):
    return class_name.replace("\\", "\\\\").replace(".", "\\.").replace("$", "\\$")

def _build_test_args(test_packages, test_classes, fail_if_no_tests, integration = False):
    """Builds JUnit ConsoleLauncher CLI arguments."""
    args = ["execute"]
    if fail_if_no_tests:
        args.append("--fail-if-no-tests")
    for pkg in test_packages:
        args.append("--select-package=" + pkg)
    for cls in test_classes:
        args.append("--select-class=" + cls)
    if integration:
        include_patterns = [".*IT$"] + ["^" + _regex_escape_class_name(cls) + "$" for cls in test_classes]
        args.append("--include-classname=(" + "|".join(include_patterns) + ")")
    else:
        args.append("--exclude-classname=.*IT$")
    return " ".join(args)

def _integration_version_error(rule_name, test_version, app_label, app_version):
    if test_version == app_version:
        return ""
    return "quarkus_integration_test rule '{}' uses Quarkus {}, but app '{}' was built with Quarkus {}".format(
        rule_name,
        test_version,
        app_label,
        app_version,
    )

def _integration_artifact(ctx):
    app = ctx.attr.app
    if QuarkusAppInfo in app:
        info = app[QuarkusAppInfo]
        artifact_type = "jar"
        artifact = info.fast_jar_dir
        artifact_path = artifact.short_path + "/quarkus-app/quarkus-run.jar"
        quarkus_version = info.quarkus_version
    elif QuarkusNativeInfo in app:
        info = app[QuarkusNativeInfo]
        artifact_type = "native"
        artifact = info.binary
        artifact_path = artifact.short_path
        quarkus_version = info.quarkus_version
    else:
        fail("quarkus_integration_test rule '{}' requires 'app' to provide QuarkusAppInfo or QuarkusNativeInfo".format(ctx.label.name))

    version_error = _integration_version_error(
        ctx.label.name,
        ctx.attr.quarkus_version,
        app.label,
        quarkus_version,
    )
    if version_error:
        fail(version_error)

    return struct(
        artifact = artifact,
        artifact_path = artifact_path,
        artifact_type = artifact_type,
    )

def _test_impl(ctx, integration):
    if not ctx.attr.deps:
        rule_name = "quarkus_integration_test" if integration else "quarkus_test"
        fail("{} rule '{}' requires at least one dependency in 'deps'".format(rule_name, ctx.label.name))
    if integration and ctx.configuration.coverage_enabled:
        fail("bazel coverage is not supported for quarkus_integration_test; use quarkus_test for Bazel LCOV coverage")

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps + ctx.attr.model_private_deps)
    conditional_classpath = collect_runtime_classpath([ctx.attr.conditional_deps])
    deploy_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)
    model = assemble_application_model(ctx, ctx.attr.deps, runtime_classpath, conditional_classpath, deploy_classpath, "test")

    # Runtime classpath (for both JUnit -cp and quarkifier --application-classpath)
    # and the user-built jars Quarkus must scan (comma-separated, for
    # OUTPUT_SOURCES_DIR).
    # Extension runtime jars are excluded from direct_jars: leaving them as app
    # roots exposes their @ConfigRoot classes to both classloaders (SRCFG00027).
    cp_file = write_runfiles_paths_file(ctx, "_cp.txt", runtime_classpath, ":")
    ext_rt_jars = collect_extension_runtime_jars(ctx.attr.deps)
    direct_jars_file = write_runfiles_paths_file(ctx, "_direct_jars.txt", collect_local_app_jars(ctx.attr.deps, runtime_classpath, ext_rt_jars), ",")
    coverage_jars_file = write_runfiles_paths_file(ctx, "_coverage_jars.txt", collect_local_app_jars(ctx.attr.deps, runtime_classpath), ",")

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    coverage_enabled = ctx.configuration.coverage_enabled
    coverage_files = []
    coverage_runfiles = None
    coverage_reporter_path = ""
    jacoco_runner_path = ""
    if coverage_enabled and not integration:
        coverage_reporter = ctx.attr._coverage_reporter[DefaultInfo]
        coverage_reporter_path = coverage_reporter.files_to_run.executable.short_path
        coverage_runfiles = coverage_reporter.default_runfiles
        jacoco_runner = ctx.attr._jacoco_runner[JavaInfo].runtime_output_jars[0]
        jacoco_runner_path = jacoco_runner.short_path
        coverage_files.append(jacoco_runner)

    integration_artifact = _integration_artifact(ctx) if integration else None
    launcher = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{app_name}": ctx.label.name,
            "%{artifact_path}": integration_artifact.artifact_path if integration else "",
            "%{artifact_type}": integration_artifact.artifact_type if integration else "",
            "%{classpath_file}": cp_file.short_path,
            "%{coverage_enabled}": "true" if coverage_enabled else "false",
            "%{coverage_jars_file}": coverage_jars_file.short_path,
            "%{coverage_reporter}": coverage_reporter_path,
            "%{direct_jars_file}": direct_jars_file.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{jvm_flags}": " ".join([shell.quote(f) for f in ctx.attr.jvm_flags]),
            "%{model_file}": model.short_path,
            "%{jacoco_runner}": jacoco_runner_path,
            "%{quarkus_jacoco_present}": "true" if has_maven_artifact(ctx.attr.deps, "io.quarkus", "quarkus-jacoco") else "false",
            "%{test_args}": _build_test_args(ctx.attr.test_packages, ctx.attr.test_classes, ctx.attr.fail_if_no_tests, integration),
            "%{test_kind}": "integration" if integration else "quarkus",
            "%{tool_jar}": tool_jar.short_path,
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )

    direct_runfiles = [cp_file, coverage_jars_file, direct_jars_file, model, tool_jar] + coverage_files
    if integration:
        direct_runfiles.append(integration_artifact.artifact)
    runfiles = ctx.runfiles(
        files = direct_runfiles,
        transitive_files = depset(
            transitive = [runtime_classpath, conditional_classpath, deploy_classpath, java_runtime.files],
        ),
    )
    if coverage_runfiles:
        runfiles = runfiles.merge(coverage_runfiles)

    return [
        DefaultInfo(executable = launcher, runfiles = runfiles),
        OutputGroupInfo(quarkus_model = depset([model])),
    ]

def _quarkus_test_impl(ctx):
    return _test_impl(ctx, False)

def _quarkus_integration_test_impl(ctx):
    return _test_impl(ctx, True)

def _test_attrs(integration = False):
    attrs = {
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(mandatory = True, providers = [JavaInfo]),
        "deployment_deps": attr.label(doc = "Resolved Quarkus deployment closure (set by macro)."),
        "deployment_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal deployment resolver graph catalog (set by macro).",
        ),
        "platform_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal Quarkus platform metadata catalog (set by macro).",
        ),
        "platform_properties": attr.label(
            mandatory = True,
            doc = "Internal Quarkus platform property files (set by macro).",
        ),
        "deps": attr.label_list(
            mandatory = True,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
            ],
            providers = [JavaInfo],
            doc = "Test java_library targets. Transitive deps (app code, quarkus-junit, etc.) are included automatically.",
        ),
        "fail_if_no_tests": attr.bool(
            default = True,
            doc = "Fail the test if zero tests are discovered/executed. Set to False for targets where an empty test set is acceptable.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running tests.",
        ),
        "model_private_deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Internal test compile/launcher dependencies omitted from ApplicationModel semantics.",
        ),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar (fat jar with all tool deps bundled).",
        ),
        "runtime_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal runtime resolver graph catalog (set by macro).",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "test_classes": attr.string_list(
            doc = "Fully-qualified test class names to run (--select-class).",
        ),
        "test_packages": attr.string_list(
            doc = "Java packages to scan for test classes (--select-package).",
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
        "_coverage_reporter": attr.label(
            default = Label("//quarkus/private:bazel_jacoco_reporter"),
            cfg = config.exec(exec_group = "test"),
            executable = True,
        ),
        "_jacoco_runner": attr.label(
            default = "@bazel_tools//tools/jdk:JacocoCoverageRunner",
            providers = [JavaInfo],
        ),
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:test_launcher.sh.tpl"),
            allow_single_file = True,
        ),
        "_lcov_merger": attr.label(
            default = configuration_field(fragment = "coverage", name = "output_generator"),
            cfg = config.exec(exec_group = "test"),
            executable = True,
        ),
    }
    if integration:
        attrs["app"] = attr.label(
            mandatory = True,
            providers = [[QuarkusAppInfo], [QuarkusNativeInfo]],
            doc = "Packaged quarkus_app or quarkus_app(native=True) target to launch.",
        )
    return attrs

quarkus_test = rule(
    implementation = _quarkus_test_impl,
    test = True,
    attrs = _test_attrs(),
    doc = """\
Internal rule — use quarkus_test() macro from @rules_quarkus//quarkus:defs.bzl instead.

Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.
At test time, the quarkifier serializes an ApplicationModel from the actual
runfiles jar paths, then QuarkusTestExtension uses it to bootstrap the
application in Mode.TEST.
""",
)

quarkus_integration_test = rule(
    implementation = _quarkus_integration_test_impl,
    test = True,
    attrs = _test_attrs(integration = True),
    doc = """\
Internal rule — use quarkus_integration_test() from @rules_quarkus//quarkus:defs.bzl.

Runs @QuarkusIntegrationTest-annotated JUnit 5 tests against a packaged Fast
JAR or native executable while retaining Quarkus test resources and Dev
Services through the serialized TEST-mode ApplicationModel.
""",
)

build_test_args_for_test = _build_test_args
integration_version_error_for_test = _integration_version_error
