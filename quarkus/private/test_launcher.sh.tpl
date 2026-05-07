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

# Read runtime classpath (app + test jars, for both JUnit -cp and quarkifier)
CLASSPATH=""
while IFS=: read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$CLASSPATH" ]; then CLASSPATH="${CLASSPATH}:"; fi
    CLASSPATH="${CLASSPATH}${WORKSPACE_DIR}/${entry}"
  done
done < "${WORKSPACE_DIR}/%{classpath_file}"

# Read deployment classpath (for quarkifier only, NOT on JUnit -cp)
DEPLOY_CP=""
while IFS=: read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$DEPLOY_CP" ]; then DEPLOY_CP="${DEPLOY_CP}:"; fi
    DEPLOY_CP="${DEPLOY_CP}${WORKSPACE_DIR}/${entry}"
  done
done < "${WORKSPACE_DIR}/%{deploy_cp_file}"

# Read direct dep jars (comma-separated, for OUTPUT_SOURCES_DIR).
# These are the user's app and test jars that Quarkus needs to scan.
DIRECT_JARS=""
while IFS=, read -ra ENTRIES; do
  for entry in "${ENTRIES[@]}"; do
    if [ -n "$DIRECT_JARS" ]; then DIRECT_JARS="${DIRECT_JARS},"; fi
    DIRECT_JARS="${DIRECT_JARS}${WORKSPACE_DIR}/${entry}"
  done
done < "${WORKSPACE_DIR}/%{direct_jars_file}"

# Phase 1: Generate serialized ApplicationModel at test time.
MODEL_DIR=$(mktemp -d)
trap "rm -rf $MODEL_DIR" EXIT

# Write the combined deployment classpath (app + deploy) to a temp file.
# This avoids "Argument list too long" on Linux when the classpath contains
# many jars with long paths (the unified @rules_quarkus repo has longer paths).
APP_CP_FILE=$(mktemp)
echo -n "$CLASSPATH" > "$APP_CP_FILE"
COMBINED_DEPLOY_CP_FILE=$(mktemp)
echo -n "$CLASSPATH:$DEPLOY_CP" > "$COMBINED_DEPLOY_CP_FILE"

"$JAVA" \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$TOOL_JAR" \
  --application-classpath-file "$APP_CP_FILE" \
  --deployment-classpath-file "$COMBINED_DEPLOY_CP_FILE" \
  --output-dir "$MODEL_DIR" \
  --mode test \
  --expected-quarkus-version %{quarkus_version} \
  --app-name %{app_name}

if [ $? -ne 0 ]; then
  rm -f "$APP_CP_FILE" "$COMBINED_DEPLOY_CP_FILE"
  echo "ERROR: Quarkifier test model generation failed" >&2
  exit 1
fi

rm -f "$APP_CP_FILE" "$COMBINED_DEPLOY_CP_FILE"

if [ ! -f "$MODEL_DIR/test-app-model.dat" ]; then
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
  IFS=',' read -ra JAR_ENTRIES <<< "$DIRECT_JARS"
  for jar_path in "${JAR_ENTRIES[@]}"; do
    if [ -f "$jar_path" ]; then
      while IFS= read -r cls; do
        # Convert path to class name: org/acme/FooTest.class -> org.acme.FooTest
        cls="${cls%.class}"
        cls="${cls//\//.}"
        # Match JUnit's default pattern and exclude IT classes
        if echo "$cls" | grep -qE '(^Test|[.$]Test|Tests?$)' && ! echo "$cls" | grep -qE 'IT$'; then
          TEST_ARGS="${TEST_ARGS} --select-class=${cls}"
        fi
      done < <("$JAR_CMD" tf "$jar_path" 2>/dev/null | grep '\.class$' | grep -v '\$')
    fi
  done
fi

REPORTS_DIR=$(mktemp -d)
"$JAVA" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -Dquarkus-internal-test.serialized-app-model.path="$MODEL_DIR/test-app-model.dat" \
  -DOUTPUT_SOURCES_DIR="$DIRECT_JARS" \
  -Dplatform.quarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 \
  -Dquarkus.package.jar.type=fast-jar \
  %{jvm_flags} \
  -cp "$CLASSPATH" \
  org.junit.platform.console.ConsoleLauncher \
  $TEST_ARGS \
  --reports-dir="$REPORTS_DIR"
JUNIT_EXIT=$?

# Check XML reports for the real test result.
if ls "$REPORTS_DIR"/TEST-*.xml >/dev/null 2>&1; then
  if grep -q 'failures="[1-9]' "$REPORTS_DIR"/TEST-*.xml 2>/dev/null || \
     grep -q 'errors="[1-9]' "$REPORTS_DIR"/TEST-*.xml 2>/dev/null; then
    rm -rf "$REPORTS_DIR"
    exit 1
  fi
  rm -rf "$REPORTS_DIR"
  exit 0
fi

rm -rf "$REPORTS_DIR"
exit $JUNIT_EXIT
