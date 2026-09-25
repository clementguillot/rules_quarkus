#!/usr/bin/env bash
set -euo pipefail
exec python3 "${TEST_SRCDIR}/_main/continuous_testing_test.py" "$@"
