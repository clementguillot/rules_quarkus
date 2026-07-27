"""Private lifecycle build setting used to configure transitive codegen actions."""

QuarkusCodeGenLifecycleInfo = provider(
    "Carries the active code-generation lifecycle phase (main or dev).",
    fields = {"value": "main or dev"},
)

def _codegen_lifecycle_impl(ctx):
    value = ctx.build_setting_value
    if value not in ("main", "dev"):
        fail("codegen lifecycle must be 'main' or 'dev', got '{}'".format(value))
    return [QuarkusCodeGenLifecycleInfo(value = value)]

codegen_lifecycle_setting = rule(
    implementation = _codegen_lifecycle_impl,
    build_setting = config.string(flag = True),
)
