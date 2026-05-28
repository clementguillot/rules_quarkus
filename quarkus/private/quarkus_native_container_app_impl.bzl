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
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath")

def _quarkus_native_container_app_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_native_container_app_rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    output_dir = ctx.actions.declare_directory(ctx.label.name + "-native-sources")

    deployment_classpath = collect_runtime_classpath([ctx.attr.deployment_deps]) if ctx.attr.deployment_deps else depset()

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]

    # ---- Action 1: Quarkifier NATIVE augmentation ----
    args = ctx.actions.args()
    args.add_joined("--application-classpath", runtime_classpath, join_with = ":")
    args.add_joined("--deployment-classpath", depset(transitive = [runtime_classpath, deployment_classpath]), join_with = ":")
    args.add("--output-dir", output_dir.path)
    args.add("--mode", "native")
    args.add("--expected-quarkus-version", ctx.attr.quarkus_version)
    args.add("--app-name", ctx.label.name)
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

    # ---- Action 2: native-image inside container ----
    binary = ctx.actions.declare_file(ctx.label.name)

    native_sources_path = output_dir.path + "/native-sources"
    requested_runtime = ctx.attr.container_runtime
    builder_image = ctx.attr.builder_image
    app_name = ctx.label.name

    ctx.actions.run_shell(
        command = """
set -euo pipefail

# ---- Container runtime auto-detection ----
# Backported from Quarkus ContainerRuntimeUtil.java
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

$RUNTIME run --rm --user root --entrypoint bash \
  -v "$NATIVE_SOURCES:/project-src:ro" \
  -v "$OUTPUT_DIR:/output" \
  -w /work \
  {builder_image} \
  -c '
    cp -a /project-src/. /work/ &&
    ARGS=$(sed -e "s| {app_name}-runner -jar | -jar |" -e "s|--enable-monitoring=[^ ]*||g" native-image.args) &&
    native-image $ARGS -o /output/{app_name}
  '
""".format(
            requested_runtime = requested_runtime,
            native_sources = native_sources_path,
            output = binary.path,
            builder_image = builder_image,
            app_name = app_name,
        ),
        inputs = depset(direct = [output_dir]),
        outputs = [binary],
        mnemonic = "NativeImageContainer",
        progress_message = "Compiling native image in container for %{label}",
        use_default_shell_env = True,
        execution_requirements = {"no-sandbox": "1"},
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
        "deployment_deps": attr.label(doc = "Deployment deps (set by macro)."),
        "deps": attr.label_list(
            mandatory = True,
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
        ),
    },
    doc = """\
Internal rule — use quarkus_app(native_container_build=True) from @rules_quarkus//quarkus:defs.bzl.

Produces a Linux native binary by running native-image inside a Docker/Podman container.
Requires Docker or Podman on the host.
""",
)
