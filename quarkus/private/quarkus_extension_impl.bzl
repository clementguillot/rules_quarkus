"""Implementation of quarkus_extension_runtime rule.

Wraps a plain runtime java_library: bundles the generated extension descriptor
(META-INF/quarkus-extension.properties) and an enriched META-INF/quarkus-extension.yaml
into the jar via singlejar, and exposes the deployment classpath so quarkus_app
wires it into augmentation automatically.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusExtensionInfo")

# Captures a runtime library's own dependencies so the merged extension jar can
# propagate them WITHOUT re-adding the library's output jar (which the merged jar
# already contains). Reaching the library's deps requires an aspect because the
# rule only receives its JavaInfo, not its attributes.
_RuntimeLibDepsInfo = provider(
    doc = "Merged JavaInfo of a runtime library's dependencies, excluding the library's own jar.",
    fields = {"java_info": "Merged JavaInfo of the runtime lib's deps/exports/runtime_deps, or None."},
)

def _runtime_lib_deps_aspect_impl(_target, ctx):
    infos = []
    for attr_name in ("deps", "exports", "runtime_deps"):
        for dep in getattr(ctx.rule.attr, attr_name, []):
            if JavaInfo in dep:
                infos.append(dep[JavaInfo])
    return [_RuntimeLibDepsInfo(java_info = java_common.merge(infos) if infos else None)]

_runtime_lib_deps_aspect = aspect(implementation = _runtime_lib_deps_aspect_impl)

# Same pattern for the deployment target: captures its deps' jars so we can
# construct the deployment classpath with the merged (pom.properties-injected)
# jar replacing the original.
_DeployDepsInfo = provider(
    doc = "Merged JavaInfo of the deployment library's dependencies, excluding its own jar.",
    fields = {"java_info": "Merged JavaInfo of the deployment lib's deps/exports/runtime_deps, or None."},
)

def _deploy_deps_aspect_impl(_target, ctx):
    infos = []
    for attr_name in ("deps", "exports", "runtime_deps"):
        for dep in getattr(ctx.rule.attr, attr_name, []):
            if JavaInfo in dep:
                infos.append(dep[JavaInfo])
    return [_DeployDepsInfo(java_info = java_common.merge(infos) if infos else None)]

_deploy_deps_aspect = aspect(implementation = _deploy_deps_aspect_impl)

def _runtime_jar_path(group_id, artifact_id, version):
    return "maven2/{group}/{artifact}/{version}/{artifact}-{version}.jar".format(
        group = group_id.replace(".", "/"),
        artifact = artifact_id,
        version = version,
    )

# Reproduces the metadata the Maven/Gradle Quarkus extension plugin appends to
# quarkus-extension.yaml: the Quarkus core version it was built against, and the
# extension's extension dependencies (the other Quarkus extensions on its compile
# classpath, discovered via their embedded pom.properties). Both are consumed by
# Quarkus tooling (extension dependency graph, Dev UI, platform descriptors).
_ENRICH_YAML_CMD = """\
RUNTIME_JAR="$1"; OUTPUT="$2"; QUARKUS_VERSION="$3"; CLASSPATH_FILE="$4"; NAME="$5"

# Start from the extension's own quarkus-extension.yaml (carried in the runtime jar).
if ! unzip -q -p "$RUNTIME_JAR" META-INF/quarkus-extension.yaml > "$OUTPUT" 2>/dev/null || [ ! -s "$OUTPUT" ]; then
    printf 'name: "%s"\\nmetadata:\\n' "$NAME" > "$OUTPUT"
fi

# Append build metadata under the (last) metadata: key.
printf '  built-with-quarkus-core: "%s"\\n  extension-dependencies:\\n' "$QUARKUS_VERSION" >> "$OUTPUT"
while IFS= read -r jar; do
    [ -f "$jar" ] || continue
    unzip -l "$jar" META-INF/quarkus-extension.properties 2>/dev/null | grep -q quarkus-extension.properties || continue
    coords=$(unzip -p "$jar" 'META-INF/maven/*/*/pom.properties' 2>/dev/null \\
        | awk -F= '/^groupId/{g=$2} /^artifactId/{a=$2} END{if (g && a) print g":"a}')
    [ -n "$coords" ] && printf '  - "%s"\\n' "$coords"
done < "$CLASSPATH_FILE" | sort -u >> "$OUTPUT"
"""

def _enrich_extension_yaml(ctx, runtime_jar):
    """Builds the enriched quarkus-extension.yaml from the runtime jar's source yaml."""
    enriched_yaml = ctx.actions.declare_file(ctx.label.name + "_extension.yaml")
    compile_classpath = ctx.attr.runtime[JavaInfo].transitive_compile_time_jars

    classpath_file = ctx.actions.declare_file(ctx.label.name + "_classpath.params")
    classpath_args = ctx.actions.args()
    classpath_args.set_param_file_format("multiline")
    classpath_args.add_all(compile_classpath)
    ctx.actions.write(output = classpath_file, content = classpath_args)

    ctx.actions.run_shell(
        inputs = depset([runtime_jar, classpath_file], transitive = [compile_classpath]),
        outputs = [enriched_yaml],
        command = _ENRICH_YAML_CMD,
        arguments = [
            runtime_jar.path,
            enriched_yaml.path,
            ctx.attr.quarkus_version,
            classpath_file.path,
            ctx.label.name,
        ],
        mnemonic = "QuarkusExtYaml",
        progress_message = "Enriching quarkus-extension.yaml for %{label}",
    )
    return enriched_yaml

