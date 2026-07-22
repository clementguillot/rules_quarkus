"Hermetic action that assembles the explicit Bazel application model."

load("@rules_java//java/common:java_common.bzl", "java_common")
load("//quarkus/private:application_model_aspect.bzl", "collect_deployment_model_artifacts", "collect_deployment_model_fragments", "collect_local_deployments", "collect_local_runtime_aliases", "collect_model_artifacts", "collect_model_fragments", "write_model_roots_file")
load("//quarkus/private:versions.bzl", "RULES_VERSION")

_DEPLOYMENT_PATH_MARKER = "deployment/jars/"
_CONDITIONAL_PATH_MARKER = "conditional/jars/"
_PLATFORM_PROPERTY_PATH_MARKER = "model/platform-properties/"

def _write_path_lines(ctx, suffix, files):
    output = ctx.actions.declare_file(ctx.label.name + suffix)
    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.add_all(files)
    ctx.actions.write(output = output, content = args)
    return output

def _write_marker_paths(ctx, files, marker, suffix, label, require_marker = False):
    """Writes a sorted tab-separated path manifest for files matching a marker prefix.

    Args:
        ctx: Rule context.
        files: Iterable of File objects to scan.
        marker: Path substring that marks relevant files (e.g. "deployment/jars/").
        suffix: Output file name suffix (e.g. ".quarkus-deployment-paths-v1.txt").
        label: Human-readable label for error messages.
        require_marker: When True, files missing the marker cause a fail() instead of being skipped.

    Returns:
        The declared output file.
    """
    output = ctx.actions.declare_file(ctx.label.name + suffix)
    seen = {}
    for file in files:
        marker_index = file.path.find(marker)
        if marker_index < 0:
            if require_marker:
                fail("{} input '{}' is outside the generated model repository".format(label, file.path))
            continue
        repo_path = file.path[marker_index:]
        if repo_path in seen and seen[repo_path] != file.path:
            fail("{} path '{}' maps to both '{}' and '{}'".format(label, repo_path, seen[repo_path], file.path))
        seen[repo_path] = file.path
    lines = [repo_path + "\t" + seen[repo_path] for repo_path in sorted(seen)]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _write_deployment_paths(ctx, deployment_classpath):
    return _write_marker_paths(ctx, deployment_classpath.to_list(), _DEPLOYMENT_PATH_MARKER, ".quarkus-deployment-paths-v1.txt", "deployment catalog")

def _write_conditional_paths(ctx, conditional_classpath):
    return _write_marker_paths(ctx, conditional_classpath.to_list(), _CONDITIONAL_PATH_MARKER, ".quarkus-conditional-paths-v1.txt", "conditional catalog")

def _write_platform_property_paths(ctx):
    return _write_marker_paths(ctx, ctx.files.platform_properties, _PLATFORM_PROPERTY_PATH_MARKER, ".quarkus-platform-property-paths-v1.txt", "platform properties", require_marker = True)

