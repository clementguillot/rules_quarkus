"""Shared quarkifier augmentation action.

Used by quarkus_app_rule (Fast_Jar mode) and by quarkus_native_app_rule /
quarkus_native_container_app_rule (native mode).
"""

load("@rules_java//java/common:java_common.bzl", "java_common")

def _write_classpath_file(ctx, name_suffix, jars):
    """Writes colon-joined jar paths to a declared file (avoids E2BIG on argv).

    Args:
        ctx: Rule context.
        name_suffix: Suffix appended to the target name for the file name.
        jars: Depset or list of jar files; order is preserved.
    Returns:
        The declared File.
    """
    out = ctx.actions.declare_file(ctx.label.name + name_suffix)
    args = ctx.actions.args()
    args.set_param_file_format("flag_per_line")
    args.add_joined(jars, join_with = ":")
    ctx.actions.write(output = out, content = args)
    return out

def run_augmentation(ctx, output_dir, runtime_classpath, deployment_classpath, mode = None, local_jars = None):
    """Runs the quarkifier deploy jar to augment the application.

    The deploy jar is a fat jar containing all tool classes + dependencies,
    so no -cp assembly is needed. The tool handles classloader isolation
    internally via the augment classloader + TCCL.

    Args:
        ctx: Rule context. Must define quarkifier_tool, quarkus_version,
            version, main_class attrs and the _java_runtime attr.
        output_dir: Declared directory the quarkifier writes into.
        runtime_classpath: Depset of runtime classpath jars.
        deployment_classpath: Depset of deployment classpath jars.
        mode: Quarkifier mode ("native"), or None for the default Fast_Jar mode.
        local_jars: Optional list of local workspace jars, passed via
            --local-app-jars-file. The first entry is treated as the
            application artifact, so order matters.
    """

    app_cp_file = _write_classpath_file(ctx, "_app_classpath.txt", runtime_classpath)
    deploy_cp_file = _write_classpath_file(
        ctx,
        "_deploy_classpath.txt",
        depset(transitive = [runtime_classpath, deployment_classpath]),
    )

    args = ctx.actions.args()
    args.add("--application-classpath-file", app_cp_file)
    local_jars_file = None
    if local_jars:
        local_jars_file = _write_classpath_file(ctx, "_local_app_jars.txt", local_jars)
        args.add("--local-app-jars-file", local_jars_file)
    args.add("--deployment-classpath-file", deploy_cp_file)
    args.add("--output-dir", output_dir.path)
    if mode:
        args.add("--mode", mode)
    args.add("--expected-quarkus-version", ctx.attr.quarkus_version)
    args.add("--app-name", ctx.label.name)
    if hasattr(ctx.attr, "builder_image") and ctx.attr.builder_image:
        args.add("--native-builder-image", ctx.attr.builder_image)
    if ctx.attr.version:
        args.add("--app-version", ctx.attr.version)
    if ctx.attr.main_class:
        args.add("--main-class", ctx.attr.main_class)

    tool_jar = ctx.file.quarkifier_tool
    jar_args = ctx.actions.args()
    jar_args.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
    jar_args.add("-jar")
    jar_args.add(tool_jar)

    if mode == "native":
        mnemonic = "QuarkusNativeAugmentation"
        progress_message = "Running Quarkus native augmentation for %{label}"
    else:
        mnemonic = "QuarkusAugmentation"
        progress_message = "Running Quarkus augmentation for %{label}"

    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [jar_args, args],
        inputs = depset(
            direct = [tool_jar, app_cp_file, deploy_cp_file] + ([local_jars_file] if local_jars_file else []),
            transitive = [runtime_classpath, deployment_classpath, java_runtime.files],
        ),
        outputs = [output_dir],
        mnemonic = mnemonic,
        progress_message = progress_message,
    )