def _quarkus_extension_runtime_impl(ctx):
    runtime_jar = ctx.attr.runtime[JavaInfo].java_outputs[0].class_jar

    # Generate META-INF/quarkus-extension.properties — links the runtime jar to its
    # -deployment artifact (normally produced by the Maven/Gradle Quarkus build plugin).
    deployment_gav = "{group}:{artifact}-deployment:{version}".format(
        group = ctx.attr.group_id,
        artifact = ctx.attr.artifact_id,
        version = ctx.attr.version,
    )
    properties_file = ctx.actions.declare_file(ctx.label.name + "_extension.properties")
    ctx.actions.write(
        output = properties_file,
        content = "deployment-artifact=" + deployment_gav + "\n",
    )

    enriched_yaml = _enrich_extension_yaml(ctx, runtime_jar)

    # Merge the runtime jar (classes, config-roots.list) with the generated descriptor
    # and the enriched yaml. The descriptor must live in the same jar as the runtime
    # classes so Quarkus treats it as a runtime extension and loads its build-time
    # config. singlejar's --resources override the source yaml carried in --sources.
    merged_jar = ctx.actions.declare_file(_runtime_jar_path(
        ctx.attr.group_id,
        ctx.attr.artifact_id,
        ctx.attr.version,
    ))
    args = ctx.actions.args()
    args.add("--output", merged_jar)
    args.add("--sources", runtime_jar)
    args.add("--resources", "%s:%s" % (properties_file.path, "META-INF/quarkus-extension.properties"))
    args.add("--resources", "%s:%s" % (enriched_yaml.path, "META-INF/quarkus-extension.yaml"))
    args.add("--dont_change_compression")
    args.add("--exclude_build_data")
    ctx.actions.run(
        executable = ctx.executable._singlejar,
        arguments = [args],
        inputs = [runtime_jar, properties_file, enriched_yaml],
        outputs = [merged_jar],
        mnemonic = "QuarkusExtJar",
        progress_message = "Building Quarkus extension runtime jar for %s" % ctx.label.name,
    )

    # Expose the merged jar together with the runtime library's own deps — but not
    # the library's output jar, which is already inside the merged jar.
    runtime_lib_deps = ctx.attr.runtime[_RuntimeLibDepsInfo].java_info
    java_info = JavaInfo(
        output_jar = merged_jar,
        compile_jar = merged_jar,
        runtime_deps = [runtime_lib_deps] if runtime_lib_deps else [],
    )

    return [
        DefaultInfo(files = depset([merged_jar])),
        java_info,
        QuarkusExtensionInfo(
            deployment_classpath = _build_deployment_classpath(ctx),
        ),
    ]

def _build_deployment_classpath(ctx):
    """Produces a deployment classpath with pom.properties injected into the deployment jar.

    DevUI's ArtifactInfoUtil needs pom.properties inside the deployment jar to
    associate CardPageBuildItem instances with the correct extension card.
    """
    deploy_jar = ctx.attr.deployment[JavaInfo].java_outputs[0].class_jar
    deploy_artifact_id = ctx.attr.artifact_id + "-deployment"

    # Generate META-INF/maven/<groupId>/<artifactId>/pom.properties
    pom_props = ctx.actions.declare_file(ctx.label.name + "_deploy_pom.properties")
    ctx.actions.write(
        output = pom_props,
        content = "groupId={g}\nartifactId={a}\nversion={v}\n".format(
            g = ctx.attr.group_id,
            a = deploy_artifact_id,
            v = ctx.attr.version,
        ),
    )

    pom_props_path = "META-INF/maven/{g}/{a}/pom.properties".format(
        g = ctx.attr.group_id,
        a = deploy_artifact_id,
    )

    merged_deploy_jar = ctx.actions.declare_file(ctx.label.name + "_deploy_merged.jar")
    args = ctx.actions.args()
    args.add("--output", merged_deploy_jar)
    args.add("--sources", deploy_jar)
    args.add("--resources", "%s:%s" % (pom_props.path, pom_props_path))
    args.add("--dont_change_compression")
    args.add("--exclude_build_data")
    ctx.actions.run(
        executable = ctx.executable._singlejar,
        arguments = [args],
        inputs = [deploy_jar, pom_props],
        outputs = [merged_deploy_jar],
        mnemonic = "QuarkusExtDeployJar",
        progress_message = "Injecting pom.properties into deployment jar for %s" % ctx.label.name,
    )

    # Classpath = merged deploy jar + deployment target's transitive deps (excluding its own jar)
    deploy_deps_info = ctx.attr.deployment[_DeployDepsInfo].java_info
    if deploy_deps_info:
        return depset([merged_deploy_jar], transitive = [deploy_deps_info.transitive_runtime_jars])
    return depset([merged_deploy_jar])

quarkus_extension_runtime_rule = rule(
    implementation = _quarkus_extension_runtime_impl,
    attrs = {
        "runtime": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            aspects = [_runtime_lib_deps_aspect],
            doc = "Runtime java_library target (classes + resources, incl. quarkus-extension.yaml).",
        ),
        "deployment": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            aspects = [_deploy_deps_aspect],
            doc = "Deployment java_library target.",
        ),
        "group_id": attr.string(mandatory = True, doc = "Maven groupId."),
        "artifact_id": attr.string(mandatory = True, doc = "Extension artifactId."),
        "version": attr.string(mandatory = True, doc = "Extension version."),
        "quarkus_version": attr.string(mandatory = True, doc = "Quarkus core version (set by macro)."),
        "_singlejar": attr.label(
            default = Label("@bazel_tools//tools/jdk:singlejar"),
            executable = True,
            cfg = "exec",
        ),
    },
    provides = [JavaInfo, QuarkusExtensionInfo],
    doc = "Builds a Quarkus extension runtime jar with metadata and exposes the deployment classpath.",
)
