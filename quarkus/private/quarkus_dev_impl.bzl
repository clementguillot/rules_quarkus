"""Implementation of the quarkus_dev rule.

Launches a Quarkus application in dev mode with the Quarkus Dev UI.
The process blocks until terminated (Ctrl+C / SIGTERM).

When source or code-generation input directories are detected in deps, the
rule also wires a Java file watcher (BazelFileWatcher) that triggers incremental
`bazel build` actions and syncs fresh .class files to a mutable directory for
Quarkus hot-reload.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:build_properties.bzl", "write_build_properties")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_local_app_jars", "collect_resource_dir_paths", "collect_runtime_classpath", "collect_source_dir_paths", "is_local_artifact", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
load("//quarkus/private:coverage_transition.bzl", "dev_lifecycle_transition", "disable_coverage_transition", "single_transitioned_target")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")
load("//quarkus/private:quarkus_codegen_impl.bzl", "collect_codegen_input_dirs", "quarkus_codegen_metadata_aspect")

def _hot_reload_bazel_target(ctx):
    """Returns the label the file watcher rebuilds on a source change.

    This must be the dev target itself, never its deps: `deps` is configured
    through dev_lifecycle_transition, so a dep built directly from the command
    line lands in the baseline output tree while _classes_output_dirs.txt
    points into the transitioned one, and hot-reload would sync stale classes.
    Building the dev target re-applies the transition to the whole graph.

    Args:
        ctx: Rule context for the dev target.
    Returns:
        A single-element list holding the target label (e.g. ["//pkg:app_dev"]).
    """

    # str(label) is "@@//pkg:name" (or "@//pkg:name" pre-Bazel 7); strip
    # the canonical repo prefix for CLI invocation.
    return [str(ctx.label).lstrip("@")]

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
    conditional_classpath = collect_runtime_classpath([single_transitioned_target(ctx.attr.conditional_deps)])
    deployment_classpath = collect_deployment_classpath(single_transitioned_target(ctx.attr.deployment_deps), ctx.attr.deps)
    core_deployment_dep = single_transitioned_target(ctx.attr.core_deployment_deps)
    core_deployment_classpath = collect_runtime_classpath([core_deployment_dep]) if core_deployment_dep else depset()
    model = assemble_application_model(
        ctx,
        ctx.attr.deps,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        "dev",
        ctx.label.name.removesuffix("_dev"),
    )
    codegen_input_dirs = collect_codegen_input_dirs(ctx.attr.deps).to_list()
    bazel_targets = _hot_reload_bazel_target(ctx)

    # Classpath and hot-reload metadata files, read by the launcher at runtime
    # and resolved against the runfiles tree.
    files = struct(
        app_cp = write_runfiles_paths_file(ctx, "_app_cp.txt", runtime_classpath, ":"),
        build_properties = write_build_properties(ctx),
        local_app_jars = write_runfiles_paths_file(ctx, "_local_app_jars.txt", depset(collect_local_app_jars(ctx.attr.deps, runtime_classpath)), ":"),
        core_deploy_cp = write_runfiles_paths_file(ctx, "_core_deploy_cp.txt", core_deployment_classpath, ":"),
        source_dirs = _write_csv_file(ctx, "_source_dirs.txt", collect_source_dir_paths(ctx.attr.deps, runtime_classpath)),
        resource_dirs = _write_csv_file(ctx, "_resource_dirs.txt", collect_resource_dir_paths(ctx.attr.deps, runtime_classpath)),
        bazel_targets = _write_csv_file(ctx, "_bazel_targets.txt", bazel_targets),
        classes_output_dirs = _write_csv_file(ctx, "_classes_output_dirs.txt", _collect_classes_output_dirs(ctx.attr.deps, runtime_classpath)),
        codegen_input_dirs = _write_csv_file(ctx, "_codegen_input_dirs.txt", codegen_input_dirs),
    )

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    launcher = _write_dev_launcher(ctx, tool_jar, files, model, java_runtime)

    runfiles = ctx.runfiles(
        files = [
            tool_jar,
            files.app_cp,
            files.build_properties,
            files.local_app_jars,
            files.core_deploy_cp,
            files.source_dirs,
            files.resource_dirs,
            files.bazel_targets,
            files.classes_output_dirs,
            files.codegen_input_dirs,
            model,
        ] + ctx.files.deployment_artifacts,
        transitive_files = depset(transitive = [runtime_classpath, conditional_classpath, deployment_classpath, core_deployment_classpath, java_runtime.files]),
    )

    return [
        DefaultInfo(executable = launcher, runfiles = runfiles),
        OutputGroupInfo(
            quarkus_build_properties = depset([files.build_properties]),
            quarkus_model = depset([model]),
        ),
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
            "%{build_properties_file}": files.build_properties.short_path,
            "%{dev_build_args}": _join_dev_build_args(ctx.attr.dev_build_args),
            "%{classes_output_dirs_file}": files.classes_output_dirs.short_path,
            "%{codegen_input_dirs_file}": files.codegen_input_dirs.short_path,
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
        "build_properties": attr.string_dict(
            doc = "Declared build-time properties passed hermetically to Quarkus dev mode.",
        ),
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(
            mandatory = True,
            cfg = disable_coverage_transition,
            providers = [JavaInfo],
        ),
        "core_deployment_deps": attr.label(
            cfg = disable_coverage_transition,
            doc = "Dev process infrastructure — bootstrap resolvers plus quarkus-core-deployment (set by macro).",
        ),
        "deployment_deps": attr.label(
            cfg = disable_coverage_transition,
            doc = "Resolved Quarkus deployment closure (set by macro).",
        ),
        "deployment_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal deployment resolver graph catalog (set by macro).",
        ),
        "deployment_artifacts": attr.label(mandatory = True),
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
            cfg = dev_lifecycle_transition,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
                quarkus_codegen_metadata_aspect,
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
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        ),
    },
)
