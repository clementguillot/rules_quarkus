#!/usr/bin/env bash
set -euo pipefail

RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"

# Resolve paths relative to runfiles
TOOL_JAR="${RUNFILES_DIR}/%{workspace}/%{tool_jar}"
APP_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{app_cp_file}"
DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{deploy_cp_file}"
CORE_DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{core_deploy_cp_file}"
FILE_WATCHER_JAR="${RUNFILES_DIR}/%{workspace}/%{file_watcher_jar}"

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

# Read Bazel targets and classes output dirs for the file watcher
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

# Create a temp output dir (required by quarkifier CLI)
OUTPUT_DIR=$(mktemp -d)

# Track background process PIDs for cleanup
WATCHER_PID=""
CLASSES_DIR=""

cleanup() {
    if [ -n "$WATCHER_PID" ]; then
        kill "$WATCHER_PID" 2>/dev/null || true
        wait "$WATCHER_PID" 2>/dev/null || true
    fi
    rm -rf "$OUTPUT_DIR"
    if [ -n "$CLASSES_DIR" ]; then
        rm -rf "$CLASSES_DIR"
    fi
}
trap cleanup EXIT INT TERM

# Hot-reload: start the file watcher if source dirs are available
if [ -n "$SOURCE_DIRS" ] && [ -n "$BAZEL_TARGETS" ]; then
    # Create a mutable classes directory for hot-reload class syncing
    CLASSES_DIR=$(mktemp -d)

    # Resolve source dirs to absolute paths using the workspace directory.
    # BUILD_WORKSPACE_DIRECTORY is set by `bazel run` to the workspace root.
    WORKSPACE_ROOT="${BUILD_WORKSPACE_DIRECTORY:-$(pwd)}"
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
        abs_cod="${WORKSPACE_ROOT}/${cod}"
        if [ -n "$ABS_CLASSES_OUTPUT_DIRS" ]; then ABS_CLASSES_OUTPUT_DIRS="${ABS_CLASSES_OUTPUT_DIRS},"; fi
        ABS_CLASSES_OUTPUT_DIRS="${ABS_CLASSES_OUTPUT_DIRS}${abs_cod}"
    done

    java -jar "$FILE_WATCHER_JAR" \
      --source-dirs "$ABS_SOURCE_DIRS" \
      --bazel-targets "$BAZEL_TARGETS" \
      --classes-dir "$CLASSES_DIR" \
      --classes-output-dirs "$ABS_CLASSES_OUTPUT_DIRS" \
      --workspace-dir "$WORKSPACE_ROOT" &
    WATCHER_PID=$!
fi

# Launch the Quarkus dev mode process.
# Note: we use 'exec' only when no watcher is running (backward compat).
# When the watcher is running, we use a regular invocation so the trap
# can clean up both processes.
if [ -n "$WATCHER_PID" ]; then
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
      --source-dirs "$SOURCE_DIRS" \
      --classes-dir "$CLASSES_DIR" \
      --bazel-targets "$BAZEL_TARGETS" \
      "$@"
else
    exec java \
      -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
      -jar "$TOOL_JAR" \
      --application-classpath "$APP_CP" \
      --deployment-classpath "$DEPLOY_CP" \
      --core-deployment-classpath "$CORE_DEPLOY_CP" \
      --output-dir "$OUTPUT_DIR" \
      --mode dev \
      --expected-quarkus-version %{quarkus_version} \
      --app-name %{app_name} \
      ${SOURCE_DIRS:+--source-dirs "$SOURCE_DIRS"} \
      "$@"
fi
