"""Bzlmod module extension for configuring the Quarkus toolchain.

Auto-discovers Quarkus extensions from the maven_install.json lock file and
resolves their -deployment counterparts automatically. The quarkifier deploy
jar is downloaded from GitHub releases or overridden with a local build.

Produces a single generated repository (@rules_quarkus) containing:
  - quarkus/defs.bzl: public API macros (quarkus_app, quarkus_test)
  - quarkifier/: the quarkifier tool jar
  - deployment/: deployment jars resolved via Coursier
"""

load("//quarkus/private:versions.bzl", "COURSIER_SHA256", "COURSIER_URL", "GITHUB_OWNER", "GITHUB_REPO", "MAVEN_CENTRAL", "QUARKIFIER_SHA256", "RULES_VERSION", "SUPPORTED_VERSIONS")

_DEFAULT_EXTENSION_GROUP_PREFIXES = ["io.quarkus", "io.quarkiverse."]

# ---- Version helpers ----

def _extract_minor_version(version):
    """Extracts the minor version (e.g. "3.27") from a full version string."""
    parts = version.split(".")
    if len(parts) < 2:
        fail("Invalid Quarkus version '{}': expected MAJOR.MINOR.PATCH format".format(version))
    return parts[0] + "." + parts[1]

def _validate_version(version):
    """Validates a full Quarkus version against SUPPORTED_VERSIONS.

    The quarkifier is bytecode-coupled to exact patch versions, so the full
    version must match the supported patch for its minor exactly.

    Returns:
        The minor version string (e.g. "3.27").
    """
    minor = _extract_minor_version(version)
    supported_list = ", ".join([SUPPORTED_VERSIONS[m] for m in sorted(SUPPORTED_VERSIONS)])
    expected = SUPPORTED_VERSIONS.get(minor)
    if not expected:
        fail("Unsupported Quarkus minor version '{}' (from version '{}'). Supported versions: {}".format(
            minor,
            version,
            supported_list,
        ))
    if version != expected:
        fail("Unsupported Quarkus version '{}': the quarkifier is coupled to exact patch versions. Supported versions: {}".format(
            version,
            supported_list,
        ))
    return minor

def _sanitize_version(minor_version):
    """Replaces dots with underscores for use in Bazel target and repo names."""
    return minor_version.replace(".", "_")

# ---- Coursier stderr parsing ----

def _coursier_error_gav(line):
    """Returns the GAV from a Coursier error header, or empty string."""
    token = ""
    if line.startswith("Resolution error: Error downloading "):
        token = line[len("Resolution error: Error downloading "):]
    elif line.startswith("Error downloading "):
        token = line[len("Error downloading "):]
    if not token:
        return ""

    gav = token.split(" ")[0]
    return gav if gav.count(":") == 2 else ""

def _coursier_error_block_is_missing(lines):
    """True only when the whole Coursier error block is made of not-found attempts."""
    saw_not_found = False
    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("not found:"):
            saw_not_found = True
            continue
        return False
    return saw_not_found

def _missing_artifacts_from_coursier_stderr(stderr):
    """Finds GAVs whose Coursier error blocks prove the artifact is missing."""
    missing = {}
    current_gav = ""
    current_lines = []

    for raw_line in stderr.split("\n"):
        line = raw_line.strip()
        gav = _coursier_error_gav(line)
        if gav:
            if current_gav and _coursier_error_block_is_missing(current_lines):
                missing[current_gav] = True
            current_gav = gav
            current_lines = []
        elif current_gav:
            current_lines.append(line)

    if current_gav and _coursier_error_block_is_missing(current_lines):
        missing[current_gav] = True

    return missing

# ---- Deployment artifact discovery ----

def _matches_prefix(group_id, prefixes):
    for prefix in prefixes:
        if prefix.endswith("."):
            if group_id.startswith(prefix) or group_id == prefix[:-1]:
                return True
        elif group_id == prefix:
            return True
    return False

