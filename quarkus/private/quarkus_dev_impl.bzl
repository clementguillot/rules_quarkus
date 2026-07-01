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
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_resource_dir_paths", "collect_runtime_classpath", "collect_source_dir_paths", "is_local_artifact", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")

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
    deployment_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)
    core_deployment_classpath = collect_runtime_classpath([ctx.attr.core_deployment_deps]) if ctx.attr.core_deployment_deps else depset()

    # Classpath and hot-reload metadata files, read by the launcher at runtime
    # and resolved against the runfiles tree.
    files = struct(
        app_cp = write_runfiles_paths_file(ctx, "_app_cp.txt", runtime_classpath, ":"),
        deploy_cp = write_runfiles_paths_file(ctx, "_deploy_cp.txt", depset(transitive = [runtime_classpath, deployment_classpath]), ":"),
        core_deploy_cp = write_runfiles_paths_file(ctx, "_core_deploy_cp.txt", core_deployment_classpath, ":"),
        source_dirs = _write_csv_file(ctx, "_source_dirs.txt", collect_source_dir_paths(ctx.attr.deps, runtime_classpath)),
        resource_dirs = _write_csv_file(ctx, "_resource_dirs.txt", collect_resource_dir_paths(ctx.attr.deps, runtime_classpath)),
        bazel_targets = _write_csv_file(ctx, "_bazel_targets.txt", _collect_bazel_targets(ctx.attr.deps)),
        classes_output_dirs = _write_csv_file(ctx, "_classes_output_dirs.txt", _collect_classes_output_dirs(ctx.attr.deps, runtime_classpath)),
    )

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    launcher = _write_dev_launcher(ctx, tool_jar, files, java_runtime)

    runfiles = ctx.runfiles(
        files = [
            tool_jar,
            files.app_cp,
            files.deploy_cp,
            files.core_deploy_cp,
            files.source_dirs,
            files.resource_dirs,
            files.bazel_targets,
            files.classes_output_dirs,
        ],
        transitive_files = depset(transitive = [runtime_classpath, deployment_classpath, core_deployment_classpath, java_runtime.files]),
    )

    return [DefaultInfo(executable = launcher, runfiles = runfiles)]

def _write_dev_launcher(ctx, tool_jar, files, java_runtime):
    """Expands the dev launcher template with the metadata file locations."""
    launcher = ctx.actions.declare_file(ctx.label.name + "_dev.sh")
    ctx.actions.expand_template(
        template = ctx.file._dev_launcher_template,
        output = launcher,
        substitutions = {
            "%{app_cp_file}": files.app_cp.short_path,
            "%{app_name}": ctx.label.name.removesuffix("_dev"),
            "%{app_version_flag}": "--app-version " + ctx.attr.version if ctx.attr.version else "",
            "%{bazel_targets_file}": files.bazel_targets.short_path,
            "%{classes_output_dirs_file}": files.classes_output_dirs.short_path,
            "%{core_deploy_cp_file}": files.core_deploy_cp.short_path,
            "%{deploy_cp_file}": files.deploy_cp.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{quarkus_version}": ctx.attr.quarkus_version,
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
        "core_deployment_deps": attr.label(doc = "Core deployment deps only — quarkus-core-deployment transitive closure (set by macro)."),
        "deployment_deps": attr.label(doc = "Resolved Quarkus deployment closure (set by macro)."),
        "deps": attr.label_list(
            mandatory = True,
            aspects = [quarkus_extension_deployment_classpath_aspect],
            providers = [JavaInfo],
            doc = "java_library and Maven artifact targets.",
        ),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar.",
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
