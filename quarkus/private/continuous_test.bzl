"""Continuous testing: combines quarkus_test targets into the dev session's single TEST model.

Every quarkus_test exports its graph parts through QuarkusContinuousTestPartsInfo. The hidden
aggregate created by quarkus_app(continuous_test = ...) joins them with the application graph into
one application-rooted TEST model, because Quarkus runs one shared dev/test JVM, and hands the
dev target a QuarkusContinuousTestInfo.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusContinuousTestInfo")
load("//quarkus/private:application_model_aspect.bzl", "build_file_path", "collect_deployment_model_artifacts", "collect_deployment_model_fragments", "collect_local_deployments", "collect_local_runtime_aliases", "collect_model_artifacts", "collect_model_fragments", "collect_model_root_ids", "collect_watch_metadata", "quarkus_application_model_aspect", "write_synthetic_test_root_fragment")
load("//quarkus/private:classpath_utils.bzl", "collect_extension_deployment_classpath", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect")
load("//quarkus/private:coverage_transition.bzl", "disable_coverage_transition")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model_from_parts")
load("//quarkus/private:quarkus_codegen_impl.bzl", "collect_codegen_input_files")
load("//quarkus/private:test_selectors.bzl", "regex_escape_class_name")

QuarkusContinuousTestPartsInfo = provider(
    doc = """Internal: continuous-testing inputs of one quarkus_test, consumed only by the
    aggregate. Fields stay lazy so tests that no dev target references pay no analysis-time
    flattening.""",
    fields = {
        "build_files": "Depset of BUILD files whose changes require restarting dev mode.",
        "build_properties": "Declared test JVM system properties.",
        "class_output_candidates": "Depset of local compiled test jars that may be synchronized.",
        "codegen_input_files": "Depset of exact workspace-relative test code-generation inputs.",
        "conditional_classpath": "Depset of conditional dependency candidates.",
        "deployment_classpath": "Depset of deployment artifacts.",
        "deployment_model_artifacts": "Depset of artifacts referenced by local deployment fragments.",
        "deployment_model_fragments": "Depset of local-extension deployment graph fragments.",
        "input_files": "Depset of exact declared test-graph source and resource files.",
        "jvm_flags": "Declared flags for the shared dev/test child JVM.",
        "local_deployments": "Local extension deployment coordinate-to-target mappings.",
        "local_runtime_aliases": "Raw-to-packaged local extension runtime target mappings.",
        "model_artifacts": "Depset of artifacts referenced by runtime model fragments.",
        "model_fragments": "Depset of runtime target model fragments.",
        "root_ids": "Ordered graph root ids of the test.",
        "runtime_classpath": "Depset of runtime artifacts.",
        "test_classes": "Explicit class selectors.",
        "test_packages": "Explicit package selectors.",
    },
)

def continuous_test_parts(ctx, runtime_classpath, conditional_classpath, deployment_classpath):
    """Exports the continuous-testing graph parts of a quarkus_test.

    Args:
      ctx: The quarkus_test rule context.
      runtime_classpath: The test's runtime classpath depset.
      conditional_classpath: The test's conditional dependency candidates.
      deployment_classpath: The test's deployment classpath depset.

    Returns:
      A QuarkusContinuousTestPartsInfo provider.
    """
    deps = ctx.attr.deps
    metadata = collect_watch_metadata(deps)
    return QuarkusContinuousTestPartsInfo(
        # The test's own package holds its selectors and properties, even when it declares no
        # Java target (a precompiled test in a separate package).
        build_files = depset(
            [] if ctx.label.workspace_name else [build_file_path(ctx)],
            transitive = [metadata.build_files],
        ),
        build_properties = ctx.attr.build_properties,
        class_output_candidates = depset(_direct_class_outputs(deps), transitive = [metadata.test_outputs]),
        codegen_input_files = collect_codegen_input_files(deps),
        conditional_classpath = conditional_classpath,
        deployment_classpath = deployment_classpath,
        deployment_model_artifacts = collect_deployment_model_artifacts(deps),
        deployment_model_fragments = collect_deployment_model_fragments(deps),
        input_files = metadata.input_files,
        jvm_flags = ctx.attr.jvm_flags,
        local_deployments = collect_local_deployments(deps),
        local_runtime_aliases = collect_local_runtime_aliases(deps),
        model_artifacts = collect_model_artifacts(deps),
        model_fragments = collect_model_fragments(deps),
        root_ids = collect_model_root_ids(deps),
        runtime_classpath = runtime_classpath,
        test_classes = ctx.attr.test_classes,
        test_packages = ctx.attr.test_packages,
    )

def continuous_build_properties(app_properties, test_info):
    """Returns the dev JVM's build properties, including the continuous-test selection.

    Args:
      app_properties: The quarkus_app build_properties.
      test_info: The aggregated QuarkusContinuousTestInfo.

    Returns:
      The merged properties; conflicting app/test values fail analysis.
    """
    properties = dict(app_properties)
    for key, value in test_info.build_properties.items():
        if key in properties and properties[key] != value:
            fail("continuous_test: conflicting build_properties value for '{}'; dev and tests share one JVM".format(key))
        properties[key] = value
    selectors = ["^" + regex_escape_class_name(name) + "$" for name in test_info.test_classes]
    selectors.extend(["^" + regex_escape_class_name(name) + "\\..*$" for name in test_info.test_packages])
    if selectors:
        # Quarkus ignores quarkus.test.exclude-pattern once an include-pattern is set, so the
        # packaged *IT exclusion that quarkus_test applies must be part of the include pattern.
        # Without selectors, Quarkus' default (or configured) exclude-pattern already skips *IT.
        configured = properties.get("quarkus.test.include-pattern")
        properties["quarkus.test.include-pattern"] = (
            "(?!.*IT$)" +
            ("(?=(?:" + configured + ")$)" if configured else "") +
            "(" + "|".join(selectors) + ")"
        )
    return properties

def continuous_selection_error(labels, selector_counts):
    """Returns an error when only some aggregated targets narrow their test selection.

    Quarkus applies one include-pattern to the shared dev/test JVM. A target without
    selectors runs every test in its jars, which a class-name pattern cannot express
    next to another target's selectors.

    Args:
      labels: Labels of the aggregated quarkus_test targets.
      selector_counts: Number of test_classes plus test_packages for each target.

    Returns:
      An actionable error message, or an empty string.
    """
    unselected = [str(labels[i]) for i in range(len(labels)) if not selector_counts[i]]
    if not unselected or len(unselected) == len(labels):
        return ""
    return ("continuous_test: {} declare no test_classes/test_packages while other targets do; " +
            "one dev session applies a single test selection, so add selectors to these targets " +
            "or remove them from the others").format(unselected)

def merge_continuous_build_properties(labels, property_dicts):
    """Merges build_properties of aggregated tests, rejecting conflicting values.

    Args:
      labels: Labels of the aggregated quarkus_test targets.
      property_dicts: Their declared build_properties, in the same order.

    Returns:
      A struct with the merged `properties` and an `error` message (empty on success).
    """
    properties = {}
    owners = {}
    for index in range(len(property_dicts)):
        for key, value in property_dicts[index].items():
            if key in properties and properties[key] != value:
                return struct(properties = {}, error = "continuous_test targets '{}' and '{}' declare conflicting build_properties value for '{}'".format(
                    owners[key],
                    labels[index],
                    key,
                ))
            properties[key] = value
            owners[key] = labels[index]
    return struct(properties = properties, error = "")

def merge_continuous_jvm_flags(flag_lists):
    """Concatenates JVM flags in target order.

    Flags are not de-duplicated: options such as `--add-opens` take their value as a separate
    argument, so dropping a repeated token would corrupt the command line.

    Args:
      flag_lists: The jvm_flags of each aggregated test, in target order.

    Returns:
      The concatenated flag list.
    """
    return [flag for flags in flag_lists for flag in flags]

def merge_continuous_mappings(item_lists, key_field, value_field, label):
    """Merges local-extension mappings of aggregated tests, rejecting conflicting targets.

    Args:
      item_lists: Mapping dicts of each aggregated test, in target order.
      key_field: Field identifying a mapping.
      value_field: Field that must agree across tests for the same key.
      label: Human-readable mapping kind for the error message.

    Returns:
      A struct with the merged `items` and an `error` message (empty on success).
    """
    result = []
    seen = {}
    for items in item_lists:
        for item in items:
            key = item[key_field]
            value = item[value_field]
            if key in seen and seen[key] != value:
                return struct(items = [], error = "continuous_test {} '{}' maps to multiple targets".format(label, key))
            if key not in seen:
                seen[key] = value
                result.append(item)
    return struct(items = result, error = "")

def _direct_class_outputs(deps):
    """Returns compiled jars for the test libraries named directly by the test rule.

    External-repository deps are skipped: their jars are dependencies, not
    reloadable test outputs, and the dev target extracts these into the mutable
    test-classes directory together with their packaged resources.
    """
    outputs = []
    seen = {}
    for dep in deps:
        if JavaInfo not in dep or dep.label.workspace_name:
            continue
        for jar_output in dep[JavaInfo].outputs.jars:
            class_jar = jar_output.class_jar
            if class_jar.path not in seen:
                seen[class_jar.path] = True
                outputs.append(class_jar)
    return outputs

def _ordered_test_outputs(part):
    """Orders one test's reloadable outputs exactly like its runtime classpath."""
    candidates = {file.path: True for file in part.class_output_candidates.to_list()}
    return [file for file in part.runtime_classpath.to_list() if file.path in candidates]

