#!/usr/bin/env bash
set -euo pipefail

RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"

# Resolve paths relative to runfiles
TOOL_JAR="${RUNFILES_DIR}/%{workspace}/%{tool_jar}"
APP_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{app_cp_file}"
DEPLOY_CP_FILE="${RUNFILES_DIR}/%{workspace}/%{deploy_cp_file}"

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

# Read source directories for hot-reload
SOURCE_DIRS_FILE="${RUNFILES_DIR}/%{workspace}/%{source_dirs_file}"
SOURCE_DIRS=""
if [ -f "$SOURCE_DIRS_FILE" ]; then
    SOURCE_DIRS=$(cat "$SOURCE_DIRS_FILE")
fi

# Create a temp output dir (required by quarkifier CLI)
OUTPUT_DIR=$(mktemp -d)
trap "rm -rf $OUTPUT_DIR" EXIT

exec java \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$TOOL_JAR" \
  --application-classpath "$APP_CP" \
  --deployment-classpath "$DEPLOY_CP" \
  --output-dir "$OUTPUT_DIR" \
  --mode dev \
  --expected-quarkus-version %{quarkus_version} \
  --app-name %{app_name} \
  ${SOURCE_DIRS:+--source-dirs "$SOURCE_DIRS"} \
  "$@"
