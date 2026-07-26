"""Lightweight coverage for native quarkus_integration_test launcher wiring."""

load("@bazel_skylib//lib:shell.bzl", "shell")
load("@com_clementguillot_rules_quarkus//quarkus:providers.bzl", "QuarkusNativeInfo")

def _fake_native_app_impl(ctx):
    binary = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        output = binary,
        content = "#!/usr/bin/env bash\nexit 0\n",
        is_executable = True,
    )
    return [
        DefaultInfo(files = depset([binary])),
        QuarkusNativeInfo(
            application_classpath = depset(),
            binary = binary,
            native_sources_dir = binary,
            quarkus_version = ctx.attr.quarkus_version,
        ),
    ]

fake_native_app = rule(
    implementation = _fake_native_app_impl,
    attrs = {
        "quarkus_version": attr.string(mandatory = True),
    },
)

def _native_integration_launcher_test_impl(ctx):
    target = ctx.attr.target
    launcher = target[DefaultInfo].files_to_run.executable
    binary = ctx.attr.app[QuarkusNativeInfo].binary
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = script,
        content = """\
#!/usr/bin/env bash
set -euo pipefail

launcher=%s

assert_contains() {
  local expected="$1"
  if ! grep -Fq "$expected" "$launcher"; then
    echo "FAIL: native integration launcher is missing: $expected" >&2
    exit 1
  fi
}

assert_contains %s
assert_contains %s
assert_contains %s
assert_contains %s
assert_contains %s
assert_contains %s

validation_line=$(grep -nF '# Validate packaged integration artifacts' "$launcher" | head -n 1 | cut -d: -f1 || true)
model_line=$(grep -nF '# Phase 1: Generate serialized ApplicationModel' "$launcher" | head -n 1 | cut -d: -f1 || true)
if [ -z "$validation_line" ] || [ -z "$model_line" ] || [ "$validation_line" -ge "$model_line" ]; then
  echo "FAIL: integration artifact validation must precede model generation" >&2
  exit 1
fi
""" % (
            shell.quote(launcher.short_path),
            shell.quote('COVERAGE_ENABLED="false"'),
            shell.quote('ARTIFACT_TYPE="native"'),
            shell.quote('ARTIFACT_PATH="${WORKSPACE_DIR}/' + binary.short_path + '"'),
            shell.quote('printf \'type=%s\\n\' "$ARTIFACT_TYPE"'),
            shell.quote('printf \'path=%s\\n\' "$ARTIFACT_PATH"'),
            shell.quote('TEST_JVM_ARGS+=("-Dnative.image.path=${ARTIFACT_PATH}")'),
        ),
        is_executable = True,
    )

    runfiles = ctx.runfiles(files = [binary, launcher])
    runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = script, runfiles = runfiles)]

native_integration_launcher_test = rule(
    implementation = _native_integration_launcher_test_impl,
    test = True,
    attrs = {
        "app": attr.label(
            mandatory = True,
            providers = [QuarkusNativeInfo],
        ),
        "target": attr.label(
            mandatory = True,
            cfg = "target",
            executable = True,
        ),
    },
)
