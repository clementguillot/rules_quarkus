"""Implementation rules for extension-provided Quarkus source generation."""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusCodeGenInfo")
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:build_properties.bzl", "write_build_properties")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")
load("//quarkus/private:codegen_lifecycle.bzl", "QuarkusCodeGenLifecycleInfo")
load("//quarkus/private:coverage_transition.bzl", "disable_coverage_transition", "single_transitioned_target")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

QuarkusCodeGenTransitiveInfo = provider(
    "Accumulates exact CodeGenProvider inputs and source resources across deps.",
    fields = {
        "input_files": "Transitive exact workspace-relative CodeGenProvider input files.",
        "resource_files": "Transitive exact workspace-relative source resource files.",
    },
)

def _resource_entry_path(short_path, strip_prefix, package):
    if strip_prefix:
        normalized = strip_prefix.strip("/")
        workspace_prefix = normalized
        if package and not normalized.startswith(package + "/"):
            workspace_prefix = package + "/" + normalized
        prefix = workspace_prefix + "/"
        if not short_path.startswith(prefix):
            fail("resource '{}' is outside resource_strip_prefix '{}'".format(short_path, strip_prefix))
        return short_path[len(prefix):]

    # Match rules_java's default resource-path semantics: first prefer any
    # Maven-style src/<source-set>/resources root, then the topmost java or
    # javatests directory.
    segments = short_path.split("/")
    for index in range(0, len(segments) - 2):
        if segments[index] == "src" and segments[index + 2] == "resources":
            return "/".join(segments[index + 3:])
    for index in range(0, len(segments)):
        if segments[index] in ("java", "javatests"):
            return "/".join(segments[index + 1:])
    return short_path

def _resource_entry(file, strip_prefix, package):
    return _resource_entry_path(file.short_path, strip_prefix, package)

resource_entry_for_test = _resource_entry_path

def _codegen_root_impl(ctx):
    output_jar = ctx.actions.declare_file(ctx.label.name + ".jar")
    args = ctx.actions.args()
    args.add("--output", output_jar)
    args.add("--normalize")
    args.add("--exclude_build_data")
    for resource in ctx.files.resources:
        args.add("--resources", "{}:{}".format(resource.path, _resource_entry(resource, ctx.attr.resource_strip_prefix, ctx.label.package)))
    ctx.actions.run(
        executable = ctx.executable._singlejar,
        arguments = [args],
        inputs = ctx.files.resources,
        outputs = [output_jar],
        mnemonic = "QuarkusCodeGenRoot",
        progress_message = "Preparing Quarkus code-generation root for %{label}",
    )
    java_deps = [dep[JavaInfo] for dep in ctx.attr.deps]
    java_exports = [dep[JavaInfo] for dep in ctx.attr.exports]
    java_runtime_deps = [dep[JavaInfo] for dep in ctx.attr.runtime_deps]
    return [
        DefaultInfo(files = depset([output_jar])),
        JavaInfo(
            output_jar = output_jar,
            compile_jar = output_jar,
            deps = java_deps,
            exports = java_exports,
            runtime_deps = java_runtime_deps,
        ),
    ]

quarkus_codegen_root_rule = rule(
    implementation = _codegen_root_impl,
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo]),
        "exports": attr.label_list(providers = [JavaInfo]),
        "resources": attr.label_list(allow_files = True),
        "resource_strip_prefix": attr.string(),
        "runtime_deps": attr.label_list(providers = [JavaInfo]),
        # The model aspect deliberately reads ctx.rule.files.srcs from this
        # synthetic application root to retain the original codegen inputs as
        # source provenance, even though JavaInfo only exposes the resource jar.
        "srcs": attr.label_list(allow_files = True),
        "_singlejar": attr.label(
            default = Label("@bazel_tools//tools/jdk:singlejar"),
            cfg = "exec",
            executable = True,
        ),
    },
)

