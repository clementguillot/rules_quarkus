"""Bzlmod module extension for configuring the Quarkus toolchain.

Scans the exact runtime jars pinned by maven_install.json for Quarkus extension
descriptors and resolves their declared deployment artifacts automatically.
The quarkifier deploy jar is downloaded from GitHub releases or overridden
with a local build.

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

def _maven_target_name(coordinate_key):
    """Matches rules_jvm_external's versionless target-name escaping."""
    parts = coordinate_key.split(":")

    # rules_jvm_external omits the default `jar` packaging segment when it
    # constructs a classifier target (G:A:jar:C -> G_A_C).
    target_key = ":".join([parts[0], parts[1], parts[3]]) if len(parts) == 4 and parts[2] == "jar" else coordinate_key
    return target_key.replace(".", "_").replace("-", "_").replace(":", "_").replace("$", "_")

def _coordinate_fields(coordinate_key, version):
    """Converts a rules_jvm_external versionless coordinate to GACTV fields."""
    parts = coordinate_key.split(":")
    if len(parts) < 2 or len(parts) > 4:
        fail("Unsupported artifact coordinate '{}' in maven lock file".format(coordinate_key))
    return {
        "artifactId": parts[1],
        "classifier": parts[3] if len(parts) == 4 else "",
        "groupId": parts[0],
        "type": parts[2] if len(parts) >= 3 else "jar",
        "version": version,
    }

def _resolved_coordinate_keys(artifact_key, artifact, dependencies):
    """Returns every concrete lock key represented by an artifact entry.

    rules_jvm_external v3 keys `artifacts` by G:A even when the same artifact
    resolves more than one file (for example default and `runtime` classifier
    JARs). The dependency graph retains the concrete identities, so it is
    authoritative whenever it contains matching entries.
    """
    if len(artifact_key.split(":")) > 2:
        return [artifact_key]

    prefix = artifact_key + ":"
    graph_keys = [
        key
        for key in dependencies
        if key == artifact_key or key.startswith(prefix)
    ]
    if graph_keys:
        return sorted(graph_keys)

    shasums = artifact.get("shasums", {})
    if type(shasums) != "dict" or len(shasums) != 1:
        fail("Artifact '{}' has ambiguous resolved files but no concrete v3 dependency keys".format(artifact_key))
    file_kind = shasums.keys()[0]
    return [artifact_key if file_kind == "jar" else artifact_key + ":jar:" + file_kind]

def _runtime_catalog(lock_data, resolver_report = None):
    """Normalizes lock identities plus an optional Coursier-resolved Maven graph."""
    lock_version = str(lock_data.get("version", ""))
    if lock_version != "3":
        fail("Unsupported rules_jvm_external lock version '{}'; application-model fidelity requires v3".format(lock_version))

    artifacts = lock_data.get("artifacts", {})
    dependencies = lock_data.get("dependencies", {})
    if type(artifacts) != "dict" or type(dependencies) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'artifacts' and 'dependencies' must be objects")

    nodes_by_key = {}
    base_to_resolved = {}
    for artifact_key in sorted(artifacts):
        artifact = artifacts[artifact_key]
        if type(artifact) != "dict" or not artifact.get("version"):
            fail("Invalid artifact entry '{}' in maven lock file".format(artifact_key))
        coordinate_keys = _resolved_coordinate_keys(artifact_key, artifact, dependencies)
        base_to_resolved[artifact_key] = artifact_key if artifact_key in coordinate_keys else coordinate_keys[0]
        for coordinate_key in coordinate_keys:
            base_to_resolved[coordinate_key] = coordinate_key
            nodes_by_key[coordinate_key] = {
                "coordinateKey": coordinate_key,
                "coordinates": _coordinate_fields(coordinate_key, artifact["version"]),
                "dependencies": [],
                "exclusions": [],
                "optional": False,
                "targetName": _maven_target_name(coordinate_key),
            }

    nodes = [nodes_by_key[key] for key in sorted(nodes_by_key)]

    for node in nodes:
        direct_dependencies = dependencies.get(node["coordinateKey"], [])
        if type(direct_dependencies) != "list":
            fail("Invalid dependency list for '{}' in maven lock file".format(node["coordinateKey"]))
        node["dependencies"] = sorted([base_to_resolved.get(dep, dep) for dep in direct_dependencies])

    if resolver_report != None:
        raw_nodes = resolver_report.get("dependencies", [])
        if type(raw_nodes) != "list":
            fail("Invalid Coursier runtime report: 'dependencies' must be an array")
        report_to_key = {}
        nodes_by_key = {}
        for node in nodes:
            # The lock graph is intentionally not the fallback once a Maven
            # resolver report is present: nodes absent from the report were
            # pruned by Maven optionality/exclusions and must stay edge-less.
            node["dependencies"] = []
            node["exclusions"] = []
            node["optional"] = False
            fields = node["coordinates"]
            canonical = "{}:{}:{}:{}:{}".format(
                fields["groupId"],
                fields["artifactId"],
                fields["classifier"],
                fields["type"],
                fields["version"],
            )
            report_coordinate = _coursier_artifact(canonical).report
            if report_coordinate in report_to_key:
                fail("Runtime lock coordinates collapse to duplicate Coursier identity '{}'".format(report_coordinate))
            report_to_key[report_coordinate] = node["coordinateKey"]
            nodes_by_key[node["coordinateKey"]] = node

        resolved_edges = {}
        for raw_node in raw_nodes:
            if type(raw_node) != "dict":
                fail("Invalid Coursier runtime report: dependency entries must be objects")
            coordinate = raw_node.get("coord", "")
            coordinate_key = report_to_key.get(coordinate)
            if not coordinate_key:
                # The pinned rules_jvm_external graph is authoritative for explicit
                # exclusions. Coursier resolves raw POM semantics and may therefore
                # report artifacts deliberately excluded from the Bazel lock.
                continue
            if coordinate_key not in resolved_edges:
                resolved_edges[coordinate_key] = {}
            node = nodes_by_key[coordinate_key]
            exclusions = raw_node.get("exclusions", [])
            if type(exclusions) != "list":
                fail("Invalid Coursier runtime report: 'exclusions' must be an array")
            node["exclusions"] = sorted(exclusions)
            node["optional"] = raw_node.get("optional", False)
            if type(node["optional"]) != "bool":
                fail("Invalid Coursier runtime report: 'optional' must be a boolean")
            for dependency in raw_node.get("directDependencies", []):
                dependency_key = report_to_key.get(dependency)
                if dependency_key:
                    resolved_edges[coordinate_key][dependency_key] = True
        for coordinate_key in resolved_edges:
            nodes_by_key[coordinate_key]["dependencies"] = sorted(resolved_edges[coordinate_key])

    conflicts = (resolver_report or lock_data).get("conflict_resolution", {})
    if type(conflicts) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'conflict_resolution' must be an object")
    ordered_conflicts = {key: conflicts[key] for key in sorted(conflicts)}
    direct_artifacts = [
        base_to_resolved[key]
        for key in sorted(lock_data.get("__INPUT_ARTIFACTS_HASH", {}))
        # The input signature also contains repositories and BOMs. Neither is
        # a resolved runtime artifact; resolved artifacts always have an entry
        # in the v3 lock's `artifacts` object.
        if key in base_to_resolved
    ]
    return {
        "conflictResolution": ordered_conflicts,
        "directArtifacts": direct_artifacts,
        "nodes": nodes,
        "schemaVersion": "quarkus-bazel-runtime-catalog-v1",
    }

