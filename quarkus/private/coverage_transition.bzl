"""Configuration helpers for targets that must consume uninstrumented Java artifacts."""

def _disable_coverage_transition_impl(_settings, _attr):
    return {"//command_line_option:collect_code_coverage": False}

disable_coverage_transition = transition(
    implementation = _disable_coverage_transition_impl,
    inputs = [],
    outputs = ["//command_line_option:collect_code_coverage"],
)

def _dev_codegen_transition_impl(_settings, _attr):
    return {
        "//command_line_option:collect_code_coverage": False,
        "//quarkus/private:codegen_lifecycle": "dev",
    }

dev_codegen_transition = transition(
    implementation = _dev_codegen_transition_impl,
    inputs = [],
    outputs = [
        "//command_line_option:collect_code_coverage",
        "//quarkus/private:codegen_lifecycle",
    ],
)

def single_transitioned_target(value):
    """Unwraps scalar labels exposed as singleton lists by the transition."""
    return value[0] if type(value) == "list" else value
