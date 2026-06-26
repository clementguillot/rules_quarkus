"Classpath and source directory collection utilities for rules_quarkus."

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//quarkus:providers.bzl", "QuarkusExtensionInfo")

QuarkusExtensionDeploymentClasspathInfo = provider(
    doc = "Transitive deployment classpath contributed by local Quarkus extensions.",
    fields = {
        "deployment_classpath": "Depset of deployment classpath jars.",
    },
)

def _as_list(value):
    if value == None:
        return []
    return value if type(value) == "list" else [value]

def _collect_aspect_attr_classpaths(ctx, attr_name):
    if not hasattr(ctx.rule.attr, attr_name):
        return []

    classpaths = []
    for dep in _as_list(getattr(ctx.rule.attr, attr_name)):
        if QuarkusExtensionDeploymentClasspathInfo in dep:
            classpaths.append(dep[QuarkusExtensionDeploymentClasspathInfo].deployment_classpath)
    return classpaths

def _quarkus_extension_deployment_classpath_aspect_impl(target, ctx):
    classpaths = []
    if QuarkusExtensionInfo in target:
        classpaths.append(target[QuarkusExtensionInfo].deployment_classpath)

    classpaths.extend(_collect_aspect_attr_classpaths(ctx, "deps"))
    classpaths.extend(_collect_aspect_attr_classpaths(ctx, "runtime_deps"))
    classpaths.extend(_collect_aspect_attr_classpaths(ctx, "exports"))

    return [
        QuarkusExtensionDeploymentClasspathInfo(
            deployment_classpath = depset(transitive = classpaths),
        ),
    ]

quarkus_extension_deployment_classpath_aspect = aspect(
    implementation = _quarkus_extension_deployment_classpath_aspect_impl,
    attr_aspects = ["deps", "runtime_deps", "exports"],
)

# Maven-layout markers used to derive source/resource roots from package paths.
_SOURCE_MARKERS = ["src/main/java", "src/test/java"]
_RESOURCE_MARKERS = ["src/main/resources"]

def short_path(f):
    """Returns the short_path of a File, for use with args.add_joined(map_each=...)."""
    return f.short_path

def is_local_artifact(file):
    """Returns True if the file belongs to the local workspace (not an external repo)."""
    return file.owner != None and not file.owner.workspace_name

def collect_runtime_classpath(deps):
    """Collects the transitive runtime classpath from JavaInfo providers.

    Args:
        deps: List of targets that may provide JavaInfo.
    Returns:
        A depset of runtime jar Files.
    """
    transitive = []
    for dep in deps:
        if JavaInfo in dep:
            transitive.append(dep[JavaInfo].transitive_runtime_jars)
    return depset(transitive = transitive)

def collect_extension_deployment_classpath(deps):
    """Collects deployment classpath jars advertised by local extension runtime deps.

    Args:
        deps: List of targets that may provide QuarkusExtensionDeploymentClasspathInfo
    Returns:
        A depset of deployment classpath jar Files.
        """
    transitive = []
    for dep in deps:
        if QuarkusExtensionDeploymentClasspathInfo in dep:
            transitive.append(dep[QuarkusExtensionDeploymentClasspathInfo].deployment_classpath)
    return depset(transitive = transitive)

def collect_deployment_classpath(deployment_deps, deps):
    """Builds the full Quarkus deployment classpath.

    Combines the resolved Quarkus deployment closure (set by the macro) with the
    deployment classpath advertised by any local quarkus_extension found in deps.

    Args:
        deployment_deps: The deployment-closure target (set by the macro), or None.
        deps: List of dep targets, scanned for QuarkusExtensionDeploymentClasspathInfo.
    Returns:
        A depset of deployment classpath jar Files.
    """
    return depset(transitive = [
        collect_runtime_classpath([deployment_deps]) if deployment_deps else depset(),
        collect_extension_deployment_classpath(deps),
    ])

