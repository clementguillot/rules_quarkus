#!/usr/bin/env bash
set -euo pipefail

model="${RUNFILES_DIR:?}/$1"
coordinate="io.quarkus:quarkus-rest-kotlin::jar:3.33.2"
activated_node="\"id\":\"conditional:${coordinate}\""

if ! grep -Fq "${activated_node}" "${model}"; then
  echo "Expected production conditional dependency ${coordinate} to be activated" >&2
  exit 1
fi
