#!/usr/bin/env bash
set -euo pipefail
RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"
APP_DIR="${RUNFILES_DIR}/%{workspace}/%{output_dir}/quarkus-app"
exec java %{jvm_flags} %{main_class_flag} -jar "${APP_DIR}/quarkus-run.jar" "$@"
