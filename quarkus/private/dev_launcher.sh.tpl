#!/usr/bin/env bash
set -euo pipefail

# Ensure cleanup runs on all exit conditions including errors and signals
cleanup() {
    rm -rf "${OUTPUT_DIR:-}"
    rm -rf "${CLASSES_DIR:-}"
    rm -f "${ABS_APP_CP_FILE:-}" "${ABS_CORE_DEPLOY_CP_FILE:-}" "${ABS_LOCAL_APP_JARS_FILE:-}"
}
trap cleanup EXIT ERR TERM INT QUIT ABRT

RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"

# Resolve paths relative to runfiles
JAVA="${RUNFILES_DIR}/%{workspace}/%{java_home}/bin/java"
TOOL_JAR="${RUNFILES_DIR}/%{workspace}/%{tool_jar}"
APP_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{app_cp_file}"
CORE_DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{core_deploy_cp_file}"
LOCAL_APP_JARS_FILE="${RUNFILES_DIR}/%{workspace}/%{local_app_jars_file}"
MODEL_FILE="${RUNFILES_DIR}/%{workspace}/%{model_file}"

# Build absolute-path classpath files for quarkifier (avoids E2BIG on Linux).
# Each entry in the source files is prefixed with the runfiles directory.
_prefix_cp_to_file() {
  local src="$1" dest="$2"
  # Prefix every colon-separated entry in one pass. The prefix is passed via
  # the environment: ENVIRON values are byte-exact, whereas awk -v would
  # reinterpret backslash escape sequences in the value.
  CP_PREFIX="${RUNFILES_DIR}/%{workspace}/" awk \
    'BEGIN{FS=":"; OFS=":"; p=ENVIRON["CP_PREFIX"]} {for(i=1;i<=NF;i++) $i=p $i; print}' \
    < "$src" > "$dest"
}

ABS_APP_CP_FILE=$(mktemp)
ABS_CORE_DEPLOY_CP_FILE=$(mktemp)
ABS_LOCAL_APP_JARS_FILE=$(mktemp)

_prefix_cp_to_file "$APP_CP_FILE" "$ABS_APP_CP_FILE"
_prefix_cp_to_file "$CORE_DEPLOY_CP_FILE" "$ABS_CORE_DEPLOY_CP_FILE"
_prefix_cp_to_file "$LOCAL_APP_JARS_FILE" "$ABS_LOCAL_APP_JARS_FILE"

# Read source directories for hot-reload
SOURCE_DIRS_FILE="${RUNFILES_DIR}/%{workspace}/%{source_dirs_file}"
SOURCE_DIRS=""
if [ -f "$SOURCE_DIRS_FILE" ]; then
    SOURCE_DIRS=$(cat "$SOURCE_DIRS_FILE")
fi

# Read resource directories
RESOURCE_DIRS_FILE="${RUNFILES_DIR}/%{workspace}/%{resource_dirs_file}"
RESOURCE_DIRS=""
if [ -f "$RESOURCE_DIRS_FILE" ]; then
    RESOURCE_DIRS=$(cat "$RESOURCE_DIRS_FILE")
fi

# Read Bazel targets and classes output dirs for hot-reload
BAZEL_TARGETS_FILE="${RUNFILES_DIR}/%{workspace}/%{bazel_targets_file}"
BAZEL_TARGETS=""
if [ -f "$BAZEL_TARGETS_FILE" ]; then
    BAZEL_TARGETS=$(cat "$BAZEL_TARGETS_FILE")
fi

CLASSES_OUTPUT_DIRS_FILE="${RUNFILES_DIR}/%{workspace}/%{classes_output_dirs_file}"
CLASSES_OUTPUT_DIRS=""
if [ -f "$CLASSES_OUTPUT_DIRS_FILE" ]; then
    CLASSES_OUTPUT_DIRS=$(cat "$CLASSES_OUTPUT_DIRS_FILE")
fi

# Create temp dirs with unique prefixes for security
OUTPUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/quarkus_dev_output_XXXXXX")
CLASSES_DIR=""

# Resolve absolute paths for hot-reload if source dirs are available
WORKSPACE_ROOT="${BUILD_WORKSPACE_DIRECTORY:-$(pwd)}"

