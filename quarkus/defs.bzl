"""Public API for rules_quarkus.

Users should load quarkus_app and quarkus_test from the generated repo:

    load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")

This file re-exports the internal rules for use by the generated repo macros.
"""

load("//quarkus/private:quarkus_app_impl.bzl", _quarkus_app_rule = "quarkus_app_rule")
load("//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")

# Exposed for the generated macro in @rules_quarkus//quarkus:defs.bzl
quarkus_app_rule = _quarkus_app_rule
quarkus_test = _quarkus_test
