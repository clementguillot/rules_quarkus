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
load("//quarkus/private:application_model_aspect.bzl", "collect_deployment_model_artifacts", "collect_deployment_model_fragments", "collect_direct_model_dependency_ids", "collect_local_deployments", "collect_local_runtime_aliases", "collect_model_artifacts", "collect_model_fragments", "collect_model_root_ids", "collect_watch_metadata", "has_maven_artifact", "quarkus_application_model_aspect", "write_synthetic_test_root_fragment")
load("//quarkus/private:build_properties.bzl", "validate_build_property_keys")
load("//quarkus/private:classpath_utils.bzl", "collect_deployment_classpath", "collect_extension_runtime_jars", "collect_local_app_jars", "collect_runtime_classpath", "quarkus_extension_deployment_classpath_aspect", "write_runfiles_paths_file")
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
    if resources and not srcs:
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

_QuarkusTestModelPartsInfo = provider(
    doc = """Graph parts of one quarkus_test TEST model. Only the continuous-test aggregate
    reads them, to re-assemble several tests into one model without reading lossy model files.""",
    fields = {
        "conditional_classpath": "Depset of conditional dependency candidates.",
        "deployment_classpath": "Depset of deployment artifacts.",
        "deployment_model_artifacts": "Depset of artifacts referenced by local deployment fragments.",
        "deployment_model_fragments": "Depset of local-extension deployment graph fragments.",
        "local_deployments": "Local extension deployment coordinate-to-target mappings.",
        "local_runtime_aliases": "Raw-to-packaged local extension runtime target mappings.",
        "model_artifacts": "Depset of artifacts referenced by runtime model fragments.",
        "model_fragments": "Depset of runtime target model fragments.",
        "root_ids": "Ordered graph root ids of the test.",
        "runtime_classpath": "Depset of runtime artifacts.",
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

def _ordered_continuous_test_outputs(deps, runtime_classpath, test_outputs):
    """Orders reloadable test outputs exactly like the test runtime classpath."""
    candidates = {
        file.path: True
        for file in _direct_class_outputs(deps) + test_outputs.to_list()
    }
    ordered = []
    seen = {}
    for file in runtime_classpath.to_list():
        if file.path in candidates and file.path not in seen:
            seen[file.path] = True
            ordered.append(file)
    return ordered

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

def _merge_continuous_properties(targets, infos):
    properties = {}
    owners = {}
    for index in range(len(infos)):
        for key, value in infos[index].build_properties.items():
            if key in properties and properties[key] != value:
                fail("continuous_test targets '{}' and '{}' declare conflicting build_properties value for '{}'".format(
                    owners[key],
                    targets[index].label,
                    key,
                ))
            properties[key] = value
            owners[key] = targets[index].label
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

def _merge_continuous_mappings(infos, field, key_field, value_field, label):
    result = []
    seen = {}
    for info in infos:
        for item in getattr(info, field):
            key = item[key_field]
            value = item[value_field]
            if key in seen and seen[key] != value:
                fail("continuous_test {} '{}' maps to multiple targets".format(label, key))
            if key not in seen:
                seen[key] = value
                result.append(item)
    return result

def _continuous_test_aggregate_impl(ctx):
    if len(ctx.attr.tests) < 2:
        fail("continuous test aggregation requires at least two quarkus_test targets")

    infos = [target[QuarkusContinuousTestInfo] for target in ctx.attr.tests]
    parts = [target[_QuarkusTestModelPartsInfo] for target in ctx.attr.tests]
    selection_error = continuous_selection_error(
        [target.label for target in ctx.attr.tests],
        [len(info.test_classes) + len(info.test_packages) for info in infos],
    )
    if selection_error:
        fail(selection_error)
    application_root_ids = collect_model_root_ids(ctx.attr.application_deps)
    if not application_root_ids:
        fail("continuous test aggregation requires at least one application dependency")
    classes_output_dirs = _ordered_unique_files([info.classes_output_dirs for info in infos])
    synthetic_root = write_synthetic_test_root_fragment(
        ctx,
        application_root_ids,
        _ordered_unique_strings([part.root_ids for part in parts]),
        classes_output_dirs,
    )
    model = assemble_application_model_from_parts(
        ctx,
        [str(ctx.label)],
        depset([synthetic_root], transitive = [part.model_fragments for part in parts]),
        depset(transitive = [part.model_artifacts for part in parts]),
        depset(transitive = [part.deployment_model_fragments for part in parts]),
        depset(transitive = [part.deployment_model_artifacts for part in parts]),
        _merge_continuous_mappings(parts, "local_deployments", "coordinate", "targetId", "local deployment coordinate"),
        _merge_continuous_mappings(parts, "local_runtime_aliases", "rawTargetId", "targetId", "local runtime target"),
        depset(transitive = [part.runtime_classpath for part in parts]),
        depset(transitive = [part.conditional_classpath for part in parts]),
        depset(transitive = [part.deployment_classpath for part in parts]),
        "test",
    )

    return [
        DefaultInfo(files = depset([model])),
        OutputGroupInfo(quarkus_model = depset([model])),
        QuarkusContinuousTestInfo(
            application_dependency_ids = depset(
                application_root_ids,
                transitive = [info.application_dependency_ids for info in infos],
            ),
            application_model = model,
            build_files = depset(transitive = [info.build_files for info in infos]),
            build_properties = _merge_continuous_properties(ctx.attr.tests, infos),
            classes_output_dirs = classes_output_dirs,
            codegen_input_files = depset(transitive = [info.codegen_input_files for info in infos]),
            input_files = depset(transitive = [info.input_files for info in infos]),
            # Concatenate rather than de-duplicate: flags such as --add-opens take a separate value.
            jvm_flags = [flag for info in infos for flag in info.jvm_flags],
            model_classpath = depset(
                [model],
                transitive = [info.model_classpath for info in infos],
            ),
            test_classes = _ordered_unique_strings([info.test_classes for info in infos]),
            test_packages = _ordered_unique_strings([info.test_packages for info in infos]),
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
        model_artifacts = collect_model_artifacts(ctx.attr.deps)
        deployment_model_artifacts = collect_deployment_model_artifacts(ctx.attr.deps)
        providers.append(QuarkusContinuousTestInfo(
            application_dependency_ids = depset(collect_direct_model_dependency_ids(ctx.attr.deps)),
            application_model = model,
            build_files = metadata.build_files,
            build_properties = declared_build_properties,
            classes_output_dirs = _ordered_continuous_test_outputs(ctx.attr.deps, runtime_classpath, metadata.test_outputs),
            codegen_input_files = collect_codegen_input_files(ctx.attr.deps),
            input_files = metadata.input_files,
            jvm_flags = ctx.attr.jvm_flags,
            model_classpath = depset(
                [model],
                transitive = [
                    runtime_classpath,
                    conditional_classpath,
                    deploy_classpath,
                    model_artifacts,
                    deployment_model_artifacts,
                ],
            ),
            test_classes = ctx.attr.test_classes,
            test_packages = ctx.attr.test_packages,
        ))
        providers.append(_QuarkusTestModelPartsInfo(
            conditional_classpath = conditional_classpath,
            deployment_classpath = deploy_classpath,
            deployment_model_artifacts = deployment_model_artifacts,
            deployment_model_fragments = collect_deployment_model_fragments(ctx.attr.deps),
            local_deployments = collect_local_deployments(ctx.attr.deps),
            local_runtime_aliases = collect_local_runtime_aliases(ctx.attr.deps),
            model_artifacts = model_artifacts,
            model_fragments = collect_model_fragments(ctx.attr.deps),
            root_ids = collect_model_root_ids(ctx.attr.deps),
            runtime_classpath = runtime_classpath,
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
            aspects = [quarkus_application_model_aspect],
            providers = [JavaInfo],
            doc = "Application dependencies used to identify the TEST model root.",
        ),
        "tests": attr.label_list(
            mandatory = True,
            cfg = disable_coverage_transition,
            providers = [QuarkusContinuousTestInfo, _QuarkusTestModelPartsInfo],
            doc = "Independently executable quarkus_test targets combined for one dev session.",
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
