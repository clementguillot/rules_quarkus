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
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath")

def _short_path(f):
    return f.short_path

def _collect_java_source_dirs(deps):
    """Extracts source directory paths from java_library deps.

    Derives source root directories from each dep's package path using
    standard Maven-layout markers (src/main/java, src/test/java).
    For a dep at //pkg:lib, checks for pkg/src/main/java.

    Args:
        deps: List of targets providing JavaInfo.
    Returns:
        A deduplicated list of workspace-relative source directory path strings.
    """
    source_dirs = []
    seen = {}
    for dep in deps:
        if JavaInfo in dep:
            # Use the dep's package path to derive source directories
            pkg_path = dep.label.package
            for marker in ["src/main/java", "src/test/java"]:
                candidate = pkg_path + "/" + marker if pkg_path else marker
                if candidate not in seen:
                    seen[candidate] = True
                    source_dirs.append(candidate)
    return source_dirs

def _is_local_artifact(file):
    return file.owner != None and not file.owner.workspace_name

def _collect_transitive_java_source_dirs(runtime_classpath):
    """Derives candidate source roots from transitive local Java output jars."""
    source_dirs = []
    seen = {}
    for jar in runtime_classpath.to_list():
        if not _is_local_artifact(jar):
            continue

        pkg_path = jar.owner.package

        for marker in ["src/main/java", "src/test/java"]:
            candidate = pkg_path + "/" + marker if pkg_path else marker
            if candidate not in seen:
                seen[candidate] = True
                source_dirs.append(candidate)
    return source_dirs

def _collect_bazel_targets(deps):
    """Collects Bazel target labels from deps that have JavaInfo.

    Includes all deps that provide JavaInfo and are local workspace targets
    (not external repositories). These labels are passed to the file watcher
    for `bazel build` invocation.

    Args:
        deps: List of targets providing JavaInfo.
    Returns:
        A deduplicated list of target label strings (e.g., ["//pkg:lib"]).
    """
    targets = []
    seen = {}
    for dep in deps:
        if JavaInfo in dep:
            label_str = str(dep.label)
            if label_str in seen:
                continue

            # Only include local workspace targets (not external repos)
            if not dep.label.workspace_name:
                seen[label_str] = True

                # Strip the canonical repo prefix for CLI invocation
                clean_label = label_str
                if clean_label.startswith("@" + "@//"):
                    clean_label = clean_label[2:]  # strip leading @@
                elif clean_label.startswith("@//"):
                    clean_label = clean_label[1:]  # strip leading @
                targets.append(clean_label)
    return targets

def _collect_classes_output_dirs(deps, runtime_classpath):
    """Derives bazel-bin output paths for .class files from deps.

    For each direct dep with JavaInfo, inspects JavaInfo.outputs.jars to find
    the class jar path. Also includes transitive local runtime jars so classes
    from workspace dependencies are available in the mutable classes directory.

    Args:
        deps: List of targets providing JavaInfo.
        runtime_classpath: Depset of transitive runtime jars.
    Returns:
        A deduplicated list of class jar path strings.
    """
    jars = []
    seen = {}
    for dep in deps:
        if JavaInfo not in dep:
            continue

        # Only include local workspace targets
        if dep.label.workspace_name:
            continue

        for jar_output in dep[JavaInfo].outputs.jars:
            jar_path = jar_output.class_jar.path
            if jar_path in seen:
                continue
            seen[jar_path] = True
            jars.append(jar_path)
    for jar in runtime_classpath.to_list():
        jar_path = jar.path
        if not _is_local_artifact(jar):
            continue
        if jar_path in seen:
            continue
        seen[jar_path] = True
        jars.append(jar_path)
    return jars

