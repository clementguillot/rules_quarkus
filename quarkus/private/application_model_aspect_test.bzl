"""Integration tests for generic coordinate collection by the model aspect."""

load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusExtensionInfo")
load(":application_model_aspect.bzl", "QuarkusBazelTargetGraphInfo", "quarkus_application_model_aspect")

def _fake_local_extension_impl(ctx):
    runtime_java_info = ctx.attr.runtime[JavaInfo]
    deployment_jar = ctx.attr.deployment[JavaInfo].runtime_output_jars[0]
    return [
        DefaultInfo(files = ctx.attr.runtime[DefaultInfo].files),
        runtime_java_info,
        QuarkusExtensionInfo(
            artifact_id = ctx.attr.artifact_id,
            deployment_classpath = depset([deployment_jar]),
            deployment_jar = deployment_jar,
            group_id = ctx.attr.group_id,
            version = ctx.attr.version,
        ),
    ]

_fake_local_extension = rule(
    implementation = _fake_local_extension_impl,
    attrs = {
        "artifact_id": attr.string(mandatory = True),
        "deployment": attr.label(mandatory = True, providers = [JavaInfo]),
        "group_id": attr.string(mandatory = True),
        "runtime": attr.label(mandatory = True, providers = [JavaInfo]),
        "version": attr.string(mandatory = True),
    },
)

def _coordinate_graph_test_impl(ctx):
    actual = sorted(ctx.attr.target[QuarkusBazelTargetGraphInfo].coordinate_keys.to_list())
    expected = sorted(ctx.attr.expected)
    if actual != expected:
        fail("coordinate keys mismatch: expected {}, got {}".format(expected, actual))

    executable = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = executable,
        content = """\
#!/usr/bin/env bash
if [ -n "${XML_OUTPUT_FILE:-}" ]; then
  mkdir -p "$(dirname "$XML_OUTPUT_FILE")"
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '<testsuites tests="0" failures="0" errors="0"/>' > "$XML_OUTPUT_FILE"
fi
exit 0
""",
        is_executable = True,
    )
    return [DefaultInfo(executable = executable)]

_coordinate_graph_test = rule(
    implementation = _coordinate_graph_test_impl,
    test = True,
    attrs = {
        "expected": attr.string_list(mandatory = True),
        "target": attr.label(
            mandatory = True,
            aspects = [quarkus_application_model_aspect],
            providers = [JavaInfo],
        ),
    },
)

def application_model_aspect_test_suite(name = "application_model_aspect_tests"):
    """Analysis tests for quarkus_application_model_aspect.

    Args:
      name: test suite name.
    """
    java_library(
        name = "coordinate_maven_leaf",
        testonly = True,
        tags = ["maven_coordinates=io.quarkus:quarkus-jacoco:3.33.2"],
    )
    java_library(
        name = "coordinate_maven_parent",
        testonly = True,
        srcs = ["CoordinateRuntimeFixture.java"],
        deps = [":coordinate_maven_leaf"],
        tags = ["maven_coordinates=org.example:parent:1.0"],
    )
    _coordinate_graph_test(
        name = "maven_coordinate_graph_test",
        expected = [
            "io.quarkus:quarkus-jacoco",
            "org.example:parent",
        ],
        target = ":coordinate_maven_parent",
    )

    java_library(
        name = "coordinate_local_runtime",
        testonly = True,
        srcs = ["CoordinateRuntimeFixture.java"],
        deps = [":coordinate_maven_leaf"],
    )
    java_library(
        name = "coordinate_local_deployment",
        testonly = True,
        srcs = ["CoordinateDeploymentFixture.java"],
    )
    _fake_local_extension(
        name = "coordinate_local_extension",
        testonly = True,
        artifact_id = "local-extension",
        deployment = ":coordinate_local_deployment",
        group_id = "com.example",
        runtime = ":coordinate_local_runtime",
        version = "1.0.0",
    )
    _coordinate_graph_test(
        name = "local_extension_coordinate_graph_test",
        expected = [
            "com.example:local-extension",
            "io.quarkus:quarkus-jacoco",
        ],
        target = ":coordinate_local_extension",
    )
