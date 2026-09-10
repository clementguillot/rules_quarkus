#!/usr/bin/env bash
set -euo pipefail

runfiles_root="${TEST_SRCDIR}/${TEST_WORKSPACE}"
targets_file=$(find "$runfiles_root" -name 'app_dev_bazel_targets.txt' -print -quit)
input_files_file=$(find "$runfiles_root" -name 'app_dev_watched_inputs.txt' -print -quit)
dependency_input_files_file=$(find "$runfiles_root" -name 'dependency_only_codegen_app_dev_codegen_input_files.txt' -print -quit)

if [[ -z "$targets_file" || -z "$input_files_file" || -z "$dependency_input_files_file" ]]; then
  echo "dev codegen metadata was not present in runfiles" >&2
  exit 1
fi
if [[ $(<"$targets_file") != "//:app_dev" ]]; then
  echo "hot reload must rebuild //:app_dev under its lifecycle transition" >&2
  exit 1
fi
if grep -qx 'src/main' "$input_files_file"; then
  echo "codegen watcher uses the broad source parent instead of exact declared inputs" >&2
  exit 1
fi
if ! grep -qx 'src/main/proto/greeting.proto' "$input_files_file"; then
  echo "codegen watcher does not include the exact declared proto input" >&2
  exit 1
fi
if ! grep -qx 'src/main/resources/application.properties' "$input_files_file"; then
  echo "codegen watcher does not include exact resources consumed during provider initialization" >&2
  exit 1
fi
if ! grep -qx 'src/dependency/proto/dependency.proto' "$dependency_input_files_file"; then
  echo "codegen watcher does not include the exact dependency-only resource input" >&2
  exit 1
fi
