"""Implementation of the quarkus_test and quarkus_integration_test rules.

Runs Quarkus JUnit 5 tests under Bazel by:
1. Assembling the runtime and deployment classpaths
2. At test time, invoking the quarkifier in test mode to serialize an
   ApplicationModel from the actual runfiles jar paths
3. Launching JUnit ConsoleLauncher either with @QuarkusTest's in-process
   bootstrap or with metadata for an @QuarkusIntegrationTest artifact

The two-phase approach (model generation at test time, not build time) ensures
that jar paths in the ApplicationModel match the actual runfiles locations.
"""

load("@bazel_skylib//lib:shell.bzl", "shell")
load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusAppInfo", "QuarkusContinuousTestInfo", "QuarkusNativeInfo")
load("//quarkus/private:application_model_aspect.bzl", "build_file_path", "collect_deployment_model_artifacts", "collect_deployment_model_fragments", "collect_local_deployments", "collect_local_runtime_aliases", "collect_model_artifacts", "collect_model_fragments", "collect_model_root_ids", "collect_watch_metadata", "has_maven_artifact", "quarkus_application_model_aspect", "write_synthetic_test_root_fragment")
load("//quarkus/private:build_properties.bzl", "validate_build_property_keys")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_extension_deployment_classpath", "collect_extension_runtime_jars", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
load("//quarkus/private:coverage_transition.bzl", "disable_coverage_transition", "single_transitioned_target")
load("//quarkus/private:model_assembly.bzl", "assemble_application_model", "assemble_application_model_from_parts")
load("//quarkus/private:quarkus_codegen_impl.bzl", "collect_codegen_input_files", "quarkus_codegen_metadata_aspect")

def regex_escape_class_name(class_name):
    """Escapes a Java class or package name for use inside a regular expression.

    Args:
      class_name: Fully-qualified class or package name.

    Returns:
      The name with regex metacharacters valid in Java identifiers escaped.
    """
    return class_name.replace("\\", "\\\\").replace(".", "\\.").replace("$", "\\$")

def test_resources_without_sources_error(srcs, resources):
    """Returns an actionable error for resources the public macro would ignore."""
    if resources and srcs == None:
        return "quarkus_test resources require inline srcs; declare resources on the precompiled java_library instead"
    return ""

def _build_test_args(test_packages, test_classes, fail_if_no_tests, integration = False):
    """Builds JUnit ConsoleLauncher CLI arguments."""
    args = ["execute"]
    if fail_if_no_tests:
        args.append("--fail-if-no-tests")
    for pkg in test_packages:
        args.append("--select-package=" + pkg)
    for cls in test_classes:
        args.append("--select-class=" + cls)
    if integration:
        include_patterns = [".*IT$"] + ["^" + regex_escape_class_name(cls) + "$" for cls in test_classes]
        args.append("--include-classname=(" + "|".join(include_patterns) + ")")
    else:
        args.append("--exclude-classname=.*IT$")
    return " ".join(args)

def _quarkus_jacoco_present(integration, jacoco_dep_present):
    """Whether the launcher should let quarkus-jacoco write its own report.

    Independent of `--collect_code_coverage`: the launcher itself sets
    `quarkus.jacoco.enabled=false` when Bazel coverage runs, so gating on
    coverage here would make the report unreachable in both modes.
    """
    return not integration and jacoco_dep_present

def _build_property_jvm_flags(build_properties):
    """Returns shell-safe JVM flags for the test-time Quarkus bootstrap.

    The JVM splits `-Dkey=value` on the first `=` and offers no key escaping,
    so keys containing `=` must be rejected. Other characters remain safe
    because `shell.quote` preserves the complete flag as one argv element.
    """
    validate_build_property_keys(build_properties)
    return [
        shell.quote("-D{}={}".format(key, build_properties[key]))
        for key in sorted(build_properties)
    ]