def _quarkus_dev_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_dev rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    deployment_classpath = collect_runtime_classpath([ctx.attr.deployment_deps]) if ctx.attr.deployment_deps else depset()
    core_deployment_classpath = collect_runtime_classpath([ctx.attr.core_deployment_deps]) if ctx.attr.core_deployment_deps else depset()

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]

    # Write the classpath files so the launcher script can read them at runtime.
    # We use short_path so paths resolve correctly in the runfiles tree.
    app_cp_file = ctx.actions.declare_file(ctx.label.name + "_app_cp.txt")
    deploy_cp_file = ctx.actions.declare_file(ctx.label.name + "_deploy_cp.txt")
    core_deploy_cp_file = ctx.actions.declare_file(ctx.label.name + "_core_deploy_cp.txt")

    app_cp_args = ctx.actions.args()
    app_cp_args.add_joined(runtime_classpath, join_with = ":", map_each = _short_path)
    ctx.actions.write(output = app_cp_file, content = app_cp_args)

    deploy_cp_args = ctx.actions.args()
    deploy_cp_args.add_joined(depset(transitive = [runtime_classpath, deployment_classpath]), join_with = ":", map_each = _short_path)
    ctx.actions.write(output = deploy_cp_file, content = deploy_cp_args)

    core_deploy_cp_args = ctx.actions.args()
    core_deploy_cp_args.add_joined(core_deployment_classpath, join_with = ":", map_each = _short_path)
    ctx.actions.write(output = core_deploy_cp_file, content = core_deploy_cp_args)

    # Collect source directories from java_library deps for hot-reload support.
    source_dirs = _collect_java_source_dirs(ctx.attr.deps)
    for source_dir in _collect_transitive_java_source_dirs(runtime_classpath):
        if source_dir not in source_dirs:
            source_dirs.append(source_dir)
    source_dirs_file = ctx.actions.declare_file(ctx.label.name + "_source_dirs.txt")
    ctx.actions.write(output = source_dirs_file, content = ",".join(source_dirs))

    # Collect Bazel target labels for the file watcher to rebuild on changes.
    bazel_targets = _collect_bazel_targets(ctx.attr.deps)
    bazel_targets_file = ctx.actions.declare_file(ctx.label.name + "_bazel_targets.txt")
    ctx.actions.write(output = bazel_targets_file, content = ",".join(bazel_targets))

    # Collect classes output directory paths for class syncing after builds.
    classes_output_dirs = _collect_classes_output_dirs(ctx.attr.deps, runtime_classpath)
    classes_output_dirs_file = ctx.actions.declare_file(ctx.label.name + "_classes_output_dirs.txt")
    ctx.actions.write(output = classes_output_dirs_file, content = ",".join(classes_output_dirs))

    # Generate the launcher script
    launcher = ctx.actions.declare_file(ctx.label.name + "_dev.sh")
    ctx.actions.expand_template(
        template = ctx.file._dev_launcher_template,
        output = launcher,
        substitutions = {
            "%{workspace}": ctx.workspace_name,
            "%{tool_jar}": tool_jar.short_path,
            "%{app_cp_file}": app_cp_file.short_path,
            "%{deploy_cp_file}": deploy_cp_file.short_path,
            "%{core_deploy_cp_file}": core_deploy_cp_file.short_path,
            "%{quarkus_version}": ctx.attr.quarkus_version,
            "%{app_name}": ctx.label.name,
            "%{source_dirs_file}": source_dirs_file.short_path,
            "%{bazel_targets_file}": bazel_targets_file.short_path,
            "%{classes_output_dirs_file}": classes_output_dirs_file.short_path,
        },
        is_executable = True,
    )

    runfiles = ctx.runfiles(
        files = [
            tool_jar,
            app_cp_file,
            deploy_cp_file,
            core_deploy_cp_file,
            source_dirs_file,
            bazel_targets_file,
            classes_output_dirs_file,
        ],
        transitive_files = depset(transitive = [runtime_classpath, deployment_classpath, core_deployment_classpath, java_runtime.files]),
    )

    return [
        DefaultInfo(
            executable = launcher,
            runfiles = runfiles,
        ),
    ]

quarkus_dev_rule = rule(
    implementation = _quarkus_dev_impl,
    executable = True,
    attrs = {
        "deps": attr.label_list(
            mandatory = True,
            providers = [JavaInfo],
            doc = "java_library and Maven artifact targets.",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar.",
        ),
        "deployment_deps": attr.label(doc = "All deployment deps including extensions (set by macro)."),
        "core_deployment_deps": attr.label(doc = "Core deployment deps only — quarkus-core-deployment transitive closure (set by macro)."),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
        "_dev_launcher_template": attr.label(
            default = Label("//quarkus/private:dev_launcher.sh.tpl"),
            allow_single_file = True,
        ),
    },
)