def _lock_artifact_versions(lock_data):
    """Builds a "group:artifact" → version map from the lock file's resolved artifacts.

    rules_jvm_external lock files store the resolved version of every artifact
    in the "artifacts" map ({"g:a": {"shasums": {...}, "version": "..."}}).
    """
    versions = {}
    artifacts = lock_data.get("artifacts", {})
    if type(artifacts) != "dict":
        return versions
    for coord_key, info in artifacts.items():
        if type(info) != "dict":
            continue
        version = info.get("version", "")
        if not version:
            continue
        parts = coord_key.split(":")
        if len(parts) < 2:
            continue
        versions[parts[0] + ":" + parts[1]] = version
    return versions

def _append_deployment_artifacts_from_lock_map(lock_artifacts, deployment_artifacts, prefixes, quarkus_version, versions_by_ga):
    seen = {gav: True for gav in deployment_artifacts}
    for coord_key in lock_artifacts:
        parts = coord_key.split(":")
        if len(parts) < 2:
            continue
        group_id = parts[0]
        artifact_id = parts[1]
        if artifact_id.endswith("-deployment"):
            continue
        if not _matches_prefix(group_id, prefixes):
            continue

        # Extensions version independently from Quarkus core (e.g. Quarkiverse),
        # and a deployment artifact must match its runtime counterpart exactly,
        # so use the runtime artifact's own version from the lock file. Fall
        # back to the Quarkus version only when the lock carries no version —
        # correct for io.quarkus artifacts, a guess for anything else.
        ga = group_id + ":" + artifact_id
        version = versions_by_ga.get(ga, "")
        if not version:
            version = quarkus_version
            if group_id != "io.quarkus":
                # buildifier: disable=print
                print(("WARNING: rules_quarkus could not determine the version of {} from the " +
                       "maven lock file; assuming Quarkus version {} for its -deployment " +
                       "artifact, which may not exist for independently-versioned extensions.").format(ga, quarkus_version))

        deployment_gav = ga + "-deployment:" + version
        if deployment_gav not in seen:
            seen[deployment_gav] = True
            deployment_artifacts.append(deployment_gav)

def _discover_deployment_artifacts(mctx, tc, version):
    """Builds the deployment GAV list: core + lock-file extensions + dev-only deps."""
    deployment_artifacts = ["io.quarkus:quarkus-core-deployment:" + version]
    prefixes = tc.extension_group_prefixes

    if tc.lock_file:
        lock_content = mctx.read(tc.lock_file)
        if lock_content:
            lock_data = json.decode(lock_content)
            versions_by_ga = _lock_artifact_versions(lock_data)

            # Prefer direct dependencies first for deterministic ordering, then
            # scan the resolved graph too so transitive Quarkus extensions are
            # not missed. Non-extension internal modules that do not publish a
            # -deployment artifact are filtered during the Coursier retry.
            input_artifacts = lock_data.get("__INPUT_ARTIFACTS_HASH", {})
            if input_artifacts and type(input_artifacts) == "dict":
                _append_deployment_artifacts_from_lock_map(input_artifacts, deployment_artifacts, prefixes, version, versions_by_ga)

            resolved_artifacts = lock_data.get("artifacts", lock_data.get("dependencies", lock_data.get("__RESOLVED_ARTIFACTS_HASH", {})))
            if resolved_artifacts and type(resolved_artifacts) == "dict":
                _append_deployment_artifacts_from_lock_map(resolved_artifacts, deployment_artifacts, prefixes, version, versions_by_ga)

    # quarkus-devui-deployment is a conditional dev dependency of
    # quarkus-vertx-http (Dev UI): Quarkus activates it only in dev mode via
    # "conditional-dev-dependencies" in quarkus-extension.properties. Maven's
    # resolver handles this automatically; we must add it explicitly.
    devui_gav = "io.quarkus:quarkus-devui-deployment:" + version
    if devui_gav not in deployment_artifacts:
        deployment_artifacts.append(devui_gav)

    return deployment_artifacts

# ---- Quarkifier tool resolution ----