_QuarkusContinuousTestPartsInfo = provider(
    doc = """Continuous-testing inputs of one quarkus_test, consumed only by the aggregate that
    combines the listed tests into the dev session's single TEST model. Fields stay lazy so tests
    that no dev target references pay no analysis-time flattening.""",
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

def _ordered_continuous_test_outputs(part):
    """Orders one test's reloadable outputs exactly like its runtime classpath."""
    candidates = {file.path: True for file in part.class_output_candidates.to_list()}
    return [file for file in part.runtime_classpath.to_list() if file.path in candidates]

def _ordered_unique_strings(groups):
    result = []
    seen = {}
    for group in groups:
        for value in group:
            if value not in seen:
                seen[value] = True
                result.append(value)
    return result

def _ordered_unique_files(groups):
    result = []
    seen = {}
    for group in groups:
        for file in group:
            if file.path not in seen:
                seen[file.path] = True
                result.append(file)
    return result

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

def _checked(result):
    if result.error:
        fail(result.error)
    return result

def _continuous_test_aggregate_impl(ctx):
    labels = [target.label for target in ctx.attr.tests]
    parts = [target[_QuarkusContinuousTestPartsInfo] for target in ctx.attr.tests]
    selection_error = continuous_selection_error(
        labels,
        [len(part.test_classes) + len(part.test_packages) for part in parts],
    )
    if selection_error:
        fail(selection_error)
    application_root_ids = collect_model_root_ids(ctx.attr.application_deps)
    if not application_root_ids:
        fail("continuous test aggregation requires at least one application dependency")
    classes_output_dirs = _ordered_unique_files([_ordered_continuous_test_outputs(part) for part in parts])
    synthetic_root = write_synthetic_test_root_fragment(
        ctx,
        application_root_ids,
        _ordered_unique_strings([part.root_ids for part in parts]),
        classes_output_dirs,
    )

    # The synthetic root depends on the application itself, so its graph is part of the model even
    # when every listed test is module-owned and never reaches the application on its own.
    application = ctx.attr.application_deps
    model_artifacts = depset(transitive = [collect_model_artifacts(application)] + [part.model_artifacts for part in parts])
    deployment_model_artifacts = depset(transitive = [collect_deployment_model_artifacts(application)] + [part.deployment_model_artifacts for part in parts])
    runtime_classpath = depset(transitive = [collect_runtime_classpath(application)] + [part.runtime_classpath for part in parts])
    conditional_classpath = depset(transitive = [part.conditional_classpath for part in parts])
    deployment_classpath = depset(transitive = [collect_extension_deployment_classpath(application)] + [part.deployment_classpath for part in parts])
    model = assemble_application_model_from_parts(
        ctx,
        [str(ctx.label)],
        depset([synthetic_root], transitive = [collect_model_fragments(application)] + [part.model_fragments for part in parts]),
        model_artifacts,
        depset(transitive = [collect_deployment_model_fragments(application)] + [part.deployment_model_fragments for part in parts]),
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
            build_files = depset(transitive = [part.build_files for part in parts]),
            build_properties = _checked(merge_continuous_build_properties(labels, [part.build_properties for part in parts])).properties,
            classes_output_dirs = classes_output_dirs,
            codegen_input_files = depset(transitive = [part.codegen_input_files for part in parts]),
            input_files = depset(transitive = [part.input_files for part in parts]),
            jvm_flags = merge_continuous_jvm_flags([part.jvm_flags for part in parts]),
            model_classpath = depset(
                [model],
                transitive = [
                    runtime_classpath,
                    conditional_classpath,
                    deployment_classpath,
                    model_artifacts,
                    deployment_model_artifacts,
                ],
            ),
            test_classes = _ordered_unique_strings([part.test_classes for part in parts]),
            test_packages = _ordered_unique_strings([part.test_packages for part in parts]),
        ),
    ]

def _integration_version_error(rule_name, test_version, app_label, app_version):
    if test_version == app_version:
        return ""
    return "quarkus_integration_test rule '{}' uses Quarkus {}, but app '{}' was built with Quarkus {}".format(
        rule_name,
        test_version,
        app_label,
        app_version,
    )

