package com.clementguillot.quarkifier.model.transport;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import java.util.List;
import java.util.Map;

/** Typed representations of the internal Bazel model fragments and generated catalogs. */
@SuppressWarnings("PMD.DataClass")
public final class BazelModelInputs {

  public static final String ROOTS_SCHEMA = "quarkus-bazel-roots-v1";
  public static final String TARGET_SCHEMA = "quarkus-bazel-target-v1";
  public static final String RUNTIME_CATALOG_SCHEMA = "quarkus-bazel-runtime-catalog-v1";
  public static final String CONDITIONAL_CATALOG_SCHEMA = "quarkus-bazel-conditional-catalog-v1";
  public static final String DEPLOYMENT_CATALOG_SCHEMA = "quarkus-bazel-deployment-catalog-v1";
  public static final String PLATFORM_CATALOG_SCHEMA = "quarkus-bazel-platform-catalog-v1";

  private BazelModelInputs() {}

  public record Roots(String applicationLabel, List<String> rootIds) {

    public Roots {
      rootIds = List.copyOf(rootIds);
    }
  }

  public record TargetFragment(
      String targetId,
      String bazelLabel,
      String workspaceName,
      String packageName,
      String targetName,
      String ruleKind,
      String buildFile,
      boolean neverlink,
      ArtifactCoordinates coordinates,
      List<FileReference> runtimeOutputJars,
      List<FileReference> outputDirectories,
      List<FileReference> sourceJars,
      List<FileReference> sources,
      List<FileReference> resources,
      List<TargetEdge> edges) {

    public TargetFragment {
      runtimeOutputJars = List.copyOf(runtimeOutputJars);
      outputDirectories = List.copyOf(outputDirectories);
      sourceJars = List.copyOf(sourceJars);
      sources = List.copyOf(sources);
      resources = List.copyOf(resources);
      edges = List.copyOf(edges);
    }

    public boolean workspaceTarget() {
      return workspaceName.isEmpty();
    }
  }

  public record FileReference(String path, String shortPath, String owner, boolean source) {}

  public record TargetEdge(
      String targetId,
      String relation,
      BazelApplicationModel.DependencyScope scope,
      boolean optional,
      List<BazelApplicationModel.ArtifactKey> exclusions) {

    public TargetEdge {
      exclusions = List.copyOf(exclusions);
    }
  }

  public record RuntimeCatalog(
      List<RuntimeCatalogNode> nodes,
      List<String> directArtifacts,
      Map<String, String> conflictResolution) {

    public RuntimeCatalog {
      nodes = List.copyOf(nodes);
      directArtifacts = List.copyOf(directArtifacts);
      conflictResolution = Map.copyOf(conflictResolution);
    }
  }

  public record RuntimeCatalogNode(
      String coordinateKey,
      String targetName,
      ArtifactCoordinates coordinates,
      List<String> dependencies,
      boolean optional,
      List<String> exclusions) {

    public RuntimeCatalogNode {
      dependencies = List.copyOf(dependencies);
      exclusions = List.copyOf(exclusions);
    }

    public RuntimeCatalogNode(
        String coordinateKey,
        String targetName,
        ArtifactCoordinates coordinates,
        List<String> dependencies) {
      this(coordinateKey, targetName, coordinates, dependencies, false, List.of());
    }
  }

  public record DeploymentCatalog(
      String resolver,
      String resolverReportVersion,
      List<String> roots,
      List<String> droppedRoots,
      List<DeploymentCatalogNode> nodes,
      Map<String, String> conflictResolution) {

    public DeploymentCatalog {
      roots = List.copyOf(roots);
      droppedRoots = List.copyOf(droppedRoots);
      nodes = List.copyOf(nodes);
      conflictResolution = Map.copyOf(conflictResolution);
    }
  }

  public record DeploymentCatalogNode(
      String coordinate, String repoPath, List<String> dependencies, List<String> exclusions) {

    public DeploymentCatalogNode {
      dependencies = List.copyOf(dependencies);
      exclusions = List.copyOf(exclusions);
    }
  }

  public record ConditionalCatalog(
      String resolver,
      String resolverReportVersion,
      List<String> roots,
      List<ConditionalCatalogNode> nodes,
      List<ExtensionDescriptor> extensions,
      Map<String, String> conflictResolution) {

    public ConditionalCatalog {
      roots = List.copyOf(roots);
      nodes = List.copyOf(nodes);
      extensions = List.copyOf(extensions);
      conflictResolution = Map.copyOf(conflictResolution);
    }
  }

  public record ConditionalCatalogNode(
      String coordinate, String repoPath, List<String> dependencies, List<String> exclusions) {

    public ConditionalCatalogNode {
      dependencies = List.copyOf(dependencies);
      exclusions = List.copyOf(exclusions);
    }
  }

  public record ExtensionDescriptor(
      String runtimeArtifact,
      String deploymentArtifact,
      List<String> conditionalDependencies,
      List<String> conditionalDevDependencies,
      List<String> dependencyConditions) {

    public ExtensionDescriptor {
      conditionalDependencies = List.copyOf(conditionalDependencies);
      conditionalDevDependencies = List.copyOf(conditionalDevDependencies);
      dependencyConditions = List.copyOf(dependencyConditions);
    }
  }

  public record PlatformCatalog(
      List<ArtifactCoordinates> imports,
      List<String> propertyFiles,
      Map<String, String> properties) {

    public PlatformCatalog {
      imports = List.copyOf(imports);
      propertyFiles = List.copyOf(propertyFiles);
      properties = Map.copyOf(properties);
    }
  }
}