def _build_quarkifier_from_source(rctx):
    """Builds the quarkifier deploy jar from a local source checkout.

    Runs a nested Bazel build with a dedicated output base so it does not
    clash with the outer build holding the workspace lock.

    The build runs on every fetch — a previous `test -f` fast path silently
    reused stale jars after quarkifier source edits, making bugfixes appear
    to have no effect during development. With a warm nested output base the
    no-change build is a no-op in seconds. The quarkifier source tree is
    additionally watched so edits invalidate this repository and trigger a
    refetch in the first place.
    """
    src_workspace = str(rctx.path(rctx.attr.quarkifier_source_dir).dirname)

    # Invalidate this repo when quarkifier sources change (Bazel 7.1+). The
    # watched subtree must not contain build outputs: watching the whole
    # source workspace would self-invalidate on every nested build (it hosts
    # .bazel-nested-build/ and the bazel-* convenience symlinks).
    if hasattr(rctx, "watch_tree"):
        rctx.watch_tree(src_workspace + "/quarkifier")

    nested_output_base = src_workspace + "/.bazel-nested-build"

    bin_result = rctx.execute(
        ["bazel", "--output_base=" + nested_output_base, "info", "bazel-bin", "--lockfile_mode=off"],
        working_directory = src_workspace,
        timeout = 60,
    )
    bazel_bin = bin_result.stdout.strip() if bin_result.return_code == 0 else src_workspace + "/bazel-bin"

    target = rctx.attr.quarkifier_build_target
    deploy_jar = bazel_bin + "/" + target.lstrip("/").replace(":", "/")

    rctx.report_progress("Building {} from source".format(target))
    build_result = rctx.execute(
        ["bazel", "--output_base=" + nested_output_base, "build", target, "--lockfile_mode=off"],
        working_directory = src_workspace,
        # Generous: a cold nested build fetches the maven deps over the network.
        timeout = 600,
    )
    if build_result.return_code != 0:
        fail("Failed to build {} in {}:\n{}".format(target, src_workspace, build_result.stderr))
    if not rctx.path(deploy_jar).exists:
        fail("Quarkifier deploy jar not found at: {}".format(deploy_jar))

    # Copy (not symlink): a symlink into the nested bazel-bin dangles after a
    # `bazel clean` in the source checkout, with no repo invalidation to heal it.
    if rctx.execute(["mkdir", "-p", "quarkifier"]).return_code != 0:
        fail("Failed to create the quarkifier directory in the repository")
    copy_result = rctx.execute(["cp", deploy_jar, "quarkifier/tool.jar"])
    if copy_result.return_code != 0:
        fail("Failed to copy the quarkifier deploy jar into the repository: " + copy_result.stderr)

def _resolve_quarkifier_tool(rctx):
    """Materializes quarkifier/tool.jar from a local build or a release download.

    Release downloads are verified against the checksum patched into
    QUARKIFIER_SHA256 by release_prep.sh (or supplied by the user via
    quarkus.toolchain(quarkifier_sha256 = ...)). Without a checksum the
    download still works, but the computed hash is printed so it can be
    pinned — the jar is executable code run by build actions, dev mode, and
    tests, so unverified use should be a deliberate choice.
    """
    if rctx.attr.quarkifier_source_dir:
        _build_quarkifier_from_source(rctx)
    elif rctx.attr.quarkifier_url:
        if rctx.attr.quarkifier_sha256:
            rctx.download(
                url = rctx.attr.quarkifier_url,
                output = "quarkifier/tool.jar",
                sha256 = rctx.attr.quarkifier_sha256,
            )
        else:
            result = rctx.download(url = rctx.attr.quarkifier_url, output = "quarkifier/tool.jar")

            # buildifier: disable=print
            print((
                "\nWARNING: rules_quarkus downloaded the quarkifier tool jar without " +
                "checksum verification:\n    {url}\n" +
                "Pin it in MODULE.bazel:\n" +
                "    quarkus.toolchain(quarkifier_sha256 = \"{sha}\", ...)\n"
            ).format(url = rctx.attr.quarkifier_url, sha = result.sha256))
    else:
        fail("Either quarkifier_source_dir or quarkifier_url must be set")

# ---- Deployment jar resolution via Coursier ----

# bin/java of the rules_java-managed remote JDKs, keyed by host platform.
# These are the same repos --java_runtime_version=remotejdk_17 resolves to,
# so when the build already uses a remote JDK no extra download happens.
_REMOTE_JDK_JAVA = {
    "linux-aarch64": Label("@remotejdk17_linux_aarch64//:bin/java"),
    "linux-x86_64": Label("@remotejdk17_linux//:bin/java"),
    "macos-aarch64": Label("@remotejdk17_macos_aarch64//:bin/java"),
    "macos-x86_64": Label("@remotejdk17_macos//:bin/java"),
}