def _integration_artifact(ctx):
    app = single_transitioned_target(ctx.attr.app)
    if QuarkusAppInfo in app:
        info = app[QuarkusAppInfo]
        artifact_type = "jar"
        artifact = info.output_dir
        artifact_path = artifact.short_path + "/" + info.runner_path
        quarkus_version = info.quarkus_version
    elif QuarkusNativeInfo in app:
        info = app[QuarkusNativeInfo]
        artifact_type = "native"
        artifact = info.binary
        artifact_path = artifact.short_path
        quarkus_version = info.quarkus_version
    else:
        fail("quarkus_integration_test rule '{}' requires 'app' to provide QuarkusAppInfo or QuarkusNativeInfo".format(ctx.label.name))

    version_error = _integration_version_error(
        ctx.label.name,
        ctx.attr.quarkus_version,
        app.label,
        quarkus_version,
    )
    if version_error:
        fail(version_error)

    return struct(
        artifact = artifact,
        artifact_path = artifact_path,
        artifact_type = artifact_type,
    )

def _test_impl(ctx, integration):
    if not ctx.attr.deps:
        rule_name = "quarkus_integration_test" if integration else "quarkus_test"
        fail("{} rule '{}' requires at least one dependency in 'deps'".format(rule_name, ctx.label.name))

    runtime_classpath = collect_runtime_classpath(ctx.attr.deps + ctx.attr.model_private_deps)
    conditional_classpath = collect_runtime_classpath([single_transitioned_target(ctx.attr.conditional_deps)])
    deploy_classpath = collect_deployment_classpath(single_transitioned_target(ctx.attr.deployment_deps), ctx.attr.deps)
    model = assemble_application_model(ctx, ctx.attr.deps, runtime_classpath, conditional_classpath, deploy_classpath, "test")

    # Runtime classpath (for both JUnit -cp and quarkifier --application-classpath)
    # and the user-built jars Quarkus must scan (comma-separated, for
    # OUTPUT_SOURCES_DIR).
    # Extension runtime jars are excluded from direct_jars: leaving them as app
    # roots exposes their @ConfigRoot classes to both classloaders (SRCFG00027).
    cp_file = write_runfiles_paths_file(ctx, "_cp.txt", runtime_classpath, ":")
    declared_build_properties = ctx.attr.build_properties if not integration else {}
    ext_rt_jars = collect_extension_runtime_jars(ctx.attr.deps)
    direct_jars_file = write_runfiles_paths_file(ctx, "_direct_jars.txt", collect_local_app_jars(ctx.attr.deps, runtime_classpath, ext_rt_jars), ",")

    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    coverage_enabled = ctx.configuration.coverage_enabled and not integration
    coverage_jars_file = None
    coverage_files = []
    coverage_runfiles = None
    coverage_reporter_path = ""
    jacoco_runner_path = ""
    if coverage_enabled:
        coverage_jars_file = write_runfiles_paths_file(ctx, "_coverage_jars.txt", collect_local_app_jars(ctx.attr.deps, runtime_classpath), ",")
        coverage_reporter = ctx.attr._coverage_reporter[DefaultInfo]
        coverage_reporter_path = coverage_reporter.files_to_run.executable.short_path
        coverage_runfiles = coverage_reporter.default_runfiles
        jacoco_runner = ctx.attr._jacoco_runner[JavaInfo].runtime_output_jars[0]
        jacoco_runner_path = jacoco_runner.short_path
        coverage_files.append(jacoco_runner)

    integration_artifact = _integration_artifact(ctx) if integration else None
    launcher = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{app_name}": ctx.label.name,
            "%{artifact_path}": integration_artifact.artifact_path if integration else "",
            "%{artifact_type}": integration_artifact.artifact_type if integration else "",
            "%{build_property_jvm_flags}": " ".join(_build_property_jvm_flags(declared_build_properties)),
            "%{classpath_file}": cp_file.short_path,
            "%{coverage_enabled}": "true" if coverage_enabled else "false",
            "%{coverage_jars_file}": coverage_jars_file.short_path if coverage_jars_file else "",
            "%{coverage_reporter}": coverage_reporter_path,
            "%{direct_jars_file}": direct_jars_file.short_path,
            "%{java_home}": java_runtime.java_home_runfiles_path,
            "%{jvm_flags}": " ".join([shell.quote(f) for f in ctx.attr.jvm_flags]),
            "%{model_file}": model.short_path,
            "%{jacoco_runner}": jacoco_runner_path,
            "%{quarkus_jacoco_present}": "true" if _quarkus_jacoco_present(integration, has_maven_artifact(ctx.attr.deps, "io.quarkus", "quarkus-jacoco")) else "false",
            "%{test_args}": _build_test_args(ctx.attr.test_packages, ctx.attr.test_classes, ctx.attr.fail_if_no_tests, integration),
            "%{test_kind}": "integration" if integration else "quarkus",
            "%{tool_jar}": tool_jar.short_path,
            "%{workspace}": ctx.workspace_name,
        },
        is_executable = True,
    )

    direct_runfiles = [cp_file, direct_jars_file, model, tool_jar] + coverage_files
    if coverage_jars_file:
        direct_runfiles.append(coverage_jars_file)
    if integration:
        direct_runfiles.append(integration_artifact.artifact)
    runfiles = ctx.runfiles(
        files = direct_runfiles + ctx.files.deployment_artifacts,
        transitive_files = depset(
            transitive = [runtime_classpath, conditional_classpath, deploy_classpath, java_runtime.files],
        ),
    )
    if coverage_runfiles:
        runfiles = runfiles.merge(coverage_runfiles)

    providers = [
        DefaultInfo(executable = launcher, runfiles = runfiles),
        OutputGroupInfo(quarkus_model = depset([model])),
    ]
    if not integration:
        metadata = collect_watch_metadata(ctx.attr.deps)
        providers.append(_QuarkusContinuousTestPartsInfo(
            # The test's own package holds its selectors and properties, even when it declares no
            # Java target (a precompiled test in a separate package).
            build_files = depset(
                [] if ctx.label.workspace_name else [build_file_path(ctx)],
                transitive = [metadata.build_files],
            ),
            build_properties = declared_build_properties,
            class_output_candidates = depset(_direct_class_outputs(ctx.attr.deps), transitive = [metadata.test_outputs]),
            codegen_input_files = collect_codegen_input_files(ctx.attr.deps),
            conditional_classpath = conditional_classpath,
            deployment_classpath = deploy_classpath,
            deployment_model_artifacts = collect_deployment_model_artifacts(ctx.attr.deps),
            deployment_model_fragments = collect_deployment_model_fragments(ctx.attr.deps),
            input_files = metadata.input_files,
            jvm_flags = ctx.attr.jvm_flags,
            local_deployments = collect_local_deployments(ctx.attr.deps),
            local_runtime_aliases = collect_local_runtime_aliases(ctx.attr.deps),
            model_artifacts = collect_model_artifacts(ctx.attr.deps),
            model_fragments = collect_model_fragments(ctx.attr.deps),
            root_ids = collect_model_root_ids(ctx.attr.deps),
            runtime_classpath = runtime_classpath,
            test_classes = ctx.attr.test_classes,
            test_packages = ctx.attr.test_packages,
        ))
    return providers

