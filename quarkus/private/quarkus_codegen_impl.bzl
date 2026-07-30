"""Implementation rules for extension-provided Quarkus source generation."""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusCodeGenInfo")
load("//quarkus/private:application_model_aspect.bzl", "quarkus_application_model_aspect")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")
load("//quarkus/private:codegen_lifecycle.bzl", "QuarkusCodeGenLifecycleInfo")
load("//quarkus/private:coverage_transition.bzl", "disable_coverage_transition", "single_transitioned_target")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model")

QuarkusCodeGenTransitiveInfo = provider(
    "Accumulates main CodeGenProvider input directories transitively across deps.",
    fields = {
        "input_dirs": "Transitive workspace-relative CodeGenProvider input directories.",
    },
)

def _resource_entry(file, strip_prefix, package):
    short_path = file.short_path
    if strip_prefix:
        normalized = strip_prefix.strip("/")
        workspace_prefix = normalized
        if package and not normalized.startswith(package + "/"):
            workspace_prefix = package + "/" + normalized
        prefix = workspace_prefix + "/"
        if not short_path.startswith(prefix):
            fail("resource '{}' is outside resource_strip_prefix '{}'".format(short_path, strip_prefix))
        return short_path[len(prefix):]
    for marker in ("src/main/resources/", "src/test/resources/"):
        # A resource in the root package starts with the marker outright, so
        # matching only "/<marker>" would leave the whole path as the jar entry
        # and keep application.properties off the classpath root.
        if short_path.startswith(marker):
            return short_path[len(marker):]
        index = short_path.find("/" + marker)
        if index >= 0:
            return short_path[index + 1 + len(marker):]
    return short_path

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

def _escape_property(value):
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!")

escape_property_for_test = _escape_property

def _write_properties(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".codegen.properties")
    lines = [
        "{}={}".format(_escape_property(key), _escape_property(ctx.attr.build_properties[key]))
        for key in sorted(ctx.attr.build_properties)
    ]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _workspace_source_roots(ctx):
    package = ctx.label.package
    roots = []
    for root in ctx.attr.source_roots:
        normalized = root.strip("/")
        if not normalized or root.startswith("/") or ".." in normalized.split("/"):
            fail("source_roots entries must be non-empty package-relative paths, got '{}'".format(root))
        roots.append(package + "/" + normalized if package else normalized)
    for source in ctx.files.srcs:
        if not source.is_source:
            # --source-parent is a workspace-relative path resolved against the
            # exec root, while a generated file lives under bazel-out. Its
            # short_path is indistinguishable from a source file's, so without
            # this check the provider would silently find an empty input
            # directory and generate nothing.
            fail("codegen input '{}' is a generated file; quarkus_codegen inputs must be source files".format(source.short_path))
        contained = False
        for root in roots:
            if source.short_path == root or source.short_path.startswith(root + "/"):
                contained = True
                break
        if not contained:
            fail("codegen input '{}' is outside source_roots {}".format(source.short_path, roots))
    return roots

def _codegen_input_dirs(source_paths, source_roots):
    """Returns the workspace-relative directories that hold the declared generator inputs.

    Dev-mode watching keys off these rather than off the source roots: a root is
    the whole `src/main` tree, so watching it would rebuild on every resource and
    non-Java file save. A provider's inputs live in its own subdirectory
    (`src/main/proto`, `src/main/hello`), which is what actually needs watching.

    Args:
        source_paths: Workspace-relative paths of the declared generator inputs.
        source_roots: Validated workspace-relative CodeGenProvider source parents.
    Returns:
        A sorted, deduplicated list of workspace-relative directory paths.
    """
    candidates = {}
    for source_path in source_paths:
        matched = False
        for root in source_roots:
            if source_path != root and not source_path.startswith(root + "/"):
                continue
            matched = True
            relative = source_path[len(root):].lstrip("/")
            parts = relative.split("/")
            input_dir = root
            if len(parts) > 1:
                input_dir += "/" + parts[0]
            candidates[input_dir] = True
        if not matched:
            fail("codegen input '{}' is outside source_roots {}".format(source_path, source_roots))

    dirs = []
    for candidate in sorted(candidates):
        if not any([
            candidate != parent and candidate.startswith(parent + "/")
            for parent in candidates
        ]):
            dirs.append(candidate)
    return dirs

codegen_input_dirs_for_test = _codegen_input_dirs

def _input_dirs(ctx, source_roots):
    return _codegen_input_dirs(
        [source.short_path for source in ctx.files.srcs],
        source_roots,
    )

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
    properties = _write_properties(ctx)
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
            input_dirs = _input_dirs(ctx, source_roots),
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
    dirs = []
    if QuarkusCodeGenInfo in target:
        info = target[QuarkusCodeGenInfo]
        if info.mode == "main":
            dirs.append(depset(info.input_dirs))
    for attr_name in ("srcs", "deps", "exports", "runtime_deps"):
        if not hasattr(ctx.rule.attr, attr_name):
            continue
        value = getattr(ctx.rule.attr, attr_name)
        dependencies = value if type(value) == "list" else [value]
        for dependency in dependencies:
            if QuarkusCodeGenTransitiveInfo in dependency:
                dirs.append(dependency[QuarkusCodeGenTransitiveInfo].input_dirs)
    return [QuarkusCodeGenTransitiveInfo(input_dirs = depset(transitive = dirs))]

quarkus_codegen_metadata_aspect = aspect(
    implementation = _metadata_aspect_impl,
    attr_aspects = ["srcs", "deps", "exports", "runtime_deps"],
)

def collect_codegen_input_dirs(deps):
    """Collects transitive main CodeGenProvider input directories from deps.

    Args:
        deps: List of targets carrying QuarkusCodeGenTransitiveInfo.

    Returns:
        A depset of workspace-relative generator input directory strings.
    """
    dirs = []
    for dep in deps:
        if QuarkusCodeGenTransitiveInfo in dep:
            dirs.append(dep[QuarkusCodeGenTransitiveInfo].input_dirs)
    return depset(transitive = dirs)
