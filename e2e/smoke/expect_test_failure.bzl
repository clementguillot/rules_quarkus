"""Rule that asserts a test target fails at execution time.

Use this to verify that a test correctly detects and reports failures,
e.g. a quarkus_test with zero tests should fail when fail_if_no_tests=True.
"""

def _expect_test_failure_impl(ctx):
    target = ctx.attr.target
    target_executable = target[DefaultInfo].files_to_run.executable

    # Build a wrapper script that runs the target and asserts non-zero exit.
    script = ctx.actions.declare_file(ctx.label.name + "_expect_failure.sh")
    ctx.actions.write(
        output = script,
        content = """\
#!/usr/bin/env bash
set -uo pipefail
if "{target}" "$@" 2>&1; then
  echo "FAIL: expected target to fail but it exited 0" >&2
  exit 1
fi
echo "PASS: target correctly failed"
exit 0
""".format(target = target_executable.short_path),
        is_executable = True,
    )

    runfiles = ctx.runfiles(files = [target_executable])
    runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)

    return [DefaultInfo(executable = script, runfiles = runfiles)]

expect_test_failure_test = rule(
    implementation = _expect_test_failure_impl,
    test = True,
    attrs = {
        "target": attr.label(
            mandatory = True,
            executable = True,
            cfg = "target",
            doc = "Test target expected to fail at execution time.",
        ),
    },
    doc = "A test that passes when the wrapped target fails at execution time.",
)
