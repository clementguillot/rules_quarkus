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
COVERAGE_ENABLED="%{coverage_enabled}"
QUARKUS_JACOCO_PRESENT="%{quarkus_jacoco_present}"
JACOCO_RUNNER="${WORKSPACE_DIR}/%{jacoco_runner}"
COVERAGE_REPORTER="${WORKSPACE_DIR}/%{coverage_reporter}"
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

REPORTS_DIR=$(mktemp -d "${TEST_TMPDIR:-/tmp}/junit-console-reports.XXXXXX")

# ConsoleLauncher writes one JUnit XML document per test engine. Bazel expects
# one XML_OUTPUT_FILE, so wrap those trusted documents in a testsuites root.
# This report is diagnostic output only; its contents never determine the test
# exit code.
_write_bazel_test_xml() {
  local output="$1"
  local reports_dir="$2"
  local report_list
  local tmp_output="${output}.tmp.$$"
  local merge_failed=0

  report_list=$(mktemp "${TEST_TMPDIR:-/tmp}/junit-report-list.XXXXXX")
  if ! find "$reports_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print |
      LC_ALL=C sort > "$report_list"; then
    rm -f "$report_list"
    return 1
  fi
  if [ ! -s "$report_list" ]; then
    rm -f "$report_list"
    return 1
  fi

  mkdir -p "$(dirname "$output")"
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<testsuites>'
    while IFS= read -r report; do
      # JUnit emits a single XML declaration on the first line of every file.
      if ! tail -n +2 "$report"; then
        merge_failed=1
        break
      fi
    done < "$report_list"
    printf '%s\n' '</testsuites>'
  } > "$tmp_output"
  rm -f "$report_list"

  if [ "$merge_failed" -ne 0 ]; then
    rm -f "$tmp_output"
    return 1
  fi
  mv "$tmp_output" "$output"
}

# Bazel instruments local jars before the test starts. Its runner jar is used
# only for the matching offline JaCoCo runtime; ConsoleLauncher remains main.
TEST_JVM_ARGS=(
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager"
)
COVERAGE_EXEC_FILE=""
if [ "$COVERAGE_ENABLED" = "true" ]; then
  COVERAGE_EXEC_FILE=$(mktemp "${TEST_TMPDIR:-/tmp}/quarkus-jacoco.XXXXXX")
  TEST_JVM_ARGS+=(
    "-Djacoco-agent.output=file"
    "-Djacoco-agent.append=false"
    "-Djacoco-agent.dumponexit=true"
    "-Djacoco-agent.destfile=${COVERAGE_EXEC_FILE}"
  )
  if [ "$QUARKUS_JACOCO_PRESENT" = "true" ]; then
    TEST_JVM_ARGS+=("-Dquarkus.jacoco.enabled=false")
  fi
elif [ "$QUARKUS_JACOCO_PRESENT" = "true" ]; then
  if [ -z "${TEST_UNDECLARED_OUTPUTS_DIR:-}" ]; then
    echo "ERROR: TEST_UNDECLARED_OUTPUTS_DIR is required for Quarkus JaCoCo reports" >&2
    exit 1
  fi
  mkdir -p "$TEST_UNDECLARED_OUTPUTS_DIR"
  TEST_JVM_ARGS+=(
    "-Dquarkus.jacoco.data-file=${TEST_UNDECLARED_OUTPUTS_DIR}/jacoco-quarkus.exec"
    "-Dquarkus.jacoco.report-location=${TEST_UNDECLARED_OUTPUTS_DIR}/jacoco-report"
  )
fi

# Use a JDK @argfile to avoid E2BIG on the -cp and -DOUTPUT_SOURCES_DIR args.
# Values are double-quoted per JDK argfile syntax to handle paths with spaces;
# backslash and double-quote are escaped inside the quotes.
_argfile_escape() { tr -d '\n' | sed 's/\\/\\\\/g; s/"/\\"/g'; }

JAVA_ARGS_FILE=$(mktemp)
{
  printf '%s\n' "-cp"
  printf '"'
  if [ "$COVERAGE_ENABLED" = "true" ]; then
    printf '%s:' "$JACOCO_RUNNER" | _argfile_escape
  fi
  _argfile_escape < "$APP_CP_FILE"
  printf '"\n'
  printf '"'
  printf '%s' "-DOUTPUT_SOURCES_DIR="
  _argfile_escape < "$DIRECT_JARS_FILE"
  printf '"\n'
} > "$JAVA_ARGS_FILE"

"$JAVA" "@$JAVA_ARGS_FILE" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  -Dquarkus-internal-test.serialized-app-model.path="$MODEL_DIR/test-app-model.dat" \
  -Dplatform.quarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25@sha256:4dda6a3d677b57614849557d0d18aac7326c4f30175142b0f1bb91bdcfc5c29a \
  -Dquarkus.package.jar.type=fast-jar \
  "${TEST_JVM_ARGS[@]}" \
  %{jvm_flags} \
  org.junit.platform.console.ConsoleLauncher \
  $TEST_ARGS \
  --reports-dir="$REPORTS_DIR"
FINAL_EXIT=$?

rm -f "$JAVA_ARGS_FILE" "$APP_CP_FILE"

# Publish JUnit's real test suites to Bazel. A reporting failure can fail an
# otherwise successful test, but never replaces or hides a JUnit failure.
if [ -n "${XML_OUTPUT_FILE:-}" ] &&
    ! _write_bazel_test_xml "$XML_OUTPUT_FILE" "$REPORTS_DIR"; then
  echo "ERROR: Failed to write Bazel JUnit XML report" >&2
  if [ "$FINAL_EXIT" -eq 0 ]; then
    FINAL_EXIT=1
  fi
fi

# Format only successful test executions. Reporter failure turns an otherwise
# successful coverage test into a failure and never hides a failed test.
if [ "$FINAL_EXIT" -eq 0 ] && [ "$COVERAGE_ENABLED" = "true" ]; then
  if [ -z "${JAVA_COVERAGE_FILE:-}" ]; then
    echo "ERROR: JAVA_COVERAGE_FILE is not set during Bazel coverage" >&2
    FINAL_EXIT=1
  elif ! "$COVERAGE_REPORTER" \
      --execution-data "$COVERAGE_EXEC_FILE" \
      --output "$JAVA_COVERAGE_FILE" \
      --class-jars-file "$DIRECT_JARS_FILE"; then
    echo "ERROR: Bazel JaCoCo LCOV reporting failed" >&2
    FINAL_EXIT=1
  fi
fi

rm -rf "$REPORTS_DIR"
rm -f "$DIRECT_JARS_FILE"
if [ -n "$COVERAGE_EXEC_FILE" ]; then
  rm -f "$COVERAGE_EXEC_FILE"
fi
exit "$FINAL_EXIT"
