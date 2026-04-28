"Classpath and source directory collection utilities for rules_quarkus."

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

# Maven-layout source root markers used to derive source directories from package paths.
_SOURCE_MARKERS = ["src/main/java", "src/test/java"]

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

def collect_source_dir_paths(deps, runtime_classpath = None):
    """Derives workspace-relative source directory paths from deps and classpath.

    Examines each dep's package path and each local jar's owner package to
    derive candidate source roots using Maven-layout markers (src/main/java,
    src/test/java).

    Args:
        deps: List of targets providing JavaInfo (direct deps).
        runtime_classpath: Optional depset of transitive runtime jars. If provided,
            also derives source dirs from transitive local artifacts.
    Returns:
        A deduplicated list of workspace-relative source directory path strings.
    """
    source_dirs = []
    seen = {}

    # From direct deps
    for dep in deps:
        if JavaInfo in dep:
            _add_source_dirs_for_package(dep.label.package, seen, source_dirs)

    # From transitive runtime classpath (local artifacts only)
    if runtime_classpath:
        for jar in runtime_classpath.to_list():
            if is_local_artifact(jar):
                _add_source_dirs_for_package(jar.owner.package, seen, source_dirs)

    return source_dirs

def _add_source_dirs_for_package(pkg_path, seen, source_dirs):
    """Adds source directory candidates for a given package path."""
    for marker in _SOURCE_MARKERS:
        candidate = pkg_path + "/" + marker if pkg_path else marker
        if candidate not in seen:
            seen[candidate] = True
            source_dirs.append(candidate)
