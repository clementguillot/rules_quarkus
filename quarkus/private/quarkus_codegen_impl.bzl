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
    "Accumulates QuarkusCodeGenInfo metadata and input files transitively across deps.",
    fields = {
        "entries": "Transitive codegen metadata entries.",
        "input_files": "Transitive generator input files.",
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
    for marker in ("/src/main/resources/", "/src/test/resources/"):
        index = short_path.find(marker)
        if index >= 0:
            return short_path[index + len(marker):]
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
    return [
        DefaultInfo(files = depset([output_jar])),
        JavaInfo(
            output_jar = output_jar,
            compile_jar = output_jar,
            deps = java_deps,
        ),
    ]

quarkus_codegen_root_rule = rule(
    implementation = _codegen_root_impl,
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo]),
        "resources": attr.label_list(allow_files = True),
        "resource_strip_prefix": attr.string(),
        "srcs": attr.label_list(allow_files = True),
        "_singlejar": attr.label(
            default = Label("@bazel_tools//tools/jdk:singlejar"),
            cfg = "exec",
            executable = True,
        ),
    },
)

def _escape_property(value):
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("=", "\\=").replace(":", "\\:")

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
        contained = False
        for root in roots:
            if source.short_path == root or source.short_path.startswith(root + "/"):
                contained = True
                break
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
        ctx.attr.application_name or ctx.label.name,
    )
    properties = _write_properties(ctx)
    generated_tree = ctx.actions.declare_directory(ctx.label.name + ".generated")
    auxiliary_tree = ctx.actions.declare_directory(ctx.label.name + ".aux")
    build_dir = ctx.actions.declare_directory(ctx.label.name + ".codegen-build")
    source_jar = ctx.actions.declare_file(ctx.label.name + ".srcjar")

    args = ctx.actions.args()
    args.add("-Djava.io.tmpdir=" + build_dir.path)
    args.add("-jar")
    args.add(ctx.file.quarkifier_tool)
    args.add("codegen")
    args.add("--application-model", model)
    args.add_all(source_roots, before_each = "--source-parent")
    args.add("--generated-sources-dir", generated_tree.path)
    args.add("--aux-output-dir", auxiliary_tree.path)
    args.add("--source-jar", source_jar)
    args.add("--build-dir", build_dir.path)
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
        outputs = [generated_tree, auxiliary_tree, build_dir, source_jar],
        mnemonic = "QuarkusCodeGen",
        progress_message = "Generating Quarkus sources for %{label}",
        use_default_shell_env = False,
    )
    return [
        DefaultInfo(files = depset([source_jar])),
        OutputGroupInfo(
            quarkus_codegen_aux = depset([auxiliary_tree]),
            quarkus_codegen_model = depset([model]),
            quarkus_codegen_sources = depset([generated_tree]),
        ),
        QuarkusCodeGenInfo(
            build_properties = dict(ctx.attr.build_properties),
            generated_source_jar = source_jar,
            input_files = depset(ctx.files.srcs),
            mode = ctx.attr.mode,
            owner_label = ctx.attr.owning_module or str(ctx.label),
            source_roots = source_roots,
        ),
    ]

quarkus_codegen_rule = rule(
    implementation = _quarkus_codegen_impl,
    attrs = {
        "application_name": attr.string(),
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
        "owning_module": attr.string(),
        "platform_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "platform_properties": attr.label(mandatory = True),
        "quarkifier_tool": attr.label(allow_single_file = [".jar"], mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
        "resources": attr.label_list(allow_files = True),
        "runtime_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "source_roots": attr.string_list(mandatory = True),
        "srcs": attr.label_list(allow_files = True, mandatory = True),
        "version": attr.string(),
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
    entries = []
    inputs = []
    if QuarkusCodeGenInfo in target:
        info = target[QuarkusCodeGenInfo]
        entries.append(struct(
            build_properties = info.build_properties,
            mode = info.mode,
            owner_label = info.owner_label,
            source_roots = info.source_roots,
        ))
        inputs.append(info.input_files)
    for attr_name in ("srcs", "deps", "exports", "runtime_deps"):
        if not hasattr(ctx.rule.attr, attr_name):
            continue
        value = getattr(ctx.rule.attr, attr_name)
        dependencies = value if type(value) == "list" else [value]
        for dependency in dependencies:
            if QuarkusCodeGenTransitiveInfo in dependency:
                entries.extend(dependency[QuarkusCodeGenTransitiveInfo].entries)
                inputs.append(dependency[QuarkusCodeGenTransitiveInfo].input_files)
    return [QuarkusCodeGenTransitiveInfo(entries = entries, input_files = depset(transitive = inputs))]

quarkus_codegen_metadata_aspect = aspect(
    implementation = _metadata_aspect_impl,
    attr_aspects = ["srcs", "deps", "exports", "runtime_deps"],
)

def collect_codegen_metadata(deps):
    """Collects transitive QuarkusCodeGenTransitiveInfo entries and input files from deps.

    Args:
        deps: list of targets carrying QuarkusCodeGenTransitiveInfo.

    Returns:
        A struct with fields `entries` (list) and `input_files` (depset).
    """
    entries = []
    inputs = []
    for dep in deps:
        if QuarkusCodeGenTransitiveInfo in dep:
            entries.extend(dep[QuarkusCodeGenTransitiveInfo].entries)
            inputs.append(dep[QuarkusCodeGenTransitiveInfo].input_files)
    return struct(entries = entries, input_files = depset(transitive = inputs))
