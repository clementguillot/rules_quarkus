load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("@com_clementguillot_rules_quarkus//quarkus:defs.bzl", "quarkus_native_app_rule")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_integration_test", "quarkus_java_library", "quarkus_test")
load("@rules_shell//shell:sh_test.bzl", "sh_test")
load(":assert_junit_xml.bzl", "assert_junit_xml_test")
load(":expect_test_failure.bzl", "expect_test_failure_test")
load(":launcher_precedence_test.bzl", "launcher_precedence_test")
load(":native_build_properties_test.bzl", "native_build_properties_test")
load(":native_integration_launcher_test.bzl", "fake_native_app", "native_integration_launcher_test")

# Overrides src/main/resources/application.properties to prove the declared
# build-system input outranks it during augmentation. The channel differs per
# lifecycle: packaged and native augmentation scope these as system properties
# around the Quarkus bootstrap, while quarkus_test hands them to the test JVM,
# where they also stay visible to run-time config for the duration of the test.
_BUILD_PROPERTIES = {
    # Delimiters, whitespace, and a backslash exercise the Starlark writer to
    # Java Properties.load round trip used by packaged augmentation.
    "declared.smoke.prefix": "Declared:=\\ spaced",
    # Dedicated package_type/test lifecycle invariants must win this conflict.
    "quarkus.package.jar.type": "legacy-jar",
    "smoke.ext.prefix": "${declared.smoke.prefix}",
}

_PACKAGING_BUILD_PROPERTIES = dict(_BUILD_PROPERTIES)

_PACKAGING_BUILD_PROPERTIES.update({
    # Defaults to true. The packaging smoke asserts that the dependency-list
    # file is absent, proving this declaration reached real augmentation.
    "quarkus.package.jar.include-dependency-list": "false",
})

_TEST_BUILD_PROPERTIES = dict(_BUILD_PROPERTIES)

_TEST_BUILD_PROPERTIES.update({
    # Quoted JVM argv preserves spaces and colons in property names.
    "declared: smoke key": "round trip",
    # Quotes and shell-looking syntax must remain data in generated launchers.
    "declared.shell.value": "quote '$dollar `backtick` $(subshell)",
    # The launcher's serialized model path is structural: a declared property
    # with the same key must not redirect Quarkus to an arbitrary file.
    "quarkus-internal-test.serialized-app-model.path": "/invalid/declared-model.dat",
})

_NATIVE_BUILD_PROPERTIES = dict(_BUILD_PROPERTIES)

_NATIVE_BUILD_PROPERTIES.update({
    # Lands verbatim in native-image.args, giving the smoke test an observable
    # proof that native-sources augmentation consumed the declared map.
    "quarkus.native.additional-build-args": "-J-Drules.quarkus.native.marker=declared",
})

quarkus_java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    codegen_build_properties = {
        # Overrides the same non-Quarkus key in application.properties. The
        # key with delimiters also certifies Starlark writer -> Java loader.
        "smoke.codegen.declared-prefix": "Declared init ",
        # Provider.init receives the resolved value, matching context.config.
        "smoke.codegen.init-prefix": "${smoke.codegen.declared-prefix}",
        "smoke.codegen.prefix": "Hello from ",
        "smoke.codegen.require-application-properties": "true",
        "smoke.codegen.work-report": "true",
        "smoke:codegen key": "round trip",
    },
    codegen_srcs = ["src/main/hello/greeting.hello"],
    resources = glob(["src/main/resources/**/*"]),
    # //big reuses the app sources for the E2BIG regression targets.
    visibility = ["//big:__pkg__"],
    deps = [
        ":grpc_lib",
        # Generated (Avro + gRPC) and handwritten types consumed from another package.
        "//contracts",
        # Locally-built custom extension (runtime side); deployment is auto-wired.
        "//ext/runtime",
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:io_quarkus_quarkus_rest",
        "@maven//:jakarta_inject_jakarta_inject_api",
        "@maven//:jakarta_ws_rs_jakarta_ws_rs_api",
    ],
)

