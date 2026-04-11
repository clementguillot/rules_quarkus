"Classpath collection utilities for rules_quarkus."

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def collect_runtime_classpath(deps):
    """Collects the transitive runtime classpath from JavaInfo providers.

    Args:
        deps: tmp
    Returns:
        tmp"""
    transitive = []
    for dep in deps:
        if JavaInfo in dep:
            transitive.append(dep[JavaInfo].transitive_runtime_jars)
    return depset(transitive = transitive)

def collect_source_dirs(deps):
    """Collects transitive source jars from JavaInfo providers.

    Args:
        deps: tmp
    Returns:
        tmp"""
    transitive = []
    for dep in deps:
        if JavaInfo in dep:
            transitive.append(dep[JavaInfo].transitive_source_jars)
    return depset(transitive = transitive)