def _ordered_unique(groups, key = lambda value: value):
    result = []
    seen = {}
    for group in groups:
        for value in group:
            if key(value) not in seen:
                seen[key(value)] = True
                result.append(value)
    return result

def _union(parts, field, application = None):
    """Joins one depset field of every test part, plus the application's own contribution."""
    return depset(transitive = ([application] if application else []) + [getattr(part, field) for part in parts])

def _checked(result):
    if result.error:
        fail(result.error)
    return result

def _continuous_test_aggregate_impl(ctx):
    labels = [target.label for target in ctx.attr.tests]
    parts = [target[QuarkusContinuousTestPartsInfo] for target in ctx.attr.tests]
    selection_error = continuous_selection_error(
        labels,
        [len(part.test_classes) + len(part.test_packages) for part in parts],
    )
    if selection_error:
        fail(selection_error)
    application = ctx.attr.application_deps
    application_root_ids = collect_model_root_ids(application)
    if not application_root_ids:
        fail("continuous test aggregation requires at least one application dependency")
    classes_output_dirs = _ordered_unique([_ordered_test_outputs(part) for part in parts], key = lambda file: file.path)
    synthetic_root = write_synthetic_test_root_fragment(
        ctx,
        application_root_ids,
        _ordered_unique([part.root_ids for part in parts]),
        classes_output_dirs,
    )

    # The synthetic root depends on the application itself, so its graph is part of the model even
    # when every listed test is module-owned and never reaches the application on its own.
    model_artifacts = _union(parts, "model_artifacts", collect_model_artifacts(application))
    deployment_model_artifacts = _union(parts, "deployment_model_artifacts", collect_deployment_model_artifacts(application))
    runtime_classpath = _union(parts, "runtime_classpath", collect_runtime_classpath(application))
    conditional_classpath = _union(parts, "conditional_classpath")
    deployment_classpath = _union(parts, "deployment_classpath", collect_extension_deployment_classpath(application))
    model = assemble_application_model_from_parts(
        ctx,
        [str(ctx.label)],
        depset([synthetic_root], transitive = [_union(parts, "model_fragments", collect_model_fragments(application))]),
        model_artifacts,
        _union(parts, "deployment_model_fragments", collect_deployment_model_fragments(application)),
        deployment_model_artifacts,
        _checked(merge_continuous_mappings([collect_local_deployments(application)] + [part.local_deployments for part in parts], "coordinate", "targetId", "local deployment coordinate")).items,
        _checked(merge_continuous_mappings([collect_local_runtime_aliases(application)] + [part.local_runtime_aliases for part in parts], "rawTargetId", "targetId", "local runtime target")).items,
        runtime_classpath,
        conditional_classpath,
        deployment_classpath,
        "test",
        # Like DEV mode, the first application dependency is the application: the synthetic root
        # also depends on every other application root, which must not compete with it.
        test_application_id = application_root_ids[0],
    )

    return [
        DefaultInfo(files = depset([model])),
        OutputGroupInfo(quarkus_model = depset([model])),
        QuarkusContinuousTestInfo(
            application_model = model,
            build_files = _union(parts, "build_files"),
            build_properties = _checked(merge_continuous_build_properties(labels, [part.build_properties for part in parts])).properties,
            classes_output_dirs = classes_output_dirs,
            codegen_input_files = _union(parts, "codegen_input_files"),
            input_files = _union(parts, "input_files"),
            jvm_flags = merge_continuous_jvm_flags([part.jvm_flags for part in parts]),
            model_classpath = depset(
                [model],
                transitive = [runtime_classpath, conditional_classpath, deployment_classpath, model_artifacts, deployment_model_artifacts],
            ),
            test_classes = _ordered_unique([part.test_classes for part in parts]),
            test_packages = _ordered_unique([part.test_packages for part in parts]),
        ),
    ]

quarkus_continuous_test_aggregate = rule(
    implementation = _continuous_test_aggregate_impl,
    attrs = {
        "application_deps": attr.label_list(
            mandatory = True,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
            ],
            providers = [JavaInfo],
            doc = "Application dependencies: the TEST model root and part of its graph.",
        ),
        "tests": attr.label_list(
            mandatory = True,
            cfg = disable_coverage_transition,
            providers = [QuarkusContinuousTestPartsInfo],
            doc = "quarkus_test targets combined into the dev session's single TEST model.",
        ),
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "deployment_artifacts": attr.label(mandatory = True),
        "deployment_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "model_private_deps": attr.label_list(
            cfg = disable_coverage_transition,
            providers = [JavaInfo],
        ),
        "platform_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "platform_properties": attr.label(mandatory = True),
        "quarkifier_tool": attr.label(allow_single_file = [".jar"], mandatory = True),
        "quarkus_version": attr.string(mandatory = True),
        "runtime_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
    },
    doc = "Internal non-test rule that combines quarkus_test providers for dev mode.",
)
