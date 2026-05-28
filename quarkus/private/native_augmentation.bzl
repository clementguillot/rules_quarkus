"""Shared utility for running quarkifier in NATIVE mode (Action 1).

Used by both quarkus_native_app_rule and quarkus_native_container_app_rule.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")

def run_native_augmentation(ctx, output_dir, runtime_classpath, deployment_classpath):
    """Runs the quarkifier in NATIVE mode to produce native-sources/.

    Args:
        ctx: The rule context.
        output_dir: Declared directory for the native-sources output.
        runtime_classpath: Depset of runtime classpath jars.
        deployment_classpath: Depset of deployment classpath jars.
    """
    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]

    args = ctx.actions.args()
    args.add_joined("--application-classpath", runtime_classpath, join_with = ":")
    args.add_joined("--deployment-classpath", depset(transitive = [runtime_classpath, deployment_classpath]), join_with = ":")
    args.add("--output-dir", output_dir.path)
    args.add("--mode", "native")
    args.add("--expected-quarkus-version", ctx.attr.quarkus_version)
    args.add("--app-name", ctx.label.name)
    if hasattr(ctx.attr, "builder_image") and ctx.attr.builder_image:
        args.add("--native-builder-image", ctx.attr.builder_image)
    if ctx.attr.version:
        args.add("--app-version", ctx.attr.version)
    if ctx.attr.main_class:
        args.add("--main-class", ctx.attr.main_class)

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
        mnemonic = "QuarkusNativeAugmentation",
        progress_message = "Running Quarkus native augmentation for %{label}",
    )
