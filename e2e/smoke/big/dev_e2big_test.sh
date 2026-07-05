#!/usr/bin/env bash
# Regression test for findings/01-dev-mode-e2big.md (TDD: fails until fixed).
#
# Launches the quarkus_dev launcher for an app whose runtime classpath joins
# to ~2 MB — past macOS ARG_MAX (1 MiB total) and Linux MAX_ARG_STRLEN
# (128 KiB per argument). The launcher must hand those classpaths to the
# quarkifier via files; today it passes them as raw argv and execve fails
# with E2BIG ("Argument list too long").
#
# Pass criterion: Quarkus dev mode reaches "Listening on:".
set -uo pipefail
set -m # own process group for the background job, so we can kill its tree

LAUNCHER_RLOCATION="${1:?usage: dev_e2big_test.sh <rlocationpath of the app_dev launcher>}"
LAUNCHER="${TEST_SRCDIR}/${LAUNCHER_RLOCATION}"

WORK="${TEST_TMPDIR}/devrun"
mkdir -p "${WORK}"

# The dev launcher resolves its runfiles as "<script path>.runfiles". Running
# as a data dep of this test, it has no runfiles tree of its own — but this
# test's TEST_SRCDIR is a superset (data runfiles are merged). Copy the script
# and fake the .runfiles link next to the copy.
cp "${LAUNCHER}" "${WORK}/dev.sh"
ln -sfn "${TEST_SRCDIR}" "${WORK}/dev.sh.runfiles"

# cd so the launcher's relative mktemp dirs land in the test tmpdir.
cd "${WORK}"

# Random HTTP port to avoid collisions with parallel tests / a real dev server.
export QUARKUS_HTTP_PORT=0

LOG="${WORK}/dev.log"
bash dev.sh >"${LOG}" 2>&1 &
DEV_PID=$!

cleanup() {
  kill -TERM -- "-${DEV_PID}" 2>/dev/null || true
  wait "${DEV_PID}" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 300); do
  if grep -q "Argument list too long" "${LOG}"; then
    echo "FAIL: dev launcher hit E2BIG — classpaths passed as raw argv" >&2
    echo "      (see findings/01-dev-mode-e2big.md)" >&2
    tail -n 20 "${LOG}" >&2
    exit 1
  fi
  if grep -q "Listening on:" "${LOG}"; then
    echo "PASS: dev mode started with a >ARG_MAX classpath"
    exit 0
  fi
  if ! kill -0 "${DEV_PID}" 2>/dev/null; then
    echo "FAIL: dev launcher exited before Quarkus started" >&2
    tail -n 50 "${LOG}" >&2
    exit 1
  fi
  sleep 1
done

echo "FAIL: timed out waiting for dev mode startup" >&2
tail -n 50 "${LOG}" >&2
exit 1