quarkus_java_library(
    name = "grpc_lib",
    srcs = ["src/grpc/java/smoke/GrpcGeneratedResource.java"],
    codegen_build_properties = {
        "quarkus.generate-code.grpc.descriptor-set.generate": "true",
        "quarkus.generate-code.grpc.descriptor-set.output-dir": "descriptors",
    },
    codegen_srcs = ["src/main/proto/greeting.proto"],
    deps = [
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_quarkus_quarkus_grpc",
        "@maven//:io_quarkus_quarkus_grpc_api",
        "@maven//:io_quarkus_quarkus_grpc_stubs",
        "@maven//:io_quarkus_quarkus_rest",
        "@maven//:io_smallrye_reactive_mutiny",
        "@maven//:jakarta_annotation_jakarta_annotation_api",
        "@maven//:jakarta_ws_rs_jakarta_ws_rs_api",
    ],
)

quarkus_app(
    name = "app",
    build_properties = _PACKAGING_BUILD_PROPERTIES,
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

sh_test(
    name = "build_properties_packaging_test",
    srcs = ["build_properties_packaging_test.sh"],
    data = [":app"],
)

# Every Quarkus JVM package type must remain a first-class Bazel output. These
# variants share the same application graph and intentionally omit dev targets.
quarkus_app(
    name = "app_uber",
    build_properties = _BUILD_PROPERTIES,
    dev = False,
    package_type = "uber-jar",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

quarkus_app(
    name = "app_mutable",
    build_properties = _BUILD_PROPERTIES,
    dev = False,
    package_type = "mutable-jar",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

quarkus_app(
    name = "app_legacy",
    build_properties = _BUILD_PROPERTIES,
    dev = False,
    package_type = "legacy-jar",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

quarkus_app(
    name = "app_aot",
    build_properties = _BUILD_PROPERTIES,
    dev = False,
    package_type = "aot-jar",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

# Exercise native-sources augmentation without selecting a native binary in
# wildcard builds. The public macro forwards the same build_properties attr.
quarkus_native_app_rule(
    name = "native_properties_native",
    build_properties = _NATIVE_BUILD_PROPERTIES,
    conditional_catalog = "@rules_quarkus//model:conditional-catalog-v1.json",
    conditional_deps = "@rules_quarkus//conditional:all",
    deployment_artifacts = "@rules_quarkus//deployment:artifacts",
    deployment_catalog = "@rules_quarkus//model:deployment-catalog-v1.json",
    deployment_deps = "@rules_quarkus//deployment:all",
    platform_catalog = "@rules_quarkus//model:platform-catalog-v1.json",
    platform_properties = "@rules_quarkus//model:platform_properties",
    quarkifier_tool = "@rules_quarkus//quarkifier:tool.jar",
    quarkus_version = "3.33.2",
    runtime_catalog = "@rules_quarkus//model:runtime-catalog-v1.json",
    tags = ["manual"],
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

native_build_properties_test(
    name = "native_build_properties_test",
    tags = ["manual"],
    target = ":native_properties_native",
)

quarkus_java_library(
    name = "test_lib",
    testonly = True,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/smoke/EmptyTest.java"],
    ),
    codegen_build_properties = {"smoke.codegen.prefix": "Test: "},
    codegen_mode = "test",
    codegen_srcs = ["src/test/hello/test.hello"],
    # //big reuses the test sources for the E2BIG regression targets.
    visibility = ["//big:__pkg__"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:io_rest_assured_rest_assured",
        "@maven//:org_hamcrest_hamcrest",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

quarkus_test(
    name = "test",
    build_properties = _TEST_BUILD_PROPERTIES,
    jvm_flags = ["-Drules.quarkus.user-jvm-flag=declared"],
    deps = [
        ":test_lib",
    ],
)

# //:test proves the serialized-model key is protected by actually running.
# This pins the ordering itself, covering the coverage and port keys that only
# appear under `bazel coverage` or on integration targets.
launcher_precedence_test(
    name = "launcher_precedence_test",
    target = ":test",
)

quarkus_integration_test(
    name = "integration_test",
    app = ":app",
    deps = [":test_lib"],
)

quarkus_integration_test(
    name = "uber_integration_test",
    app = ":app_uber",
    tags = ["exclusive"],
    deps = [":test_lib"],
)

quarkus_integration_test(
    name = "mutable_integration_test",
    app = ":app_mutable",
    tags = ["exclusive"],
    deps = [":test_lib"],
)

quarkus_integration_test(
    name = "legacy_integration_test",
    app = ":app_legacy",
    tags = ["exclusive"],
    deps = [":test_lib"],
)

quarkus_integration_test(
    name = "aot_integration_test",
    app = ":app_aot",
    tags = ["exclusive"],
    deps = [":test_lib"],
)

# Proves checked-in application.properties still participates when no declared
# test augmentation override is supplied.
quarkus_test(
    name = "application_properties_test",
    srcs = ["src/application-properties-test/java/smoke/ApplicationPropertiesSmokeExtTest.java"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:io_rest_assured_rest_assured",
        "@maven//:org_hamcrest_hamcrest",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

# Strict deps force a test that imports shared contract types to declare those
# libraries alongside the application library. Only //:lib may be promoted to
# the application under test; the libraries it owns must not compete with it.
quarkus_test(
    name = "multi_dep_test",
    srcs = ["src/multidep/java/smoke/MultiDepCodegenTest.java"],
    deps = [
        ":grpc_lib",
        ":lib",
        "//contracts",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

fake_native_app(
    name = "fake_native_app",
    testonly = True,
    quarkus_version = "3.33.2",
)

quarkus_integration_test(
    name = "fake_native_integration_test",
    app = ":fake_native_app",
    tags = ["manual"],
    deps = [":test_lib"],
)

native_integration_launcher_test(
    name = "native_integration_launcher_test",
    app = ":fake_native_app",
    target = ":fake_native_integration_test",
)

assert_junit_xml_test(
    name = "junit_xml_report_test",
    expected_class = "smoke.GreetingResourceTest",
    expected_test = "testHelloEndpoint()",
    tags = ["exclusive"],
    target = ":test",
)

assert_junit_xml_test(
    name = "integration_junit_xml_report_test",
    expected_class = "smoke.GreetingResourceIT",
    expected_test = "testHelloEndpoint()",
    tags = ["exclusive"],
    target = ":integration_test",
)

build_test(
    name = "smoke_test",
    targets = [
        ":app",
    ],
)

build_test(
    name = "package_outputs_build_test",
    targets = [
        ":app_aot",
        ":app_legacy",
        ":app_mutable",
        ":app_uber",
    ],
)

filegroup(
    name = "codegen_model",
    srcs = [":lib_quarkus_codegen"],
    output_group = "quarkus_codegen_model",
)

sh_test(
    name = "codegen_application_name_test",
    srcs = ["codegen_application_name_test.sh"],
    data = [":codegen_model"],
)

sh_test(
    name = "dev_codegen_metadata_test",
    srcs = ["dev_codegen_metadata_test.sh"],
    data = [
        ":app_dev",
        ":dependency_only_codegen_app_dev",
    ],
)

filegroup(
    name = "codegen_work",
    srcs = [":lib_quarkus_codegen"],
    output_group = "quarkus_codegen_work",
)

filegroup(
    name = "grpc_codegen_work",
    srcs = [":grpc_lib_quarkus_codegen"],
    output_group = "quarkus_codegen_work",
)

sh_test(
    name = "codegen_work_output_test",
    srcs = ["codegen_work_output_test.sh"],
    args = [
        "$(rootpath :codegen_work)",
        "$(rootpath :grpc_codegen_work)",
    ],
    data = [
        ":codegen_work",
        ":grpc_codegen_work",
    ],
)

# Code generation is allowed to no-op when every discovered provider declines
# the configured roots. The deterministic empty source JAR remains consumable
# by java_library.
quarkus_java_library(
    name = "no_output_codegen",
    codegen = True,
    deps = ["@maven//:io_quarkus_quarkus_rest"],
)

# A local CodeGenProvider may be discovered from exports or runtime_deps, not
# just deps. Both targets compile handwritten Java against their generated
# source JAR.
quarkus_java_library(
    name = "runtime_edge_codegen",
    srcs = ["src/codegen_edges/runtime/java/smoke/RuntimeCodegenConsumer.java"],
    codegen_source_roots = ["src/codegen_edges/runtime"],
    codegen_srcs = ["src/codegen_edges/runtime/hello/runtime.hello"],
    runtime_deps = ["//ext/runtime"],
)

quarkus_java_library(
    name = "exports_edge_codegen",
    srcs = ["src/codegen_edges/exports/java/smoke/ExportedCodegenConsumer.java"],
    codegen_source_roots = ["src/codegen_edges/exports"],
    codegen_srcs = ["src/codegen_edges/exports/hello/exported.hello"],
    exports = ["//ext/runtime"],
)

# Auxiliary-only providers are successful and expose their files through the
# existing quarkus_codegen_aux output group.
quarkus_java_library(
    name = "aux_only_codegen",
    codegen_build_properties = {"smoke.codegen.aux-only": "true"},
    codegen_source_roots = ["src/codegen_edges/aux"],
    codegen_srcs = ["src/codegen_edges/aux/hello/auxiliary.hello"],
    runtime_deps = ["//ext/runtime"],
)

java_library(
    name = "proto_dependency",
    resource_strip_prefix = "src/dependency",
    resources = ["src/dependency/proto/dependency.proto"],
)

# gRPC supports scanning .proto resources embedded in dependencies without a
# local proto input. codegen=True opts the helper into that dependency-only
# lifecycle while keeping codegen_srcs empty.
quarkus_java_library(
    name = "dependency_only_grpc_codegen",
    srcs = ["src/dependency/java/smoke/DependencyGeneratedConsumer.java"],
    codegen = True,
    codegen_build_properties = {
        "quarkus.generate-code.grpc.scan-for-imports": "none",
        "quarkus.generate-code.grpc.scan-for-proto": "bazel.workspace:proto_dependency",
    },
    deps = [
        ":proto_dependency",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_quarkus_quarkus_grpc",
        "@maven//:io_quarkus_quarkus_grpc_api",
        "@maven//:io_quarkus_quarkus_grpc_stubs",
        "@maven//:io_smallrye_reactive_mutiny",
        "@maven//:jakarta_annotation_jakarta_annotation_api",
    ],
)

# Its dev target certifies that resource-only generator inputs from a
# dependency propagate into the Bazel rebuild watcher even though the codegen
# target has no local codegen_srcs.
quarkus_app(
    name = "dependency_only_codegen_app",
    deps = [":dependency_only_grpc_codegen"],
)

build_test(
    name = "codegen_edge_build_test",
    targets = [
        "//contracts",
        ":aux_only_codegen",
        ":dependency_only_grpc_codegen",
        ":exports_edge_codegen",
        ":no_output_codegen",
        ":runtime_edge_codegen",
    ],
)

# Real production-mode conditional-dependency regression. quarkus-rest declares
# quarkus-rest-kotlin as conditional on quarkus-kotlin. The candidate must be selected by the
# ApplicationModel without becoming an explicit user dependency.
java_library(
    name = "conditional_lib",
    srcs = ["src/conditional/java/smoke/ConditionalMarker.java"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_kotlin",
    ],
)

quarkus_app(
    name = "conditional_app",
    version = "1.0.0-SNAPSHOT",
    deps = [":conditional_lib"],
)

build_test(
    name = "conditional_app_build_test",
    targets = [":conditional_app"],
)

filegroup(
    name = "conditional_app_model",
    srcs = [":conditional_app"],
    output_group = "quarkus_model",
)

sh_test(
    name = "conditional_dependency_model_test",
    srcs = ["conditional_dependency_model_test.sh"],
    args = ["$(rlocationpath :conditional_app_model)"],
    data = [":conditional_app_model"],
)

sh_test(
    name = "dev_ui_application_model_test",
    size = "large",
    srcs = ["dev_ui_application_model_test.sh"],
    args = ["$(rlocationpath :app_dev)"],
    data = [":app_dev"],
    # The test copies the launcher and points it at this test's merged runfiles tree.
    tags = ["no-sandbox"],
)

# --- fail_if_no_tests smoke tests (issue #125) ---
#
# EmptyTest.java is a @QuarkusTest with zero @Test methods.
java_library(
    name = "empty_test_lib",
    testonly = True,
    srcs = ["src/test/java/smoke/EmptyTest.java"],
    deps = [
        ":lib",
        "@maven//:io_quarkus_quarkus_junit",
        "@maven//:org_junit_jupiter_junit_jupiter",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_platform_junit_platform_console_standalone",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)

# Zero tests + strict mode (default) — must fail.
quarkus_test(
    name = "empty_test_strict",
    tags = ["manual"],
    deps = [":empty_test_lib"],
)

# Wraps empty_test_strict and asserts it fails. This target runs in CI.
expect_test_failure_test(
    name = "empty_test_strict_fails",
    target = ":empty_test_strict",
)

# Zero tests + lenient mode — must pass. Runs in CI directly.
quarkus_test(
    name = "empty_test_lenient",
    fail_if_no_tests = False,
    deps = [":empty_test_lib"],
)

# Verifies the extension publish artifacts (jars + POMs) build for both halves.
build_test(
    name = "publish_test",
    targets = [
        "//ext/runtime:runtime_publish-project",
        "//ext/runtime:runtime_publish-pom",
        "//ext/deployment:deployment_publish-project",
        "//ext/deployment:deployment_publish-pom",
    ],
)
