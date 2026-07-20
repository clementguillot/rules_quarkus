#!/usr/bin/env bash
set -euo pipefail
set -m

LAUNCHER_RLOCATION="${1:?usage: dev_ui_application_model_test.sh <app_dev launcher>}"
LAUNCHER="${TEST_SRCDIR}/${LAUNCHER_RLOCATION}"
WORK="${TEST_TMPDIR}/dev-ui-model"
mkdir -p "${WORK}"

# A data dependency contributes its runfiles to this test's merged tree, not a sibling tree beside
# the launcher. Copy the script and point it directly at that merged root.
cp "${LAUNCHER}" "${WORK}/dev.sh"
sed -i.bak "s|^RUNFILES_DIR=.*|RUNFILES_DIR=\"${TEST_SRCDIR}\"|" "${WORK}/dev.sh"

# Quarkus runs DevModeMain and Dev UI bootstrap code on the system classloader. Match the
# Maven/Gradle dev launcher contract: the core manifest classpath must include the Maven resolver
# closure, not just quarkus-core-deployment. Without it the Extensions page fails its JSON-RPC call
# with NoClassDefFoundError: SettingsDecryptionRequest.
CORE_CP_RLOCATION=$(sed -n 's|^CORE_DEPLOY_CP_FILE="${RUNFILES_DIR}/\(.*\)"$|\1|p' "${WORK}/dev.sh")
CORE_CP_FILE="${TEST_SRCDIR}/${CORE_CP_RLOCATION}"
grep -Fq '/quarkus-bootstrap-maven-resolver/' "${CORE_CP_FILE}"
grep -Fq '/org/apache/maven/maven-settings-builder/' "${CORE_CP_FILE}"

cd "${WORK}"
export QUARKUS_HTTP_PORT=0
LOG="${WORK}/dev.log"
bash dev.sh >"${LOG}" 2>&1 &
DEV_PID=$!

cleanup() {
  kill -TERM -- "-${DEV_PID}" 2>/dev/null || true
  wait "${DEV_PID}" 2>/dev/null || true
}
trap cleanup EXIT

BASE_URL=""
for _ in $(seq 1 60); do
  if ! kill -0 "${DEV_PID}" 2>/dev/null; then
    echo "FAIL: dev launcher exited before Quarkus started" >&2
    tail -n 80 "${LOG}" >&2
    exit 1
  fi
  BASE_URL=$(grep -Eo 'http://localhost:[0-9]+' "${LOG}" | tail -1 || true)
  if [ -n "${BASE_URL}" ]; then
    break
  fi
  sleep 1
done

if [ -z "${BASE_URL}" ]; then
  echo "FAIL: timed out waiting for the Dev UI application" >&2
  tail -n 80 "${LOG}" >&2
  exit 1
fi

DATA="${WORK}/devui-data.js"
curl -fsS "${BASE_URL}/q/dev-ui/devui-data.js" -o "${DATA}"

APP='bazel.workspace:app:1.0.0-SNAPSHOT'
EXT='com.example.smoke:smoke-extension:1.0.0'
ARC='io.quarkus:quarkus-arc:3.33.2'

grep -Fq "\"source\":\"${APP}\",\"target\":\"${EXT}\"" "${DATA}"
grep -Fq "\"source\":\"${EXT}\",\"target\":\"${ARC}\"" "${DATA}"
grep -Fq '"title":"Application Dependencies"' "${DATA}"

if grep -Fq 'Failed to process extension descriptor' "${LOG}" || \
   grep -Fq 'Failed to locate META-INF/quarkus-extension.properties' "${LOG}" || \
   grep -Fq 'SettingsDecryptionRequest' "${LOG}"; then
  echo "FAIL: Dev UI could not read the local extension descriptor" >&2
  tail -n 80 "${LOG}" >&2
  exit 1
fi

echo "PASS: Dev UI exposes the nested Bazel ApplicationModel graph"
