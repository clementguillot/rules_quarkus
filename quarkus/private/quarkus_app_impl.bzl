"""Implementation of the quarkus_app rule.

Builds a Quarkus application by collecting the transitive runtime classpath
from java_library deps, invoking the Quarkifier tool to produce a Fast_Jar
output, and generating a launcher script for `bazel run`.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusAppInfo")
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath", "collect_source_jars")

def _quarkus_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_app rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    source_jars = collect_source_jars(ctx.attr.deps)
    output_dir = ctx.actions.declare_directory(ctx.label.name + "-quarkus-app")

    deployment_classpath = collect_runtime_classpath([ctx.attr.deployment_deps]) if ctx.attr.deployment_deps else depset()

    # Resolve the quarkifier deploy jar (fat/uber jar with all tool deps bundled).
    tool_jar = ctx.file.quarkifier_tool

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

    # Invoke: java -jar quarkifier_deploy.jar <args>
    # The deploy jar is a fat jar containing all tool classes + dependencies,
    # so no -cp assembly is needed. The tool handles classloader isolation
    # internally via the augment classloader + TCCL.
    jar_args = ctx.actions.args()
    jar_args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
    jar_args.add("-jar")
    jar_args.add(tool_jar)

    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [jar_args, args],
        inputs = depset(
            direct = [tool_jar],
            transitive = [runtime_classpath, deployment_classpath, java_runtime.files],
        ),
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
            "%{jvm_flags}": jvm_flags,
            "%{main_class_flag}": main_class_flag,
            "%{output_dir}": output_dir.short_path,
            "%{workspace}": ctx.workspace_name,
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
            source_jars = source_jars,
            quarkus_version = ctx.attr.quarkus_version,
        ),
    ]

quarkus_app_rule = rule(
    implementation = _quarkus_app_impl,
    executable = True,
    attrs = {
        "deployment_deps": attr.label(doc = "Deployment deps (set by macro)."),
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
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar (fat jar with all tool deps bundled).",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "version": attr.string(
            doc = "Application version shown in Quarkus startup banner.",
        ),
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