# Resolve the bazel binary once: dev mode runs with a sanitized PATH in some
# setups, and bazelisk-only installs have no `bazel` shim.
BAZEL_BIN=$(command -v bazel || command -v bazelisk || echo bazel)
# Run `bazel info` from the real workspace so it attaches to the user's
# existing Bazel server instead of spawning a throwaway one from runfiles cwd.
BAZEL_EXEC_ROOT=$(cd "$WORKSPACE_ROOT" && "$BAZEL_BIN" info execution_root 2>/dev/null || printf '%s' "$WORKSPACE_ROOT")
# The model contains Bazel exec paths. Resolve the model's runfiles symlink to
# recover the exact execution root that produced it; this also works when the
# outer `bazel run` used a non-default --output_base.
MODEL_REAL=$(realpath "$MODEL_FILE")
case "$MODEL_REAL" in
  */bazel-out/*) MODEL_EXEC_ROOT="${MODEL_REAL%%/bazel-out/*}" ;;
  *) MODEL_EXEC_ROOT="$BAZEL_EXEC_ROOT" ;;
esac
HOT_RELOAD_ARGS=()
RESOURCES_VALUE=""

# Prefixing helper: accumulate into an array (O(1) append) and join once.
# String accumulation in a loop is quadratic in bash and takes minutes on
# large entry counts (many local workspace jars).
_join_comma() {
  local IFS=','
  printf '%s' "$*"
}

# Resolve resource dirs to absolute paths, keeping only ones that exist
if [ -n "$RESOURCE_DIRS" ]; then
    RD_ABS=()
    IFS=',' read -ra RD_ENTRIES <<< "$RESOURCE_DIRS"
    for rd in "${RD_ENTRIES[@]}"; do
        abs_rd="${WORKSPACE_ROOT}/${rd}"
        if [ -d "$abs_rd" ]; then
            RD_ABS+=("$abs_rd")
        fi
    done
    if [ "${#RD_ABS[@]}" -gt 0 ]; then
        RESOURCES_VALUE=$(_join_comma "${RD_ABS[@]}")
    fi
fi

if [ -n "$SOURCE_DIRS" ] && [ -n "$BAZEL_TARGETS" ]; then
    CLASSES_DIR=$(mktemp -d "${TMPDIR:-/tmp}/quarkus_hotreload_classes_XXXXXX")

    # Resolve source dirs to absolute paths
    SD_ABS=()
    IFS=',' read -ra SD_ENTRIES <<< "$SOURCE_DIRS"
    for sd in "${SD_ENTRIES[@]}"; do
        SD_ABS+=("${WORKSPACE_ROOT}/${sd}")
    done
    ABS_SOURCE_DIRS=$(_join_comma "${SD_ABS[@]}")

    # Resolve classes output dirs to absolute paths
    COD_ABS=()
    IFS=',' read -ra COD_ENTRIES <<< "$CLASSES_OUTPUT_DIRS"
    for cod in "${COD_ENTRIES[@]}"; do
        COD_ABS+=("${MODEL_EXEC_ROOT}/${cod}")
    done
    ABS_CLASSES_OUTPUT_DIRS=$(_join_comma "${COD_ABS[@]}")

    HOT_RELOAD_ARGS=(
      "--source-dirs" "$ABS_SOURCE_DIRS"
      "--classes-dir" "$CLASSES_DIR"
      "--bazel-targets" "$BAZEL_TARGETS"
      "--classes-output-dirs" "$ABS_CLASSES_OUTPUT_DIRS"
    )
fi

# Use a JDK @argfile to pass all java arguments, avoiding E2BIG.
# This keeps the actual execve argv to just: java @argfile
# JDK argfile tokenizes on whitespace; values with spaces must be double-quoted,
# and backslash/double-quote must be escaped inside the quotes.
_q() {
  local v="$1"
  v="${v//\\/\\\\}"
  v="${v//\"/\\\"}"
  printf '"%s"\n' "$v"
}

_JAVA_ARGFILE=$(mktemp "${OUTPUT_DIR}/quarkus_dev_args_XXXXXX")
{
  echo "-Djava.util.logging.manager=org.jboss.logmanager.LogManager"
  _q "-jar"
  _q "$TOOL_JAR"
  echo "augmentation"
  echo "--application-classpath-file"
  _q "$ABS_APP_CP_FILE"
  echo "--local-app-jars-file"
  _q "$ABS_LOCAL_APP_JARS_FILE"
  echo "--application-model"
  _q "$MODEL_FILE"
  echo "--core-deployment-classpath-file"
  _q "$ABS_CORE_DEPLOY_CP_FILE"
  echo "--output-dir"
  _q "$OUTPUT_DIR"
  echo "--mode"
  echo "dev"
  echo "--app-name"
  echo "%{app_name}"
  echo "--workspace-dir"
  _q "$WORKSPACE_ROOT"
  echo "--bazel-command"
  _q "$BAZEL_BIN"
  if [ -n "%{dev_build_args}" ]; then
    echo "--bazel-build-args"
    _q "%{dev_build_args}"
  fi
  if [ -n "$RESOURCES_VALUE" ]; then
    echo "--resources"
    _q "$RESOURCES_VALUE"
  fi
  for arg in ${HOT_RELOAD_ARGS[@]+"${HOT_RELOAD_ARGS[@]}"}; do
    _q "$arg"
  done
} > "$_JAVA_ARGFILE"

cd "$MODEL_EXEC_ROOT"
"$JAVA" "@$_JAVA_ARGFILE" "$@" || exit $?
