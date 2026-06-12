#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"
WORKSPACE_DIR="${RUNFILES_DIR}/%{workspace}"
APP_DIR="${WORKSPACE_DIR}/%{output_dir}/quarkus-app"
JAVA="${WORKSPACE_DIR}/%{java_home}/bin/java"
exec "$JAVA" %{jvm_flags} %{main_class_flag} -jar "${APP_DIR}/quarkus-run.jar" "$@"
