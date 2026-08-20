"""Public API for rules_quarkus.

Users should load application and test macros from the generated repo:

    load(
        "@rules_quarkus//quarkus:defs.bzl",
        "quarkus_app",
        "quarkus_integration_test",
        "quarkus_test",
    )

This file re-exports the internal rules for use by the generated repo macros.
"""

load("//quarkus/private:quarkus_app_impl.bzl", _quarkus_app_rule = "quarkus_app_rule")
load("//quarkus/private:quarkus_codegen_impl.bzl", _quarkus_codegen_root_rule = "quarkus_codegen_root_rule", _quarkus_codegen_rule = "quarkus_codegen_rule")
load("//quarkus/private:quarkus_native_app_impl.bzl", _quarkus_native_app_rule = "quarkus_native_app_rule")
load("//quarkus/private:quarkus_native_container_app_impl.bzl", _quarkus_native_container_app_rule = "quarkus_native_container_app_rule")
load("//quarkus/private:quarkus_test_impl.bzl", _quarkus_integration_test = "quarkus_integration_test", _quarkus_test = "quarkus_test")

# Exposed for the generated macro in @rules_quarkus//quarkus:defs.bzl
quarkus_app_rule = _quarkus_app_rule
quarkus_codegen_root_rule = _quarkus_codegen_root_rule
quarkus_codegen_rule = _quarkus_codegen_rule
quarkus_native_app_rule = _quarkus_native_app_rule
quarkus_native_container_app_rule = _quarkus_native_container_app_rule
quarkus_integration_test = _quarkus_integration_test
quarkus_test = _quarkus_test
