"""Manual real-native coverage for declared build properties."""

load("@com_clementguillot_rules_quarkus//quarkus:providers.bzl", "QuarkusNativeInfo")

def _native_build_properties_test_impl(ctx):
    native_sources = ctx.attr.target[QuarkusNativeInfo].native_sources_dir
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = script,
        content = """\
#!/usr/bin/env bash
set -euo pipefail

args_file="${TEST_SRCDIR}/%s/%s/native-sources/native-image.args"
if [[ ! -f "${args_file}" ]]; then
  echo "Expected native-sources argument file ${args_file}" >&2
  exit 1
fi
if ! grep -Fq -- '-J-Drules.quarkus.native.marker=declared' "${args_file}"; then
  echo "Declared native build property did not reach native-sources augmentation" >&2
  exit 1
fi
""" % (ctx.workspace_name, native_sources.short_path),
        is_executable = True,
    )

    return [
        DefaultInfo(
            executable = script,
            runfiles = ctx.runfiles(files = [native_sources]),
        ),
    ]

native_build_properties_test = rule(
    implementation = _native_build_properties_test_impl,
    test = True,
    doc = "Asserts a declared native build property reached native-image.args.",
    attrs = {
        "target": attr.label(
            mandatory = True,
            providers = [QuarkusNativeInfo],
            doc = "A native target whose native-sources action should be tested.",
        ),
    },
)