def _write_local_deployments(ctx, local_deployments):
    output = ctx.actions.declare_file(ctx.label.name + ".quarkus-local-deployments-v1.txt")
    lines = [deployment["coordinate"] + "\t" + deployment["targetId"] for deployment in local_deployments]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _write_local_runtime_aliases(ctx, aliases):
    output = ctx.actions.declare_file(ctx.label.name + ".quarkus-local-runtime-aliases-v1.txt")
    lines = [alias["rawTargetId"] + "\t" + alias["targetId"] for alias in aliases]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _run_model_assembly(ctx, roots, fragments, model_artifacts, deployment_fragments, deployment_model_artifacts, local_deployments, local_runtime_aliases, runtime_classpath, conditional_classpath, deployment_classpath, mode, application_name = None):
    """Assembles a canonical model without changing the public rule API.

    Args:
        ctx: Rule context for the application target.
        roots: File containing the ordered direct dependency target ids.
        fragments: Depset of runtime target model fragments.
        model_artifacts: Depset of artifacts referenced by runtime fragments.
        deployment_fragments: Depset of local-extension deployment model fragments.
        deployment_model_artifacts: Depset of artifacts referenced by deployment fragments.
        local_deployments: Local extension deployment coordinate-to-target mappings.
        local_runtime_aliases: Raw-to-packaged local extension runtime target mappings.
        runtime_classpath: Depset of runtime classpath artifacts.
        conditional_classpath: Depset of conditional dependency candidates.
        deployment_classpath: Depset of deployment classpath artifacts.
        mode: Quarkus launch mode represented by the assembled model.
        application_name: Optional application artifact name; defaults to the target name.

    Returns:
        The declared canonical application-model JSON file.
    """
    all_fragments = depset(transitive = [fragments, deployment_fragments])
    all_model_artifacts = depset(transitive = [model_artifacts, deployment_model_artifacts])
    fragment_manifest = _write_path_lines(
        ctx,
        ".quarkus-target-fragments-v1.txt",
        all_fragments.to_list(),
    )
    deployment_paths = _write_deployment_paths(ctx, deployment_classpath)
    conditional_paths = _write_conditional_paths(ctx, conditional_classpath)
    platform_property_paths = _write_platform_property_paths(ctx)
    runtime_classpath_paths = _write_path_lines(ctx, ".quarkus-runtime-classpath-paths-v1.txt", runtime_classpath.to_list())
    deployment_classpath_paths = _write_path_lines(ctx, ".quarkus-deployment-classpath-paths-v1.txt", deployment_classpath.to_list())
    model_private_targets = _write_path_lines(
        ctx,
        ".quarkus-model-private-targets-v1.txt",
        [str(dep.label) for dep in ctx.attr.model_private_deps] if hasattr(ctx.attr, "model_private_deps") else [],
    )
    local_deployments_file = _write_local_deployments(ctx, local_deployments)
    local_runtime_aliases_file = _write_local_runtime_aliases(ctx, local_runtime_aliases)
    output = ctx.actions.declare_file(ctx.label.name + ".quarkus-bazel-model-v1.json")

    args = ctx.actions.args()
    args.add("-jar")
    args.add(ctx.file.quarkifier_tool)
    args.add("assemble-model")
    args.add("--roots", roots)
    args.add("--target-fragments-file", fragment_manifest)
    args.add("--runtime-catalog", ctx.file.runtime_catalog)
    args.add("--conditional-catalog", ctx.file.conditional_catalog)
    args.add("--deployment-catalog", ctx.file.deployment_catalog)
    args.add("--platform-catalog", ctx.file.platform_catalog)
    args.add("--deployment-paths-file", deployment_paths)
    args.add("--conditional-paths-file", conditional_paths)
    args.add("--platform-property-paths-file", platform_property_paths)
    args.add("--runtime-classpath-paths-file", runtime_classpath_paths)
    args.add("--deployment-classpath-paths-file", deployment_classpath_paths)
    args.add("--model-private-targets-file", model_private_targets)
    args.add("--local-deployments-file", local_deployments_file)
    args.add("--local-runtime-aliases-file", local_runtime_aliases_file)
    args.add("--quarkus-version", ctx.attr.quarkus_version)
    args.add("--mode", mode)
    args.add("--application-name", application_name or ctx.label.name)
    if hasattr(ctx.attr, "version") and ctx.attr.version:
        args.add("--application-version", ctx.attr.version)
    args.add("--producer-version", RULES_VERSION)
    args.add("--output", output)

    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [args],
        inputs = depset(
            direct = [
                ctx.file.quarkifier_tool,
                roots,
                fragment_manifest,
                ctx.file.runtime_catalog,
                ctx.file.conditional_catalog,
                ctx.file.deployment_catalog,
                ctx.file.platform_catalog,
                deployment_paths,
                conditional_paths,
                platform_property_paths,
                runtime_classpath_paths,
                deployment_classpath_paths,
                model_private_targets,
                local_deployments_file,
                local_runtime_aliases_file,
            ],
            transitive = [all_fragments, all_model_artifacts, runtime_classpath, conditional_classpath, deployment_classpath, depset(ctx.files.platform_properties), java_runtime.files],
        ),
        outputs = [output],
        mnemonic = "QuarkusModelAssembly",
        progress_message = "Assembling explicit Quarkus application model for %{label}",
    )
    return output

def assemble_application_model(ctx, deps, runtime_classpath, conditional_classpath, deployment_classpath, mode, application_name = None):
    """Collects aspect outputs and assembles one lifecycle-specific model.

    Args:
        ctx: Rule context for the application target.
        deps: Direct application dependencies carrying model aspect providers.
        runtime_classpath: Depset of runtime classpath artifacts.
        conditional_classpath: Depset of conditional dependency candidates.
        deployment_classpath: Depset of deployment classpath artifacts.
        mode: Quarkus launch mode represented by the assembled model.
        application_name: Optional application artifact name; defaults to the target name.

    Returns:
        The declared canonical application-model JSON file.
    """
    roots = write_model_roots_file(ctx, deps)
    fragments = collect_model_fragments(deps)
    return _run_model_assembly(
        ctx,
        roots,
        fragments,
        collect_model_artifacts(deps),
        collect_deployment_model_fragments(deps),
        collect_deployment_model_artifacts(deps),
        collect_local_deployments(deps),
        collect_local_runtime_aliases(deps),
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        mode,
        application_name,
    )
