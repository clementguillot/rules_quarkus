"""Implementation of the quarkus_dev rule.

Launches a Quarkus application in dev mode with the Quarkus Dev UI.
The process blocks until terminated (Ctrl+C / SIGTERM).
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath")

def _short_path(f):
    return f.short_path

def _collect_java_source_dirs(deps):
    """Extracts source directory paths from java_library deps.

    Examines source files from each dep's DefaultInfo and derives source root
    directories by finding standard Maven-layout markers (src/main/java,
    src/test/java) in the file paths.

    Args:
        deps: List of targets providing JavaInfo.
    Returns:
        A deduplicated list of workspace-relative source directory path strings.
    """
    source_dirs = []
    seen = {}
    for dep in deps:
        if JavaInfo in dep:
            for src in dep[DefaultInfo].files.to_list():
                if src.is_source and src.path.endswith(".java"):
                    path = src.path
                    for marker in ["src/main/java", "src/test/java"]:
                        idx = path.find(marker)
                        if idx >= 0:
                            root = path[:idx + len(marker)]
                            if root not in seen:
                                seen[root] = True
                                source_dirs.append(root)
                            break
    return source_dirs

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
    source_dirs_file = ctx.actions.declare_file(ctx.label.name + "_source_dirs.txt")
    ctx.actions.write(output = source_dirs_file, content = ",".join(source_dirs))

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
        },
        is_executable = True,
    )

    runfiles = ctx.runfiles(
        files = [tool_jar, app_cp_file, deploy_cp_file, core_deploy_cp_file, source_dirs_file],
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