def _normalize_source_roots(package, declared_roots):
    if not declared_roots:
        fail("source_roots must contain at least one package-relative path")

    roots = []
    seen = {}
    for root in declared_roots:
        normalized = root.strip("/")
        segments = normalized.split("/")
        if (
            not normalized or
            root.startswith("/") or
            (normalized != "." and any([segment in ("", ".", "..") for segment in segments])) or
            ".." in segments
        ):
            fail("source_roots entries must be non-empty package-relative paths, got '{}'".format(root))
        if normalized == ".":
            workspace_root = package if package else "."
        else:
            workspace_root = package + "/" + normalized if package else normalized
        if workspace_root not in seen:
            roots.append(workspace_root)
            seen[workspace_root] = True
    return roots

normalize_source_roots_for_test = _normalize_source_roots

def _is_under_root(path, root):
    return not path.startswith("../") and (root == "." or path == root or path.startswith(root + "/"))

def _workspace_source_roots(ctx):
    roots = _normalize_source_roots(ctx.label.package, ctx.attr.source_roots)
    for source in ctx.files.srcs:
        if not source.is_source:
            # --source-parent is a workspace-relative path resolved against the
            # exec root, while a generated file lives under bazel-out. Its
            # short_path is indistinguishable from a source file's, so without
            # this check the provider would silently find an empty input
            # directory and generate nothing.
            fail("codegen input '{}' is a generated file; quarkus_codegen inputs must be source files".format(source.short_path))
        contained = any([_is_under_root(source.short_path, root) for root in roots])
        if not contained:
            fail("codegen input '{}' is outside source_roots {}".format(source.short_path, roots))
    return roots

def _effective_mode(ctx):
    if ctx.attr.mode == "test":
        return struct(model = "test", launch = "TEST", test = True)
    lifecycle = ctx.attr._codegen_lifecycle[QuarkusCodeGenLifecycleInfo].value
    if lifecycle == "dev":
        return struct(model = "dev", launch = "DEVELOPMENT", test = False)
    return struct(model = "normal", launch = "NORMAL", test = False)

def _quarkus_codegen_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_codegen requires its generated application root")
    mode = _effective_mode(ctx)
    source_roots = _workspace_source_roots(ctx)
    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    conditional_classpath = collect_runtime_classpath([single_transitioned_target(ctx.attr.conditional_deps)])
    deployment_classpath = collect_deployment_classpath(single_transitioned_target(ctx.attr.deployment_deps), ctx.attr.deps)
    model = assemble_application_model(
        ctx,
        ctx.attr.deps,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        mode.model,
        ctx.attr.application_name,
    )
    properties = write_build_properties(ctx, ctx.attr.build_properties)
    generated_tree = ctx.actions.declare_directory(ctx.label.name + ".generated")
    auxiliary_tree = ctx.actions.declare_directory(ctx.label.name + ".aux")
    work_tree = ctx.actions.declare_directory(ctx.label.name + ".work")
    source_jar = ctx.actions.declare_file(ctx.label.name + ".srcjar")

    args = ctx.actions.args()
    args.add("-jar")
    args.add(ctx.file.quarkifier_tool)
    args.add("codegen")
    args.add("--application-model", model)
    args.add_all(source_roots, before_each = "--source-parent")
    args.add("--generated-sources-dir", generated_tree.path)
    args.add("--aux-output-dir", auxiliary_tree.path)
    args.add("--work-output-dir", work_tree.path)
    args.add("--source-jar", source_jar)
    args.add("--launch-mode", mode.launch)
    args.add("--test=" + str(mode.test).lower())
    args.add("--properties-file", properties)

    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [args],
        inputs = depset(
            direct = [ctx.file.quarkifier_tool, model, properties] + ctx.files.srcs + ctx.files.resources + ctx.files.deployment_artifacts,
            transitive = [
                runtime_classpath,
                conditional_classpath,
                deployment_classpath,
                java_runtime.files,
            ],
        ),
        outputs = [generated_tree, auxiliary_tree, work_tree, source_jar],
        mnemonic = "QuarkusCodeGen",
        progress_message = "Generating Quarkus sources for %{label}",
        use_default_shell_env = False,
    )
    return [
        DefaultInfo(files = depset([source_jar])),
        OutputGroupInfo(
            quarkus_codegen_aux = depset([auxiliary_tree, work_tree]),
            quarkus_codegen_model = depset([model]),
            quarkus_codegen_sources = depset([generated_tree]),
            quarkus_codegen_work = depset([work_tree]),
        ),
        QuarkusCodeGenInfo(
            input_files = [source.short_path for source in ctx.files.srcs],
            mode = ctx.attr.mode,
            source_roots = source_roots,
        ),
    ]

