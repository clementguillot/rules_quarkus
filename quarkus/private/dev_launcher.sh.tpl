#!/usr/bin/env bash
set -euo pipefail

# Ensure cleanup runs on all exit conditions including errors and signals
cleanup() {
    rm -rf "${OUTPUT_DIR:-}"
    rm -rf "${CLASSES_DIR:-}"
}
trap cleanup EXIT ERR TERM INT QUIT ABRT

RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"

# Resolve paths relative to runfiles
TOOL_JAR="${RUNFILES_DIR}/%{workspace}/%{tool_jar}"
APP_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{app_cp_file}"
DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{deploy_cp_file}"
CORE_DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{core_deploy_cp_file}"

# Read classpaths from files, prefixing each entry with the runfiles dir
APP_CP=""
while IFS=: read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$APP_CP" ]; then APP_CP="${APP_CP}:"; fi
    APP_CP="${APP_CP}${RUNFILES_DIR}/%{workspace}/${entry}"
  done
done < "$APP_CP_FILE"

DEPLOY_CP=""
while IFS=: read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$DEPLOY_CP" ]; then DEPLOY_CP="${DEPLOY_CP}:"; fi
    DEPLOY_CP="${DEPLOY_CP}${RUNFILES_DIR}/%{workspace}/${entry}"
  done
done < "$DEPLOY_CP_FILE"

CORE_DEPLOY_CP=""
while IFS=: read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$CORE_DEPLOY_CP" ]; then CORE_DEPLOY_CP="${CORE_DEPLOY_CP}:"; fi
    CORE_DEPLOY_CP="${CORE_DEPLOY_CP}${RUNFILES_DIR}/%{workspace}/${entry}"
  done
done < "$CORE_DEPLOY_CP_FILE"

# Read source directories for hot-reload
SOURCE_DIRS_FILE="${RUNFILES_DIR}/%{workspace}/%{source_dirs_file}"
SOURCE_DIRS=""
if [ -f "$SOURCE_DIRS_FILE" ]; then
    SOURCE_DIRS=$(cat "$SOURCE_DIRS_FILE")
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
OUTPUT_DIR=$(mktemp -d "quarkus_dev_output_XXXXXX")
CLASSES_DIR=""

# Resolve absolute paths for hot-reload if source dirs are available
WORKSPACE_ROOT="${BUILD_WORKSPACE_DIRECTORY:-$(pwd)}"
BAZEL_EXEC_ROOT=$(bazel info execution_root 2>/dev/null || printf '%s' "$WORKSPACE_ROOT")
HOT_RELOAD_ARGS=()
if [ -n "$SOURCE_DIRS" ] && [ -n "$BAZEL_TARGETS" ]; then
    CLASSES_DIR=$(mktemp -d "quarkus_hotreload_classes_XXXXXX")

    # Resolve source dirs to absolute paths
    ABS_SOURCE_DIRS=""
    IFS=',' read -ra SD_ENTRIES <<< "$SOURCE_DIRS"
    for sd in "${SD_ENTRIES[@]}"; do
        abs_sd="${WORKSPACE_ROOT}/${sd}"
        if [ -n "$ABS_SOURCE_DIRS" ]; then ABS_SOURCE_DIRS="${ABS_SOURCE_DIRS},"; fi
        ABS_SOURCE_DIRS="${ABS_SOURCE_DIRS}${abs_sd}"
    done

    # Resolve classes output dirs to absolute paths
    ABS_CLASSES_OUTPUT_DIRS=""
    IFS=',' read -ra COD_ENTRIES <<< "$CLASSES_OUTPUT_DIRS"
    for cod in "${COD_ENTRIES[@]}"; do
        abs_cod="${BAZEL_EXEC_ROOT}/${cod}"
        if [ -n "$ABS_CLASSES_OUTPUT_DIRS" ]; then ABS_CLASSES_OUTPUT_DIRS="${ABS_CLASSES_OUTPUT_DIRS},"; fi
        ABS_CLASSES_OUTPUT_DIRS="${ABS_CLASSES_OUTPUT_DIRS}${abs_cod}"
    done

    HOT_RELOAD_ARGS=(
      "--source-dirs" "$ABS_SOURCE_DIRS"
      "--classes-dir" "$CLASSES_DIR"
      "--bazel-targets" "$BAZEL_TARGETS"
      "--classes-output-dirs" "$ABS_CLASSES_OUTPUT_DIRS"
      "--workspace-dir" "$WORKSPACE_ROOT"
    )
fi

java \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$TOOL_JAR" \
  --application-classpath "$APP_CP" \
  --deployment-classpath "$DEPLOY_CP" \
  --core-deployment-classpath "$CORE_DEPLOY_CP" \
  --output-dir "$OUTPUT_DIR" \
  --mode dev \
  --expected-quarkus-version %{quarkus_version} \
  --app-name %{app_name} \
  ${HOT_RELOAD_ARGS[@]+"${HOT_RELOAD_ARGS[@]}"} \
  "$@" || exit $?