def _quarkus_test_impl(ctx):
    return _test_impl(ctx, False)

def _quarkus_integration_test_impl(ctx):
    return _test_impl(ctx, True)

def _test_attrs(integration = False):
    dependency_cfg = disable_coverage_transition if integration else "target"
    attrs = {
        "conditional_catalog": attr.label(allow_single_file = [".json"], mandatory = True),
        "conditional_deps": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            cfg = dependency_cfg,
        ),
        "deployment_deps": attr.label(
            doc = "Resolved Quarkus deployment closure (set by macro).",
            cfg = dependency_cfg,
        ),
        "deployment_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal deployment resolver graph catalog (set by macro).",
        ),
        "deployment_artifacts": attr.label(mandatory = True),
        "platform_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal Quarkus platform metadata catalog (set by macro).",
        ),
        "platform_properties": attr.label(
            mandatory = True,
            doc = "Internal Quarkus platform property files (set by macro).",
        ),
        "deps": attr.label_list(
            mandatory = True,
            cfg = dependency_cfg,
            aspects = [
                quarkus_extension_deployment_classpath_aspect,
                quarkus_application_model_aspect,
            ] + ([] if integration else [quarkus_codegen_metadata_aspect]),
            providers = [JavaInfo],
            doc = "Test java_library targets. Transitive deps (app code, quarkus-junit, etc.) are included automatically.",
        ),
        "fail_if_no_tests": attr.bool(
            default = True,
            doc = "Fail the test if zero tests are discovered/executed. Set to False for targets where an empty test set is acceptable.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running tests.",
        ),
        "model_private_deps": attr.label_list(
            providers = [JavaInfo],
            cfg = dependency_cfg,
            doc = "Internal test compile/launcher dependencies omitted from ApplicationModel semantics.",
        ),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar (fat jar with all tool deps bundled).",
        ),
        "runtime_catalog": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "Internal runtime resolver graph catalog (set by macro).",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "test_classes": attr.string_list(
            doc = "Fully-qualified test class names to run (--select-class).",
        ),
        "test_packages": attr.string_list(
            doc = "Java packages to scan for test classes (--select-package).",
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:test_launcher.sh.tpl"),
            allow_single_file = True,
        ),
    }
    if integration:
        attrs["app"] = attr.label(
            mandatory = True,
            cfg = dependency_cfg,
            providers = [[QuarkusAppInfo], [QuarkusNativeInfo]],
            doc = "Packaged quarkus_app or quarkus_app(native=True) target to launch.",
        )
        attrs["_allowlist_function_transition"] = attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        )
    else:
        attrs.update({
            "build_properties": attr.string_dict(
                doc = "Declared test-augmentation properties. These are passed as JVM system properties and therefore remain visible while the test runs.",
            ),
            "_coverage_reporter": attr.label(
                default = Label("//quarkus/private:bazel_jacoco_reporter"),
                cfg = config.exec(exec_group = "test"),
                executable = True,
            ),
            "_jacoco_runner": attr.label(
                default = "@bazel_tools//tools/jdk:JacocoCoverageRunner",
                providers = [JavaInfo],
            ),
            "_lcov_merger": attr.label(
                default = configuration_field(fragment = "coverage", name = "output_generator"),
                cfg = config.exec(exec_group = "test"),
                executable = True,
            ),
        })
    return attrs

quarkus_test = rule(
    implementation = _quarkus_test_impl,
    test = True,
    attrs = _test_attrs(),
    doc = """\
Internal rule — use quarkus_test() macro from @rules_quarkus//quarkus:defs.bzl instead.

Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.
At test time, the quarkifier serializes an ApplicationModel from the actual
runfiles jar paths, then QuarkusTestExtension uses it to bootstrap the
application in Mode.TEST.
""",
)

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
            providers = [_QuarkusContinuousTestPartsInfo],
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

quarkus_integration_test = rule(
    implementation = _quarkus_integration_test_impl,
    test = True,
    attrs = _test_attrs(integration = True),
    doc = """\
Internal rule — use quarkus_integration_test() from @rules_quarkus//quarkus:defs.bzl.

Runs @QuarkusIntegrationTest-annotated JUnit 5 tests against a packaged Fast
JAR or native executable while retaining Quarkus test resources and Dev
Services through the serialized TEST-mode ApplicationModel.
""",
)

build_test_args_for_test = _build_test_args
integration_version_error_for_test = _integration_version_error
quarkus_jacoco_present_for_test = _quarkus_jacoco_present
build_property_jvm_flags_for_test = _build_property_jvm_flags
