"""Pins the JVM flag precedence contract of the quarkus_test launcher.

Declared `build_properties` reach the test JVM as `-D` flags, so precedence is
decided purely by argument order: the JVM keeps the last value for a key. This
test asserts the generated launcher emits declared flags before structural and
launcher-owned runtime settings, then caller `jvm_flags`. This keeps declared
properties from changing coverage, dynamic ports, or the serialized model while
preserving the pre-existing contract that explicit `jvm_flags` win last.

Testing those last two by observation would need a coverage-enabled run and an
integration target that accepts `build_properties` (it deliberately does not).
Asserting they live inside `TEST_JVM_ARGS`, plus asserting where
`TEST_JVM_ARGS` is expanded, covers them without either.
"""

load("@bazel_skylib//lib:shell.bzl", "shell")

# Marker, description pairs in required order of appearance in the java
# invocation. Earlier entries lose to later ones for the same property key.
_ORDERED_MARKERS = [
    (
        "-Dquarkus-internal-test.serialized-app-model.path=/invalid/declared-model.dat",
        "declared build_properties",
    ),
    (
        "-Dquarkus-internal-test.serialized-app-model.path=\"$MODEL_DIR",
        "structural serialized application model",
    ),
    ("-Dplatform.quarkus.native.builder-image=", "pinned native builder image"),
    ("-Dquarkus.package.jar.type=fast-jar", "package_type invariant"),
    ("\"${TEST_JVM_ARGS[@]}\"", "launcher-owned TEST_JVM_ARGS"),
    ("-Drules.quarkus.user-jvm-flag=declared", "caller jvm_flags"),
]

# Keys that are only set conditionally at run time. Declared build properties
# lose to these entries, while the final caller jvm_flags may override them.
_TEST_JVM_ARGS_MEMBERS = [
    "TEST_JVM_ARGS+=(\"-Dquarkus.jacoco.enabled=false\")",
    "\"-Dquarkus.http.test-port=0\"",
    "\"-Djacoco-agent.destfile=${COVERAGE_EXEC_FILE}\"",
]

def _launcher_precedence_test_impl(ctx):
    launcher = ctx.attr.target[DefaultInfo].files_to_run.executable
    script = ctx.actions.declare_file(ctx.label.name + ".sh")

    checks = ["previous_line=0", "previous_label=start-of-file"]
    for marker, label in _ORDERED_MARKERS:
        checks.append("assert_after {} {}".format(shell.quote(marker), shell.quote(label)))
    for member in _TEST_JVM_ARGS_MEMBERS:
        checks.append("assert_present {}".format(shell.quote(member)))

    ctx.actions.write(
        output = script,
        content = """\
#!/usr/bin/env bash
set -euo pipefail

launcher=%s

line_of() {
  local marker="$1"
  grep -nF -- "$marker" "$launcher" | tail -n 1 | cut -d: -f1
}

assert_present() {
  local marker="$1"
  if ! grep -qF -- "$marker" "$launcher"; then
    echo "FAIL: launcher no longer contains protected flag: $marker" >&2
    exit 1
  fi
}

# Asserts the marker exists and appears strictly after the previous marker, so
# its value wins for any property key the two have in common.
assert_after() {
  local marker="$1" label="$2" line
  assert_present "$marker"
  line=$(line_of "$marker")
  if [ "$line" -le "$previous_line" ]; then
    echo "FAIL: $label (line $line) must come after $previous_label (line $previous_line)" >&2
    echo "      later JVM -D flags win, so this ordering is the precedence contract" >&2
    exit 1
  fi
  previous_line="$line"
  previous_label="$label"
}

%s

""" % (shell.quote(launcher.short_path), "\n".join(checks)),
        is_executable = True,
    )

    return [DefaultInfo(executable = script, runfiles = ctx.runfiles(files = [launcher]))]

launcher_precedence_test = rule(
    implementation = _launcher_precedence_test_impl,
    test = True,
    doc = "Asserts build-property, caller-flag, and structural JVM precedence.",
    attrs = {
        "target": attr.label(
            mandatory = True,
            cfg = "target",
            executable = True,
            doc = "A quarkus_test target declaring build_properties.",
        ),
    },
)
