#!/usr/bin/env bash
set -euo pipefail

runfiles_root="${TEST_SRCDIR}/${TEST_WORKSPACE}"
targets_file=$(find "$runfiles_root" -name 'app_dev_bazel_targets.txt' -print -quit)
input_dirs_file=$(find "$runfiles_root" -name 'app_dev_codegen_input_dirs.txt' -print -quit)

if [[ -z "$targets_file" || -z "$input_dirs_file" ]]; then
  echo "dev codegen metadata was not present in runfiles" >&2
  exit 1
fi
if [[ $(<"$targets_file") != "//:app_dev" ]]; then
  echo "hot reload must rebuild //:app_dev under its lifecycle transition" >&2
  exit 1
fi
if tr ',' '\n' <"$input_dirs_file" | grep -qx 'src/main'; then
  echo "codegen watcher uses the broad source parent instead of declared input directories" >&2
  exit 1
fi
if ! tr ',' '\n' <"$input_dirs_file" | grep -qx 'src/main/proto'; then
  echo "codegen watcher does not include the declared proto input directory" >&2
  exit 1
fi
