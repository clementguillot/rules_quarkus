"""Bzlmod module extension for configuring the Quarkus toolchain.

Auto-discovers Quarkus extensions from the maven_install.json lock file and
resolves their -deployment counterparts automatically. The quarkifier deploy
jar is downloaded from GitHub releases or overridden with a local build.
"""

load("//quarkus/private:versions.bzl", "GITHUB_OWNER", "GITHUB_REPO", "MAVEN_CENTRAL", "RULES_VERSION", "SUPPORTED_VERSIONS")

# ---- Repository rules ----

def _quarkus_toolchains_repo_impl(rctx):
    """Creates @rules_quarkus_toolchains with defs.bzl macro and tool alias."""
    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["defs.bzl"])
""")

    rctx.file("defs.bzl", content = """\
\"\"\"Public API — load quarkus_app, quarkus_dev, and quarkus_test from here.\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_dev_impl.bzl", "quarkus_dev_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "{tool}"
_FILE_WATCHER_TOOL = "{watcher_tool}"
_DEPLOYMENT_DEPS = "@quarkus_deployment//:all"
_CORE_DEPLOYMENT_DEPS = "@quarkus_deployment//:core"

def quarkus_app(name, **kwargs):
    quarkus_app_rule(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        **kwargs
    )

def quarkus_dev(name, **kwargs):
    quarkus_dev_rule(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        file_watcher_tool = _FILE_WATCHER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        core_deployment_deps = _CORE_DEPLOYMENT_DEPS,
        **kwargs
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
        watcher_tool = rctx.attr.quarkifier_tool.replace("quarkifier.jar", "bazel_file_watcher.jar"),
    ))

