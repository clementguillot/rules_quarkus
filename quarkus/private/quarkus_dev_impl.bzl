"""Implementation of the quarkus_dev rule.

Launches a Quarkus application in dev mode with the Quarkus Dev UI.
The process blocks until terminated (Ctrl+C / SIGTERM).

When source directories are detected in deps, the rule also wires a Java
file watcher (BazelFileWatcher) that monitors source files, triggers
incremental `bazel build` on changes, and syncs fresh .class files to a
mutable directory for Quarkus hot-reload.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_local_app_jars", "collect_resource_dir_paths", "collect_runtime_classpath", "collect_source_dir_paths", "is_local_artifact", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

def _collect_bazel_targets(deps):
    """Collects local workspace target labels for the file watcher's `bazel build`.

    Args:
        deps: List of targets providing JavaInfo.
    Returns:
        A deduplicated list of target label strings (e.g., ["//pkg:lib"]).
    """
    targets = []
    seen = {}
    for dep in deps:
        if JavaInfo not in dep or dep.label.workspace_name:
            continue

        # str(label) is "@@//pkg:name" (or "@//pkg:name" pre-Bazel 7); strip
        # the canonical repo prefix for CLI invocation.
        label_str = str(dep.label).lstrip("@")
        if label_str not in seen:
            seen[label_str] = True
            targets.append(label_str)
    return targets

def _collect_classes_output_dirs(deps, runtime_classpath):
    """Derives bazel-bin class jar paths for syncing into the mutable classes dir.

    Direct deps contribute their compiled class jars; transitive local runtime
    jars are added so classes from dependencies are available too.

    Args:
        deps: List of targets providing JavaInfo.
        runtime_classpath: Depset of transitive runtime jars.
    Returns:
        A deduplicated list of class jar path strings.
    """
    jars = []
    seen = {}
    for dep in deps:
        if JavaInfo not in dep or dep.label.workspace_name:
            continue
        for jar_output in dep[JavaInfo].outputs.jars:
            jar_path = jar_output.class_jar.path
            if jar_path not in seen:
                seen[jar_path] = True
                jars.append(jar_path)
    for jar in runtime_classpath.to_list():
        if is_local_artifact(jar) and jar.path not in seen:
            seen[jar.path] = True
            jars.append(jar.path)
    return jars

def _write_csv_file(ctx, name_suffix, values):
    out = ctx.actions.declare_file(ctx.label.name + name_suffix)
    ctx.actions.write(output = out, content = ",".join(values))
    return out

def _quarkus_dev_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_dev rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    conditional_classpath = collect_runtime_classpath([ctx.attr.conditional_deps])
    deployment_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)
    core_deployment_classpath = collect_runtime_classpath([ctx.attr.core_deployment_deps]) if ctx.attr.core_deployment_deps else depset()
    model = assemble_application_model(
        ctx,
        ctx.attr.deps,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        "dev",
        ctx.label.name.removesuffix("_dev"),
    )

    # Classpath and hot-reload metadata files, read by the launcher at runtime
    # and resolved against the runfiles tree.
    files = struct(
        app_cp = write_runfiles_paths_file(ctx, "_app_cp.txt", runtime_classpath, ":"),
        local_app_jars = write_runfiles_paths_file(ctx, "_local_app_jars.txt", depset(collect_local_app_jars(ctx.attr.deps, runtime_classpath)), ":"),
        core_deploy_cp = write_runfiles_paths_file(ctx, "_core_deploy_cp.txt", core_deployment_classpath, ":"),
        source_dirs = _write_csv_file(ctx, "_source_dirs.txt", collect_source_dir_paths(ctx.attr.deps, runtime_classpath)),
        resource_dirs = _write_csv_file(ctx, "_resource_dirs.txt", collect_resource_dir_paths(ctx.attr.deps, runtime_classpath)),
        bazel_targets = _write_csv_file(ctx, "_bazel_targets.txt", _collect_bazel_targets(ctx.attr.deps)),
        classes_output_dirs = _write_csv_file(ctx, "_classes_output_dirs.txt", _collect_classes_output_dirs(ctx.attr.deps, runtime_classpath)),
    )

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    launcher = _write_dev_launcher(ctx, tool_jar, files, model, java_runtime)

    runfiles = ctx.runfiles(
        files = [
            tool_jar,
            files.app_cp,
            files.local_app_jars,
            files.core_deploy_cp,
            files.source_dirs,
            files.resource_dirs,
            files.bazel_targets,
            files.classes_output_dirs,
            model,
        ],
        transitive_files = depset(transitive = [runtime_classpath, conditional_classpath, deployment_classpath, core_deployment_classpath, java_runtime.files]),
    )

    return [
        DefaultInfo(executable = launcher, runfiles = runfiles),
        OutputGroupInfo(quarkus_model = depset([model])),
    ]

def _join_dev_build_args(args):
    """Validates and comma-joins dev_build_args; fails if any entry contains a comma."""
    for arg in args:
        if "," in arg:
            fail("dev_build_args: commas are not supported (used as delimiter); got '{}'".format(arg))
    return ",".join(args)

def _write_dev_launcher(ctx, tool_jar, files, model_file, java_runtime):
    """Expands the dev launcher template with the metadata file locations."""
    launcher = ctx.actions.declare_file(ctx.label.name + "_dev.sh")
    ctx.actions.expand_template(
        template = ctx.file._dev_launcher_template,
        output = launcher,
        substitutions = {
            "%{app_cp_file}": files.app_cp.short_path,
            "%{app_name}": ctx.label.name.removesuffix("_dev"),
            "%{bazel_targets_file}": files.bazel_targets.short_path,
            "%{dev_build_args}": _join_dev_build_args(ctx.attr.dev_build_args),
            "%{classes_output_dirs_file}": files.classes_output_dirs.short_path,
            "%{core_deploy_cp_file}": files.core_deploy_cp.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{local_app_jars_file}": files.local_app_jars.short_path,
            "%{model_file}": model_file.short_path,
            "%{resource_dirs_file}": files.resource_dirs.short_path,
            "%{source_dirs_file}": files.source_dirs.short_path,
            "%{tool_jar}": tool_jar.short_path,
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )
    return launcher

quarkus_dev_rule = rule(
    implementation = _quarkus_dev_impl,
    executable = True,
    attrs = {
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(mandatory = True, providers = [JavaInfo]),
        "core_deployment_deps": attr.label(doc = "Dev process infrastructure — bootstrap resolvers plus quarkus-core-deployment (set by macro)."),
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
            doc = "java_library and Maven artifact targets.",
        ),
        "dev_build_args": attr.string_list(
            doc = """\
Extra flags for the hot-reload `bazel build` (e.g. ["--config=dev"]). Must
match the configuration used to `bazel run` the dev target — otherwise
rebuilt classes land in a different bazel-out tree and hot-reload syncs
stale files. Flags containing commas are not supported.
""",
        ),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar.",
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
        "_dev_launcher_template": attr.label(
            default = Label("//quarkus/private:dev_launcher.sh.tpl"),
            allow_single_file = True,
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
    },
)
