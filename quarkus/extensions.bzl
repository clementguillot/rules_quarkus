"""Bzlmod module extension for configuring the Quarkus toolchain.

Auto-discovers Quarkus extensions from the maven_install.json lock file and
resolves their -deployment counterparts automatically. The quarkifier deploy
jar is downloaded from GitHub releases or overridden with a local build.

Produces a single generated repository (@rules_quarkus) containing:
  - quarkus/defs.bzl: public API macros (quarkus_app, quarkus_test)
  - quarkifier/: the quarkifier tool jar
  - deployment/: deployment jars resolved via Coursier
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

_DEFAULT_EXTENSION_GROUP_PREFIXES = ["io.quarkus", "io.quarkiverse."]

def _matches_prefix(group_id, prefixes):
    for prefix in prefixes:
        if prefix.endswith("."):
            if group_id.startswith(prefix) or group_id == prefix[:-1]:
                return True
        elif group_id == prefix:
            return True
    return False

def _rules_quarkus_repo_impl(rctx):
    """Creates the unified @rules_quarkus repository.

    Contains:
      - quarkus/defs.bzl: public macros
      - quarkifier/tool.jar: the quarkifier deploy jar
      - deployment/: java_library targets for deployment jars
    """

    # ---- Phase 1: Resolve quarkifier tool ----
    if rctx.attr.quarkifier_source_dir:
        # Local build mode
        src_workspace_raw = str(rctx.path(rctx.attr.quarkifier_source_dir).dirname)
        realpath_result = rctx.execute(["realpath", src_workspace_raw])
        src_workspace = realpath_result.stdout.strip() if realpath_result.return_code == 0 else src_workspace_raw

        nested_output_base = src_workspace + "/.bazel-nested-build"

        bin_result = rctx.execute(
            ["bazel", "--output_base=" + nested_output_base, "info", "bazel-bin", "--lockfile_mode=off"],
            working_directory = src_workspace,
            timeout = 60,
        )
        if bin_result.return_code == 0:
            bazel_bin = bin_result.stdout.strip()
        else:
            bazel_bin = src_workspace + "/bazel-bin"

        target = rctx.attr.quarkifier_build_target
        target_path = target.lstrip("/").replace(":", "/")
        deploy_jar = bazel_bin + "/" + target_path

        prod_exists = rctx.execute(["test", "-f", deploy_jar]).return_code == 0
        if not prod_exists:
            rctx.report_progress("Building {} from source...".format(target))
            build_result = rctx.execute(
                ["bazel", "--output_base=" + nested_output_base, "build", target, "--lockfile_mode=off"],
                working_directory = src_workspace,
                timeout = 300,
            )
            if build_result.return_code != 0:
                fail("Failed to build {} in {}:\n{}".format(target, src_workspace, build_result.stderr))

        result = rctx.execute(["test", "-f", deploy_jar])
        if result.return_code != 0:
            fail("Quarkifier deploy jar not found at: {}".format(deploy_jar))

        rctx.symlink(deploy_jar, "quarkifier/tool.jar")
    elif rctx.attr.quarkifier_url:
        # Download mode
        rctx.download(url = rctx.attr.quarkifier_url, output = "quarkifier/tool.jar")
    else:
        fail("Either quarkifier_source_dir or quarkifier_url must be set")

    # ---- Phase 2: Resolve deployment jars via Coursier ----
    rctx.download(
        url = "https://github.com/coursier/launchers/raw/master/coursier",
        output = "deployment/coursier",
        executable = True,
    )

    artifacts = rctx.attr.deployment_artifacts
    repository_url = rctx.attr.repository_url

    # Phase 2a: Resolve quarkus-core-deployment transitive deps (the "core" set).
    core_jar_paths = []
    core_gav = artifacts[0] if artifacts else None
    if core_gav:
        result = rctx.execute(
            ["./deployment/coursier", "fetch", "--repository", repository_url, core_gav],
            timeout = 300,
        )
        if result.return_code == 0:
            for line in result.stdout.strip().split("\n"):
                line = line.strip()
                if line.endswith(".jar") and line not in core_jar_paths:
                    core_jar_paths.append(line)

    core_jar_set = {p: True for p in core_jar_paths}

    # Phase 2b: Resolve all deployment artifacts (core + extensions).
    all_jars = list(core_jar_paths)
    for gav in artifacts:
        result = rctx.execute(
            ["./deployment/coursier", "fetch", "--repository", repository_url, gav],
            timeout = 300,
        )
        if result.return_code == 0:
            for line in result.stdout.strip().split("\n"):
                line = line.strip()
                if line.endswith(".jar") and line not in all_jars:
                    all_jars.append(line)

    # Phase 2c: Generate deployment BUILD targets.
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

        # Preserve Maven directory structure in symlinks for Dev UI version extraction.
        relative_jar_path = jar_file
        path_parts = jar_path.replace("\\", "/").split("/")

        for marker in ["maven2", "v1"]:
            if marker in path_parts:
                marker_idx = path_parts.index(marker)
                if marker == "v1" and marker_idx + 3 < len(path_parts):
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

        rctx.symlink(jar_path, "deployment/jars/" + relative_jar_path)

        imports.append(
            'java_import(name = "{n}", jars = ["jars/{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = relative_jar_path),
        )
        all_targets.append('":{}"'.format(target_name))
        if jar_path in core_jar_set:
            core_targets.append('":{}"'.format(target_name))

    # ---- Phase 3: Write BUILD files ----

    # Root BUILD
    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
""")

    # quarkifier/ BUILD
    rctx.file("quarkifier/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool.jar"])