def collect_local_app_jars(deps, runtime_classpath):
    """Returns local workspace jars with direct-dep output jars ordered first.

    The quarkifier treats the first `--local-app-jars` entry as the application
    artifact. Direct deps (the app's own java_library) must therefore come
    before transitive local jars such as a locally-built Quarkus extension
    runtime jar — otherwise the extension jar would be mistaken for the app and
    skipped during extension scanning.

    Args:
        deps: List of direct dep targets that may provide JavaInfo.
        runtime_classpath: Depset of transitive runtime jars.
    Returns:
        A deduplicated, direct-first ordered list of local jar Files.
    """
    seen = {}
    ordered = []
    for dep in deps:
        if JavaInfo not in dep or dep.label.workspace_name:
            continue
        for jar in dep[JavaInfo].runtime_output_jars:
            if jar.path not in seen:
                seen[jar.path] = True
                ordered.append(jar)
    for jar in runtime_classpath.to_list():
        if is_local_artifact(jar) and jar.path not in seen:
            seen[jar.path] = True
            ordered.append(jar)
    return ordered

def collect_source_jars(deps):
    """Collects transitive source jars from JavaInfo providers.

    Args:
        deps: List of targets that may provide JavaInfo.
    Returns:
        A depset of source jar Files.
    """
    transitive = []
    for dep in deps:
        if JavaInfo in dep:
            transitive.append(dep[JavaInfo].transitive_source_jars)
    return depset(transitive = transitive)

def _collect_marker_dir_paths(deps, runtime_classpath, markers):
    """Derives workspace-relative directory paths from deps and classpath.

    Joins each direct dep's package path — and, when runtime_classpath is
    given, each transitive local jar's owner package — with the Maven-layout
    markers.

    Returns:
        A deduplicated list of workspace-relative directory path strings.
    """
    packages = [dep.label.package for dep in deps if JavaInfo in dep]
    if runtime_classpath:
        packages += [jar.owner.package for jar in runtime_classpath.to_list() if is_local_artifact(jar)]

    dirs = []
    seen = {}
    for pkg_path in packages:
        for marker in markers:
            candidate = pkg_path + "/" + marker if pkg_path else marker
            if candidate not in seen:
                seen[candidate] = True
                dirs.append(candidate)
    return dirs

def collect_source_dir_paths(deps, runtime_classpath = None):
    """Derives candidate source roots (src/main/java, src/test/java) from deps.

    Args:
        deps: List of targets providing JavaInfo (direct deps).
        runtime_classpath: Optional depset of transitive runtime jars. If provided,
            also derives source dirs from transitive local artifacts.
    Returns:
        A deduplicated list of workspace-relative source directory path strings.
    """
    return _collect_marker_dir_paths(deps, runtime_classpath, _SOURCE_MARKERS)

def collect_resource_dir_paths(deps, runtime_classpath = None):
    """Derives candidate resource roots (src/main/resources) from deps.

    Args:
        deps: List of targets providing JavaInfo (direct deps).
        runtime_classpath: Optional depset of transitive runtime jars. If provided,
            also derives resource dirs from transitive local artifacts.
    Returns:
        A deduplicated list of workspace-relative resource directory path strings.
    """
    return _collect_marker_dir_paths(deps, runtime_classpath, _RESOURCE_MARKERS)

def write_runfiles_paths_file(ctx, name_suffix, files, separator):
    """Writes the runfiles short_paths of `files`, joined by `separator`, to a file.

    Launcher scripts read these files at runtime to rebuild classpaths
    relative to the runfiles tree.

    Args:
        ctx: Rule context.
        name_suffix: Suffix appended to the target name for the output file name.
        files: Depset or list of Files.
        separator: Join character (e.g. ":" or ",").
    Returns:
        The declared output File.
    """
    out = ctx.actions.declare_file(ctx.label.name + name_suffix)
    args = ctx.actions.args()
    args.add_joined(files, join_with = separator, map_each = short_path)
    ctx.actions.write(output = out, content = args)
    return out
