"""Implementation of the quarkus_native_app rule (internal).

Builds a Quarkus native binary via two Bazel actions:
  Action 1: Runs quarkifier in NATIVE mode to produce native-sources/
  Action 2: Invokes native-image via rules_graalvm toolchain to produce the binary

This rule is not loaded directly by users — it is instantiated by the
quarkus_app macro when native=True, creating a <name>_native target.
"""

load("@bazel_tools//tools/cpp:toolchain_utils.bzl", "find_cpp_toolchain", "use_cpp_toolchain")
load("@rules_cc//cc/common:cc_common.bzl", "cc_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusNativeInfo")
load("//quarkus/private:augmentation.bzl", "run_augmentation")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")

_GVM_TOOLCHAIN_TYPE = "@rules_graalvm//graalvm/toolchain"

# We cd into native-sources/ so that relative paths in the args file resolve
# correctly. The args file ends with "<output-name> -jar <runner>.jar": the
# output-name token is removed and replaced by -o with the absolute output
# path. Monitoring options that may be incompatible with the installed
# GraalVM version are stripped.
_NATIVE_IMAGE_SCRIPT = """
set -euo pipefail
EXECROOT="$(pwd)"
NATIVE_IMAGE="$EXECROOT/{native_image}"
OUTPUT="$EXECROOT/{output}"
CC_PATH="{cc_path}"
case "$CC_PATH" in
  /*) ;;
  *) CC_PATH="$EXECROOT/$CC_PATH" ;;
esac
cd "{native_sources}"
ARGS=$(sed -e 's| {runner_name} -jar | -jar |' -e 's|--enable-monitoring=[^ ]*||g' native-image.args)
exec "$NATIVE_IMAGE" $ARGS -H:CCompilerPath="$CC_PATH" -o "$OUTPUT"
"""

def _run_native_image(ctx, output_dir, binary):
    """Compiles native-sources/ into a binary via the rules_graalvm toolchain."""
    graalvm_toolchain = ctx.toolchains[_GVM_TOOLCHAIN_TYPE].graalvm
    native_image_bin = graalvm_toolchain.native_image_bin.files_to_run

    # Resolve the CC toolchain to get the C compiler path for native-image linking.
    cc_toolchain = find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )
    c_compiler_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = "c-compile",
    )

    ctx.actions.run_shell(
        command = _NATIVE_IMAGE_SCRIPT.format(
            native_image = native_image_bin.executable.path,
            native_sources = output_dir.path + "/native-sources",
            output = binary.path,
            runner_name = ctx.label.name + "-runner",
            cc_path = c_compiler_path,
        ),
        inputs = depset(
            direct = [output_dir, native_image_bin.executable],
            transitive = [
                graalvm_toolchain.gvm_files[DefaultInfo].files,
                cc_toolchain.all_files,
            ],
        ),
        outputs = [binary],
        mnemonic = "NativeImage",
        progress_message = "Compiling native image for %{label}",
    )

def _quarkus_native_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_native_app_rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    deployment_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)

    output_dir = ctx.actions.declare_directory(ctx.label.name + "-native-sources")
    run_augmentation(ctx, output_dir, runtime_classpath, deployment_classpath, mode = "native")

    binary = ctx.actions.declare_file(ctx.label.name)
    _run_native_image(ctx, output_dir, binary)

    return [
        DefaultInfo(
            executable = binary,
            files = depset([binary]),
        ),
        QuarkusNativeInfo(
            native_sources_dir = output_dir,
            binary = binary,
            application_classpath = runtime_classpath,
            quarkus_version = ctx.attr.quarkus_version,
        ),
    ]

quarkus_native_app_rule = rule(
    implementation = _quarkus_native_app_impl,
    executable = True,
    attrs = {
        "deployment_deps": attr.label(doc = "Resolved Quarkus deployment closure (set by macro)."),
        "deps": attr.label_list(
            mandatory = True,
            aspects = [quarkus_extension_deployment_classpath_aspect],
            providers = [JavaInfo],
            doc = "java_library and Maven artifact targets.",
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
            cfg = "exec",
        ),
        "_cc_toolchain": attr.label(
            default = Label("@bazel_tools//tools/cpp:current_cc_toolchain"),
        ),
    },
    toolchains = [_GVM_TOOLCHAIN_TYPE] + use_cpp_toolchain(),
    fragments = ["cpp"],
    doc = """\
Internal rule — use quarkus_app(native=True) macro from @rules_quarkus//quarkus:defs.bzl instead.

Requires the @rules_graalvm GraalVM toolchain to be registered by the user.
""",
)
