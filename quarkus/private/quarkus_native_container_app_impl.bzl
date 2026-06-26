"""Implementation of the quarkus_native_container_app rule (internal).

Builds a Quarkus native Linux binary via two Bazel actions:
  Action 1: Runs quarkifier in NATIVE mode to produce native-sources/
  Action 2: Invokes native-image inside a Docker/Podman container

This rule is not loaded directly by users — it is instantiated by the
quarkus_app macro when native_container_build=True, creating a <name>_native target.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusNativeInfo")
load("//quarkus/private:augmentation.bzl", "run_augmentation")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")

# Container runtime auto-detection is backported from Quarkus
# ContainerRuntimeUtil.java. Inside the container, the args file ends with
# "<app_name>-runner -jar <runner>.jar": the output-name token is removed and
# replaced by -o with the mounted output path, and monitoring options that may
# be incompatible with the builder image's GraalVM version are stripped.
_CONTAINER_BUILD_SCRIPT = """
set -euo pipefail

detect_container_runtime() {{
  local REQUESTED="$1"

  if [ "$REQUESTED" != "auto" ]; then
    if ! command -v "$REQUESTED" &>/dev/null; then
      echo "ERROR: Requested container runtime '$REQUESTED' not found on PATH." >&2
      exit 1
    fi
    local VERSION_OUTPUT
    VERSION_OUTPUT=$("$REQUESTED" --version 2>/dev/null || true)
    if [ -z "$VERSION_OUTPUT" ]; then
      echo "ERROR: '$REQUESTED --version' failed. Is the daemon running?" >&2
      exit 1
    fi
    echo "$REQUESTED"
    return
  fi

  # Auto-detect: try Docker first, then Podman
  if command -v docker &>/dev/null; then
    local DOCKER_VERSION
    DOCKER_VERSION=$(docker --version 2>/dev/null || true)
    if echo "$DOCKER_VERSION" | grep -qi "podman"; then
      echo "podman"
      return
    elif echo "$DOCKER_VERSION" | grep -q "Docker version"; then
      echo "docker"
      return
    fi
  fi

  if command -v podman &>/dev/null; then
    local PODMAN_VERSION
    PODMAN_VERSION=$(podman --version 2>/dev/null || true)
    if echo "$PODMAN_VERSION" | grep -qi "podman version"; then
      echo "podman"
      return
    fi
  fi

  echo "ERROR: No container runtime found. Install Docker or Podman." >&2
  exit 1
}}

RUNTIME=$(detect_container_runtime "{requested_runtime}")
echo "Using container runtime: $RUNTIME"

# Warn if not on Linux — the produced binary is Linux-only
if [ "$(uname -s)" != "Linux" ]; then
  echo "WARNING: native_container_build produces a Linux binary. It will not run on this $(uname -s) host." >&2
  echo "WARNING: Use 'bazel build' to produce the binary, then deploy to a Linux environment." >&2
fi

EXECROOT="$(pwd)"
NATIVE_SOURCES="$EXECROOT/{native_sources}"
OUTPUT_DIR=$(dirname "$EXECROOT/{output}")

mkdir -p "$OUTPUT_DIR"

# On Linux the bind mounts write straight to the host filesystem, so run the
# build as the host user — otherwise the binary lands root-owned in bazel-out
# (breaking e.g. `bazel clean`). Rootless Podman additionally needs keep-id so
# the host uid maps to itself inside the container. On macOS/Windows the VM
# file-sharing layer translates ownership, so the image default (root) is fine.
USER_ARGS=(--user root)
if [ "$(uname -s)" = "Linux" ]; then
  USER_ARGS=(--user "$(id -u):$(id -g)")
  if [ "$RUNTIME" = "podman" ] && [ "$(id -u)" != "0" ]; then
    USER_ARGS+=(--userns=keep-id)
  fi
fi

# Sources are copied to a container-local directory because native-image
# resolves the relative paths in native-image.args against the working
# directory and writes temp files next to them. /tmp is writable for any uid.
$RUNTIME run --rm "${{USER_ARGS[@]}}" --entrypoint bash \\
  -v "$NATIVE_SOURCES:/project-src:ro" \\
  -v "$OUTPUT_DIR:/output" \\
  '{builder_image}' \\
  -c '
    mkdir -p /tmp/work && cd /tmp/work &&
    cp -a /project-src/. . &&
    ARGS=$(sed -e "s| {app_name}-runner -jar | -jar |" -e "s|--enable-monitoring=[^ ]*||g" native-image.args) &&
    native-image $ARGS -o /output/{app_name}
  '
"""

def _quarkus_native_container_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_native_container_app_rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    deployment_classpath = collect_deployment_classpath(ctx.attr.deployment_deps, ctx.attr.deps)

    output_dir = ctx.actions.declare_directory(ctx.label.name + "-native-sources")
    run_augmentation(ctx, output_dir, runtime_classpath, deployment_classpath, mode = "native")

    binary = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.run_shell(
        command = _CONTAINER_BUILD_SCRIPT.format(
            requested_runtime = ctx.attr.container_runtime,
            native_sources = output_dir.path + "/native-sources",
            output = binary.path,
            builder_image = ctx.attr.builder_image,
            app_name = ctx.label.name,
        ),
        inputs = depset(direct = [output_dir]),
        outputs = [binary],
        mnemonic = "NativeImageContainer",
        progress_message = "Compiling native image in container for %{label}",
        use_default_shell_env = True,
        execution_requirements = {
            "no-sandbox": "1",
            # Tag with the target os/arch so remote cache distinguishes
            # native binaries compiled for different platforms.
            "native-arch": ctx.attr.native_arch,
            "native-os": ctx.attr.native_os,
        },
    )

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

quarkus_native_container_app_rule = rule(
    implementation = _quarkus_native_container_app_impl,
    executable = True,
    attrs = {
        "builder_image": attr.string(
            default = "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25",
            doc = "Container image with native-image tool (Mandrel/GraalVM builder).",
        ),
        "container_runtime": attr.string(
            default = "auto",
            values = ["auto", "docker", "podman"],
            doc = "Container runtime to use. 'auto' detects Docker or Podman.",
        ),
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
        "native_arch": attr.string(
            mandatory = True,
            doc = """\
Target CPU architecture for the native binary. This value is embedded in the
action's execution_requirements to ensure remote cache distinguishes binaries
compiled for different architectures (e.g. amd64 vs aarch64).
Set automatically by the quarkus_app macro via select() on the host CPU.
""",
        ),
        "native_os": attr.string(
            default = "linux",
            doc = "Target OS for the native binary. Always 'linux' for container builds.",
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
            providers = [java_common.JavaRuntimeInfo],
        ),
    },
    doc = """\
Internal rule — use quarkus_app(native_container_build=True) from @rules_quarkus//quarkus:defs.bzl.

Produces a Linux native binary by running native-image inside a Docker/Podman container.
Requires Docker or Podman on the host.
""",
)