def _host_platform_key(os):
    """Maps an os struct to a _REMOTE_JDK_JAVA key, or None if unsupported."""
    name = os.name.lower()
    if "mac" in name:
        platform = "macos"
    elif "linux" in name:
        platform = "linux"
    else:
        return None
    arch = os.arch.lower()
    if arch in ["aarch64", "arm64"]:
        cpu = "aarch64"
    elif arch in ["x86_64", "amd64"]:
        cpu = "x86_64"
    else:
        return None
    return platform + "-" + cpu

def _is_usable_java(rctx, java):
    return rctx.execute([java, "-version"], timeout = 30).return_code == 0

def _find_java(rctx):
    """Locates a JVM to run Coursier with.

    Repository rules run in the fetch phase, before toolchain resolution, so
    --java_runtime_version cannot be honored here. Order: JAVA_HOME, then a
    validated `java` from PATH (the macOS /usr/bin/java stub fails without an
    installed JDK), then the hermetic fallback JDK (fetched lazily).
    """
    java_home = rctx.getenv("JAVA_HOME")
    if java_home:
        java = java_home + "/bin/java"
        if rctx.path(java).exists and _is_usable_java(rctx, java):
            return java

    java = rctx.which("java")
    if java and _is_usable_java(rctx, str(java)):
        return str(java)

    if rctx.attr.fallback_java:
        return str(rctx.path(rctx.attr.fallback_java))

    fail("No Java runtime found to run Coursier: set JAVA_HOME or add java to PATH " +
         "(no bundled JDK fallback is available for this host platform).")

def _coursier_fetch(rctx, java, artifacts):
    """Runs a batched Coursier fetch.

    Timeout scales with artifact count: cold-cache batch fetches for large
    projects can legitimately exceed a flat 300 s.
    """
    return rctx.execute(
        [java, "-jar", "deployment/coursier.jar", "fetch", "--repository", rctx.attr.repository_url] + artifacts,
        timeout = max(300, len(artifacts) * 60),
    )

def _jar_paths_from_fetch_output(stdout, seen):
    """Extracts unique jar paths from Coursier fetch stdout, updating `seen`."""
    jars = []
    for raw_line in stdout.strip().split("\n"):
        line = raw_line.strip()
        if line.endswith(".jar") and line not in seen:
            seen[line] = True
            jars.append(line)
    return jars

def _resolve_core_jars(rctx, java):
    """Resolves the quarkus-core-deployment transitive closure (the "core" set).

    Fails hard on error: continuing would materialize an empty deployment:core
    target and silently break dev mode at runtime.
    """
    artifacts = rctx.attr.deployment_artifacts
    if not artifacts:
        return []
    rctx.report_progress("Resolving core deployment dependencies")
    result = _coursier_fetch(rctx, java, [artifacts[0]])
    if result.return_code != 0:
        fail("Failed to resolve core deployment dependencies ('{}') via Coursier:\n{}".format(
            artifacts[0],
            result.stderr,
        ))
    return _jar_paths_from_fetch_output(result.stdout, {})

