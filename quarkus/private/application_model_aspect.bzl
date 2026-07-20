"Internal aspect that serializes Bazel Java target facts for model assembly."

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusExtensionInfo")

QuarkusBazelTargetGraphInfo = provider(
    doc = "Internal dependency-graph fragments produced by the Quarkus model aspect.",
    fields = {
        "artifacts": "Depset of runtime artifact Files described by this target graph.",
        "deployment_artifacts": "Depset of artifact Files in local deployment graphs.",
        "deployment_fragments": "Depset of fragments in local deployment graphs.",
        "fragments": "Depset of runtime JSON fragments for this target graph.",
        "local_deployments": "Local deployment root coordinate/id records.",
        "local_runtime_aliases": "Raw runtime target to packaged extension target aliases.",
        "root_edges": "Direct edge records of this graph root.",
        "root_ids": "Depset of graph node ids represented by this target.",
        "transitive_artifacts": "Runtime artifacts below the root, excluding its outputs.",
        "transitive_fragments": "Runtime fragments below the root, excluding its fragment.",
    },
)

def _as_list(value):
    if value == None:
        return []
    return value if type(value) == "list" else [value]

def _graphs_for_attr(ctx, attr_name):
    if not hasattr(ctx.rule.attr, attr_name):
        return []
    return [
        dep[QuarkusBazelTargetGraphInfo]
        for dep in _as_list(getattr(ctx.rule.attr, attr_name))
        if QuarkusBazelTargetGraphInfo in dep
    ]

def _file_record(file):
    return {
        "isSource": file.is_source,
        "owner": str(file.owner) if file.owner else None,
        "path": file.path,
        "shortPath": file.short_path,
    }

def _files_attr(ctx, attr_name):
    if not hasattr(ctx.rule.files, attr_name):
        return []
    return [_file_record(file) for file in getattr(ctx.rule.files, attr_name)]

def _edge_records(graphs, relation, scope):
    edges = []
    for graph in graphs:
        for target_id in graph.root_ids.to_list():
            edges.append({
                "exclusions": [],
                "optional": False,
                "relation": relation,
                "scope": scope,
                "targetId": target_id,
            })
    return edges

def _edge_sort_key(edge):
    return (edge["targetId"], edge["relation"])

def _coordinates(group_id, artifact_id, version):
    return {
        "artifactId": artifact_id,
        "classifier": "",
        "groupId": group_id,
        "type": "jar",
        "version": version,
    }

def _extract_workspace_outputs(ctx, target, output_jars, suffix = ""):
    if target.label.workspace_name:
        return []
    outputs = []
    for index, jar in enumerate(output_jars):
        index_suffix = "" if len(output_jars) == 1 else ".{}".format(index)
        output = ctx.actions.declare_directory(
            ctx.label.name + suffix + index_suffix + ".quarkus-classes",
        )
        args = ctx.actions.args()
        args.add("x")
        args.add(jar)
        args.add("-d")
        args.add(output.path)
        ctx.actions.run(
            executable = ctx.executable._zipper,
            arguments = [args],
            inputs = [jar],
            outputs = [output],
            mnemonic = "QuarkusWorkspaceClasses",
            progress_message = "Extracting workspace classes for {}".format(target.label),
        )
        outputs.append(output)
    return outputs

def _target_fragment(ctx, target, edges, coordinates = None, output_jars = None, output_directories = None, suffix = ""):
    target_id = str(target.label) if not suffix else "local-deployment:" + str(target.label)
    output = ctx.actions.declare_file(ctx.label.name + suffix + ".quarkus-target-v1.json")
    build_file = ctx.build_file_path if hasattr(ctx, "build_file_path") else (ctx.label.package + "/BUILD.bazel" if ctx.label.package else "BUILD.bazel")
    java_info = target[JavaInfo]
    content = json.encode({
        "bazelLabel": str(target.label),
        "buildFile": build_file,
        "coordinates": coordinates,
        "edges": sorted(edges, key = _edge_sort_key),
        "neverlink": getattr(ctx.rule.attr, "neverlink", False),
        "outputDirectories": [_file_record(file) for file in (output_directories or [])],
        "package": target.label.package,
        "resources": _files_attr(ctx, "resources"),
        "ruleKind": ctx.rule.kind,
        "runtimeOutputJars": [_file_record(file) for file in (output_jars if output_jars != None else java_info.runtime_output_jars)],
        "schemaVersion": "quarkus-bazel-target-v1",
        "sourceJars": [_file_record(file) for file in java_info.source_jars],
        "sources": _files_attr(ctx, "srcs"),
        "targetId": target_id,
        "targetName": target.label.name,
        "workspaceName": target.label.workspace_name,
    }) + "\n"
    ctx.actions.write(output = output, content = content)
    return output

