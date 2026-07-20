package com.clementguillot.quarkifier.model.transport;

import java.util.List;
import java.util.Map;

/**
 * Versioned, Quarkus-independent description of a Bazel application dependency graph.
 *
 * <p>This is the boundary between Bazel analysis/repository data and the Quarkus-version-specific
 * adapter. It deliberately contains build-system facts, not Quarkus internal flag values.
 */
public record BazelApplicationModel(
    String schemaVersion,
    Producer producer,
    String quarkusVersion,
    Mode mode,
    String applicationId,
    List<Node> nodes,
    List<WorkspaceModule> workspaceModules,
    Platform platform,
    Diagnostics diagnostics) {

  public static final String SCHEMA_VERSION = "quarkus-bazel-model-v1";

  public BazelApplicationModel {
    nodes = List.copyOf(nodes);
    workspaceModules = List.copyOf(workspaceModules);
  }

  public enum Mode {
    NORMAL,
    TEST,
    DEV,
    NATIVE
  }

  public enum NodeKind {
    APPLICATION,
    WORKSPACE,
    MAVEN,
    DEPLOYMENT
  }

  public enum DependencyScope {
    COMPILE,
    RUNTIME,
    PROVIDED,
    TEST
  }

  public enum DependencyRelation {
    DEPS,
    EXPORTS,
    RUNTIME_DEPS,
    DEPLOYMENT
  }

  public record Producer(String name, String version) {}

  public record Node(
      String id,
      NodeKind kind,
      ArtifactCoordinates coordinates,
      List<String> paths,
      List<DependencyEdge> dependencies,
      ClasspathFacts classpath,
      String workspaceModuleId,
      String bazelLabel) {

    public Node {
      paths = List.copyOf(paths);
      dependencies = List.copyOf(dependencies);
    }
  }

  public record ArtifactCoordinates(
      String groupId, String artifactId, String classifier, String type, String version) {}

  public record ArtifactKey(String groupId, String artifactId) {}

  public record DependencyEdge(
      String targetId,
      DependencyRelation relation,
      DependencyScope scope,
      boolean optional,
      List<ArtifactKey> exclusions) {

    public DependencyEdge {
      exclusions = List.copyOf(exclusions);
    }
  }

  public record ClasspathFacts(
      boolean directFromApplication,
      boolean runtimeClasspath,
      boolean deploymentClasspath,
      boolean compileOnly,
      boolean optional,
      boolean reloadable,
      boolean topLevelRuntimeExtension) {}

  public record WorkspaceModule(
      String id,
      String bazelLabel,
      String moduleDir,
      String buildDir,
      String buildFile,
      List<SourceSet> sourceSets,
      List<String> directDependencyIds,
      List<ArtifactKey> dependencyConstraints,
      List<ArtifactKey> testClasspathExclusions,
      List<String> additionalTestClasspathElements,
      String parentId) {

    public WorkspaceModule {
      sourceSets = List.copyOf(sourceSets);
      directDependencyIds = List.copyOf(directDependencyIds);
      dependencyConstraints = List.copyOf(dependencyConstraints);
      testClasspathExclusions = List.copyOf(testClasspathExclusions);
      additionalTestClasspathElements = List.copyOf(additionalTestClasspathElements);
    }
  }

  public record SourceSet(
      String classifier,
      List<String> sourceDirectories,
      List<String> resourceDirectories,
      List<String> outputDirectories,
      List<String> generatedSourceDirectories,
      List<String> generatedResourceDirectories) {

    public SourceSet {
      sourceDirectories = List.copyOf(sourceDirectories);
      resourceDirectories = List.copyOf(resourceDirectories);
      outputDirectories = List.copyOf(outputDirectories);
      generatedSourceDirectories = List.copyOf(generatedSourceDirectories);
      generatedResourceDirectories = List.copyOf(generatedResourceDirectories);
    }
  }

  public record Platform(
      List<ArtifactCoordinates> imports,
      Map<String, String> properties,
      List<PlatformRelease> releases) {

    public Platform {
      imports = List.copyOf(imports);
      properties = Map.copyOf(properties);
      releases = List.copyOf(releases);
    }
  }

  public record PlatformRelease(
      String platformKey, String stream, String version, List<ArtifactCoordinates> memberBoms) {

    public PlatformRelease {
      memberBoms = List.copyOf(memberBoms);
    }
  }

  public record Diagnostics(List<String> warnings, List<String> provenance) {

    public Diagnostics {
      warnings = List.copyOf(warnings);
      provenance = List.copyOf(provenance);
    }
  }
}
