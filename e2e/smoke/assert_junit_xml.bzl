"""Test helper that validates a quarkus_test executable's Bazel JUnit XML."""

load("@bazel_skylib//lib:shell.bzl", "shell")

def _assert_junit_xml_test_impl(ctx):
    target = ctx.attr.target
    target_executable = target[DefaultInfo].files_to_run.executable

    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = script,
        content = """\
#!/usr/bin/env bash
set -uo pipefail

REPORT="${{TEST_TMPDIR}}/quarkus-test.xml"
rm -f "$REPORT"
if ! XML_OUTPUT_FILE="$REPORT" {target} "$@"; then
  echo "FAIL: quarkus_test executable failed" >&2
  exit 1
fi
if [ ! -s "$REPORT" ]; then
  echo "FAIL: quarkus_test did not write XML_OUTPUT_FILE" >&2
  exit 1
fi
if ! grep -qF '<testsuites>' "$REPORT"; then
  echo "FAIL: XML report has no testsuites root" >&2
  exit 1
fi
if ! grep -qF 'classname="{expected_class}"' "$REPORT"; then
  echo "FAIL: XML report does not contain expected test class" >&2
  exit 1
fi
if ! grep -qF 'name="{expected_test}"' "$REPORT"; then
  echo "FAIL: XML report does not contain expected test method" >&2
  exit 1
fi
if grep -qF '<testcase name="{launcher_name}"' "$REPORT"; then
  echo "FAIL: XML report contains Bazel's generic launcher testcase" >&2
  exit 1
fi
""".format(
            expected_class = ctx.attr.expected_class,
            expected_test = ctx.attr.expected_test,
            launcher_name = target_executable.basename,
            target = shell.quote(target_executable.short_path),
        ),
        is_executable = True,
    )

    runfiles = ctx.runfiles(files = [target_executable])
    runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = script, runfiles = runfiles)]

assert_junit_xml_test = rule(
    implementation = _assert_junit_xml_test_impl,
    test = True,
    attrs = {
        "expected_class": attr.string(mandatory = True),
        "expected_test": attr.string(mandatory = True),
        "target": attr.label(
            mandatory = True,
            cfg = "target",
            executable = True,
        ),
    },
)