""")

    # deployment/ BUILD
    rctx.file("deployment/BUILD.bazel", content = """\
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

    # ---- Phase 4: Write quarkus/defs.bzl ----

    rctx.file("quarkus/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["defs.bzl"])
""")

    rctx.file("quarkus/defs.bzl", content = """\
\"\"\"Public API — load quarkus_app and quarkus_test from here.

    load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")

quarkus_app() automatically creates a <name>_dev target for Quarkus dev mode
with hot-reload support. Use dev=False to opt out.
\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_dev_impl.bzl", "quarkus_dev_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")
load("@rules_java//java:java_library.bzl", "java_library")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "@rules_quarkus//quarkifier:tool.jar"
_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:all"
_CORE_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:core"

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
        java_library(
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
""".format(version = rctx.attr.quarkus_version))

_rules_quarkus_repo = repository_rule(
    implementation = _rules_quarkus_repo_impl,
    attrs = {
        "deployment_artifacts": attr.string_list(mandatory = True, doc = "Deployment GAV coordinates to resolve."),
        "quarkifier_build_target": attr.string(doc = "Bazel target for the per-minor deploy jar (local build mode)."),
        "quarkifier_source_dir": attr.label(doc = "Label in the rules_quarkus source dir (local build mode)."),
        "quarkifier_url": attr.string(doc = "URL to download the quarkifier jar from (release mode)."),
        "quarkus_version": attr.string(mandatory = True, doc = "Quarkus version."),
        "repository_url": attr.string(default = MAVEN_CENTRAL, doc = "Maven repository URL for Coursier."),
    },
)

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

    # Resolve quarkifier tool source
    quarkifier_url = ""
    quarkifier_source_dir = None
    quarkifier_build_target = ""

    if tc.quarkifier_source_dir:
        quarkifier_source_dir = tc.quarkifier_source_dir
        quarkifier_build_target = "//quarkifier:quarkifier_{}_deploy.jar".format(sanitized)
    else:
        # Download per-minor JAR from GitHub releases.
        release_tag = "v" + RULES_VERSION
        quarkifier_url = "https://github.com/{}/{}/releases/download/{}/quarkifier-{}-{}.jar".format(
            GITHUB_OWNER,
            GITHUB_REPO,
            release_tag,
            minor,
            release_tag,
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
        if lock_content:
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

    # Create the single unified repository
    repo_attrs = {
        "name": "rules_quarkus",
        "quarkus_version": version,
        "deployment_artifacts": deployment_artifacts,
    }
    if quarkifier_source_dir:
        repo_attrs["quarkifier_source_dir"] = quarkifier_source_dir
        repo_attrs["quarkifier_build_target"] = quarkifier_build_target
    else:
        repo_attrs["quarkifier_url"] = quarkifier_url

    _rules_quarkus_repo(**repo_attrs)

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