def _resolve_deployment_jars(rctx, java, core_jar_paths):
    """Resolves all deployment artifacts in a single batched Coursier call.

    Batching avoids spawning a separate JVM per artifact (6-10x faster). If
    the batch fails because some artifacts don't exist, stderr is parsed to
    identify genuinely missing ones and the fetch is retried without them.
    Up to 3 retries, so incrementally-reported missing artifacts are
    progressively removed until resolution stabilises.

    Returns:
        The list of all resolved jar paths, starting with core_jar_paths.
    """
    rctx.report_progress("Resolving deployment artifacts")
    fetch_artifacts = list(rctx.attr.deployment_artifacts)
    first_stderr = ""
    dropped = []
    result = _coursier_fetch(rctx, java, fetch_artifacts)
    for _ in range(3):
        if result.return_code == 0:
            break

        # Coursier stderr contains multi-line blocks per failed artifact:
        #   "Error downloading group:artifact:version"
        #   "  not found: <url>"
        #   "  download error: ..."
        # Retry only when every non-empty line in a GAV's error block is
        # "not found:". Mixed blocks fail closed so transient/private-repo
        # failures cannot be mistaken for missing extension deployment jars.
        missing = _missing_artifacts_from_coursier_stderr(result.stderr)
        filtered = [a for a in fetch_artifacts if a not in missing]
        if len(filtered) == len(fetch_artifacts) or not filtered:
            break
        if not first_stderr:
            first_stderr = result.stderr
        dropped += [a for a in fetch_artifacts if a in missing]
        fetch_artifacts = filtered
        result = _coursier_fetch(rctx, java, fetch_artifacts)

    if result.return_code != 0:
        message = "Failed to resolve deployment artifacts via Coursier:\n" + result.stderr
        if first_stderr:
            message += "\n\n(Initial error before retry:\n{})".format(first_stderr)
        fail(message)

    if dropped:
        # Internal (non-extension) modules matching extension_group_prefixes
        # legitimately have no -deployment artifact, but a real extension in
        # this list means its build steps will silently not run — surface it.
        # buildifier: disable=print
        print(("WARNING: rules_quarkus skipped deployment artifacts that do not exist in the " +
               "Maven repository:\n    {}\n" +
               "Internal (non-extension) modules matching extension_group_prefixes are expected " +
               "here. If one of these is a real Quarkus extension, its deployment jar was NOT " +
               "resolved — check the artifact's version in your maven lock file.").format(
            "\n    ".join(dropped),
        ))

    seen_jars = {p: True for p in core_jar_paths}
    return list(core_jar_paths) + _jar_paths_from_fetch_output(result.stdout, seen_jars)

def _maven_relative_path(jar_path):
    """Returns the jar path relative to its Maven repository root.

    Preserving the Maven directory structure (group/artifact/version/file) in
    the copied deployment jars is required for Dev UI version extraction.
    Coursier cache paths contain a "maven2" component; everything after it is
    the Maven-layout path. Falls back to the bare file name.
    """
    parts = jar_path.replace("\\", "/").split("/")
    if "maven2" in parts:
        maven2_idx = parts.index("maven2")
        if maven2_idx + 1 < len(parts):
            return "/".join(parts[maven2_idx + 1:])
    return parts[-1]

def _jar_target_name(relative_jar_path):
    """Derives the java_import target name from the Maven-relative jar path.

    Uses the group/artifact/version directory — unique per GAV — rather than
    the bare file name: different groupIds can publish the same
    artifactId-version.jar, and a name collision must never drop a jar from
    the deployment classpath.
    """
    if "/" in relative_jar_path:
        base = relative_jar_path.rsplit("/", 1)[0]
    else:
        base = relative_jar_path.removesuffix(".jar")
    return base.replace("/", "_").replace(".", "_").replace("-", "_")

def _copy_jars_into_repo(rctx, copies):
    """Copies resolved jars into the repository directory.

    The repository must own its files: symlinks into the machine-global
    Coursier cache dangle when the cache is cleaned (with no repo
    invalidation to recover), and later cache mutations change action inputs
    underneath Bazel — a remote-cache poisoning vector. Copying snapshots the
    verified download while the global cache keeps making fetches fast.

    Args:
        rctx: Repository context.
        copies: List of (source path, repo-relative destination) tuples.
    """
    if not copies:
        return
    rctx.report_progress("Copying deployment jars into the repository")

    # One mkdir for all parent directories (small argv), then one cp per jar
    # (batching all jars into a single argv could exceed the kernel limits).
    dest_dirs = {dest.rsplit("/", 1)[0]: True for _, dest in copies}
    result = rctx.execute(["mkdir", "-p"] + list(dest_dirs.keys()))
    if result.return_code != 0:
        fail("Failed to create deployment jar directories: " + result.stderr)
    for src, dest in copies:
        result = rctx.execute(["cp", src, dest])
        if result.return_code != 0:
            fail("Failed to copy deployment jar {} to {}: {}".format(src, dest, result.stderr))

