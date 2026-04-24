"""Public API for rules_quarkus.

Users should load quarkus_app from the generated toolchains repo:

    load("@rules_quarkus_toolchains//:defs.bzl", "quarkus_app")

This file re-exports the internal rules for use by the toolchains repo macro.
"""

load("//quarkus/private:quarkus_app_impl.bzl", _quarkus_app_rule = "quarkus_app_rule")
load("//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")

# Exposed for the generated macro in @rules_quarkus_toolchains//:defs.bzl
quarkus_app_rule = _quarkus_app_rule
quarkus_test = _quarkus_test