quarkus_codegen_rule = rule(
    implementation = _quarkus_codegen_impl,
    attrs = {
        "application_name": attr.string(mandatory = True),
        "build_properties": attr.string_dict(),
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(
            mandatory = True,
            cfg = disable_coverage_transition,
            providers = [JavaInfo],
        ),
        "deployment_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "deployment_artifacts": attr.label(mandatory = True),
        "deployment_deps": attr.label(cfg = disable_coverage_transition),
        "deps": attr.label_list(
            mandatory = True,
            cfg = disable_coverage_transition,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
            ],
            providers = [JavaInfo],
        ),
        "mode": attr.string(values = ["main", "test"], default = "main"),
        "platform_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "platform_properties": attr.label(mandatory = True),
        "quarkifier_tool": attr.label(allow_single_file = [".jar"], mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
        "resources": attr.label_list(allow_files = True),
        "runtime_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "source_roots": attr.string_list(mandatory = True),
        "srcs": attr.label_list(allow_files = True, mandatory = True),
        "_codegen_lifecycle": attr.label(
            default = Label("//quarkus/private:codegen_lifecycle"),
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            cfg = "exec",
        ),
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        ),
    },
)

def _metadata_aspect_impl(target, ctx):
    input_files = []
    resource_files = []
    for attr_name in ("srcs", "deps", "exports", "runtime_deps"):
        if not hasattr(ctx.rule.attr, attr_name):
            continue
        value = getattr(ctx.rule.attr, attr_name)
        dependencies = value if type(value) == "list" else [value]
        for dependency in dependencies:
            if QuarkusCodeGenTransitiveInfo in dependency:
                dependency_info = dependency[QuarkusCodeGenTransitiveInfo]
                input_files.append(dependency_info.input_files)
                resource_files.append(dependency_info.resource_files)

    direct_resource_files = []
    if not ctx.label.repo_name and hasattr(ctx.rule.files, "resources"):
        for resource in ctx.rule.files.resources:
            if not resource.is_source or resource.short_path.startswith("../"):
                continue
            direct_resource_files.append(resource.short_path)
    all_resource_files = depset(
        direct = direct_resource_files,
        transitive = resource_files,
    )

    if QuarkusCodeGenInfo in target:
        info = target[QuarkusCodeGenInfo]
        input_files.append(depset(info.input_files))

        # Resources from the synthetic application root and all of its
        # dependencies can affect provider initialization or supply
        # dependency-only generator inputs in both main and test lifecycles.
        input_files.append(all_resource_files)
    return [
        QuarkusCodeGenTransitiveInfo(
            input_files = depset(transitive = input_files),
            resource_files = all_resource_files,
        ),
    ]

quarkus_codegen_metadata_aspect = aspect(
    implementation = _metadata_aspect_impl,
    attr_aspects = ["srcs", "deps", "exports", "runtime_deps"],
)

def collect_codegen_input_files(deps):
    """Collects exact transitive CodeGenProvider input files from deps.

    Args:
        deps: List of targets carrying QuarkusCodeGenTransitiveInfo.

    Returns:
        A depset of workspace-relative generator input file paths.
    """
    files = []
    for dep in deps:
        if QuarkusCodeGenTransitiveInfo in dep:
            files.append(dep[QuarkusCodeGenTransitiveInfo].input_files)
    return depset(transitive = files)