_quarkus_toolchains_repo = repository_rule(
    implementation = _quarkus_toolchains_repo_impl,
    attrs = {
        "quarkifier_tool": attr.string(mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
    },
)

def _quarkus_quarkifier_download_repo_impl(rctx):
    """Downloads the quarkifier JAR and file watcher JAR from a URL (GitHub release or Maven Central)."""
    rctx.download(url = rctx.attr.url, output = "tool/quarkifier.jar")

    # Download the file watcher jar (same release, different artifact name)
    watcher_url = rctx.attr.url.replace("quarkifier-deploy.jar", "bazel-file-watcher-deploy.jar")
    rctx.download(url = watcher_url, output = "tool/bazel_file_watcher.jar", allow_fail = True)

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool/quarkifier.jar", "tool/bazel_file_watcher.jar"])
""")

_quarkus_quarkifier_download_repo = repository_rule(
    implementation = _quarkus_quarkifier_download_repo_impl,
    attrs = {
        "url": attr.string(mandatory = True),
    },
)

def _quarkus_quarkifier_local_build_repo_impl(rctx):
    """Builds and symlinks the quarkifier deploy jar from a local workspace.

    When quarkifier_source_dir is set (local development), this rule
    automatically builds the deploy jar if it doesn't exist, then symlinks
    it. Requires matching Bazel versions between the source and consumer
    workspaces (both should use the same .bazelversion).
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

    deploy_jar = bazel_bin + "/quarkifier/quarkifier_deploy.jar"

    # Auto-build if the jar is missing
    prod_exists = rctx.execute(["test", "-f", deploy_jar]).return_code == 0

    if not prod_exists:
        rctx.report_progress("Building quarkifier deploy jar from source...")
        build_result = rctx.execute(
            [
                "bazel",
                "build",
                "//quarkifier:quarkifier_deploy.jar",
            ],
            working_directory = src_workspace,
            timeout = 300,
        )
        if build_result.return_code != 0:
            fail(
                "Failed to build quarkifier deploy jar in {}:\n{}".format(
                    src_workspace,
                    build_result.stderr,
                ),
            )

    # Verify the jar exists after build
    result = rctx.execute(["test", "-f", deploy_jar])
    if result.return_code != 0:
        fail("Quarkifier deploy jar not found at: {}\n".format(deploy_jar))

    rctx.symlink(deploy_jar, "tool/quarkifier.jar")

    # Also build and symlink the file watcher deploy jar for hot-reload
    watcher_deploy_jar = bazel_bin + "/quarkifier/bazel_file_watcher_deploy.jar"
    watcher_exists = rctx.execute(["test", "-f", watcher_deploy_jar]).return_code == 0

    if not watcher_exists:
        rctx.report_progress("Building file watcher deploy jar from source...")
        build_result = rctx.execute(
            [
                "bazel",
                "build",
                "//quarkifier:bazel_file_watcher_deploy.jar",
            ],
            working_directory = src_workspace,
            timeout = 300,
        )
        if build_result.return_code != 0:
            fail(
                "Failed to build file watcher deploy jar in {}:\n{}".format(
                    src_workspace,
                    build_result.stderr,
                ),
            )

    watcher_result = rctx.execute(["test", "-f", watcher_deploy_jar])
    if watcher_result.return_code != 0:
        fail("File watcher deploy jar not found at: {}\n".format(watcher_deploy_jar))

    rctx.symlink(watcher_deploy_jar, "tool/bazel_file_watcher.jar")

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool/quarkifier.jar", "tool/bazel_file_watcher.jar"])
""")

_quarkus_quarkifier_local_build_repo = repository_rule(
    implementation = _quarkus_quarkifier_local_build_repo_impl,
    attrs = {
        "source_dir": attr.label(mandatory = True),
    },
)

def _quarkus_deployment_repo_impl(rctx):
    """Downloads Quarkus deployment jars with transitive deps using Coursier.

    Produces two java_library targets:
      - :core  — transitive deps of quarkus-core-deployment only (dev jar manifest)
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
        rctx.symlink(jar_path, "jars/" + jar_file)
        imports.append(
            'java_import(name = "{n}", jars = ["jars/{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = jar_file),
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
    if version not in SUPPORTED_VERSIONS:
        fail("Unsupported Quarkus version '{}'. Supported: {}".format(version, SUPPORTED_VERSIONS))

    # Resolve quarkifier tool: local override > local build > GitHub release download
    if tc.quarkifier_tool:
        tool_target = str(tc.quarkifier_tool)
    elif tc.quarkifier_source_dir:
        _quarkus_quarkifier_local_build_repo(
            name = "rules_quarkus_quarkifier",
            source_dir = tc.quarkifier_source_dir,
        )
        tool_target = "@rules_quarkus_quarkifier//:tool/quarkifier.jar"
    else:
        # Download from GitHub releases:
        # https://github.com/{owner}/{repo}/releases/download/v{version}/quarkifier-deploy.jar
        release_tag = "v" + RULES_VERSION
        url = "https://github.com/{}/{}/releases/download/{}/quarkifier-deploy.jar".format(
            GITHUB_OWNER,
            GITHUB_REPO,
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

    # Conditional dev runtime artifacts: many Quarkus extensions have separate
    # "-dev" runtime modules that are only activated in dev mode (e.g.,
    # quarkus-arc-dev, quarkus-rest-dev). These contain dev-mode-specific
    # classes like EventsMonitor that the Dev UI build steps reference.
    # We resolve them alongside deployment artifacts so they're available
    # to the ApplicationModel.
    conditional_dev_runtime_artifacts = []

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

            # Add the conditional dev runtime artifact (e.g., quarkus-arc-dev)
            dev_runtime_gav = group_id + ":" + artifact_id + "-dev:" + version
            conditional_dev_runtime_artifacts.append(dev_runtime_gav)

    # Add conditional dev deployment artifacts after extension discovery
    for gav in conditional_dev_deployment_artifacts:
        if gav not in deployment_artifacts:
            deployment_artifacts.append(gav)

    # Add conditional dev runtime artifacts
    for gav in conditional_dev_runtime_artifacts:
        if gav not in deployment_artifacts:
            deployment_artifacts.append(gav)

    _quarkus_deployment_repo(
        name = "quarkus_deployment",
        artifacts = deployment_artifacts,
    )

_toolchain_tag = tag_class(
    attrs = {
        "quarkus_version": attr.string(
            mandatory = True,
            doc = "The Quarkus version to use (e.g. '3.27.3').",
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
Override the quarkifier tool target with a pre-built deploy jar label.
Example: '//:quarkifier_deploy.jar'
""",
        ),
        "quarkifier_source_dir": attr.label(
            doc = """\
Label pointing to a file in the rules_quarkus source directory. The parent
directory is used to build the quarkifier deploy jar locally.
Used for local development and e2e testing.
Example: '@com_clementguillot_rules_quarkus//:MODULE.bazel'
""",
        ),
    },
)

quarkus = module_extension(
    implementation = _quarkus_impl,
    tag_classes = {"toolchain": _toolchain_tag},
    doc = "Configures Quarkus toolchain and auto-resolves deployment artifacts.",
)
