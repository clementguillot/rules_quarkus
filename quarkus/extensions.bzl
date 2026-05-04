"""Bzlmod module extension for configuring the Quarkus toolchain.

Auto-discovers Quarkus extensions from the maven_install.json lock file and
resolves their -deployment counterparts automatically. The quarkifier deploy
jar is downloaded from GitHub releases or overridden with a local build.
"""

load("//quarkus/private:versions.bzl", "GITHUB_OWNER", "GITHUB_REPO", "MAVEN_CENTRAL", "RULES_VERSION", "SUPPORTED_VERSIONS")

# ---- Helpers ----

def _extract_minor_version(version):
    """Extracts the minor version (MAJOR.MINOR) from a full version string.

    Args:
        version: Full version string (e.g. "3.21.1", "3.27.3").

    Returns:
        Minor version string (e.g. "3.21", "3.27").
    """
    parts = version.split(".")
    if len(parts) < 2:
        fail("Invalid Quarkus version '{}': expected MAJOR.MINOR.PATCH format".format(version))
    return parts[0] + "." + parts[1]

def _sanitize_version(minor_version):
    """Replaces dots with underscores for use in Bazel target and repo names.

    Args:
        minor_version: Minor version string (e.g. "3.27").

    Returns:
        Sanitized string (e.g. "3_27").
    """
    return minor_version.replace(".", "_")

# ---- Repository rules ----

def _quarkus_toolchains_repo_impl(rctx):
    """Creates @rules_quarkus_toolchains with defs.bzl macro and tool alias."""
    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["defs.bzl"])
""")

    rctx.file("defs.bzl", content = """\
\"\"\"Public API — load quarkus_app and quarkus_test from here.

quarkus_app() automatically creates a <name>_dev target for Quarkus dev mode
with hot-reload support. Use dev=False to opt out.
\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_dev_impl.bzl", "quarkus_dev_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "{tool}"
_DEPLOYMENT_DEPS = "@quarkus_deployment//:all"
_CORE_DEPLOYMENT_DEPS = "@quarkus_deployment//:core"

