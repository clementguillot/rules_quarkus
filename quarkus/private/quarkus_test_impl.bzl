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
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")

def _build_test_args(test_packages, test_classes):
    """Builds JUnit ConsoleLauncher CLI arguments."""
    args = ["execute", "--fail-if-no-tests"]
    for pkg in test_packages:
        args.append("--select-package=" + pkg)
    for cls in test_classes:
        args.append("--select-class=" + cls)
    args.append("--exclude-classname=.*IT$")
    return " ".join(args)

def _quarkus_test_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_test rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    deploy_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)

    # Runtime classpath (for both JUnit -cp and quarkifier --application-classpath),
    # deployment classpath (for quarkifier only, NOT on JUnit -cp), and the
    # user-built jars Quarkus must scan (comma-separated, for OUTPUT_SOURCES_DIR).
    cp_file = write_runfiles_paths_file(ctx, "_cp.txt", runtime_classpath, ":")
    deploy_cp_file = write_runfiles_paths_file(ctx, "_deploy_cp.txt", deploy_classpath, ":")
    direct_jars_file = write_runfiles_paths_file(ctx, "_direct_jars.txt", collect_local_app_jars(ctx.attr.deps, runtime_classpath), ",")

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]

    launcher = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{app_name}": ctx.label.name,
            "%{classpath_file}": cp_file.short_path,
            "%{deploy_cp_file}": deploy_cp_file.short_path,
            "%{direct_jars_file}": direct_jars_file.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{jvm_flags}": " ".join([shell.quote(f) for f in ctx.attr.jvm_flags]),
            "%{quarkus_version}": ctx.attr.quarkus_version,
            "%{test_args}": _build_test_args(ctx.attr.test_packages, ctx.attr.test_classes),
            "%{tool_jar}": tool_jar.short_path,
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )

    runfiles = ctx.runfiles(
        files = [cp_file, deploy_cp_file, direct_jars_file, tool_jar],
        transitive_files = depset(
            transitive = [runtime_classpath, deploy_classpath, java_runtime.files],
        ),
    )

    return [DefaultInfo(executable = launcher, runfiles = runfiles)]

quarkus_test = rule(
    implementation = _quarkus_test_impl,
    test = True,
    attrs = {
        "deployment_deps": attr.label(doc = "Resolved Quarkus deployment closure (set by macro)."),
        "deps": attr.label_list(
            mandatory = True,
            aspects = [quarkus_extension_deployment_classpath_aspect],
            providers = [JavaInfo],
            doc = "Test java_library targets. Transitive deps (app code, quarkus-junit, etc.) are included automatically.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running tests.",
        ),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar (fat jar with all tool deps bundled).",
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
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:test_launcher.sh.tpl"),
            allow_single_file = True,
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
