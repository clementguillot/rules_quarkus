#!/usr/bin/env bash
set -euo pipefail

report="$1"
test -f "${report}/reports/GeneratedGreeting.txt"
grep -Fx "Declared init Hello from rules_quarkus smoke test" \
  "${report}/reports/GeneratedGreeting.txt"

grpc_work="$2"
test -s "${grpc_work}/descriptors/descriptor_set.dsc"
