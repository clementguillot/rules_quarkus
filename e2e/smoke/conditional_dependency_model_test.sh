#!/usr/bin/env bash
set -euo pipefail

model="${RUNFILES_DIR:?}/$1"
coordinate="io.quarkus:quarkus-rest-kotlin::jar:3.33.2"

if ! grep -Fq "conditional dependency activated: ${coordinate}" "${model}"; then
  echo "Expected production conditional dependency ${coordinate} to be activated" >&2
  exit 1
fi

if grep -Fq "conditional dependency unsatisfied: ${coordinate}" "${model}"; then
  echo "Activated production conditional dependency was also reported unsatisfied" >&2
  exit 1
fi
