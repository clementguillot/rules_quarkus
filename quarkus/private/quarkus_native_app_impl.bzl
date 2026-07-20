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
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:augmentation.bzl", "run_augmentation")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

_GVM_TOOLCHAIN_TYPE = "@rules_graalvm//graalvm/toolchain"

# We cd into native-sources/ so that relative paths in the args file resolve
# correctly. The args file ends with "<output-name> -jar <runner>.jar": the
# output-name token is removed and replaced by -o with the absolute output
# path. Monitoring options that may be incompatible with the installed
# GraalVM version are stripped. The rewritten args are passed via @argfile
# so the -cp line (one entry per dependency jar) never lands on argv (E2BIG).
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
REWRITTEN_ARGS=$(mktemp)
sed -e 's| {runner_name} -jar | -jar |' -e 's|--enable-monitoring=[^ ]*||g' native-image.args > "$REWRITTEN_ARGS"
exec "$NATIVE_IMAGE" "@$REWRITTEN_ARGS" -H:CCompilerPath="$CC_PATH" -o "$OUTPUT"
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
    conditional_classpath = collect_runtime_classpath([ctx.attr.conditional_deps])
    deployment_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)
    model = assemble_application_model(
        ctx,
        ctx.attr.deps,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        "native",
        ctx.label.name.removesuffix("_native"),
    )

    output_dir = ctx.actions.declare_directory(ctx.label.name + "-native-sources")
    model_snapshot = run_augmentation(
        ctx,
        output_dir,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        mode = "native",
        local_jars = collect_local_app_jars(ctx.attr.deps, runtime_classpath),
        model_file = model.file,
    )

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
        OutputGroupInfo(
            quarkus_application_model = depset([model_snapshot]),
            quarkus_model = depset([model.file]),
        ),
    ]

quarkus_native_app_rule = rule(
    implementation = _quarkus_native_app_impl,
    executable = True,
    attrs = {
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(mandatory = True, providers = [JavaInfo]),
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
        "main_class": attr.string(
            doc = "Override main class. Defaults to the Quarkus runner.",
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