def _write_runtime_catalog(rctx, java, lock_data = None, lock_catalog = None):
    if not rctx.attr.lock_file:
        catalog = {
            "conflictResolution": {},
            "directArtifacts": [],
            "nodes": [],
            "schemaVersion": "quarkus-bazel-runtime-catalog-v1",
        }
    else:
        if lock_data == None:
            lock_data = json.decode(rctx.read(rctx.attr.lock_file))
        if lock_catalog == None:
            lock_catalog = _runtime_catalog(lock_data)
        nodes_by_key = {node["coordinateKey"]: node for node in lock_catalog["nodes"]}
        direct_keys = {key: True for key in lock_catalog["directArtifacts"]}
        redundant_direct_keys = {}
        for root_key in lock_catalog["directArtifacts"]:
            reachable = {key: True for key in nodes_by_key[root_key]["dependencies"]}

            # Starlark deliberately has no unbounded loops. At most N passes
            # are needed to close a graph with N nodes, including cycles.
            for _ in lock_catalog["nodes"]:
                changed = False
                for dependency_key in sorted(reachable.keys()):
                    dependency_node = nodes_by_key.get(dependency_key)
                    if dependency_node:
                        for transitive_key in dependency_node["dependencies"]:
                            if transitive_key not in reachable:
                                reachable[transitive_key] = True
                                changed = True
                if not changed:
                    break
            for dependency_key in reachable:
                if dependency_key in direct_keys and dependency_key != root_key:
                    redundant_direct_keys[dependency_key] = True

        resolution_root_keys = [
            key
            for key in lock_catalog["directArtifacts"]
            if key not in redundant_direct_keys
        ]
        roots = []
        forced_versions = _forced_versions_from_catalog(lock_catalog)
        for coordinate_key in resolution_root_keys:
            node = nodes_by_key[coordinate_key]
            fields = node["coordinates"]
            roots.append(_coursier_artifact("{}:{}:{}:{}:{}".format(
                fields["groupId"],
                fields["artifactId"],
                fields["classifier"],
                fields["type"],
                fields["version"],
            )).fetch)
        if roots:
            rctx.report_progress("Resolving Maven-faithful runtime dependency graph")
            result = _coursier_fetch(
                rctx,
                java,
                roots,
                "model/runtime-resolution.json",
                forced_versions = forced_versions,
            )
            if result.return_code != 0:
                fail("Failed to resolve runtime dependency graph via Coursier:\n" + result.stderr)
            catalog = _runtime_catalog(lock_data, json.decode(rctx.read("model/runtime-resolution.json")))
        else:
            catalog = lock_catalog
    rctx.file("model/runtime-catalog-v1.json", json.encode(catalog) + "\n")

def _platform_bom(coordinate):
    """Parses the public G:A:V platform BOM notation into transport coordinates."""
    parts = coordinate.split(":")
    if len(parts) != 3 or not parts[0] or not parts[1] or not parts[2]:
        fail("Invalid platform BOM '{}': expected groupId:artifactId:version".format(coordinate))
    return {
        "artifactId": parts[1],
        "classifier": "",
        "groupId": parts[0],
        "type": "pom",
        "version": parts[2],
    }

