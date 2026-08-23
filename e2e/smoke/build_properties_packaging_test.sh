#!/usr/bin/env bash
set -euo pipefail

# quarkus.package.jar.include-dependency-list defaults to true, so the packaged
# app carries quarkus-app-dependencies.txt unless a declared build property says
# otherwise. Asserting absence proves the declared value reached augmentation.
app_dir="${TEST_SRCDIR}/${TEST_WORKSPACE}/app-quarkus-app/quarkus-app"
dependency_list="${app_dir}/quarkus-app-dependencies.txt"

if [[ ! -d "$app_dir" ]]; then
  echo "Expected packaged application directory $app_dir" >&2
  exit 1
fi
if [[ ! -f "${app_dir}/quarkus-run.jar" ]]; then
  echo "Expected packaged runner ${app_dir}/quarkus-run.jar" >&2
  exit 1
fi
if [[ -e "$dependency_list" ]]; then
  echo "Declared build property was ignored: $dependency_list still exists" >&2
  exit 1
fi
