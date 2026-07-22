#!/usr/bin/env bash
set -uo pipefail

# Resolve runfiles directory
if [ -z "${RUNFILES_DIR:-}" ]; then
  if [ -z "${TEST_SRCDIR:-}" ]; then
    RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"
  else
    RUNFILES_DIR="$TEST_SRCDIR"
  fi
fi

WORKSPACE_DIR="${RUNFILES_DIR}/%{workspace}"
TOOL_JAR="${WORKSPACE_DIR}/%{tool_jar}"
JAVA="${WORKSPACE_DIR}/%{java_home}/bin/java"
MODEL_FILE="${WORKSPACE_DIR}/%{model_file}"
MODEL_REAL=$(realpath "$MODEL_FILE")
case "$MODEL_REAL" in
  */bazel-out/*) MODEL_EXEC_ROOT="${MODEL_REAL%%/bazel-out/*}" ;;
  *) MODEL_EXEC_ROOT="$WORKSPACE_DIR" ;;
esac

# Stream classpaths into temp files using awk (O(n), safe with special chars in paths).
# Each source file has separator-delimited relative paths; we prefix with WORKSPACE_DIR.
# The prefix and separator are passed via the environment: ENVIRON values are
# byte-exact, whereas awk -v would reinterpret backslash escape sequences.
_prefix_entries() {
  local sep="$1" src="$2" dest="$3"
  CP_PREFIX="${WORKSPACE_DIR}/" CP_SEP="$sep" awk \
    'BEGIN{FS=ENVIRON["CP_SEP"]; OFS=FS; p=ENVIRON["CP_PREFIX"]} {for(i=1;i<=NF;i++) $i=p $i; print}' \
    < "$src" > "$dest"
}

_realpath_entries() {
  local src="$1" dest="$2"
  while IFS=: read -ra ENTRIES; do
    local first=true
    for entry in "${ENTRIES[@]}"; do
      if [ "$first" = false ]; then printf ':' >> "$dest"; fi
      local resolved
      resolved=$(realpath "$entry") || { echo "ERROR: realpath failed for '$entry'" >&2; return 1; }
      printf '%s' "$resolved" >> "$dest"
      first=false
    done
  done < "$src"
}

# Runtime classpath (app + test jars, for both JUnit -cp and quarkifier)
APP_CP_FILE=$(mktemp)
_prefix_entries ":" "${WORKSPACE_DIR}/%{classpath_file}" "$APP_CP_FILE"
MODEL_APP_CP_FILE=$(mktemp)
_realpath_entries "$APP_CP_FILE" "$MODEL_APP_CP_FILE" || exit 1

# Direct dep jars: comma-separated in source, prefix each entry.
# Produces two files: comma-separated (for OUTPUT_SOURCES_DIR / test discovery)
# and colon-separated (for --local-app-jars-file).
DIRECT_JARS_FILE=$(mktemp)
_prefix_entries "," "${WORKSPACE_DIR}/%{direct_jars_file}" "$DIRECT_JARS_FILE"

LOCAL_APP_JARS_FILE=$(mktemp)
while IFS=, read -ra JAR_ENTRIES; do
  first=true
  for jar_path in "${JAR_ENTRIES[@]}"; do
    if [ "$first" = false ]; then printf ':' >> "$LOCAL_APP_JARS_FILE"; fi
    resolved_jar=$(realpath "$jar_path") || { echo "ERROR: realpath failed for '$jar_path'" >&2; exit 1; }
    printf '%s' "$resolved_jar" >> "$LOCAL_APP_JARS_FILE"
    first=false
  done
done < "$DIRECT_JARS_FILE"

# Phase 1: Generate serialized ApplicationModel at test time.
MODEL_DIR=$(mktemp -d)
trap "rm -rf $MODEL_DIR" EXIT

(cd "$MODEL_EXEC_ROOT" && "$JAVA" \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$TOOL_JAR" \
  augmentation \
  --application-classpath-file "$MODEL_APP_CP_FILE" \
  --output-dir "$MODEL_DIR" \
  --mode test \
  --application-model "$MODEL_FILE" \
  --local-app-jars-file "$LOCAL_APP_JARS_FILE" \
  --app-name %{app_name})

if [ $? -ne 0 ]; then
  rm -f "$APP_CP_FILE" "$MODEL_APP_CP_FILE" "$LOCAL_APP_JARS_FILE" "$DIRECT_JARS_FILE"
  echo "ERROR: Quarkifier test model generation failed" >&2
  exit 1
fi

# Keep APP_CP_FILE and DIRECT_JARS_FILE for phase 2; clean the rest.
rm -f "$MODEL_APP_CP_FILE" "$LOCAL_APP_JARS_FILE"

if [ ! -f "$MODEL_DIR/test-app-model.dat" ]; then
  rm -f "$APP_CP_FILE" "$DIRECT_JARS_FILE"
  echo "ERROR: test-app-model.dat was not generated" >&2
  exit 1
fi

# Phase 2: Run JUnit with the serialized model.
# OUTPUT_SOURCES_DIR tells AppMakerHelper to add the user's jars to the
# application root so Quarkus scans them for @Path endpoints and CDI beans.
# Auto-discover test classes from user jars if no explicit selectors given.
# Scan direct dep jars for .class files matching JUnit's default test pattern.
TEST_ARGS="%{test_args}"
JAR_CMD="${WORKSPACE_DIR}/%{java_home}/bin/jar"
if ! echo "$TEST_ARGS" | grep -q '\-\-select-\|--scan-'; then
  while IFS=, read -ra JAR_ENTRIES; do
    for jar_path in "${JAR_ENTRIES[@]}"; do
      if [ -f "$jar_path" ]; then
        while IFS= read -r cls; do
          cls="${cls%.class}"
          cls="${cls//\//.}"
          if echo "$cls" | grep -qE '(^Test|[.$]Test|Tests?$)' && ! echo "$cls" | grep -qE 'IT$'; then
            TEST_ARGS="${TEST_ARGS} --select-class=${cls}"
          fi
        done < <("$JAR_CMD" tf "$jar_path" 2>/dev/null | grep '\.class$' | grep -v '\$')
      fi
    done
  done < "$DIRECT_JARS_FILE"
fi

REPORTS_DIR=$(mktemp -d)

# Use a JDK @argfile to avoid E2BIG on the -cp and -DOUTPUT_SOURCES_DIR args.
# Values are double-quoted per JDK argfile syntax to handle paths with spaces;
# backslash and double-quote are escaped inside the quotes.
_argfile_escape() { tr -d '\n' | sed 's/\\/\\\\/g; s/"/\\"/g'; }

JAVA_ARGS_FILE=$(mktemp)
{
  printf '%s\n' "-cp"
  printf '"'
  _argfile_escape < "$APP_CP_FILE"
  printf '"\n'
  printf '"'
  printf '%s' "-DOUTPUT_SOURCES_DIR="
  _argfile_escape < "$DIRECT_JARS_FILE"
  printf '"\n'
} > "$JAVA_ARGS_FILE"

rm -f "$DIRECT_JARS_FILE"

"$JAVA" "@$JAVA_ARGS_FILE" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -Dquarkus-internal-test.serialized-app-model.path="$MODEL_DIR/test-app-model.dat" \
  -Dplatform.quarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25@sha256:4dda6a3d677b57614849557d0d18aac7326c4f30175142b0f1bb91bdcfc5c29a \
  -Dquarkus.package.jar.type=fast-jar \
  %{jvm_flags} \
  org.junit.platform.console.ConsoleLauncher \
  $TEST_ARGS \
  --reports-dir="$REPORTS_DIR"
JUNIT_EXIT=$?

rm -f "$JAVA_ARGS_FILE" "$APP_CP_FILE"

# Determine final exit code.
# ConsoleLauncher exits non-zero on Quarkus session-shutdown exceptions
# (ClassCastException in ConfigLauncherSession) even when all tests
# pass. The XML report is the source of truth for pass/fail, with one guard:
# if zero tests actually executed, that's a failure (misconfigured test_packages,
# no @Test methods, etc.) regardless of what the exit code says.
if ls "$REPORTS_DIR"/TEST-*.xml >/dev/null 2>&1; then
  if grep -q 'failures="[1-9]' "$REPORTS_DIR"/TEST-*.xml 2>/dev/null || \
     grep -q 'errors="[1-9]' "$REPORTS_DIR"/TEST-*.xml 2>/dev/null; then
    rm -rf "$REPORTS_DIR"
    exit 1
  fi
  if [ "%{fail_if_no_tests}" = "true" ] && \
     ! grep -q 'tests="[1-9]' "$REPORTS_DIR"/TEST-*.xml 2>/dev/null; then
    rm -rf "$REPORTS_DIR"
    echo "ERROR: Zero tests executed (check test_packages / test_classes configuration)" >&2
    exit 1
  fi
  rm -rf "$REPORTS_DIR"
  exit 0
fi

rm -rf "$REPORTS_DIR"
exit $JUNIT_EXIT
