#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"
WORKSPACE_DIR="${RUNFILES_DIR}/%{workspace}"
RUNNER_JAR="${WORKSPACE_DIR}/%{output_dir}/%{runner_path}"
JAVA="${WORKSPACE_DIR}/%{java_home}/bin/java"
exec "$JAVA" %{jvm_flags} %{main_class_flag} -jar "$RUNNER_JAR" "$@"
