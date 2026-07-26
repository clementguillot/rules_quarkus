#!/usr/bin/env bash
set -euo pipefail

bzlmod_flag="${BZLMOD_FLAG:---enable_bzlmod=true}"

bazel coverage \
  "$bzlmod_flag" \
  //:test \
  --combined_report=lcov \
  --instrumentation_filter='//:lib$,//ext/runtime:runtime_lib$' \
  --lockfile_mode=off \
  --nocache_test_results \
  --test_output=errors

assert_covered_source() {
  local report="$1"
  local source="$2"

  if [[ ! -s "$report" ]]; then
    echo "ERROR: LCOV report is missing or empty: $report" >&2
    return 1
  fi

  if ! awk -v expected_source="SF:$source" '
    $0 == expected_source {
      in_expected_source = 1
      found_source = 1
      next
    }
    in_expected_source && $0 == "end_of_record" {
      in_expected_source = 0
    }
    in_expected_source && /^DA:/ {
      split(substr($0, 4), fields, ",")
      if (fields[2] + 0 > 0) {
        found_covered_line = 1
      }
    }
    END {
      exit !(found_source && found_covered_line)
    }
  ' "$report"; then
    echo "ERROR: LCOV report has no executed lines for $source: $report" >&2
    return 1
  fi
}

assert_source_absent() {
  local report="$1"
  local source="$2"

  if grep -qF "SF:$source" "$report"; then
    echo "ERROR: LCOV report contains source excluded by --instrumentation_filter: $source ($report)" >&2
    return 1
  fi
}

for report in \
  bazel-testlogs/test/coverage.dat \
  bazel-out/_coverage/_coverage_report.dat; do
  assert_covered_source "$report" "src/main/java/smoke/GreetingResource.java"
  assert_covered_source "$report" "ext/runtime/src/main/java/smoke/ext/runtime/SmokeService.java"
  assert_source_absent "$report" "src/test/java/smoke/GreetingResourceTest.java"
done
