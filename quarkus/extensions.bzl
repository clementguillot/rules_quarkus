"""Bzlmod module extension for configuring the Quarkus toolchain.

Auto-discovers Quarkus extensions from the maven_install.json lock file and
resolves their -deployment counterparts automatically. The quarkifier tool JAR
is either downloaded from Maven Central or overridden with a local label.
"""

load("//quarkus/private:versions.bzl", "MAVEN_CENTRAL", "QUARKIFIER_ARTIFACT_ID", "QUARKIFIER_GROUP_ID", "RULES_VERSION", "SUPPORTED_VERSIONS")

# ---- Repository rules ----

def _quarkus_toolchains_repo_impl(rctx):
    """Creates @rules_quarkus_toolchains with defs.bzl macro and tool alias."""
    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
alias(name = "quarkifier_tool", actual = "{tool}")
""".format(tool = rctx.attr.quarkifier_tool))

    rctx.file("defs.bzl", content = """\
\"\"\"Public API — load quarkus_app from here.\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "{tool}"
_DEPLOYMENT_DEPS = "@quarkus_deployment//:all"

def quarkus_app(name, **kwargs):
    quarkus_app_rule(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        **kwargs
    )
""".format(version = rctx.attr.quarkus_version, tool = rctx.attr.quarkifier_tool))

_quarkus_toolchains_repo = repository_rule(
    implementation = _quarkus_toolchains_repo_impl,
    attrs = {
        "quarkifier_tool": attr.string(mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
    },
)

def _quarkus_quarkifier_repo_impl(rctx):
    """Downloads the quarkifier JAR from Maven Central."""
    version = rctx.attr.tool_version
    group_path = rctx.attr.group_id.replace(".", "/")
    artifact_id = rctx.attr.artifact_id
    jar_name = "{}-{}.jar".format(artifact_id, version)
    url = "{}/{}/{}/{}/{}".format(rctx.attr.repository_url, group_path, artifact_id, version, jar_name)

    rctx.download(url = url, output = "tool/" + jar_name)

    rctx.file("BUILD.bazel", content = """\
