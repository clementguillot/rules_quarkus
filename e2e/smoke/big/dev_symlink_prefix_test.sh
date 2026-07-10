#!/usr/bin/env bash
# Regression test for issue #132: exec_root resolution without convenience symlinks.
#
# Verifies that the dev launcher resolves BAZEL_EXEC_ROOT by running
# `bazel info execution_root` from the WORKSPACE directory (not the runfiles
# cwd). Without the fix, the launcher runs it from the runfiles tree, which
# spawns a stray Bazel server and falls back to $WORKSPACE_ROOT — making
# --classes-output-dirs paths dangle when the default bazel-out symlink is
# absent.
#
# Strategy: inject a `bazel` shim onto PATH that records the working directory
# when `info execution_root` is invoked, then assert it matches the workspace.
set -uo pipefail
set -m

LAUNCHER_RLOCATION="${1:?usage: dev_symlink_prefix_test.sh <rlocationpath of the app_dev launcher>}"
LAUNCHER="${TEST_SRCDIR}/${LAUNCHER_RLOCATION}"

WORK="${TEST_TMPDIR}/devrun"
mkdir -p "${WORK}"

# Copy launcher and fake runfiles (same pattern as dev_e2big_test.sh).
cp "${LAUNCHER}" "${WORK}/dev.sh"
ln -sfn "${TEST_SRCDIR}" "${WORK}/dev.sh.runfiles"

# --- Fake workspace (no bazel-out symlink) ---
FAKE_WORKSPACE="${TEST_TMPDIR}/fake_ws"
mkdir -p "${FAKE_WORKSPACE}"
touch "${FAKE_WORKSPACE}/MODULE.bazel"

# --- Bazel shim: records cwd for `info execution_root`, then exits ---
SHIM_DIR="${TEST_TMPDIR}/shim"
mkdir -p "${SHIM_DIR}"
SHIM_LOG="${TEST_TMPDIR}/shim_log.txt"

cat > "${SHIM_DIR}/bazel" <<'SHIM'
#!/usr/bin/env bash
# Shim that intercepts `bazel info execution_root` and records the cwd.
if [[ "${1:-}" == "info" && "${2:-}" == "execution_root" ]]; then
  printf 'CWD=%s\n' "$(pwd)" >> "$SHIM_LOG"
  # Return a fake exec root to prove the launcher uses it.
  printf '%s\n' "${FAKE_EXEC_ROOT}"
  exit 0
fi
# For any other bazel subcommand, delegate to the real binary (if reachable).
# In this test we don't expect other calls, so just succeed.
exit 0
SHIM
chmod +x "${SHIM_DIR}/bazel"

# Export vars needed by the shim and the launcher.
export SHIM_LOG
export FAKE_EXEC_ROOT="${TEST_TMPDIR}/fake_exec_root"
mkdir -p "${FAKE_EXEC_ROOT}"

export BUILD_WORKSPACE_DIRECTORY="${FAKE_WORKSPACE}"
export QUARKUS_HTTP_PORT=0
# Put shim first on PATH so the launcher picks it up via `command -v bazel`.
export PATH="${SHIM_DIR}:${PATH}"

cd "${WORK}"

LOG="${WORK}/dev.log"
# The launcher will invoke our shim for `bazel info execution_root`, get back
# FAKE_EXEC_ROOT, then try to run java. Java will fail (no real JDK at the
# runfiles path in this sandboxed test), but that's fine — we only care about
# the cwd assertion.
bash dev.sh >"${LOG}" 2>&1 &
DEV_PID=$!

cleanup() {
  kill -TERM -- "-${DEV_PID}" 2>/dev/null || true
  wait "${DEV_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# Wait for the launcher to invoke the shim (should be nearly instant).
for _ in $(seq 1 30); do
  if [ -f "${SHIM_LOG}" ]; then
    break
  fi
  sleep 0.2
done

# Give the launcher a moment to finish or fail after the shim call.
sleep 1

# --- Assertions ---

if [ ! -f "${SHIM_LOG}" ]; then
  echo "FAIL: bazel shim was never called — launcher didn't run 'bazel info'" >&2
  tail -n 30 "${LOG}" >&2
  exit 1
fi

RECORDED_CWD=$(grep '^CWD=' "${SHIM_LOG}" | head -1 | cut -d= -f2-)

if [ -z "${RECORDED_CWD}" ]; then
  echo "FAIL: shim log has no CWD entry" >&2
  cat "${SHIM_LOG}" >&2
  exit 1
fi

# The fix: launcher must cd to $WORKSPACE_ROOT before calling bazel info.
# Without the fix, cwd will be $WORK (the runfiles-adjacent directory).
if [ "${RECORDED_CWD}" = "${FAKE_WORKSPACE}" ]; then
  echo "PASS: bazel info execution_root was called from the workspace dir (issue #132)"
  exit 0
else
  echo "FAIL: bazel info execution_root was called from '${RECORDED_CWD}'" >&2
  echo "      expected: '${FAKE_WORKSPACE}'" >&2
  echo "      This means the launcher didn't cd to \$WORKSPACE_ROOT before" >&2
  echo "      invoking bazel — the bug from issue #132." >&2
  exit 1
fi
