"""Implementation of the quarkus_test rule.

Runs @QuarkusTest-annotated JUnit 5 tests under Bazel by:
1. Assembling the runtime and deployment classpaths
2. At test time, invoking the quarkifier in test mode to serialize an
   ApplicationModel from the actual runfiles jar paths
3. Launching JUnit ConsoleLauncher with the serialized model path and
   Quarkus-required system properties

The two-phase approach (model generation at test time, not build time) ensures
that jar paths in the ApplicationModel match the actual runfiles locations.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus/private:classpath_utils.bzl", "collect_runtime_classpath")

def _short_path(file):
    """Returns the short_path of a File for use with args.add_joined map_each."""
    return file.short_path

def _build_test_args(test_packages, test_classes):
    """Builds JUnit ConsoleLauncher CLI arguments."""
    args = ["execute", "--fail-if-no-tests"]
    for pkg in test_packages:
        args.append("--select-package=" + pkg)
    for cls in test_classes:
        args.append("--select-class=" + cls)
    args.append("--exclude-classname=.*IT$")
    return " ".join(args)

def _collect_direct_jars(deps):
    """Collects user-produced jars from deps (non-Maven jars).

    Filters the transitive runtime classpath to only include jars that are NOT
    from external repositories (i.e., jars produced by the user's BUILD targets).
    These are the jars Quarkus needs to scan for @Path, CDI beans, etc.
    """
    jars = []
    for dep in deps:
        if JavaInfo in dep:
            for jar in dep[JavaInfo].transitive_runtime_jars.to_list():
                # External repo jars have paths starting with "../" in short_path
                if not jar.short_path.startswith("../"):
                    jars.append(jar)
    return jars

def _quarkus_test_impl(ctx):
    if not ctx.attr.deps:
        fail("quarkus_test rule '{}' requires at least one dependency in 'deps'".format(ctx.label.name))

    # Collect classpaths
    runtime_classpath = collect_runtime_classpath(ctx.attr.deps)
    deploy_classpath = collect_runtime_classpath([ctx.attr.deployment_deps]) if ctx.attr.deployment_deps else depset()

    # Write runtime classpath file (for both JUnit -cp and quarkifier --application-classpath)
    cp_file = ctx.actions.declare_file(ctx.label.name + "_cp.txt")
    cp_args = ctx.actions.args()
    cp_args.add_joined(runtime_classpath, join_with = ":", map_each = _short_path)
    ctx.actions.write(output = cp_file, content = cp_args)

    # Write deployment classpath file (for quarkifier only, NOT on JUnit -cp)
    deploy_cp_file = ctx.actions.declare_file(ctx.label.name + "_deploy_cp.txt")
    deploy_cp_args = ctx.actions.args()
    deploy_cp_args.add_joined(deploy_classpath, join_with = ":", map_each = _short_path)
    ctx.actions.write(output = deploy_cp_file, content = deploy_cp_args)

    # Write direct dep jars file (comma-separated, for OUTPUT_SOURCES_DIR).
    # These are the jars directly produced by the deps targets — they contain
    # the user's app and test classes that Quarkus needs to scan for annotations.
    direct_jars = _collect_direct_jars(ctx.attr.deps)
    direct_jars_file = ctx.actions.declare_file(ctx.label.name + "_direct_jars.txt")
    direct_jars_args = ctx.actions.args()
    direct_jars_args.add_joined(direct_jars, join_with = ",", map_each = _short_path)
    ctx.actions.write(output = direct_jars_file, content = direct_jars_args)

    # Build test arguments
    test_args = _build_test_args(ctx.attr.test_packages, ctx.attr.test_classes)

    # Resolve tool jar and java runtime
    tool_jar = ctx.file.quarkifier_tool
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    java_home = java_runtime.java_home_runfiles_path

    # Generate launcher script
    launcher = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.expand_template(
        template = ctx.file._launcher_template,
        output = launcher,
        substitutions = {
            "%{workspace}": ctx.workspace_name,
            "%{classpath_file}": cp_file.short_path,
            "%{deploy_cp_file}": deploy_cp_file.short_path,
            "%{direct_jars_file}": direct_jars_file.short_path,
            "%{tool_jar}": tool_jar.short_path,
            "%{jvm_flags}": " ".join(ctx.attr.jvm_flags),
            "%{test_args}": test_args,
            "%{java_home}": java_home,
            "%{quarkus_version}": ctx.attr.quarkus_version,
            "%{app_name}": ctx.label.name,
        },
        is_executable = True,
    )

    # Assemble runfiles
    all_classpath = depset(transitive = [runtime_classpath, deploy_classpath])
    runfiles = ctx.runfiles(
        files = [cp_file, deploy_cp_file, direct_jars_file, tool_jar],
        transitive_files = depset(
            transitive = [all_classpath, java_runtime.files],
        ),
    )

    return [DefaultInfo(executable = launcher, runfiles = runfiles)]

quarkus_test = rule(
    implementation = _quarkus_test_impl,
    test = True,
    attrs = {
        "deps": attr.label_list(
            mandatory = True,
            providers = [JavaInfo],
            doc = "Test java_library targets. Transitive deps (app code, quarkus-junit5, etc.) are included automatically.",
        ),
        "jvm_flags": attr.string_list(
            doc = "JVM flags passed to the java command when running tests.",
        ),
        "test_packages": attr.string_list(
            doc = "Java packages to scan for test classes (--select-package).",
        ),
        "test_classes": attr.string_list(
            doc = "Fully-qualified test class names to run (--select-class).",
        ),
        "quarkus_version": attr.string(doc = "Quarkus version (set by macro)."),
        "quarkifier_tool": attr.label(
            allow_single_file = [".jar"],
            doc = "Quarkifier deploy jar (fat jar with all tool deps bundled).",
        ),
        "deployment_deps": attr.label(doc = "Deployment deps (set by macro)."),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
        ),
        "_launcher_template": attr.label(
            default = Label("//quarkus/private:test_launcher.sh.tpl"),
            allow_single_file = True,
        ),
    },
    doc = """\
Internal rule — use quarkus_test() macro from @rules_quarkus_toolchains//:defs.bzl instead.

Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.
At test time, the quarkifier serializes an ApplicationModel from the actual
runfiles jar paths, then QuarkusTestExtension uses it to bootstrap the
application in Mode.TEST.
""",
)
