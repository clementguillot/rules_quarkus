"""Implementation of the quarkus_app rule.

Builds a Quarkus application by collecting the transitive runtime classpath
from java_library deps, invoking the Quarkifier tool to produce a Fast_Jar
output, and generating a launcher script for `bazel run`.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusAppInfo")
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath", "collect_source_dirs")

_QUARKIFIER_MAIN = "com.clementguillot.quarkifier.QuarkifierLauncher"

def _quarkus_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_app rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    source_dirs = collect_source_dirs(ctx.attr.deps)
    output_dir = ctx.actions.declare_directory(ctx.label.name + "-quarkus-app")

    tool_classpath = collect_runtime_classpath([ctx.attr.quarkifier_tool])
    deployment_classpath = collect_runtime_classpath([ctx.attr.deployment_deps]) if ctx.attr.deployment_deps else depset()
    all_jars = depset(transitive = [tool_classpath, runtime_classpath, deployment_classpath])

    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]

    # Quarkifier CLI args
    args = ctx.actions.args()
    args.add_joined("--application-classpath", runtime_classpath, join_with = ":")
    args.add_joined("--deployment-classpath", depset(transitive = [runtime_classpath, deployment_classpath]), join_with = ":")
    args.add("--output-dir", output_dir.path)
    args.add("--expected-quarkus-version", ctx.attr.quarkus_version)
    args.add("--app-name", ctx.label.name)
    if ctx.attr.version:
        args.add("--app-version", ctx.attr.version)

    # JVM + classpath args
    cp_args = ctx.actions.args()
    cp_args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
    cp_args.add("-cp")
    cp_args.add_joined(all_jars, join_with = ":")
    cp_args.add(_QUARKIFIER_MAIN)

    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [cp_args, args],
        inputs = depset(transitive = [all_jars, java_runtime.files]),
        outputs = [output_dir],
        mnemonic = "QuarkusAugmentation",
        progress_message = "Running Quarkus augmentation for %{label}",
    )

    # Launcher script
    launcher = ctx.actions.declare_file(ctx.label.name + "_launcher.sh")
    jvm_flags = " ".join(ctx.attr.jvm_flags)
    main_class_flag = ""
    if ctx.attr.main_class:
        main_class_flag = "-Dquarkus.package.main-class=" + ctx.attr.main_class

    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{workspace}": ctx.workspace_name,
            "%{output_dir}": output_dir.short_path,
            "%{jvm_flags}": jvm_flags,
            "%{main_class_flag}": main_class_flag,
        },
        is_executable = True,
    )

    runfiles = ctx.runfiles(files = [output_dir])

    return [
        DefaultInfo(
            executable = launcher,
            files = depset([output_dir, launcher]),
            runfiles = runfiles,
        ),
        QuarkusAppInfo(
            fast_jar_dir = output_dir,
            application_classpath = runtime_classpath,
            source_dirs = source_dirs,
            quarkus_version = ctx.attr.quarkus_version,
            application_properties = None,
        ),
    ]

quarkus_app_rule = rule(
    implementation = _quarkus_app_impl,
    executable = True,
    attrs = {
        "deps": attr.label_list(
            mandatory = True,
            providers = [JavaInfo],
            doc = "java_library and Maven artifact targets.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running the application.",
        ),
        "main_class": attr.string(
            doc = "Override main class. Defaults to the Quarkus runner.",
        ),
        "version": attr.string(
            doc = "Application version shown in Quarkus startup banner.",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "quarkifier_tool": attr.label(providers = [JavaInfo], doc = "Quarkifier tool (set by macro)."),
        "deployment_deps": attr.label(doc = "Deployment deps (set by macro)."),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:launcher.sh.tpl"),
            allow_single_file = True,
        ),
    },
    doc = """\
Internal rule — use quarkus_app() macro from @rules_quarkus_toolchains//:defs.bzl instead.
""",
)