def _write_deployment_build(rctx, all_jars, core_jar_set):
    """Copies resolved jars into the repo and writes the deployment/ BUILD file.

    Generates one java_import per jar plus two aggregate java_library targets:
    "core" (quarkus-core-deployment closure) and "all" (every deployment jar).
    Import names are internal — consumers depend on :core / :all.
    """
    imports = []
    all_targets = []
    core_targets = []
    copies = []
    seen = {}
    for jar_path in all_jars:
        relative_jar_path = _maven_relative_path(jar_path)
        target_name = _jar_target_name(relative_jar_path)
        jar_repo_path = "jars/" + relative_jar_path

        # Residual collisions (same file name in the non-maven2 fallback, or
        # the same GAV path from two sources) are disambiguated instead of
        # dropped: every resolved jar must stay on the deployment classpath.
        if target_name in seen:
            seen[target_name] += 1
            n = seen[target_name]

            # buildifier: disable=print
            print(("WARNING: rules_quarkus: deployment jars collide on target name '{}' " +
                   "({}); keeping both, this one as '{}_dup{}'.").format(
                target_name,
                jar_path,
                target_name,
                n,
            ))
            target_name = "{}_dup{}".format(target_name, n)
            jar_repo_path = "jars/dup{}/{}".format(n, relative_jar_path)
        else:
            seen[target_name] = 1

        copies.append((jar_path, "deployment/" + jar_repo_path))

        imports.append(
            'java_import(name = "{n}", jars = ["{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = jar_repo_path),
        )
        all_targets.append('":{}"'.format(target_name))
        if jar_path in core_jar_set:
            core_targets.append('":{}"'.format(target_name))

    _copy_jars_into_repo(rctx, copies)

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

# ---- Generated @rules_quarkus//quarkus:defs.bzl ----

_DEFS_BZL_TEMPLATE = """\
\"\"\"Public API — load quarkus_app, quarkus_test and quarkus_extension_runtime from here.

    load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test", "quarkus_extension_runtime")

quarkus_app() automatically creates a <name>_dev target for Quarkus dev mode
with hot-reload support. Use dev=False to opt out.
Use native=True to create a <name>_native target for GraalVM native image compilation.

quarkus_extension_runtime() wraps a local Quarkus extension runtime module;
depend on that runtime target from application code and the deployment side is
added to Quarkus augmentation automatically.
\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_dev_impl.bzl", "quarkus_dev_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_extension_impl.bzl", "quarkus_extension_runtime_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_native_app_impl.bzl", "quarkus_native_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_native_container_app_impl.bzl", "quarkus_native_container_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_test_impl.bzl", _quarkus_test = "quarkus_test")
load("@com_clementguillot_rules_quarkus//quarkus/private:versions.bzl", "DEFAULT_NATIVE_BUILDER_IMAGE")
load("@rules_java//java:java_library.bzl", "java_library")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "@rules_quarkus//quarkifier:tool.jar"
_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:all"
_CORE_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:core"

# Digest-pinned so the native-image toolchain is part of the action key
# (a mutable tag lets the same cache key cover different GraalVM versions).
_DEFAULT_BUILDER_IMAGE = DEFAULT_NATIVE_BUILDER_IMAGE

def quarkus_app(name, dev = True, dev_build_args = [], native = False, native_container_build = False,
                native_container_runtime = "auto", native_builder_image = _DEFAULT_BUILDER_IMAGE,
                **kwargs):
    \"\"\"Builds a Quarkus application with optional dev-mode and native targets.

    Creates:
      - <name>: production Fast_Jar target (bazel run //pkg:<name>)
      - <name>_dev: dev mode with hot-reload (bazel run //pkg:<name>_dev), unless dev=False
      - <name>_native: native binary (bazel run //pkg:<name>_native), if native=True or native_container_build=True

    Args:
        name: Target name.
        dev: If True (default), also creates a <name>_dev target for dev mode.
        dev_build_args: Extra flags for the hot-reload `bazel build` (e.g. ["--config=dev"]).
            Must match the flags you pass to `bazel run` for the dev target, otherwise
            rebuilt classes land in a different output tree and hot-reload syncs stale files.
        native: If True, creates a <name>_native target using rules_graalvm (host compilation).
        native_container_build: If True, creates a <name>_native target using Docker/Podman (container compilation).
        native_container_runtime: Container runtime: 'auto' (default), 'docker', or 'podman'.
        native_builder_image: Builder image for container native compilation.
        **kwargs: Passed to the underlying quarkus_app_rule (deps, version, jvm_flags, etc.).
    \"\"\"
    if native and native_container_build:
        fail("Cannot set both 'native' and 'native_container_build'. " +
             "Use 'native' for host-based compilation (rules_graalvm) or " +
             "'native_container_build' for container-based compilation (Docker/Podman).")

    quarkus_app_rule(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        **kwargs
    )

    # Attrs shared by the secondary (_dev / _native) targets.
    common = dict(
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        deps = kwargs.get("deps", []),
        version = kwargs.get("version", ""),
    )
    main_class = kwargs.get("main_class", "")
    if dev:
        quarkus_dev_rule(
            name = name + "_dev",
            core_deployment_deps = _CORE_DEPLOYMENT_DEPS,
            dev_build_args = dev_build_args,
            **common
        )
    if native:
        quarkus_native_app_rule(
            name = name + "_native",
            main_class = main_class,
            **common
        )
    if native_container_build:
        quarkus_native_container_app_rule(
            name = name + "_native",
            main_class = main_class,
            container_runtime = native_container_runtime,
            builder_image = native_builder_image,
            native_arch = select({{
                "@platforms//cpu:aarch64": "aarch64",
                "@platforms//cpu:x86_64": "amd64",
                "//conditions:default": "unsupported",
            }}),
            **common
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

def quarkus_extension_runtime(name, group_id, version, runtime_target, deployment_target,
                              artifact_id):
    \"\"\"Builds a Quarkus extension runtime target from java_library targets.

    Mirrors the Maven/Gradle extension layout: a runtime module that application
    code depends on, and a deployment module that runs at augmentation time.
    Bundles a generated META-INF/quarkus-extension.properties into the runtime jar;
    the runtime module's own META-INF/quarkus-extension.yaml resource (read by the
    Dev UI) is carried through, enriched with the Quarkus core version and the
    extension's extension-dependencies (discovered from the compile classpath).

    Creates:
      - <name>: the runtime library. Add it to your application's java_library deps;
        the deployment side is wired into augmentation automatically.

    Args:
        name: Runtime library target name (the extension's public name).
        group_id: Maven groupId for the generated deployment-artifact descriptor.
        version: Maven version for the generated deployment-artifact descriptor.
        runtime_target: java_library target for the runtime module.
        deployment_target: java_library target for the deployment module.
        artifact_id: Extension artifactId (deployment artifact is artifact_id + "-deployment").
    \"\"\"
    quarkus_extension_runtime_rule(
        name = name,
        runtime = runtime_target,
        deployment = deployment_target,
        group_id = group_id,
        artifact_id = artifact_id,
        version = version,
        quarkus_version = _QUARKUS_VERSION,
    )
"""