def _standard_child_graphs(ctx):
    edges = []
    graphs = []
    for attr_name, scope in (("deps", "compile"), ("exports", "compile"), ("runtime_deps", "runtime")):
        attr_graphs = _graphs_for_attr(ctx, attr_name)
        graphs.extend(attr_graphs)
        edges.extend(_edge_records(attr_graphs, attr_name, scope))
    return graphs, edges

def _application_model_aspect_impl(target, ctx):
    child_graphs, edges = _standard_child_graphs(ctx)
    transitive_fragments = [graph.fragments for graph in child_graphs]
    transitive_artifacts = [graph.artifacts for graph in child_graphs]
    deployment_fragments = [graph.deployment_fragments for graph in child_graphs]
    deployment_artifacts = [graph.deployment_artifacts for graph in child_graphs]
    local_deployments = []
    local_runtime_aliases = []
    for graph in child_graphs:
        local_deployments.extend(graph.local_deployments)
        local_runtime_aliases.extend(graph.local_runtime_aliases)

    direct_fragments = []
    direct_artifacts = []
    root_ids = []
    root_edges = []
    if JavaInfo in target and QuarkusExtensionInfo in target:
        runtime_graphs = _graphs_for_attr(ctx, "runtime")
        deploy_graphs = _graphs_for_attr(ctx, "deployment")
        if len(runtime_graphs) != 1 or len(deploy_graphs) != 1:
            fail("local Quarkus extension '{}' requires one runtime and one deployment graph".format(target.label))
        runtime_graph = runtime_graphs[0]
        deploy_graph = deploy_graphs[0]
        extension = target[QuarkusExtensionInfo]
        root_edges = runtime_graph.root_edges
        runtime_outputs = target[JavaInfo].runtime_output_jars
        workspace_outputs = _extract_workspace_outputs(ctx, target, runtime_outputs)
        direct_fragments.append(_target_fragment(
            ctx,
            target,
            root_edges,
            coordinates = _coordinates(extension.group_id, extension.artifact_id, extension.version),
            output_directories = workspace_outputs,
        ))
        direct_artifacts.extend(runtime_outputs)
        direct_artifacts.extend(workspace_outputs)

        # Keep the raw runtime root fragment as workspace/source provenance.
        # The Java assembler joins it to the packaged extension via
        # local_runtime_aliases without materializing a phantom dependency.
        transitive_fragments.append(runtime_graph.fragments)
        transitive_artifacts.append(runtime_graph.artifacts)

        packaged_deployment_id = "local-deployment:" + str(target.label)
        deployment_fragments.append(depset(
            direct = [_target_fragment(
                ctx,
                target,
                deploy_graph.root_edges,
                coordinates = _coordinates(extension.group_id, extension.artifact_id + "-deployment", extension.version),
                output_jars = [extension.deployment_jar],
                suffix = ".deployment",
            )],
            # Retain the raw deployment root as source/workspace provenance. The
            # packaged node remains the graph identity and aliases this raw root.
            transitive = [deploy_graph.fragments, deploy_graph.deployment_fragments],
        ))
        deployment_artifacts.append(depset(
            direct = [extension.deployment_jar],
            transitive = [deploy_graph.transitive_artifacts, deploy_graph.deployment_artifacts],
        ))
        local_deployments.extend(deploy_graph.local_deployments)
        local_runtime_aliases.extend(deploy_graph.local_runtime_aliases)
        raw_runtime_ids = runtime_graph.root_ids.to_list()
        if len(raw_runtime_ids) != 1:
            fail("local Quarkus extension '{}' runtime must have one graph root".format(target.label))
        local_runtime_aliases.append({
            "rawTargetId": raw_runtime_ids[0],
            "targetId": str(target.label),
        })
        raw_deployment_ids = deploy_graph.root_ids.to_list()
        if len(raw_deployment_ids) != 1:
            fail("local Quarkus extension '{}' deployment must have one graph root".format(target.label))
        local_runtime_aliases.append({
            "rawTargetId": raw_deployment_ids[0],
            "targetId": packaged_deployment_id,
        })
        local_deployments.append({
            "coordinate": extension.group_id + ":" + extension.artifact_id + "-deployment::jar:" + extension.version,
            "targetId": packaged_deployment_id,
        })
        root_ids.append(str(target.label))
    elif JavaInfo in target:
        root_edges = edges
        runtime_outputs = target[JavaInfo].runtime_output_jars
        workspace_outputs = _extract_workspace_outputs(ctx, target, runtime_outputs)
        direct_fragments.append(_target_fragment(
            ctx,
            target,
            edges,
            output_directories = workspace_outputs,
        ))
        direct_artifacts.extend(runtime_outputs)
        direct_artifacts.extend(workspace_outputs)
        root_ids.append(str(target.label))
    else:
        for graph in child_graphs:
            root_ids.extend(graph.root_ids.to_list())

    return [
        QuarkusBazelTargetGraphInfo(
            artifacts = depset(direct = direct_artifacts, transitive = transitive_artifacts),
            deployment_artifacts = depset(transitive = deployment_artifacts),
            deployment_fragments = depset(transitive = deployment_fragments),
            fragments = depset(direct = direct_fragments, transitive = transitive_fragments),
            local_deployments = local_deployments,
            local_runtime_aliases = local_runtime_aliases,
            root_edges = root_edges,
            root_ids = depset(root_ids),
            transitive_artifacts = depset(transitive = transitive_artifacts),
            transitive_fragments = depset(transitive = transitive_fragments),
        ),
    ]