def quarkus_app(name, dev = True, **kwargs):
    \"\"\"Builds a Quarkus application and optionally creates a dev-mode target.

    Creates:
      - <name>: production Fast_Jar target (bazel run //pkg:<name>)
      - <name>_dev: dev mode with hot-reload (bazel run //pkg:<name>_dev), unless dev=False

    Args:
        name: Target name.
        dev: If True (default), also creates a <name>_dev target for dev mode.
        **kwargs: Passed to the underlying quarkus_app_rule (deps, version, jvm_flags, etc.).
    \"\"\"
    quarkus_app_rule(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        **kwargs
    )
    if dev:
        deps = kwargs.get("deps", [])
        version = kwargs.get("version", "")
        quarkus_dev_rule(
            name = name + "_dev",
            quarkus_version = _QUARKUS_VERSION,
            quarkifier_tool = _QUARKIFIER_TOOL,
            deployment_deps = _DEPLOYMENT_DEPS,
            core_deployment_deps = _CORE_DEPLOYMENT_DEPS,
            deps = deps,
            version = version,
        )

def quarkus_test(name, srcs = None, deps = None, test_packages = None, test_classes = None, jvm_flags = None, **kwargs):
    \"\"\"Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.

    If srcs is provided, a java_library is created internally to compile the
    test sources. If srcs is omitted, deps must include a pre-compiled
    java_library containing the test classes.
    \"\"\"
    test_deps = deps or []
    if srcs:
        native.java_library(
            name = name + "_lib",
            srcs = srcs,
            deps = test_deps,
            testonly = True,
        )
        test_deps = [":" + name + "_lib"]

    test_kwargs = {{}}
    if test_packages:
        test_kwargs["test_packages"] = test_packages
    if test_classes:
        test_kwargs["test_classes"] = test_classes
    if jvm_flags:
        test_kwargs["jvm_flags"] = jvm_flags
    test_kwargs.update(kwargs)

    _quarkus_test(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        deps = test_deps,
        **test_kwargs
    )
""".format(
        version = rctx.attr.quarkus_version,
        tool = rctx.attr.quarkifier_tool,
    ))

_quarkus_toolchains_repo = repository_rule(
    implementation = _quarkus_toolchains_repo_impl,
    attrs = {
        "quarkifier_tool": attr.string(mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
    },
)

def _quarkus_quarkifier_download_repo_impl(rctx):
    """Downloads the quarkifier JAR from a URL (GitHub release or Maven Central)."""
    rctx.download(url = rctx.attr.url, output = "tool/quarkifier.jar")

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool/quarkifier.jar"])
""")

_quarkus_quarkifier_download_repo = repository_rule(
    implementation = _quarkus_quarkifier_download_repo_impl,
    attrs = {
        "url": attr.string(mandatory = True),
    },
)

def _quarkus_quarkifier_local_build_repo_impl(rctx):
    """Builds and symlinks a per-minor quarkifier deploy jar from a local workspace.

    When quarkifier_source_dir is set (local development), this rule
    automatically builds the deploy jar if it doesn't exist, then symlinks
    it. The target attribute specifies which per-minor deploy jar to build.
    """

    # Resolve the label to get the absolute path of the source workspace.
    # Use realpath to resolve symlinks (local_path_override uses symlinks).
    src_workspace_raw = str(rctx.path(rctx.attr.source_dir).dirname)
    realpath_result = rctx.execute(["realpath", src_workspace_raw])
    src_workspace = realpath_result.stdout.strip() if realpath_result.return_code == 0 else src_workspace_raw

    # Resolve the actual bazel-bin path using 'bazel info' instead of relying
    # on the bazel-bin convenience symlink (which may not exist after clean).
    bin_result = rctx.execute(
        ["bazel", "info", "bazel-bin"],
        working_directory = src_workspace,
        timeout = 60,
    )
    if bin_result.return_code == 0:
        bazel_bin = bin_result.stdout.strip()
    else:
        bazel_bin = src_workspace + "/bazel-bin"

    # Derive the deploy jar path from the target label.
    # e.g. "//quarkifier:quarkifier_3_27_deploy.jar" → "quarkifier/quarkifier_3_27_deploy.jar"
    target = rctx.attr.target
    target_path = target.lstrip("/").replace(":", "/")
    deploy_jar = bazel_bin + "/" + target_path

    # Auto-build if the jar is missing
    prod_exists = rctx.execute(["test", "-f", deploy_jar]).return_code == 0

    if not prod_exists:
        rctx.report_progress("Building {} from source...".format(target))
        build_result = rctx.execute(
            [
                "bazel",
                "build",
                target,
            ],
            working_directory = src_workspace,
            timeout = 300,
        )
        if build_result.return_code != 0:
            fail(
                "Failed to build {} in {}:\n{}".format(
                    target,
                    src_workspace,
                    build_result.stderr,
                ),
            )

    # Verify the jar exists after build
    result = rctx.execute(["test", "-f", deploy_jar])
    if result.return_code != 0:
        fail("Quarkifier deploy jar not found at: {}\n".format(deploy_jar))

    rctx.symlink(deploy_jar, "tool/quarkifier.jar")

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool/quarkifier.jar"])
""")

_quarkus_quarkifier_local_build_repo = repository_rule(
    implementation = _quarkus_quarkifier_local_build_repo_impl,
    attrs = {
        "source_dir": attr.label(mandatory = True),
        "target": attr.string(
            mandatory = True,
            doc = "Bazel target label for the per-minor deploy jar to build.",
        ),
    },
)

def _quarkus_deployment_repo_impl(rctx):
    """Downloads Quarkus deployment jars with transitive deps using Coursier.

    Produces two java_library targets:
      - :core  — transitive deps of quarkus-core-deployment + extra_core_artifacts (dev jar manifest)
      - :all   — all deployment jars (core + extension-specific deployment jars)
    """
    rctx.download(
        url = "https://github.com/coursier/launchers/raw/master/coursier",
        output = "coursier",
        executable = True,
    )

    # Phase 1: Resolve quarkus-core-deployment transitive deps (the "core" set).
    # This is the first artifact in the list by convention.
    core_jar_paths = []
    core_gav = rctx.attr.artifacts[0] if rctx.attr.artifacts else None
    if core_gav:
        result = rctx.execute(
            ["./coursier", "fetch", "--repository", rctx.attr.repository_url, core_gav],
            timeout = 300,
        )
        if result.return_code == 0:
            for line in result.stdout.strip().split("\n"):
                line = line.strip()
                if line.endswith(".jar") and line not in core_jar_paths:
                    core_jar_paths.append(line)

    core_jar_set = {p: True for p in core_jar_paths}

    # Phase 2: Resolve all deployment artifacts (core + extensions).
    all_jars = list(core_jar_paths)
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

    # Phase 3: Generate BUILD targets, tracking which belong to core.
    imports = []
    all_targets = []
    core_targets = []
    seen = {}
    for jar_path in all_jars:
        jar_file = jar_path.split("/")[-1]
        target_name = jar_file.replace(".jar", "").replace(".", "_").replace("-", "_")
        if target_name in seen:
            continue
        seen[target_name] = True

        # Preserve Maven directory structure in symlinks so that the Dev UI's
        # extractJsVersionsFor() can parse the version from the path correctly.
        # Coursier cache paths follow Maven layout: .../groupId/artifactId/version/filename.jar
        # We extract the relative path from the repository root (after the "maven2/" or "v1/" marker).
        relative_jar_path = jar_file
        path_parts = jar_path.replace("\\", "/").split("/")

        # Find the Maven repository root marker and extract the relative path after it
        for marker in ["maven2", "v1"]:
            if marker in path_parts:
                marker_idx = path_parts.index(marker)

                # For "v1", skip the protocol segments (https/repo1.maven.org/maven2/...)
                if marker == "v1" and marker_idx + 3 < len(path_parts):
                    # v1/https/repo1.maven.org/maven2/groupId/.../artifact-version.jar
                    maven2_idx = -1
                    for k in range(marker_idx + 1, len(path_parts)):
                        if path_parts[k] == "maven2":
                            maven2_idx = k
                            break
                    if maven2_idx >= 0 and maven2_idx + 1 < len(path_parts):
                        relative_jar_path = "/".join(path_parts[maven2_idx + 1:])
                        break
                elif marker == "maven2" and marker_idx + 1 < len(path_parts):
                    relative_jar_path = "/".join(path_parts[marker_idx + 1:])
                    break

        rctx.symlink(jar_path, "jars/" + relative_jar_path)

        imports.append(
            'java_import(name = "{n}", jars = ["jars/{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = relative_jar_path),
        )
        all_targets.append('":{}"'.format(target_name))
        if jar_path in core_jar_set:
            core_targets.append('":{}"'.format(target_name))

    rctx.file("BUILD.bazel", content = """\
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility = ["//visibility:public"])
{imports}
java_library(name = "core", exports = [{core_exports}])
java_library(name = "all", exports = [{all_exports}])
""".format(
        imports = "\n".join(imports),
        core_exports = ", ".join(core_targets),
        all_exports = ", ".join(all_targets),
    ))

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

    # Extract and validate the minor version against SUPPORTED_VERSIONS keys.
    minor = _extract_minor_version(version)
    if minor not in SUPPORTED_VERSIONS:
        supported_list = ", ".join(sorted(SUPPORTED_VERSIONS.keys()))
        fail("Unsupported Quarkus minor version '{}' (from version '{}'). Supported minor versions: {}".format(
            minor,
            version,
            supported_list,
        ))

    sanitized = _sanitize_version(minor)

    # Resolve quarkifier tool: local override > local build > GitHub release download
    if tc.quarkifier_tool:
        tool_target = str(tc.quarkifier_tool)
    elif tc.quarkifier_source_dir:
        _quarkus_quarkifier_local_build_repo(
            name = "rules_quarkus_quarkifier",
            source_dir = tc.quarkifier_source_dir,
            target = "//quarkifier:quarkifier_{}_deploy.jar".format(sanitized),
        )
        tool_target = "@rules_quarkus_quarkifier//:tool/quarkifier.jar"
    else:
        # Download per-minor JAR from GitHub releases.
        # RULES_VERSION is kept in sync with the module version in MODULE.bazel
        # and updated as part of the release process.
        release_tag = "v" + RULES_VERSION
        url = "https://github.com/{}/{}/releases/download/{}/quarkifier-{}-{}.jar".format(
            GITHUB_OWNER,
            GITHUB_REPO,
            release_tag,
            minor,
            release_tag,
        )
        _quarkus_quarkifier_download_repo(
            name = "rules_quarkus_quarkifier",
            url = url,
        )
        tool_target = "@rules_quarkus_quarkifier//:tool/quarkifier.jar"

    _quarkus_toolchains_repo(
        name = "rules_quarkus_toolchains",
        quarkifier_tool = tool_target,
        quarkus_version = version,
    )

    # Auto-discover deployment artifacts from lock file
    deployment_artifacts = [
        "io.quarkus:quarkus-core-deployment:" + version,
    ]

    # Conditional dev dependencies: these are extensions that Quarkus activates
    # only in dev mode via "conditional-dev-dependencies" in quarkus-extension.properties.
    # Maven's resolver handles this automatically; we must add them explicitly.
    # quarkus-devui-deployment: conditional dev dep of quarkus-vertx-http (Dev UI)
    conditional_dev_deployment_artifacts = [
        "io.quarkus:quarkus-devui-deployment:" + version,
    ]

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

    # Add conditional dev deployment artifacts after extension discovery
    for gav in conditional_dev_deployment_artifacts:
        if gav not in deployment_artifacts:
            deployment_artifacts.append(gav)

    _quarkus_deployment_repo(
        name = "quarkus_deployment",
        artifacts = deployment_artifacts,
    )

_toolchain_tag = tag_class(
    attrs = {
        "extension_group_prefixes": attr.string_list(
            default = ["io.quarkus", "io.quarkiverse."],
            doc = "Maven groupId prefixes that identify Quarkus extensions.",
        ),
        "lock_file": attr.label(
            doc = "Path to maven_install.json for auto-discovering Quarkus extensions.",
        ),
        "quarkifier_source_dir": attr.label(
            doc = """\
Label pointing to a file in the rules_quarkus source directory. The parent
directory is used to build the quarkifier deploy jar locally.
Used for local development and e2e testing.
Example: '@com_clementguillot_rules_quarkus//:MODULE.bazel'
""",
        ),
        "quarkifier_tool": attr.label(
            doc = """\
Override the quarkifier tool target with a pre-built deploy jar label.
Example: '//:quarkifier_3_33_deploy.jar'
""",
        ),
        "quarkus_version": attr.string(
            mandatory = True,
            doc = "The Quarkus version to use (e.g. '3.27.3').",
        ),
    },
)

quarkus = module_extension(
    implementation = _quarkus_impl,
    tag_classes = {"toolchain": _toolchain_tag},
    doc = "Configures Quarkus toolchain and auto-resolves deployment artifacts.",
)
