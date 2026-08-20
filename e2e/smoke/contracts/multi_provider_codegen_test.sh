#!/usr/bin/env bash
set -euo pipefail

# One generation action must run both provider families: gRPC over
# src/main/proto and Avro over src/main/avro.
generated="$1"

if ! find "$generated" -name 'Contract.java' -print -quit | grep -q .; then
  echo "gRPC provider did not generate Contract.java into ${generated}" >&2
  exit 1
fi
if ! find "$generated" -name 'AuditEvent.java' -print -quit | grep -q .; then
  echo "Avro provider did not generate AuditEvent.java into ${generated}" >&2
  exit 1
fi