# ---- Repository rule ----

def _rules_quarkus_repo_impl(rctx):
    """Creates the unified @rules_quarkus repository.

    Contains:
      - quarkus/defs.bzl: public macros
      - quarkifier/tool.jar: the quarkifier deploy jar
      - deployment/: java_library targets for deployment jars
    """
    _resolve_quarkifier_tool(rctx)

    rctx.download(
        url = COURSIER_URL,
        sha256 = COURSIER_SHA256,
        output = "deployment/coursier.jar",
    )

    java = _find_java(rctx)
    core_jar_paths = _resolve_core_jars(rctx, java)
    all_jars = _resolve_deployment_jars(rctx, java, core_jar_paths)
    _write_deployment_build(rctx, all_jars, {p: True for p in core_jar_paths})

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
""")

    rctx.file("quarkifier/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["tool.jar"])
""")

    rctx.file("quarkus/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["defs.bzl"])
""")

    rctx.file("quarkus/defs.bzl", content = _DEFS_BZL_TEMPLATE.format(version = rctx.attr.quarkus_version))

_rules_quarkus_repo = repository_rule(
    implementation = _rules_quarkus_repo_impl,
    attrs = {
        "deployment_artifacts": attr.string_list(mandatory = True, doc = "Deployment GAV coordinates to resolve."),
        "fallback_java": attr.label(
            allow_single_file = True,
            doc = "bin/java of the hermetic JDK used to run Coursier when the host has no usable JVM.",
        ),
        "quarkifier_build_target": attr.string(doc = "Bazel target for the per-minor deploy jar (local build mode)."),
        "quarkifier_sha256": attr.string(doc = "SHA-256 checksum for the quarkifier jar download (release mode). Empty disables verification."),
        "quarkifier_source_dir": attr.label(doc = "Label in the rules_quarkus source dir (local build mode)."),
        "quarkifier_url": attr.string(doc = "URL to download the quarkifier jar from (release mode)."),
        "quarkus_version": attr.string(mandatory = True, doc = "Quarkus version."),
        "repository_url": attr.string(default = MAVEN_CENTRAL, doc = "Maven repository URL for Coursier."),
    },
)

# ---- Module extension ----

def _quarkifier_repo_attrs(tc, minor):
    """Returns the repo attrs selecting local-build or release-download mode."""
    if tc.quarkifier_source_dir:
        return {
            "quarkifier_source_dir": tc.quarkifier_source_dir,
            "quarkifier_build_target": "//quarkifier:quarkifier_{}_deploy.jar".format(_sanitize_version(minor)),
        }

    release_tag = "v" + RULES_VERSION
    return {
        "quarkifier_url": "https://github.com/{}/{}/releases/download/{}/quarkifier-{}-{}.jar".format(
            GITHUB_OWNER,
            GITHUB_REPO,
            release_tag,
            minor,
            release_tag,
        ),
        # User-supplied pin wins; otherwise the checksum patched into the
        # release archive by release_prep.sh (empty in the git tree).
        "quarkifier_sha256": tc.quarkifier_sha256 or QUARKIFIER_SHA256.get(minor, ""),
    }

def _quarkus_impl(mctx):
    # Collect toolchain tags from every module, root module first: the root's
    # choice wins, but a single fixed @rules_quarkus repo means only one
    # Quarkus version per workspace — conflicting requests must fail loudly
    # instead of silently using whichever tag happens to come first.
    root_tags = []
    dep_tags = []
    for mod in mctx.modules:
        for tag in mod.tags.toolchain:
            if mod.is_root:
                root_tags.append(tag)
            else:
                dep_tags.append(tag)
    ordered_tags = root_tags + dep_tags
    if not ordered_tags:
        fail("quarkus.toolchain() must be called in MODULE.bazel")

    tc = ordered_tags[0]
    for tag in ordered_tags[1:]:
        if tag.quarkus_version != tc.quarkus_version:
            fail(("Conflicting quarkus.toolchain() versions requested: '{}' and '{}'. " +
                  "A workspace supports a single Quarkus version; align the " +
                  "quarkus_version attributes (the root module's choice wins ties).").format(
                tc.quarkus_version,
                tag.quarkus_version,
            ))

    version = tc.quarkus_version
    minor = _validate_version(version)

    repo_attrs = {
        "name": "rules_quarkus",
        "quarkus_version": version,
        "deployment_artifacts": _discover_deployment_artifacts(mctx, tc, version),
    }
    repo_attrs.update(_quarkifier_repo_attrs(tc, minor))

    fallback_java = _REMOTE_JDK_JAVA.get(_host_platform_key(mctx.os))
    if fallback_java:
        repo_attrs["fallback_java"] = fallback_java

    _rules_quarkus_repo(**repo_attrs)

_toolchain_tag = tag_class(
    attrs = {
        "extension_group_prefixes": attr.string_list(
            default = _DEFAULT_EXTENSION_GROUP_PREFIXES,
            doc = "Maven groupId prefixes that identify Quarkus extensions.",
        ),
        "lock_file": attr.label(
            doc = "Path to maven_install.json for auto-discovering Quarkus extensions.",
        ),
        "quarkifier_sha256": attr.string(
            doc = """\
SHA-256 checksum of the quarkifier release jar, overriding the checksum
bundled in the release archive. Normally not needed: released versions carry
their own checksums. Set it when consuming rules_quarkus via git_override or
archive_override, where the bundled checksum map is empty — the build prints
the hash to pin when verification is disabled.
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
        "quarkus_version": attr.string(
            mandatory = True,
            doc = "The Quarkus version to use. Must be one of: " +
                  ", ".join([SUPPORTED_VERSIONS[m] for m in sorted(SUPPORTED_VERSIONS)]) + ".",
        ),
    },
)

quarkus = module_extension(
    implementation = _quarkus_impl,
    tag_classes = {"toolchain": _toolchain_tag},
    doc = "Configures Quarkus toolchain and auto-resolves deployment artifacts.",
)