def _write_platform_catalog(rctx):
    """Downloads exact Quarkus platform properties and writes their model catalog."""
    imports = []
    property_files = []
    repository = rctx.attr.repository_url.removesuffix("/")
    for coordinate in rctx.attr.platform_boms:
        bom = _platform_bom(coordinate)
        properties_artifact = bom["artifactId"] + "-quarkus-platform-properties"
        relative_path = "{group}/{artifact}/{version}/{artifact}-{version}.properties".format(
            artifact = properties_artifact,
            group = bom["groupId"].replace(".", "/"),
            version = bom["version"],
        )
        repo_path = "model/platform-properties/" + relative_path
        rctx.report_progress("Resolving Quarkus platform properties for " + coordinate)
        rctx.download(
            url = repository + "/" + relative_path,
            output = repo_path,
        )
        imports.append(bom)
        property_files.append(repo_path)

    catalog = {
        "imports": imports,
        "properties": {key: rctx.attr.platform_properties[key] for key in sorted(rctx.attr.platform_properties)},
        "propertyFiles": property_files,
        "schemaVersion": "quarkus-bazel-platform-catalog-v1",
    }
    rctx.file("model/platform-catalog-v1.json", json.encode(catalog) + "\n")

# Pure helpers exported only for Starlark unit tests. Production consumers use
# the generated catalog file targets, never these implementation functions.
runtime_catalog_for_test = _runtime_catalog
maven_target_name_for_test = _maven_target_name

def _coursier_artifact(coordinate):
    """Converts Quarkus GAV/GATV/GACTV notation to Coursier fetch/report forms."""
    parts = coordinate.split(":")
    if len(parts) == 3:
        if not parts[0] or not parts[1] or not parts[2]:
            fail("Invalid Maven coordinate '{}': required component is blank".format(coordinate))
        return struct(fetch = coordinate, report = coordinate)
    if len(parts) == 4:
        group_id, artifact_id, artifact_type, version = parts
        if not group_id or not artifact_id or not artifact_type or not version:
            fail("Invalid Maven coordinate '{}': required component is blank".format(coordinate))
        return struct(
            fetch = "{}:{}:{},type={}".format(group_id, artifact_id, version, artifact_type),
            report = coordinate,
        )
    if len(parts) == 5:
        group_id, artifact_id, classifier, artifact_type, version = parts
        if not group_id or not artifact_id or not artifact_type or not version:
            fail("Invalid Maven coordinate '{}': required component is blank".format(coordinate))
        attributes = []
        if classifier:
            attributes.append("classifier=" + classifier)
        if artifact_type != "jar":
            attributes.append("type=" + artifact_type)
        fetch = "{}:{}:{}".format(group_id, artifact_id, version)
        if attributes:
            fetch += "," + ",".join(attributes)
        if classifier:
            report = "{}:{}:{}:{}:{}".format(group_id, artifact_id, artifact_type, classifier, version)
        elif artifact_type == "jar":
            report = "{}:{}:{}".format(group_id, artifact_id, version)
        else:
            report = "{}:{}:{}:{}".format(group_id, artifact_id, artifact_type, version)
        return struct(fetch = fetch, report = report)
    fail("Invalid Maven coordinate '{}': expected G:A:V, G:A:T:V, or G:A:C:T:V".format(coordinate))

def _runtime_discovery_artifacts(lock_data):
    """Returns exact intransitive Coursier coordinates for every locked runtime jar."""
    if str(lock_data.get("version", "")) != "3":
        fail("Unsupported rules_jvm_external lock version '{}'; extension discovery requires v3".format(lock_data.get("version", "")))
    artifacts = lock_data.get("artifacts", {})
    dependencies = lock_data.get("dependencies", {})
    if type(artifacts) != "dict" or type(dependencies) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'artifacts' and 'dependencies' must be objects")

    result = []
    for artifact_key in sorted(artifacts):
        artifact = artifacts[artifact_key]
        if type(artifact) != "dict" or not artifact.get("version"):
            fail("Invalid artifact entry '{}' in maven lock file".format(artifact_key))
        for coordinate_key in _resolved_coordinate_keys(artifact_key, artifact, dependencies):
            fields = _coordinate_fields(coordinate_key, artifact["version"])
            if fields["type"] != "jar" or fields["classifier"] in ["sources", "javadoc"]:
                continue
            coordinate = "{}:{}:{}".format(fields["groupId"], fields["artifactId"], fields["version"])
            if fields["classifier"]:
                coordinate += ",classifier=" + fields["classifier"]
            result.append(coordinate)
    return result

coursier_artifact_for_test = _coursier_artifact
runtime_discovery_artifacts_for_test = _runtime_discovery_artifacts

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