quarkus_application_model_aspect = aspect(
    implementation = _application_model_aspect_impl,
    attr_aspects = ["deps", "runtime_deps", "exports", "runtime", "deployment"],
    attrs = {
        "_zipper": attr.label(
            default = Label("@bazel_tools//tools/zip:zipper"),
            cfg = "exec",
            executable = True,
        ),
    },
)

def _collect_graph_depset(deps, field):
    return depset(transitive = [
        getattr(dep[QuarkusBazelTargetGraphInfo], field)
        for dep in deps
        if QuarkusBazelTargetGraphInfo in dep
    ])

def collect_model_fragments(deps):
    """Collects runtime application-model fragments from direct deps."""
    return _collect_graph_depset(deps, "fragments")

def collect_model_artifacts(deps):
    """Collects artifact inputs referenced by runtime model fragments."""
    return _collect_graph_depset(deps, "artifacts")

def collect_deployment_model_fragments(deps):
    """Collects local-extension deployment graph fragments."""
    return _collect_graph_depset(deps, "deployment_fragments")

def collect_deployment_model_artifacts(deps):
    """Collects artifacts referenced by local deployment fragments."""
    return _collect_graph_depset(deps, "deployment_artifacts")

def collect_local_deployments(deps):
    """Collects descriptor coordinate to local deployment root joins.

    Args:
        deps: Direct dependencies carrying application-model graph providers.

    Returns:
        A list of unique local deployment coordinate-to-target mappings.
    """
    result = []
    seen = {}
    for dep in deps:
        if QuarkusBazelTargetGraphInfo not in dep:
            continue
        for deployment in dep[QuarkusBazelTargetGraphInfo].local_deployments:
            coordinate = deployment["coordinate"]
            target_id = deployment["targetId"]
            if coordinate in seen and seen[coordinate] != target_id:
                fail("local deployment coordinate '{}' maps to multiple targets".format(coordinate))
            if coordinate not in seen:
                seen[coordinate] = target_id
                result.append(deployment)
    return result

def collect_local_runtime_aliases(deps):
    """Collects raw local runtime to packaged extension target aliases.

    Args:
        deps: Direct dependencies carrying application-model graph providers.

    Returns:
        A list of unique raw-to-packaged local runtime target mappings.
    """
    result = []
    seen = {}
    for dep in deps:
        if QuarkusBazelTargetGraphInfo not in dep:
            continue
        for alias in dep[QuarkusBazelTargetGraphInfo].local_runtime_aliases:
            raw_id = alias["rawTargetId"]
            target_id = alias["targetId"]
            if raw_id in seen and seen[raw_id] != target_id:
                fail("local runtime target '{}' maps to multiple packaged targets".format(raw_id))
            if raw_id not in seen:
                seen[raw_id] = target_id
                result.append(alias)
    return result

def write_model_roots_file(ctx, deps):
    """Writes direct dependency ids, preserving the public deps order.

    Args:
        ctx: Rule context used to declare and write the roots file.
        deps: Ordered direct dependencies carrying application-model graph providers.

    Returns:
        The declared application-model roots JSON file.
    """
    output = ctx.actions.declare_file(ctx.label.name + ".quarkus-roots-v1.json")
    root_ids = []
    seen = {}
    for dep in deps:
        if QuarkusBazelTargetGraphInfo not in dep:
            continue
        for root_id in dep[QuarkusBazelTargetGraphInfo].root_ids.to_list():
            if root_id not in seen:
                seen[root_id] = True
                root_ids.append(root_id)
    content = json.encode({
        "applicationLabel": str(ctx.label),
        "rootIds": root_ids,
        "schemaVersion": "quarkus-bazel-roots-v1",
    }) + "\n"
    ctx.actions.write(output = output, content = content)
    return output
