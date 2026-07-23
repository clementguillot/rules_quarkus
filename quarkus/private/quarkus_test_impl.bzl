"""Implementation of the quarkus_test rule.

Runs @QuarkusTest-annotated JUnit 5 tests under Bazel by:
1. Assembling the runtime and deployment classpaths
2. At test time, invoking the quarkifier in test mode to serialize an
   ApplicationModel from the actual runfiles jar paths
3. Launching JUnit ConsoleLauncher with the serialized model path and
   Quarkus-required system properties

The two-phase approach (model generation at test time, not build time) ensures
that jar paths in the ApplicationModel match the actual runfiles locations.
"""

load("@bazel_skylib//lib:shell.bzl", "shell")
load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus/private:application_model_aspect.bzl", "has_quarkus_jacoco", "quarkus_application_model_aspect")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_extension_runtime_jars", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

def _build_test_args(test_packages, test_classes, fail_if_no_tests):
    """Builds JUnit ConsoleLauncher CLI arguments."""
    args = ["execute"]
    if fail_if_no_tests:
        args.append("--fail-if-no-tests")
    for pkg in test_packages:
        args.append("--select-package=" + pkg)
    for cls in test_classes:
        args.append("--select-class=" + cls)
    args.append("--exclude-classname=.*IT$")
    return " ".join(args)

def _quarkus_test_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_test rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

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

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    coverage_enabled = ctx.configuration.coverage_enabled
    coverage_files = []
    coverage_runfiles = None
    coverage_reporter_path = ""
    jacoco_runner_path = ""
    if coverage_enabled:
        coverage_reporter = ctx.attr._coverage_reporter[DefaultInfo]
        coverage_reporter_path = coverage_reporter.files_to_run.executable.short_path
        coverage_runfiles = coverage_reporter.default_runfiles
        jacoco_runner = ctx.attr._jacoco_runner[JavaInfo].runtime_output_jars[0]
        jacoco_runner_path = jacoco_runner.short_path
        coverage_files.append(jacoco_runner)

    launcher = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{app_name}": ctx.label.name,
            "%{classpath_file}": cp_file.short_path,
            "%{coverage_enabled}": "true" if coverage_enabled else "false",
            "%{coverage_reporter}": coverage_reporter_path,
            "%{direct_jars_file}": direct_jars_file.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{jvm_flags}": " ".join([shell.quote(f) for f in ctx.attr.jvm_flags]),
            "%{model_file}": model.short_path,
            "%{jacoco_runner}": jacoco_runner_path,
            "%{quarkus_jacoco_present}": "true" if has_quarkus_jacoco(ctx.attr.deps) else "false",
            "%{test_args}": _build_test_args(ctx.attr.test_packages, ctx.attr.test_classes, ctx.attr.fail_if_no_tests),
            "%{fail_if_no_tests}": "true" if ctx.attr.fail_if_no_tests else "false",
            "%{tool_jar}": tool_jar.short_path,
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )

    runfiles = ctx.runfiles(
        files = [cp_file, direct_jars_file, model, tool_jar] + coverage_files,
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

quarkus_test = rule(
    implementation = _quarkus_test_impl,
    test = True,
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
            cfg = "exec",
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
            default = "@bazel_tools//tools/test:lcov_merger",
            cfg = "exec",
            executable = True,
        ),
    },
    doc = """\
Internal rule — use quarkus_test() macro from @rules_quarkus//quarkus:defs.bzl instead.

Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.
At test time, the quarkifier serializes an ApplicationModel from the actual
runfiles jar paths, then QuarkusTestExtension uses it to bootstrap the
application in Mode.TEST.
""",
)
