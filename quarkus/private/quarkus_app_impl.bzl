"""Implementation of the quarkus_app rule.

Builds a Quarkus application by collecting the transitive runtime classpath
from java_library deps, invoking the Quarkifier tool to produce a Fast_Jar
output, and generating a launcher script for `bazel run`.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusAppInfo")
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:augmentation.bzl", "run_augmentation")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_local_app_jars", "collect_runtime_classpath", "collect_source_jars", "quarkus_extension_deployment_classpath_aspect")
load("//quarkus/private:coverage_transition.bzl", "disable_coverage_transition", "single_transitioned_target")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

_PACKAGE_TYPES = [
    "fast-jar",
    "uber-jar",
    "mutable-jar",
    "legacy-jar",
    "aot-jar",
]

def _runner_path(package_type):
    if package_type in ("fast-jar", "mutable-jar", "aot-jar"):
        return "quarkus-app/quarkus-run.jar"
    return "quarkus-run.jar"

def _package_type_version_error(package_type, quarkus_version):
    if package_type == "aot-jar" and quarkus_version.startswith("3.27."):
        return "package_type 'aot-jar' requires Quarkus 3.33; configured version is {}".format(quarkus_version)
    return ""

def _shell_quote(s):
    """Shell-quotes a string so it survives word splitting."""
    return "'" + s.replace("'", "'\\''") + "'"

def _write_launcher(ctx, output_dir, java_runtime):
    launcher = ctx.actions.declare_file(ctx.label.name + "_launcher.sh")
    main_class_flag = _shell_quote("-Dquarkus.package.main-class=" + ctx.attr.main_class) if ctx.attr.main_class else ""
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{jvm_flags}": " ".join([_shell_quote(f) for f in ctx.attr.jvm_flags]),
            "%{main_class_flag}": main_class_flag,
            "%{output_dir}": output_dir.short_path,
            "%{runner_path}": _runner_path(ctx.attr.package_type),
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )
    return launcher

def _quarkus_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_app rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))
    package_type_error = _package_type_version_error(ctx.attr.package_type, ctx.attr.quarkus_version)
    if package_type_error:
        fail(package_type_error)

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    conditional_classpath = collect_runtime_classpath([single_transitioned_target(ctx.attr.conditional_deps)])
    deployment_classpath = collect_deployment_classpath(single_transitioned_target(ctx.attr.deployment_deps), ctx.attr.deps)
    local_jars = collect_local_app_jars(ctx.attr.deps, runtime_classpath)
    model = assemble_application_model(ctx, ctx.attr.deps, runtime_classpath, conditional_classpath, deployment_classpath, "normal")

    output_dir = ctx.actions.declare_directory(ctx.label.name + "-quarkus-app")
    build_properties = run_augmentation(
        ctx,
        output_dir,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        package_type = ctx.attr.package_type,
        local_jars = local_jars,
        model_file = model,
    )

    # The launcher runs the app with the target-config Java runtime from
    # runfiles instead of whatever `java` happens to be on PATH.
    target_java_runtime = ctx.attr._target_java_runtime[java_common.JavaRuntimeInfo]
    launcher = _write_launcher(ctx, output_dir, target_java_runtime)

    return [
        DefaultInfo(
            executable = launcher,
            files = depset([output_dir, launcher]),
            runfiles = ctx.runfiles(
                files = [output_dir],
                transitive_files = target_java_runtime.files,
            ),
        ),
        OutputGroupInfo(
            quarkus_app = depset([output_dir]),
            quarkus_build_properties = depset([build_properties]),
            quarkus_model = depset([model]),
        ),
        QuarkusAppInfo(
            output_dir = output_dir,
            fast_jar_dir = output_dir,
            package_type = ctx.attr.package_type,
            runner_path = _runner_path(ctx.attr.package_type),
            application_classpath = runtime_classpath,
            source_jars = collect_source_jars(ctx.attr.deps),
            quarkus_version = ctx.attr.quarkus_version,
        ),
    ]

quarkus_app_rule = rule(
    implementation = _quarkus_app_impl,
    executable = True,
    attrs = {
        "build_properties": attr.string_dict(
            doc = "Declared build-time properties passed hermetically to every Quarkus augmentation lifecycle.",
        ),
        "conditional_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal conditional dependency graph catalog (set by macro).",
        ),
        "conditional_deps": attr.label(
            mandatory = True,
            cfg = disable_coverage_transition,
            providers = [JavaInfo],
            doc = "Internal conditional candidate closure (set by macro).",
        ),
        "deployment_deps": attr.label(
            cfg = disable_coverage_transition,
            doc = "Resolved Quarkus deployment closure (set by macro).",
        ),
        "deps": attr.label_list(
            mandatory = True,
            cfg = disable_coverage_transition,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
            ],
            providers = [JavaInfo],
            doc = "java_library and Maven artifact targets.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running the application.",
        ),
        "main_class": attr.string(
            doc = "Override main class. Defaults to the Quarkus runner.",
        ),
        "package_type": attr.string(
            default = "fast-jar",
            values = _PACKAGE_TYPES,
            doc = "JVM package type: fast-jar, uber-jar, mutable-jar, legacy-jar, or aot-jar.",
        ),
        "deployment_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal deployment resolver graph catalog (set by macro).",
        ),
        "deployment_artifacts": attr.label(
            mandatory = True,
            doc = "Internal non-JAR deployment artifacts (set by macro).",
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
        "version": attr.string(
            doc = "Application version shown in Quarkus startup banner.",
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            cfg = "exec",
        ),
        "_target_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            # Deliberately target-config (the attr default): this runtime ships
            # in the runfiles and runs the app, so under cross-compilation it
            # must match the target platform, not the exec platform.
            doc = "Target-config Java runtime used by the launcher at run time.",
        ),
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:launcher.sh.tpl"),
            allow_single_file = True,
        ),
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        ),
    },
    doc = """\
Internal rule — use quarkus_app() macro from @rules_quarkus//quarkus:defs.bzl instead.
""",
)

# Exported only for Starlark unit tests.
package_type_version_error_for_test = _package_type_version_error
runner_path_for_test = _runner_path