load("@rules_java//java:java_import.bzl", "java_import")
package(default_visibility = ["//visibility:public"])
java_import(name = "quarkifier_tool", jars = ["tool/{jar}"])
""".format(jar = jar_name))

_quarkus_quarkifier_repo = repository_rule(
    implementation = _quarkus_quarkifier_repo_impl,
    attrs = {
        "group_id": attr.string(default = QUARKIFIER_GROUP_ID),
        "artifact_id": attr.string(default = QUARKIFIER_ARTIFACT_ID),
        "tool_version": attr.string(mandatory = True),
        "repository_url": attr.string(default = MAVEN_CENTRAL),
    },
)

def _quarkus_deployment_repo_impl(rctx):
    """Downloads Quarkus deployment jars with transitive deps using Coursier."""
    rctx.download(
        url = "https://github.com/coursier/launchers/raw/master/coursier",
        output = "coursier",
        executable = True,
    )

    all_jars = []
    for gav in rctx.attr.artifacts:
        result = rctx.execute(
            ["./coursier", "fetch", "--repository", rctx.attr.repository_url, gav],
            timeout = 300,
        )
        if result.return_code == 0:
            for line in result.stdout.strip().split("\n"):
                line = line.strip()
                if line.endswith(".jar") and line not in all_jars:
                    all_jars.append(line)

    imports = []
    all_targets = []
    seen = {}
    for jar_path in all_jars:
        jar_file = jar_path.split("/")[-1]
        target_name = jar_file.replace(".jar", "").replace(".", "_").replace("-", "_")
        if target_name in seen:
            continue
        seen[target_name] = True
        rctx.symlink(jar_path, "jars/" + jar_file)
        imports.append(
            'java_import(name = "{n}", jars = ["jars/{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = jar_file),
        )
        all_targets.append('":{}"'.format(target_name))

    rctx.file("BUILD.bazel", content = """\
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility = ["//visibility:public"])
{imports}
java_library(name = "all", exports = [{exports}])
""".format(imports = "\n".join(imports), exports = ", ".join(all_targets)))

_quarkus_deployment_repo = repository_rule(
    implementation = _quarkus_deployment_repo_impl,
    attrs = {
        "artifacts": attr.string_list(mandatory = True),
        "repository_url": attr.string(default = MAVEN_CENTRAL),
    },
)

# ---- Helpers ----

_DEFAULT_EXTENSION_GROUP_PREFIXES = ["io.quarkus", "io.quarkiverse."]

def _matches_prefix(group_id, prefixes):
    for prefix in prefixes:
        if prefix.endswith("."):
            if group_id.startswith(prefix) or group_id == prefix[:-1]:
                return True
        elif group_id == prefix:
            return True
    return False

# ---- Module extension ----

def _quarkus_impl(mctx):
    toolchains = mctx.modules[0].tags.toolchain
    if not toolchains:
        fail("quarkus.toolchain() must be called in MODULE.bazel")

    tc = toolchains[0]
    version = tc.quarkus_version
    if version not in SUPPORTED_VERSIONS:
        fail("Unsupported Quarkus version '{}'. Supported: {}".format(version, SUPPORTED_VERSIONS))

    # Resolve quarkifier tool: local label or Maven download
    if tc.quarkifier_tool:
        tool_target = str(tc.quarkifier_tool)
    else:
        tool_version = RULES_VERSION + "-quarkus-" + version
        _quarkus_quarkifier_repo(
            name = "rules_quarkus_quarkifier",
            tool_version = tool_version,
        )
        tool_target = "@rules_quarkus_quarkifier//:quarkifier_tool"

    _quarkus_toolchains_repo(
        name = "rules_quarkus_toolchains",
        quarkifier_tool = tool_target,
        quarkus_version = version,
    )

    # Auto-discover deployment artifacts from lock file
    deployment_artifacts = ["io.quarkus:quarkus-core-deployment:" + version]
    prefixes = tc.extension_group_prefixes if tc.extension_group_prefixes else _DEFAULT_EXTENSION_GROUP_PREFIXES

    if tc.lock_file:
        lock_content = mctx.read(tc.lock_file)
        lock_data = json.decode(lock_content)
        artifacts_map = lock_data.get("artifacts", lock_data.get("dependencies", {}))

        for coord_key in artifacts_map:
            parts = coord_key.split(":")
            if len(parts) < 2:
                continue
            group_id = parts[0]
            artifact_id = parts[1]
            if artifact_id.endswith("-deployment"):
                continue
            if not _matches_prefix(group_id, prefixes):
                continue
            deployment_gav = group_id + ":" + artifact_id + "-deployment:" + version
            if deployment_gav not in deployment_artifacts:
                deployment_artifacts.append(deployment_gav)

    _quarkus_deployment_repo(
        name = "quarkus_deployment",
        artifacts = deployment_artifacts,
    )

_toolchain_tag = tag_class(
    attrs = {
        "quarkus_version": attr.string(
            mandatory = True,
            doc = "The Quarkus version to use (e.g. '3.20.6').",
        ),
        "lock_file": attr.label(
            doc = "Path to maven_install.json for auto-discovering Quarkus extensions.",
        ),
        "extension_group_prefixes": attr.string_list(
            default = ["io.quarkus", "io.quarkiverse."],
            doc = "Maven groupId prefixes that identify Quarkus extensions.",
        ),
        "quarkifier_tool": attr.label(
            doc = """\
Override the quarkifier tool target. When set, this label is used instead of
downloading from Maven Central. Use for local development or custom builds.
Example: '//quarkifier'
""",
        ),
    },
)

quarkus = module_extension(
    implementation = _quarkus_impl,
    tag_classes = {"toolchain": _toolchain_tag},
    doc = "Configures Quarkus toolchain and auto-resolves deployment artifacts.",
)