def _coursier_fetch(rctx, java, artifacts, report_path, forced_versions = []):
    """Runs a batched Coursier fetch.

    Timeout scales with artifact count: cold-cache batch fetches for large
    projects can legitimately exceed a flat 300 s.
    """
    args = [
        java,
        "-jar",
        "deployment/coursier.jar",
        "fetch",
        "--json-output-file",
        report_path,
        "--repository",
        rctx.attr.repository_url,
    ]
    for forced_version in forced_versions:
        args.extend(["--force-version", forced_version])
    return rctx.execute(
        args + artifacts,
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

def _coursier_report_coordinate(coordinate):
    """Converts Coursier report order (G:A:T:C:V) to Quarkus G:A:C:T:V."""
    parts = coordinate.split(":")
    if len(parts) <= 4:
        return coordinate
    if len(parts) == 5:
        return "{}:{}:{}:{}:{}".format(parts[0], parts[1], parts[3], parts[2], parts[4])
    fail("Invalid Coursier report coordinate '{}'".format(coordinate))

def _fetch_runtime_jars_for_discovery(rctx, java):
    """Fetches every locked runtime artifact exactly and intransitively.

    The lock graph, rather than Maven POM resolution, selects the runtime jars.
    Batches bound argv size for large workspaces. Coursier's content cache keeps
    this scan cheap and avoids duplicating jars in the generated repository.
    """
    artifacts = rctx.attr.runtime_discovery_artifacts
    if not artifacts:
        return struct(artifacts = [], jars = [])
    rctx.report_progress("Resolving locked runtime jars for Quarkus extension discovery")
    seen = {}
    jars = []
    resolved_artifacts = []
    batch_size = 100
    for offset in range(0, len(artifacts), batch_size):
        report_path = "model/runtime-discovery-resolution-{}.json".format(offset)
        rctx.file(report_path, "")
        args = [
            java,
            "-jar",
            "deployment/coursier.jar",
            "fetch",
            "--json-output-file",
            report_path,
            "--repository",
            rctx.attr.repository_url,
        ]
        for artifact in artifacts[offset:offset + batch_size]:
            args.extend(["--intransitive", artifact])
        result = rctx.execute(args, timeout = max(300, min(batch_size, len(artifacts) - offset) * 30))
        if result.return_code != 0:
            fail("Failed to fetch locked runtime artifacts for extension descriptor discovery:\n" + result.stderr)
        jars.extend(_jar_paths_from_fetch_output(result.stdout, seen))
        report = json.decode(rctx.read(report_path))
        for dependency in report.get("dependencies", []):
            coordinate = dependency.get("coord", "")
            file = dependency.get("file", "")
            if not coordinate or not file:
                fail("Invalid Coursier runtime discovery report entry: coord and file are required")
            resolved_artifacts.append({
                "coordinate": _coursier_report_coordinate(coordinate),
                "file": file,
            })
    return struct(artifacts = resolved_artifacts, jars = jars)

def _discover_deployment_artifacts(rctx, java, runtime_artifacts):
    """Reads exact deployment and conditional metadata from runtime jars."""
    artifacts_file = "model/runtime-discovery-artifacts.tsv"
    output_file = "model/discovered-deployment-artifacts.txt"
    descriptors_file = "model/extension-descriptors-v1.json"
    artifact_lines = [artifact["coordinate"] + "\t" + artifact["file"] for artifact in runtime_artifacts]
    rctx.file(artifacts_file, "\n".join(artifact_lines) + ("\n" if artifact_lines else ""))
    result = rctx.execute(
        [
            java,
            "-jar",
            "quarkifier/tool.jar",
            "discover-extensions",
            "--artifacts-file",
            artifacts_file,
            "--output",
            output_file,
            "--descriptor-output",
            descriptors_file,
        ],
        timeout = max(300, len(runtime_artifacts) * 5),
    )
    if result.return_code != 0:
        fail("Failed to discover Quarkus extension descriptors from the locked runtime graph:\n" + result.stderr)

    descriptor_coordinates = []
    for line in rctx.read(output_file).split("\n"):
        coordinate = line.strip()
        if coordinate:
            descriptor_coordinates.append(coordinate)

    # Core deployment is required even for a minimal application. It normally
    # comes from quarkus-core's own descriptor; keeping it as an explicit root
    # also supports lock-less toolchains without falling back to name guessing.
    core = "io.quarkus:quarkus-core-deployment:" + rctx.attr.quarkus_version
    if core not in descriptor_coordinates:
        descriptor_coordinates.append(core)

    fetch_roots = []
    report_roots = []
    seen = {}
    for coordinate in descriptor_coordinates:
        artifact = _coursier_artifact(coordinate)
        if artifact.report in seen:
            continue
        seen[artifact.report] = True
        fetch_roots.append(artifact.fetch)
        report_roots.append(artifact.report)
    return struct(
        descriptors = json.decode(rctx.read(descriptors_file)),
        fetch_roots = fetch_roots,
        report_roots = report_roots,
    )

def _descriptor_map(catalog):
    if catalog.get("schemaVersion") != "quarkus-extension-descriptors-v1":
        fail("Unsupported extension descriptor catalog schema '{}'".format(catalog.get("schemaVersion")))
    extensions = catalog.get("extensions", [])
    if type(extensions) != "list":
        fail("Invalid extension descriptor catalog: extensions must be an array")
    result = {}
    for extension in extensions:
        runtime = extension.get("runtimeArtifact", "")
        deployment = extension.get("deploymentArtifact", "")
        if not runtime or not deployment:
            fail("Invalid extension descriptor catalog: runtimeArtifact and deploymentArtifact are required")
        previous = result.get(runtime)
        if previous != None and previous != extension:
            fail("Runtime artifact '{}' has conflicting extension descriptors".format(runtime))
        result[runtime] = extension
    return result

def _conditional_roots(descriptors):
    roots = {}
    for runtime in sorted(descriptors):
        descriptor = descriptors[runtime]
        for coordinate in descriptor.get("conditionalDependencies", []) + descriptor.get("conditionalDevDependencies", []):
            roots[_coursier_artifact(coordinate).report] = coordinate
    return roots

def _forced_versions_from_catalog(catalog):
    """Extracts sorted G:A:V forced-version pins from a runtime catalog.

    Args:
        catalog: A runtime catalog dict with a "nodes" list.

    Returns:
        A sorted list of "groupId:artifactId:version" strings, one per module.
    """
    forced = {}
    for node in catalog["nodes"]:
        fields = node["coordinates"]
        module = fields["groupId"] + ":" + fields["artifactId"]
        value = module + ":" + fields["version"]
        previous = forced.get(module)
        if previous != None and previous != value:
            fail("Runtime lock selects multiple versions for module '{}'".format(module))
        forced[module] = value
    return [forced[module] for module in sorted(forced)]

def _scan_resolved_extensions(rctx, java, report, pass_index):
    artifacts_file = "model/conditional-runtime-artifacts-{}.tsv".format(pass_index)
    deployments_file = "model/conditional-deployments-{}.txt".format(pass_index)
    descriptors_file = "model/conditional-descriptors-{}.json".format(pass_index)
    lines = []
    for dependency in report.get("dependencies", []):
        coordinate = dependency.get("coord", "")
        file = dependency.get("file", "")
        if not coordinate or not file:
            fail("Invalid Coursier conditional report entry: coord and file are required")
        lines.append(_coursier_report_coordinate(coordinate) + "\t" + file)
    rctx.file(artifacts_file, "\n".join(lines) + ("\n" if lines else ""))
    result = rctx.execute(
        [
            java,
            "-jar",
            "quarkifier/tool.jar",
            "discover-extensions",
            "--artifacts-file",
            artifacts_file,
            "--output",
            deployments_file,
            "--descriptor-output",
            descriptors_file,
        ],
        timeout = max(300, len(lines) * 5),
    )
    if result.return_code != 0:
        fail("Failed to discover nested conditional extension descriptors:\n" + result.stderr)
    return json.decode(rctx.read(descriptors_file))

def _resolve_conditional_runtime(rctx, java, initial_catalog, forced_versions):
    """Resolves the complete normal+dev candidate universe to a stable descriptor graph."""
    descriptors = _descriptor_map(initial_catalog)
    resolved_roots = {}
    final_report = None
    final_result = None
    converged = False
    max_passes = 32
    for pass_index in range(max_passes):
        roots = _conditional_roots(descriptors)
        if not roots:
            converged = True
            break
        report_path = "conditional/conditional-resolution-{}.json".format(pass_index)
        rctx.file(report_path, "")
        result = _coursier_fetch(
            rctx,
            java,
            [_coursier_artifact(roots[key]).fetch for key in sorted(roots)],
            report_path,
            forced_versions = forced_versions,
        )
        if result.return_code != 0:
            fail("Failed to resolve descriptor-declared conditional dependencies:\n" + result.stderr)
        report = json.decode(rctx.read(report_path))
        discovered = _descriptor_map(_scan_resolved_extensions(rctx, java, report, pass_index))
        for runtime in discovered:
            previous = descriptors.get(runtime)
            if previous != None and previous != discovered[runtime]:
                fail("Runtime artifact '{}' has conflicting extension descriptors".format(runtime))
            descriptors[runtime] = discovered[runtime]
        final_report = report
        final_result = result
        next_roots = _conditional_roots(descriptors)
        if sorted(next_roots.keys()) == sorted(roots.keys()):
            resolved_roots = next_roots
            converged = True
            break
        resolved_roots = next_roots
    if not converged:
        fail("Conditional dependency descriptor discovery did not converge after {} passes".format(max_passes))
    return struct(
        descriptors = [descriptors[key] for key in sorted(descriptors)],
        report = final_report,
        resolution = final_result,
        roots = resolved_roots,
    )

def _dev_mode_artifacts(quarkus_version):
    """Returns the upstream-equivalent roots for the dev process system classpath."""
    return [
        _coursier_artifact("io.quarkus:quarkus-bootstrap-gradle-resolver:" + quarkus_version).fetch,
        _coursier_artifact("io.quarkus:quarkus-bootstrap-maven-resolver:" + quarkus_version).fetch,
        _coursier_artifact("io.quarkus:quarkus-core-deployment:" + quarkus_version).fetch,
    ]

dev_mode_artifacts_for_test = _dev_mode_artifacts

def _resolve_dev_mode_jars(rctx, java, dev_mode_artifacts):
    """Resolves the complete infrastructure classpath for the dev process.

    This mirrors Quarkus Gradle's QUARKUS_BOOTSTRAP_RESOLVER_CONFIGURATION: both
    bootstrap resolvers plus quarkus-core-deployment and their transitive closures.

    Fails hard on error: continuing would materialize an empty deployment:core
    target and silently break dev mode at runtime.
    """
    rctx.report_progress("Resolving dev mode infrastructure dependencies")
    result = _coursier_fetch(rctx, java, dev_mode_artifacts, "deployment/dev-mode-resolution.json")
    if result.return_code != 0:
        fail("Failed to resolve dev mode infrastructure dependencies ({}) via Coursier:\n{}".format(
            ", ".join(dev_mode_artifacts),
            result.stderr,
        ))
    return _jar_paths_from_fetch_output(result.stdout, {})

def _resolve_deployment_jars(rctx, java, deployment_artifacts, report_roots, core_jar_paths):
    """Resolves all deployment artifacts in a single batched Coursier call.

    Batching avoids spawning a separate JVM per artifact (6-10x faster).
    Every root came from an extension descriptor, so a missing root is a hard
    model error: dropping it would silently skip the extension's build steps.

    Returns:
        The list of all resolved jar paths, starting with core_jar_paths.
    """
    rctx.report_progress("Resolving deployment artifacts")
    report_path = "deployment/deployment-resolution.json"
    result = _coursier_fetch(rctx, java, deployment_artifacts, report_path)
    if result.return_code != 0:
        fail(("Failed to resolve descriptor-declared Quarkus deployment artifacts via Coursier. " +
              "No deployment root can be skipped safely:\n{}").format(result.stderr))

    seen_jars = {p: True for p in core_jar_paths}
    return struct(
        dropped = [],
        jars = list(core_jar_paths) + _jar_paths_from_fetch_output(result.stdout, seen_jars),
        report_path = report_path,
        roots = report_roots,
    )

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

def _write_jar_build(rctx, subdir, all_jars, core_jar_set = None):
    """Copies resolved jars into subdir/ and writes a BUILD file with java_import targets.

    Args:
        rctx: Repository context.
        subdir: Destination subdirectory (e.g. "deployment", "conditional").
        all_jars: Iterable of absolute jar paths to materialize.
        core_jar_set: Optional dict of jar paths that form the "core" subset.
            When provided an extra java_library(name = "core") is emitted.

    Returns:
        Dict mapping original jar path → repo-relative path.
    """
    imports = []
    all_targets = []
    core_targets = []
    copies = []
    repo_paths = {}
    seen = {}
    for jar_path in all_jars:
        relative_jar_path = _maven_relative_path(jar_path)
        target_name = _jar_target_name(relative_jar_path)
        jar_repo_path = "jars/" + relative_jar_path

        if target_name in seen:
            seen[target_name] += 1
            n = seen[target_name]

            # buildifier: disable=print
            print(("WARNING: rules_quarkus: {} jars collide on target name '{}' " +
                   "({}); keeping both, this one as '{}_dup{}'.").format(
                subdir,
                target_name,
                jar_path,
                target_name,
                n,
            ))
            target_name = "{}_dup{}".format(target_name, n)
            jar_repo_path = "jars/dup{}/{}".format(n, relative_jar_path)
        else:
            seen[target_name] = 1

        copies.append((jar_path, subdir + "/" + jar_repo_path))
        repo_paths[jar_path] = subdir + "/" + jar_repo_path

        imports.append(
            'java_import(name = "{n}", jars = ["{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = jar_repo_path),
        )
        all_targets.append('":{}"'.format(target_name))
        if core_jar_set and jar_path in core_jar_set:
            core_targets.append('":{}"'.format(target_name))

    _copy_jars_into_repo(rctx, copies)

    libraries = ['java_library(name = "all", exports = [{}])'.format(", ".join(all_targets))]
    if core_jar_set != None:
        libraries.insert(0, 'java_library(name = "core", exports = [{}])'.format(", ".join(core_targets)))

    rctx.file(subdir + "/BUILD.bazel", content = """\
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility = ["//visibility:public"])
{imports}
{libraries}
""".format(
        imports = "\n".join(imports),
        libraries = "\n".join(libraries),
    ))
    return repo_paths

def _write_deployment_build(rctx, all_jars, core_jar_set):
    """Copies resolved jars into deployment/ and writes its BUILD file."""
    return _write_jar_build(rctx, "deployment", all_jars, core_jar_set)

def _write_conditional_build(rctx, all_jars):
    """Materializes conditional candidates without placing them on the public runtime graph."""
    return _write_jar_build(rctx, "conditional", all_jars)

def _normalize_catalog(report, repo_paths, label, remap_coordinates = False):
    """Merges and deduplicates a Coursier dependency report into canonical nodes.

    Args:
        report: Parsed Coursier JSON report dict.
        repo_paths: Dict mapping absolute jar path → repo-relative path.
        label: Human-readable label for error messages (e.g. "deployment", "conditional").
        remap_coordinates: When True, applies _coursier_report_coordinate() to
            each coordinate and dependency (needed for conditional catalogs whose
            Coursier report uses G:A:T:C:V ordering).

    Returns:
        Tuple of (nodes list, conflicts dict).
    """
    raw_nodes = report.get("dependencies", [])
    if type(raw_nodes) != "list":
        fail("Invalid {} Coursier report: 'dependencies' must be an array".format(label))

    merged = {}
    for raw_node in raw_nodes:
        if type(raw_node) != "dict":
            fail("Invalid {} Coursier report: dependency entries must be objects".format(label))
        coordinate = raw_node.get("coord", "")
        file = raw_node.get("file", "")
        if not coordinate or not file:
            fail("Invalid {} Coursier report: 'coord' and 'file' are required".format(label))
        if remap_coordinates:
            coordinate = _coursier_report_coordinate(coordinate)
        repo_path = repo_paths.get(file)
        if not repo_path:
            fail("{} report path '{}' was not copied into the generated repository".format(label.capitalize(), file))

        node = merged.get(coordinate)
        if not node:
            node = {"dependencies": {}, "exclusions": {}, "repoPath": repo_path}
            merged[coordinate] = node
        elif node["repoPath"] != repo_path:
            fail("{} coordinate '{}' resolved to multiple files".format(label.capitalize(), coordinate))

        for dependency in raw_node.get("directDependencies", []):
            dep_key = _coursier_report_coordinate(dependency) if remap_coordinates else dependency
            node["dependencies"][dep_key] = True
        for exclusion in raw_node.get("exclusions", []):
            node["exclusions"][exclusion] = True

    nodes = []
    for coordinate in sorted(merged):
        node = merged[coordinate]
        nodes.append({
            "coordinate": coordinate,
            "dependencies": sorted(node["dependencies"]),
            "exclusions": sorted(node["exclusions"]),
            "repoPath": node["repoPath"],
        })

    conflicts = report.get("conflict_resolution", {})
    if type(conflicts) != "dict":
        fail("Invalid {} Coursier report: 'conflict_resolution' must be an object".format(label))
    sorted_conflicts = {key: conflicts[key] for key in sorted(conflicts)}
    return nodes, sorted_conflicts

def _conditional_catalog(resolution, repo_paths):
    report = resolution.report
    if report == None:
        return {
            "conflictResolution": {},
            "extensions": resolution.descriptors,
            "nodes": [],
            "resolver": "coursier",
            "resolverReportVersion": "",
            "roots": [],
            "schemaVersion": "quarkus-bazel-conditional-catalog-v1",
        }
    nodes, conflicts = _normalize_catalog(report, repo_paths, "conditional", remap_coordinates = True)
    return {
        "conflictResolution": conflicts,
        "extensions": resolution.descriptors,
        "nodes": nodes,
        "resolver": "coursier",
        "resolverReportVersion": report.get("version", ""),
        "roots": sorted(resolution.roots.values()),
        "schemaVersion": "quarkus-bazel-conditional-catalog-v1",
    }

def _write_conditional_catalog(rctx, resolution, repo_paths):
    rctx.file("model/conditional-catalog-v1.json", json.encode(_conditional_catalog(resolution, repo_paths)) + "\n")

conditional_catalog_for_test = _conditional_catalog
coursier_report_coordinate_for_test = _coursier_report_coordinate

def _deployment_catalog(report, roots, dropped_roots, repo_paths):
    """Normalizes a Coursier report and removes machine-global cache paths."""
    nodes, conflicts = _normalize_catalog(report, repo_paths, "deployment")
    return {
        "conflictResolution": conflicts,
        "droppedRoots": sorted(dropped_roots),
        "nodes": nodes,
        "resolver": "coursier",
        "resolverReportVersion": report.get("version", ""),
        "roots": sorted(roots),
        "schemaVersion": "quarkus-bazel-deployment-catalog-v1",
    }

def _write_deployment_catalog(rctx, resolution, repo_paths):
    """Writes the normalized Coursier deployment graph catalog."""
    catalog = _deployment_catalog(
        json.decode(rctx.read(resolution.report_path)),
        resolution.roots,
        resolution.dropped,
        repo_paths,
    )
    rctx.file("model/deployment-catalog-v1.json", json.encode(catalog) + "\n")

deployment_catalog_for_test = _deployment_catalog

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
_CONDITIONAL_DEPS = "@rules_quarkus//conditional:all"
_CONDITIONAL_CATALOG = "@rules_quarkus//model:conditional-catalog-v1.json"
_DEPLOYMENT_CATALOG = "@rules_quarkus//model:deployment-catalog-v1.json"
_RUNTIME_CATALOG = "@rules_quarkus//model:runtime-catalog-v1.json"
_PLATFORM_CATALOG = "@rules_quarkus//model:platform-catalog-v1.json"
_PLATFORM_PROPERTIES = "@rules_quarkus//model:platform_properties"
_TEST_INFRASTRUCTURE_DEPS = [
    "@maven//:org_hamcrest_hamcrest",
    "@maven//:org_junit_jupiter_junit_jupiter",
    "@maven//:org_junit_jupiter_junit_jupiter_api",
    "@maven//:org_junit_platform_junit_platform_console_standalone",
    "@maven//:org_junit_platform_junit_platform_launcher",
]

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
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
        **kwargs
    )

    # Attrs shared by the secondary (_dev / _native) targets.
    common = dict(
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deps = kwargs.get("deps", []),
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
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
        compile_deps = []
        seen_compile_deps = {{}}
        for dep in test_deps + _TEST_INFRASTRUCTURE_DEPS:
            if dep not in seen_compile_deps:
                seen_compile_deps[dep] = True
                compile_deps.append(dep)
        java_library(
            name = name + "_lib",
            srcs = srcs,
            deps = compile_deps,
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
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deps = test_deps,
        model_private_deps = _TEST_INFRASTRUCTURE_DEPS,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
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
        quarkifier_tool = _QUARKIFIER_TOOL,
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
    runtime_discovery = _fetch_runtime_jars_for_discovery(rctx, java)
    deployment_artifacts = _discover_deployment_artifacts(rctx, java, runtime_discovery.artifacts)

    if rctx.attr.lock_file:
        lock_data = json.decode(rctx.read(rctx.attr.lock_file))
        lock_catalog = _runtime_catalog(lock_data)
        forced_versions = _forced_versions_from_catalog(lock_catalog)
    else:
        lock_data = None
        lock_catalog = None
        forced_versions = []

    conditional_resolution = _resolve_conditional_runtime(rctx, java, deployment_artifacts.descriptors, forced_versions)
    deployment_fetch_roots = list(deployment_artifacts.fetch_roots)
    deployment_report_roots = list(deployment_artifacts.report_roots)
    seen_deployments = {coordinate: True for coordinate in deployment_report_roots}
    for descriptor in conditional_resolution.descriptors:
        deployment = _coursier_artifact(descriptor["deploymentArtifact"])
        if deployment.report not in seen_deployments:
            seen_deployments[deployment.report] = True
            deployment_fetch_roots.append(deployment.fetch)
            deployment_report_roots.append(deployment.report)
    core_jar_paths = _resolve_dev_mode_jars(rctx, java, _dev_mode_artifacts(rctx.attr.quarkus_version))
    deployment_resolution = _resolve_deployment_jars(
        rctx,
        java,
        deployment_fetch_roots,
        deployment_report_roots,
        core_jar_paths,
    )
    conditional_jars = [] if conditional_resolution.resolution == None else _jar_paths_from_fetch_output(conditional_resolution.resolution.stdout, {})
    conditional_repo_paths = _write_conditional_build(rctx, conditional_jars)
    repo_paths = _write_deployment_build(rctx, deployment_resolution.jars, {p: True for p in core_jar_paths})
    _write_runtime_catalog(rctx, java, lock_data = lock_data, lock_catalog = lock_catalog)
    _write_deployment_catalog(rctx, deployment_resolution, repo_paths)
    _write_conditional_catalog(rctx, conditional_resolution, conditional_repo_paths)
    _write_platform_catalog(rctx)

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

    rctx.file("model/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files([
    "conditional-catalog-v1.json",
    "deployment-catalog-v1.json",
    "platform-catalog-v1.json",
    "runtime-catalog-v1.json",
])
filegroup(name = "conditional_catalog", srcs = ["conditional-catalog-v1.json"])
filegroup(name = "deployment_catalog", srcs = ["deployment-catalog-v1.json"])
filegroup(name = "platform_catalog", srcs = ["platform-catalog-v1.json"])
filegroup(name = "platform_properties", srcs = glob(["platform-properties/**/*.properties"]))
filegroup(name = "runtime_catalog", srcs = ["runtime-catalog-v1.json"])
""")

    rctx.file("quarkus/defs.bzl", content = _DEFS_BZL_TEMPLATE.format(version = rctx.attr.quarkus_version))

_rules_quarkus_repo = repository_rule(
    implementation = _rules_quarkus_repo_impl,
    attrs = {
        "fallback_java": attr.label(
            allow_single_file = True,
            doc = "bin/java of the hermetic JDK used to run Coursier when the host has no usable JVM.",
        ),
        "lock_file": attr.label(doc = "rules_jvm_external v3 lock file used for the runtime catalog."),
        "platform_boms": attr.string_list(mandatory = True, doc = "Quarkus platform BOMs in G:A:V form."),
        "platform_properties": attr.string_dict(doc = "Explicit Quarkus platform property overrides."),
        "quarkifier_build_target": attr.string(doc = "Bazel target for the per-minor deploy jar (local build mode)."),
        "quarkifier_sha256": attr.string(doc = "SHA-256 checksum for the quarkifier jar download (release mode). Empty disables verification."),
        "quarkifier_source_dir": attr.label(doc = "Label in the rules_quarkus source dir (local build mode)."),
        "quarkifier_url": attr.string(doc = "URL to download the quarkifier jar from (release mode)."),
        "quarkus_version": attr.string(mandatory = True, doc = "Quarkus version."),
        "repository_url": attr.string(default = MAVEN_CENTRAL, doc = "Maven repository URL for Coursier."),
        "runtime_discovery_artifacts": attr.string_list(mandatory = True, doc = "Exact locked runtime coordinates to scan for Quarkus extension descriptors."),
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
        "platform_boms": tc.platform_boms or ["io.quarkus.platform:quarkus-bom:" + version],
        "platform_properties": tc.platform_properties,
        "runtime_discovery_artifacts": _runtime_discovery_artifacts(json.decode(mctx.read(tc.lock_file))) if tc.lock_file else [],
    }
    if tc.lock_file:
        repo_attrs["lock_file"] = tc.lock_file
    repo_attrs.update(_quarkifier_repo_attrs(tc, minor))

    fallback_java = _REMOTE_JDK_JAVA.get(_host_platform_key(mctx.os))
    if fallback_java:
        repo_attrs["fallback_java"] = fallback_java

    _rules_quarkus_repo(**repo_attrs)

_toolchain_tag = tag_class(
    attrs = {
        # Retained for source compatibility. Descriptor discovery is exact and
        # intentionally does not filter extensions by Maven group anymore.
        "extension_group_prefixes": attr.string_list(
            default = _DEFAULT_EXTENSION_GROUP_PREFIXES,
            doc = "Deprecated compatibility option; extension descriptors are discovered in every locked runtime jar.",
        ),
        "lock_file": attr.label(
            doc = "Path to a rules_jvm_external v3 maven_install.json for descriptor-driven extension discovery.",
        ),
        "platform_boms": attr.string_list(
            doc = "Quarkus platform BOM imports as groupId:artifactId:version. Defaults to the standard Quarkus platform BOM.",
        ),
        "platform_properties": attr.string_dict(
            doc = "Additional or overriding Quarkus platform properties. Custom platform release-info properties are supported.",
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
