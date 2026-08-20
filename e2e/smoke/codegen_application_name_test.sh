#!/usr/bin/env bash
set -euo pipefail

model_file=$(find "${TEST_SRCDIR}/${TEST_WORKSPACE}" \
  -name 'lib_quarkus_codegen.quarkus-bazel-model-v1.json' -print -quit)

if [[ -z "$model_file" ]]; then
  echo "codegen application model was not present in runfiles" >&2
  exit 1
fi
if ! grep -qF '"artifactId":"lib"' "$model_file"; then
  echo "codegen application model does not use the owning library name" >&2
  exit 1
fi
if grep -qF '"artifactId":"lib_quarkus_codegen"' "$model_file"; then
  echo "codegen application model leaked the hidden helper target name" >&2
  exit 1
fi
