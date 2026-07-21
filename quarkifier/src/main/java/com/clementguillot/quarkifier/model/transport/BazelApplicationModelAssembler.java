package com.clementguillot.quarkifier.model.transport;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ClasspathFacts;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyRelation;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Mode;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.NodeKind;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Platform;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.PlatformRelease;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Producer;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.SourceSet;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.WorkspaceModule;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.ConditionalCatalog;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.ConditionalCatalogNode;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.DeploymentCatalog;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.DeploymentCatalogNode;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.ExtensionDescriptor;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.FileReference;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.PlatformCatalog;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.Roots;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.RuntimeCatalog;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.RuntimeCatalogNode;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.TargetEdge;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.TargetFragment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/** Strictly joins Bazel target facts and resolver catalogs into the v1 transport model. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.TooManyMethods"
})
public final class BazelApplicationModelAssembler {

  private static final String DESCRIPTOR = "META-INF/quarkus-extension.properties";
  private static final String DEPLOYMENT_ARTIFACT = "deployment-artifact";

  private BazelApplicationModelAssembler() {}

  /** Complete, already-parsed action inputs. Deployment paths are keyed by catalog repoPath. */
  public record Inputs(
      Roots roots,
      List<TargetFragment> targetFragments,
      RuntimeCatalog runtimeCatalog,
      ConditionalCatalog conditionalCatalog,
      DeploymentCatalog deploymentCatalog,
      PlatformCatalog platformCatalog,
      Map<String, String> localDeployments,
      Map<String, String> localRuntimeAliases,
      Map<String, String> conditionalPaths,
      Map<String, String> deploymentPaths,
      Map<String, String> platformPropertyPaths,
      Set<String> runtimeClasspathPaths,
      Set<String> deploymentClasspathPaths,
      Set<String> modelPrivateTargetIds,
      String quarkusVersion,
      Mode mode,
      String applicationName,
      String applicationVersion,
      String producerVersion) {

    public Inputs {
      targetFragments = List.copyOf(targetFragments);
      localDeployments = Map.copyOf(localDeployments);
      localRuntimeAliases = Map.copyOf(localRuntimeAliases);
      conditionalPaths = Map.copyOf(conditionalPaths);
      deploymentPaths = Map.copyOf(deploymentPaths);
      platformPropertyPaths = Map.copyOf(platformPropertyPaths);
      runtimeClasspathPaths = Set.copyOf(runtimeClasspathPaths);
      deploymentClasspathPaths = Set.copyOf(deploymentClasspathPaths);
      modelPrivateTargetIds = Set.copyOf(modelPrivateTargetIds);
    }
  }

  public static BazelApplicationModel assemble(Inputs inputs) throws IOException {
    require(inputs.roots() != null, "roots input is required");
    require(inputs.runtimeCatalog() != null, "runtime catalog is required");
    require(inputs.conditionalCatalog() != null, "conditional catalog is required");
    require(inputs.deploymentCatalog() != null, "deployment catalog is required");
    require(inputs.platformCatalog() != null, "platform catalog is required");
    require(inputs.mode() != null, "mode is required");
    requireNonBlank(inputs.quarkusVersion(), "Quarkus version");
    requireNonBlank(inputs.producerVersion(), "producer version");
    require(
        !inputs.roots().rootIds().isEmpty(),
        "application root list is empty for " + inputs.roots().applicationLabel());

    Assembly assembly = new Assembly(inputs);
    BazelApplicationModel result = assembly.run();
    BazelApplicationModelValidator.validate(result);
    return result;
  }

  private static final class Assembly {
    private final Inputs inputs;
    private final Map<String, TargetFragment> fragments = new LinkedHashMap<>();
    private final Map<String, RuntimeCatalogNode> runtimeByTarget = new HashMap<>();
    private final Map<String, RuntimeCatalogNode> runtimeByCoordinateKey = new HashMap<>();
    private final Map<String, RuntimeCatalogNode> runtimeByCoordinates = new HashMap<>();
    private final Map<String, String> externalTargetIds = new HashMap<>();
    private final Map<String, DeploymentCatalogNode> deploymentByCoordinates =
        new LinkedHashMap<>();
    private final Map<String, ConditionalCatalogNode> conditionalByCoordinates =
        new LinkedHashMap<>();
    private final Map<String, ExtensionDescriptor> descriptorsByRuntime = new LinkedHashMap<>();
    private final Map<String, MutableNode> nodes = new LinkedHashMap<>();
    private final Map<String, MutableNode> nodesByCoordinates = new HashMap<>();
    private final Map<String, String> localDeployments = new LinkedHashMap<>();
    private final Map<String, String> localRuntimeAliases = new LinkedHashMap<>();
    private final Map<String, String> extensionDeployments = new LinkedHashMap<>();
    private final Map<String, String> conditionalInjectionParents = new LinkedHashMap<>();
    private final Set<String> runtimeNodeIds = new LinkedHashSet<>();
    private TargetFragment testSourceFragment;

    private Assembly(Inputs inputs) {
      this.inputs = inputs;
    }

    private BazelApplicationModel run() throws IOException {
      indexInputs();
      Reachability reachable = validateAndFindReachableTargets();
      createRuntimeNodes(reachable.runtime());
      wireRuntimeGraph(reachable.runtime());
      createLocalDeploymentNodes(reachable.deployment());
      wireLocalDeploymentGraph(reachable.deployment());
      String applicationId = promoteApplication();
      markDirectDependencies(applicationId);
      scanExtensionDescriptors();
      activateConditionalDependencies(applicationId);
      markReloadableWorkspaceDependencies(applicationId);
      injectDeploymentGraphs(applicationId);
      if (inputs.mode() == Mode.TEST) {
        pruneUnreachableNodes(applicationId);
      }
      return materialize(applicationId);
    }

    private void indexInputs() {
      for (TargetFragment fragment : inputs.targetFragments()) {
        if (fragments.putIfAbsent(fragment.targetId(), fragment) != null) {
          fail("duplicate target fragment for " + fragment.targetId());
        }
        if (!fragment.workspaceTarget()
            && externalTargetIds.putIfAbsent(fragment.targetName(), fragment.targetId()) != null) {
          fail("duplicate external target name " + fragment.targetName());
        }
      }
      for (RuntimeCatalogNode node : inputs.runtimeCatalog().nodes()) {
        runtimeByTarget.put(node.targetName(), node);
        runtimeByCoordinateKey.put(node.coordinateKey(), node);
        runtimeByCoordinates.put(BazelArtifactCoordinates.canonical(node.coordinates()), node);
      }
      for (DeploymentCatalogNode node : inputs.deploymentCatalog().nodes()) {
        ArtifactCoordinates coordinates = BazelArtifactCoordinates.parse(node.coordinate());
        String canonical = BazelArtifactCoordinates.canonical(coordinates);
        if (deploymentByCoordinates.putIfAbsent(canonical, node) != null) {
          fail("duplicate normalized deployment coordinate " + canonical);
        }
      }
      for (ConditionalCatalogNode node : inputs.conditionalCatalog().nodes()) {
        String canonical =
            BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(node.coordinate()));
        if (conditionalByCoordinates.putIfAbsent(canonical, node) != null) {
          fail("duplicate normalized conditional coordinate " + canonical);
        }
      }
      for (ExtensionDescriptor descriptor : inputs.conditionalCatalog().extensions()) {
        String runtime =
            BazelArtifactCoordinates.canonical(
                BazelArtifactCoordinates.parse(descriptor.runtimeArtifact()));
        if (descriptorsByRuntime.putIfAbsent(runtime, descriptor) != null) {
          fail("duplicate normalized extension descriptor " + runtime);
        }
      }
      inputs
          .localDeployments()
          .forEach(
              (coordinate, targetId) -> {
                String canonical =
                    BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(coordinate));
                if (localDeployments.putIfAbsent(canonical, targetId) != null) {
                  fail("duplicate local deployment coordinate " + canonical);
                }
              });
      inputs
          .localRuntimeAliases()
          .forEach(
              (rawId, targetId) -> {
                if (localRuntimeAliases.putIfAbsent(rawId, targetId) != null) {
                  fail("duplicate local runtime alias " + rawId);
                }
              });
    }

    private Reachability validateAndFindReachableTargets() {
      Set<String> runtime = reachableFrom(inputs.roots().rootIds());
      Set<String> deployment = reachableFrom(localDeployments.values());
      var reachable = new LinkedHashSet<>(runtime);
      reachable.addAll(deployment);
      var extra = new TreeSet<>(fragments.keySet());
      extra.removeAll(reachable);
      extra.removeAll(localRuntimeAliases.keySet());
      for (String id : new ArrayList<>(extra)) {
        TargetFragment fragment = fragments.get(id);
        if (!fragment.workspaceTarget() && runtimeByTarget.containsKey(fragment.targetName())) {
          extra.remove(id);
        }
      }
      if (!extra.isEmpty()) {
        fail("target fragment set contains nodes outside the application graphs: " + extra);
      }
      return new Reachability(runtime, deployment);
    }

    private Set<String> reachableFrom(Collection<String> roots) {
      var reachable = new LinkedHashSet<String>();
      Deque<String> pending = new ArrayDeque<>(roots);
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (!reachable.add(id)) {
          continue;
        }
        TargetFragment fragment = fragments.get(id);
        if (fragment == null) {
          fail("missing target fragment for reachable Bazel target " + id);
        }
        for (DependencyEdge edge : resolvedEdges(fragment)) {
          pending.addLast(edge.targetId());
        }
      }
      return reachable;
    }

    private void createRuntimeNodes(Set<String> reachable) {
      for (String id : reachable) {
        TargetFragment fragment = fragments.get(id);
        ArtifactCoordinates coordinates;
        NodeKind kind;
        if (fragment.workspaceTarget()) {
          coordinates =
              fragment.coordinates() == null
                  ? workspaceCoordinates(fragment)
                  : fragment.coordinates();
          kind = NodeKind.WORKSPACE;
        } else {
          RuntimeCatalogNode runtime = runtimeByTarget.get(fragment.targetName());
          if (runtime == null) {
            fail(
                "external target "
                    + id
                    + " does not map to any runtime-catalog targetName '"
                    + fragment.targetName()
                    + "'");
          }
          coordinates = runtime.coordinates();
          kind = NodeKind.MAVEN;
        }
        List<String> paths =
            fragment.runtimeOutputJars().stream().map(FileReference::path).sorted().toList();
        MutableNode node =
            new MutableNode(
                id,
                kind,
                coordinates,
                paths,
                fragment.workspaceTarget() ? id : null,
                fragment.bazelLabel());
        addNode(node);
        runtimeNodeIds.add(id);
      }
    }

    private void wireRuntimeGraph(Set<String> reachable) {
      for (String id : reachable) {
        TargetFragment fragment = fragments.get(id);
        MutableNode source = nodes.get(id);
        for (DependencyEdge dependency : modelEdges(fragment, reachable)) {
          source.addEdge(dependency);
          if (fragment.workspaceTarget()) {
            source.addDeclaredEdge(dependency);
          }
        }
      }
    }

    private void createLocalDeploymentNodes(Set<String> reachable) {
      for (String id : reachable) {
        if (nodes.containsKey(id)) {
          nodes.get(id).deployment = true;
          continue;
        }
        TargetFragment fragment = fragments.get(id);
        ArtifactCoordinates coordinates = fragment.coordinates();
        if (coordinates == null) {
          RuntimeCatalogNode catalogNode = runtimeByTarget.get(fragment.targetName());
          if (catalogNode == null) {
            fail("local deployment graph target " + id + " has no explicit or catalog coordinates");
          }
          coordinates = catalogNode.coordinates();
        }
        MutableNode node =
            new MutableNode(
                id,
                NodeKind.DEPLOYMENT,
                coordinates,
                fragment.runtimeOutputJars().stream().map(FileReference::path).sorted().toList(),
                fragment.workspaceTarget() ? id : null,
                fragment.bazelLabel());
        node.deployment = true;
        addNode(node);
      }
    }

    private void wireLocalDeploymentGraph(Set<String> reachable) {
      for (String id : reachable) {
        MutableNode source = nodes.get(id);
        for (DependencyEdge dependency : modelEdges(fragments.get(id), nodes.keySet())) {
          source.addEdge(dependency);
          if (source.workspaceId != null) {
            source.addDeclaredEdge(dependency);
          }
        }
      }
    }

    private String normalizeTargetId(String targetId) {
      return localRuntimeAliases.getOrDefault(targetId, targetId);
    }

    private List<DependencyEdge> resolvedEdges(TargetFragment fragment) {
      if (fragment.workspaceTarget()) {
        return fragment.edges().stream()
            .map(
                edge ->
                    new DependencyEdge(
                        normalizeTargetId(edge.targetId()),
                        relation(edge),
                        edge.scope(),
                        edge.optional(),
                        edge.exclusions()))
            .toList();
      }
      RuntimeCatalogNode source = runtimeByTarget.get(fragment.targetName());
      if (source == null) {
        fail("external target " + fragment.targetId() + " is absent from runtime catalog");
      }
      Map<String, TargetEdge> aspectEdges = new HashMap<>();
      for (TargetEdge edge : fragment.edges()) {
        aspectEdges.put(normalizeTargetId(edge.targetId()), edge);
      }
      List<DependencyEdge> result = new ArrayList<>();
      for (String dependencyKey : source.dependencies()) {
        RuntimeCatalogNode targetCatalog = runtimeByCoordinateKey.get(dependencyKey);
        if (targetCatalog == null) {
          fail(
              "runtime catalog edge from "
                  + source.coordinateKey()
                  + " points to unknown coordinate key "
                  + dependencyKey);
        }
        String targetId = externalTargetIds.get(targetCatalog.targetName());
        if (targetId == null) {
          fail(
              "Maven resolver edge "
                  + source.coordinateKey()
                  + " -> "
                  + dependencyKey
                  + " has no Bazel target fragment");
        }
        TargetEdge aspectEdge = aspectEdges.remove(targetId);
        if (aspectEdge == null) {
          result.add(
              new DependencyEdge(
                  targetId, DependencyRelation.DEPS, DependencyScope.COMPILE, false, List.of()));
        } else {
          result.add(
              new DependencyEdge(
                  targetId,
                  relation(aspectEdge),
                  aspectEdge.scope(),
                  aspectEdge.optional(),
                  aspectEdge.exclusions()));
        }
      }
      return result;
    }

    /**
     * Materializes relationships after resolver reachability has been fixed.
     *
     * <p>A Coursier JSON report is a flattened graph. If the same artifact is reached through two
     * paths with different exclusions, the report can omit an edge that is valid in one of those
     * path contexts. Bazel's aspect still observes that relationship on the actual classpath. We
     * therefore retain such an aspect edge only when both endpoints survived resolver reachability.
     * This cannot reintroduce an optional or excluded artifact: a resolver-pruned target is absent
     * from {@code retainedTargets}.
     */
    private List<DependencyEdge> modelEdges(TargetFragment fragment, Set<String> retainedTargets) {
      List<DependencyEdge> result = new ArrayList<>(resolvedEdges(fragment));
      if (fragment.workspaceTarget()) {
        return result;
      }

      Set<String> resolvedTargetIds = new HashSet<>();
      result.forEach(edge -> resolvedTargetIds.add(edge.targetId()));
      for (TargetEdge edge : fragment.edges()) {
        String targetId = normalizeTargetId(edge.targetId());
        if (!retainedTargets.contains(targetId) || !resolvedTargetIds.add(targetId)) {
          continue;
        }
        result.add(
            new DependencyEdge(
                targetId, relation(edge), edge.scope(), edge.optional(), edge.exclusions()));
      }
      return result;
    }

    private String promoteApplication() {
      String testRootId = null;
      String applicationId;
      if (inputs.mode() == Mode.TEST) {
        TestSelection selection = selectTestApplication();
        testRootId = selection.testRootId();
        applicationId = selection.applicationId();
        collapseTestRoot(testRootId, applicationId);
      } else {
        applicationId = inputs.roots().rootIds().get(0);
      }
      MutableNode application = nodes.get(applicationId);
      if (application == null || application.kind != NodeKind.WORKSPACE) {
        fail("first quarkus_app dependency must be a local Java target: " + applicationId);
      }
      application.kind = NodeKind.APPLICATION;
      String artifactId =
          inputs.mode() == Mode.TEST || isBlank(inputs.applicationName())
              ? application.coordinates.artifactId()
              : inputs.applicationName();
      String version =
          inputs.mode() == Mode.TEST || isBlank(inputs.applicationVersion())
              ? application.coordinates.version()
              : inputs.applicationVersion();
      replaceCoordinates(
          application,
          new ArtifactCoordinates(
              application.coordinates.groupId(), artifactId, "", "jar", version));
      application.direct = false;
      application.runtime = true;
      application.deployment = false;
      application.compileOnly = false;

      for (String rootId : inputs.roots().rootIds()) {
        if (rootId.equals(applicationId) || rootId.equals(testRootId)) {
          continue;
        }
        DependencyEdge dependency =
            new DependencyEdge(
                rootId,
                DependencyRelation.DEPS,
                inputs.mode() == Mode.TEST ? DependencyScope.TEST : DependencyScope.COMPILE,
                false,
                List.of());
        application.addEdge(dependency);
        application.addDeclaredEdge(dependency);
      }
      return applicationId;
    }

    private TestSelection selectTestApplication() {
      List<TestSelection> candidates = new ArrayList<>();
      for (String rootId : inputs.roots().rootIds()) {
        MutableNode testRoot = nodes.get(rootId);
        if (testRoot == null || testRoot.kind != NodeKind.WORKSPACE) {
          continue;
        }
        testRoot.edges.values().stream()
            .map(DependencyEdge::targetId)
            .filter(
                id -> {
                  MutableNode node = nodes.get(id);
                  TargetFragment fragment = fragments.get(id);
                  return node != null
                      && node.kind == NodeKind.WORKSPACE
                      && fragment != null
                      && hasMainSources(fragment);
                })
            .distinct()
            .map(applicationId -> new TestSelection(rootId, applicationId))
            .forEach(candidates::add);
      }
      if (candidates.size() != 1) {
        fail(
            "quarkus_test roots must identify exactly one local test target with exactly one direct"
                + " local application library with main sources; found "
                + candidates);
      }
      return candidates.get(0);
    }

    private record TestSelection(String testRootId, String applicationId) {}

    private static boolean hasMainSources(TargetFragment fragment) {
      return java.util.stream.Stream.concat(
              fragment.sources().stream(), fragment.resources().stream())
          .map(FileReference::path)
          .map(path -> path.replace('\\', '/'))
          .anyMatch(path -> path.contains("/src/main/") || path.startsWith("src/main/"));
    }

    private void collapseTestRoot(String testRootId, String applicationId) {
      MutableNode testRoot = nodes.get(testRootId);
      MutableNode application = nodes.get(applicationId);
      for (DependencyEdge edge : testRoot.edges.values()) {
        if (applicationId.equals(edge.targetId())) {
          continue;
        }
        if (inputs.modelPrivateTargetIds().contains(edge.targetId())) {
          continue;
        }
        DependencyEdge testDependency =
            new DependencyEdge(
                edge.targetId(),
                edge.relation(),
                DependencyScope.TEST,
                edge.optional(),
                edge.exclusions());
        application.addEdge(testDependency);
        if (testRoot.declaredEdges.containsKey(MutableNode.edgeKey(edge))) {
          application.addDeclaredEdge(testDependency);
        }
      }
      testSourceFragment = fragments.get(testRootId);
      nodes.remove(testRootId);
      nodesByCoordinates.remove(BazelArtifactCoordinates.canonical(testRoot.coordinates));
      runtimeNodeIds.remove(testRootId);
    }

    private void pruneUnreachableNodes(String applicationId) {
      Set<String> reachable = new LinkedHashSet<>();
      Deque<String> pending = new ArrayDeque<>();
      pending.add(applicationId);
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (!reachable.add(id)) {
          continue;
        }
        MutableNode node = nodes.get(id);
        if (node != null) {
          node.edges.values().stream().map(DependencyEdge::targetId).forEach(pending::addLast);
        }
      }
      for (String id : new ArrayList<>(nodes.keySet())) {
        if (reachable.contains(id)) {
          continue;
        }
        MutableNode removed = nodes.remove(id);
        nodesByCoordinates.remove(BazelArtifactCoordinates.canonical(removed.coordinates));
        runtimeNodeIds.remove(id);
      }
    }

    private void markDirectDependencies(String applicationId) {
      MutableNode application = nodes.get(applicationId);
      for (DependencyEdge edge : application.edges.values()) {
        nodes.get(edge.targetId()).direct = true;
      }
      for (String id : runtimeNodeIds) {
        if (applicationId.equals(id)) {
          continue;
        }
        MutableNode node = nodes.get(id);
        node.runtime = node.paths.stream().anyMatch(inputs.runtimeClasspathPaths()::contains);
        node.deployment =
            node.runtime
                || node.paths.stream().anyMatch(inputs.deploymentClasspathPaths()::contains);
        node.compileOnly = !node.runtime && !node.deployment;
      }
    }

    private void markReloadableWorkspaceDependencies(String applicationId) {
      Deque<String> pending =
          new ArrayDeque<>(
              nodes.get(applicationId).edges.values().stream()
                  .map(DependencyEdge::targetId)
                  .toList());
      var visited = new HashSet<String>();
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (!visited.add(id)) {
          continue;
        }
        MutableNode node = nodes.get(id);
        if (node == null || node.workspaceId == null || extensionDeployments.containsKey(id)) {
          continue;
        }
        node.reloadable = true;
        node.edges.values().stream().map(DependencyEdge::targetId).forEach(pending::addLast);
      }
    }

    private void scanExtensionDescriptors() throws IOException {
      for (String id : runtimeNodeIds) {
        MutableNode node = nodes.get(id);
        if (!"jar".equals(node.coordinates.type())) {
          continue;
        }
        String deployment = extensionDeployment(node);
        String runtime = BazelArtifactCoordinates.canonical(node.coordinates);
        ExtensionDescriptor catalogDescriptor = descriptorsByRuntime.get(runtime);
        if (catalogDescriptor != null) {
          String catalogDeployment =
              BazelArtifactCoordinates.canonical(
                  BazelArtifactCoordinates.parse(catalogDescriptor.deploymentArtifact()));
          if (deployment == null) {
            fail(
                "resolver catalog identifies "
                    + runtime
                    + " as an extension but its jar has no descriptor");
          }
          if (!deployment.equals(catalogDeployment)) {
            fail(
                "extension descriptor for "
                    + runtime
                    + " declares "
                    + deployment
                    + " but the resolver catalog declares "
                    + catalogDeployment);
          }
        }
        if (deployment != null) {
          extensionDeployments.put(id, deployment);
        }
      }
    }

    private void activateConditionalDependencies(String applicationId) {
      Set<String> processed = new HashSet<>();
      boolean changed = true;
      while (changed) {
        changed = false;
        Map<String, RuntimeFacts> facts = runtimeFacts(applicationId);
        List<String> extensions = extensionDeployments.keySet().stream().sorted().toList();
        for (String extensionId : extensions) {
          MutableNode extensionNode = nodes.get(extensionId);
          if (extensionNode == null || !extensionNode.runtime) {
            continue;
          }
          ExtensionDescriptor descriptor =
              descriptorsByRuntime.get(
                  BazelArtifactCoordinates.canonical(extensionNode.coordinates));
          if (descriptor == null) {
            continue;
          }
          List<String> candidates = new ArrayList<>(descriptor.conditionalDependencies());
          if (inputs.mode() == Mode.DEV) {
            for (String candidate : descriptor.conditionalDevDependencies()) {
              if (!candidates.contains(candidate)) {
                candidates.add(candidate);
              }
            }
          }
          for (String candidate : candidates) {
            String canonical =
                BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(candidate));
            String declaration = extensionId + "\u0000" + canonical;
            if (processed.contains(declaration)) {
              continue;
            }
            RuntimeFacts inherited = facts.getOrDefault(extensionId, RuntimeFacts.COMPILE);
            if (matchesAny(BazelArtifactCoordinates.parse(candidate), inherited.exclusions())) {
              processed.add(declaration);
              continue;
            }
            MutableNode existing = nodesByCoordinates.get(canonical);
            if (existing != null && existing.runtime) {
              processed.add(declaration);
              continue;
            }
            ExtensionDescriptor candidateDescriptor = descriptorsByRuntime.get(canonical);
            if (candidateDescriptor != null
                && !conditionsSatisfied(candidateDescriptor.dependencyConditions())) {
              continue;
            }
            activateConditionalClosure(extensionId, canonical, inherited);
            processed.add(declaration);
            changed = true;
          }
        }
      }
    }

    private boolean conditionsSatisfied(List<String> conditions) {
      for (String condition : conditions) {
        ArtifactCondition required = ArtifactCondition.parse(condition);
        boolean present =
            runtimeNodeIds.stream()
                .map(nodes::get)
                .filter(java.util.Objects::nonNull)
                .filter(node -> node.runtime)
                .map(node -> ArtifactCondition.fromCoordinates(node.coordinates))
                .anyMatch(required::equals);
        if (!present) {
          return false;
        }
      }
      return true;
    }

    private void activateConditionalClosure(
        String declaringExtensionId, String rootCanonical, RuntimeFacts inherited) {
      ConditionalCatalogNode root = conditionalByCoordinates.get(rootCanonical);
      if (root == null) {
        fail("conditional catalog is missing descriptor-declared artifact " + rootCanonical);
      }
      Deque<ConditionalStep> pending = new ArrayDeque<>();
      pending.add(new ConditionalStep(declaringExtensionId, rootCanonical, true));
      Set<String> expanded = new HashSet<>();
      while (!pending.isEmpty()) {
        ConditionalStep step = pending.removeFirst();
        ConditionalCatalogNode catalogNode = conditionalByCoordinates.get(step.coordinates());
        if (catalogNode == null) {
          fail("conditional graph contains an unresolved edge to " + step.coordinates());
        }
        ArtifactCoordinates coordinates = BazelArtifactCoordinates.parse(catalogNode.coordinate());
        if (matchesAny(coordinates, inherited.exclusions())) {
          continue;
        }
        MutableNode node = nodesByCoordinates.get(step.coordinates());
        boolean existingRuntime = node != null && node.runtime;
        if (node == null) {
          String path = inputs.conditionalPaths().get(catalogNode.repoPath());
          if (isBlank(path)) {
            fail(
                "no action input path was supplied for conditional artifact "
                    + step.coordinates()
                    + " (catalog path "
                    + catalogNode.repoPath()
                    + ")");
          }
          node =
              new MutableNode(
                  conditionalId(coordinates),
                  NodeKind.MAVEN,
                  coordinates,
                  List.of(path),
                  null,
                  null);
          node.runtime = true;
          node.deployment = true;
          addNode(node);
          runtimeNodeIds.add(node.id);
        } else {
          if (!existingRuntime) {
            // A conditional candidate can already have been materialized while wiring a
            // deployment closure. Its deployment-side outgoing edges are not its runtime Maven
            // graph and commonly point back to the declaring extension. Rebuild those edges from
            // the conditional resolver catalog before promoting it to the runtime graph.
            node.kind = NodeKind.MAVEN;
            node.edges.clear();
          }
          node.runtime = true;
          node.deployment = true;
          node.compileOnly = false;
        }
        ExtensionDescriptor descriptor = descriptorsByRuntime.get(step.coordinates());
        if (descriptor != null) {
          String deployment =
              BazelArtifactCoordinates.canonical(
                  BazelArtifactCoordinates.parse(descriptor.deploymentArtifact()));
          extensionDeployments.putIfAbsent(node.id, deployment);
          if (step.injectionEligible() && !existingRuntime) {
            conditionalInjectionParents.putIfAbsent(node.id, step.parentId());
          }
        }
        MutableNode parent = nodes.get(step.parentId());
        if (!reaches(node.id, parent.id)) {
          parent.addEdge(
              new DependencyEdge(
                  node.id,
                  DependencyRelation.DEPS,
                  inherited.scope(),
                  inherited.optional(),
                  inherited.exclusions()));
        }
        if (existingRuntime) {
          continue;
        }
        if (!expanded.add(step.coordinates())) {
          continue;
        }
        boolean childInjectionEligible = step.injectionEligible() && descriptor == null;
        for (String dependency : catalogNode.dependencies()) {
          ArtifactCoordinates dependencyCoordinates = BazelArtifactCoordinates.parse(dependency);
          String dependencyCanonical = BazelArtifactCoordinates.canonical(dependencyCoordinates);
          if (!matchesAny(dependencyCoordinates, inherited.exclusions())) {
            pending.addLast(
                new ConditionalStep(node.id, dependencyCanonical, childInjectionEligible));
          }
        }
      }
    }

    private boolean reaches(String sourceId, String targetId) {
      Deque<String> pending = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      pending.add(sourceId);
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (!visited.add(id)) {
          continue;
        }
        if (targetId.equals(id)) {
          return true;
        }
        MutableNode node = nodes.get(id);
        if (node != null) {
          node.edges.values().stream().map(DependencyEdge::targetId).forEach(pending::addLast);
        }
      }
      return false;
    }

    private Map<String, RuntimeFacts> runtimeFacts(String applicationId) {
      Map<String, RuntimeFacts> result = new LinkedHashMap<>();
      result.put(applicationId, RuntimeFacts.COMPILE);
      Deque<String> pending = new ArrayDeque<>();
      pending.add(applicationId);
      while (!pending.isEmpty()) {
        String parentId = pending.removeFirst();
        MutableNode parent = nodes.get(parentId);
        RuntimeFacts parentFacts = result.get(parentId);
        for (DependencyEdge edge : parent.edges.values()) {
          MutableNode child = nodes.get(edge.targetId());
          if (child == null || !child.runtime || result.containsKey(child.id)) {
            continue;
          }
          var exclusions =
              new ArrayList<BazelApplicationModel.ArtifactKey>(parentFacts.exclusions());
          exclusions.addAll(edge.exclusions());
          result.put(
              child.id, new RuntimeFacts(edge.scope(), edge.optional(), List.copyOf(exclusions)));
          pending.addLast(child.id);
        }
      }
      return result;
    }

    private static boolean matchesAny(
        ArtifactCoordinates coordinates, List<BazelApplicationModel.ArtifactKey> exclusions) {
      return exclusions.stream()
          .anyMatch(
              exclusion ->
                  exclusion.groupId().equals(coordinates.groupId())
                      && exclusion.artifactId().equals(coordinates.artifactId()));
    }

    private record ConditionalStep(
        String parentId, String coordinates, boolean injectionEligible) {}

    private record RuntimeFacts(
        DependencyScope scope,
        boolean optional,
        List<BazelApplicationModel.ArtifactKey> exclusions) {

      private static final RuntimeFacts COMPILE =
          new RuntimeFacts(DependencyScope.COMPILE, false, List.of());
    }

    private record ArtifactCondition(
        String groupId, String artifactId, String classifier, String type) {

      private static ArtifactCondition parse(String value) {
        String[] parts = value.split(":", -1);
        return new ArtifactCondition(
            parts[0],
            parts[1],
            parts.length > 2 ? parts[2] : "",
            parts.length > 3 ? parts[3] : "jar");
      }

      private static ArtifactCondition fromCoordinates(ArtifactCoordinates coordinates) {
        return new ArtifactCondition(
            coordinates.groupId(),
            coordinates.artifactId(),
            coordinates.classifier(),
            coordinates.type());
      }
    }

    private String extensionDeployment(MutableNode node) throws IOException {
      String result = null;
      for (String rawPath : node.paths) {
        if (rawPath.endsWith(".jar")) {
          Optional<Properties> descriptor = readDescriptor(Path.of(rawPath));
          if (descriptor.isPresent()) {
            String deployment = descriptor.orElseThrow().getProperty(DEPLOYMENT_ARTIFACT);
            if (isBlank(deployment)) {
              fail("Quarkus extension descriptor in " + rawPath + " has no deployment-artifact");
            }
            String candidate =
                BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(deployment));
            if (result != null && !result.equals(candidate)) {
              fail(
                  "node "
                      + node.id
                      + " resolves to conflicting extension deployment artifacts "
                      + result
                      + " and "
                      + candidate);
            }
            result = candidate;
          }
        }
      }
      return result;
    }

    private void injectDeploymentGraphs(String applicationId) {
      Map<String, String> topLevel = topLevelExtensionParents(applicationId);
      Map<String, String> injectionPoints = new LinkedHashMap<>(topLevel);
      conditionalInjectionParents.forEach(injectionPoints::putIfAbsent);
      for (Map.Entry<String, String> injection : injectionPoints.entrySet()) {
        String runtimeId = injection.getKey();
        nodes.get(runtimeId).topLevelRuntimeExtension = topLevel.containsKey(runtimeId);
        String deploymentCoordinates = extensionDeployments.get(runtimeId);
        if (!deploymentByCoordinates.containsKey(deploymentCoordinates)
            && !localDeployments.containsKey(deploymentCoordinates)) {
          fail(
              "deployment catalog is missing descriptor-declared artifact "
                  + deploymentCoordinates
                  + " for runtime extension "
                  + runtimeId);
        }
        String parentId = injection.getValue();
        String deploymentId =
            localDeployments.containsKey(deploymentCoordinates)
                ? localDeployments.get(deploymentCoordinates)
                : addDeploymentClosure(deploymentCoordinates, parentId);
        // The deployment artifact may already be present in the runtime graph (notably the
        // Quarkus test stack). Its edge to the runtime extension is the reinsertion edge; do not
        // add a self-dependency.
        if (parentId.equals(deploymentId)) {
          continue;
        }
        MutableNode parent = nodes.get(parentId);
        for (DependencyEdge original :
            parent.edges.values().stream()
                .filter(edge -> runtimeId.equals(edge.targetId()))
                .toList()) {
          parent.addEdge(
              new DependencyEdge(
                  deploymentId,
                  DependencyRelation.DEPLOYMENT,
                  original.scope(),
                  original.optional(),
                  original.exclusions()));
        }
      }
    }

    private Map<String, String> topLevelExtensionParents(String applicationId) {
      var result = new LinkedHashMap<String, String>();
      var visited = new HashSet<String>();
      Deque<TraversalStep> pending = new ArrayDeque<>();
      for (DependencyEdge edge : nodes.get(applicationId).edges.values()) {
        pending.addLast(new TraversalStep(applicationId, edge.targetId(), true));
      }
      while (!pending.isEmpty()) {
        TraversalStep step = pending.removeFirst();
        String id = step.targetId();
        if (!visited.add(id)) {
          continue;
        }
        boolean extension = extensionDeployments.containsKey(id);
        if (extension && step.topLevelEligible()) {
          result.put(id, step.parentId());
        }
        // Quarkus clears the top-level/injection-point walking flags at the first extension but
        // still visits its children. That visit is essential for conflict mediation: a node first
        // reached below a compile extension must not later become top-level through a test branch.
        boolean childEligible = step.topLevelEligible() && !extension;
        for (DependencyEdge edge : nodes.get(id).edges.values()) {
          pending.addLast(new TraversalStep(id, edge.targetId(), childEligible));
        }
      }
      return result;
    }

    private record TraversalStep(String parentId, String targetId, boolean topLevelEligible) {}

    private String addDeploymentClosure(String rootCoordinates, String reinsertionParentId) {
      Deque<String> pending = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      pending.add(rootCoordinates);
      while (!pending.isEmpty()) {
        String canonical = pending.removeFirst();
        if (!visited.add(canonical)) {
          continue;
        }
        DeploymentCatalogNode catalogNode = deploymentByCoordinates.get(canonical);
        if (catalogNode == null) {
          fail("deployment graph contains an unresolved edge to " + canonical);
        }
        MutableNode node = nodesByCoordinates.get(canonical);
        if (node == null) {
          ArtifactCoordinates coordinates =
              BazelArtifactCoordinates.parse(catalogNode.coordinate());
          String path = inputs.deploymentPaths().get(catalogNode.repoPath());
          if (isBlank(path)) {
            fail(
                "no action input path was supplied for deployment artifact "
                    + canonical
                    + " (catalog path "
                    + catalogNode.repoPath()
                    + ")");
          }
          node =
              new MutableNode(
                  deploymentId(coordinates),
                  NodeKind.DEPLOYMENT,
                  coordinates,
                  List.of(path),
                  null,
                  null);
          node.deployment = true;
          addNode(node);
        } else {
          node.deployment = true;
        }
        for (String dependency : catalogNode.dependencies()) {
          ArtifactCoordinates targetCoordinates = BazelArtifactCoordinates.parse(dependency);
          String targetCanonical = BazelArtifactCoordinates.canonical(targetCoordinates);
          DeploymentCatalogNode targetCatalog = deploymentByCoordinates.get(targetCanonical);
          if (targetCatalog == null) {
            fail("deployment graph contains an unresolved edge to " + targetCanonical);
          }
          MutableNode target = nodesByCoordinates.get(targetCanonical);
          String targetId = target == null ? deploymentId(targetCoordinates) : target.id;
          if (!targetId.equals(reinsertionParentId)
              && !reaches(targetId, reinsertionParentId)
              && !reaches(targetId, node.id)) {
            node.addEdge(
                new DependencyEdge(
                    targetId,
                    DependencyRelation.DEPLOYMENT,
                    DependencyScope.COMPILE,
                    false,
                    List.of()));
          }
          pending.addLast(targetCanonical);
        }
      }
      return nodesByCoordinates.get(rootCoordinates).id;
    }

    private BazelApplicationModel materialize(String applicationId) throws IOException {
      List<Node> materializedNodes =
          nodes.values().stream()
              .map(MutableNode::materialize)
              .sorted(Comparator.comparing(Node::id))
              .toList();
      List<WorkspaceModule> workspaces =
          fragments.values().stream()
              .filter(
                  fragment -> {
                    MutableNode node = nodes.get(fragment.targetId());
                    return node != null && node.workspaceId != null;
                  })
              .map(this::workspaceModule)
              .sorted(Comparator.comparing(WorkspaceModule::id))
              .toList();
      return new BazelApplicationModel(
          BazelApplicationModel.SCHEMA_VERSION,
          new Producer("rules_quarkus", inputs.producerVersion()),
          inputs.quarkusVersion(),
          inputs.mode(),
          applicationId,
          materializedNodes,
          workspaces,
          materializePlatform());
    }

    private Platform materializePlatform() throws IOException {
      var properties = new LinkedHashMap<String, String>();
      var releases = new LinkedHashMap<String, PlatformRelease>();
      for (String repoPath : inputs.platformCatalog().propertyFiles()) {
        String actionPath = inputs.platformPropertyPaths().get(repoPath);
        if (actionPath == null) {
          fail("platform properties file " + repoPath + " has no action input mapping");
        }
        Properties loaded = new Properties();
        try (InputStream input = java.nio.file.Files.newInputStream(Path.of(actionPath))) {
          loaded.load(input);
        }
        for (String name : loaded.stringPropertyNames()) {
          collectPlatformProperty(name, loaded.getProperty(name), properties, releases, false);
        }
      }
      for (Map.Entry<String, String> property : inputs.platformCatalog().properties().entrySet()) {
        collectPlatformProperty(property.getKey(), property.getValue(), properties, releases, true);
      }
      return new Platform(
          inputs.platformCatalog().imports(),
          properties,
          releases.values().stream()
              .sorted(
                  Comparator.comparing(PlatformRelease::platformKey)
                      .thenComparing(PlatformRelease::stream)
                      .thenComparing(PlatformRelease::version))
              .toList());
    }

    private static void collectPlatformProperty(
        String name,
        String value,
        Map<String, String> properties,
        Map<String, PlatformRelease> releases,
        boolean override) {
      if (!name.startsWith("platform.")) {
        return;
      }
      final String releasePrefix = "platform.release-info@";
      if (!name.startsWith(releasePrefix)) {
        if (override) {
          properties.put(name, value);
        } else {
          properties.putIfAbsent(name, value);
        }
        return;
      }
      int streamSeparator = name.indexOf('$', releasePrefix.length());
      int versionSeparator = name.indexOf('#', streamSeparator + 1);
      if (streamSeparator < 0
          || versionSeparator < 0
          || streamSeparator == releasePrefix.length()
          || versionSeparator == streamSeparator + 1
          || versionSeparator == name.length() - 1) {
        fail("invalid Quarkus platform release property '" + name + "'");
      }
      List<ArtifactCoordinates> memberBoms =
          value.isBlank()
              ? List.of()
              : java.util.Arrays.stream(value.split(",", -1))
                  .map(String::trim)
                  .map(BazelArtifactCoordinates::parse)
                  .toList();
      PlatformRelease release =
          new PlatformRelease(
              name.substring(releasePrefix.length(), streamSeparator),
              name.substring(streamSeparator + 1, versionSeparator),
              name.substring(versionSeparator + 1),
              memberBoms);
      String key =
          release.platformKey() + "\u0000" + release.stream() + "\u0000" + release.version();
      PlatformRelease previous =
          override ? releases.put(key, release) : releases.putIfAbsent(key, release);
      if (!override && previous != null && !previous.equals(release)) {
        fail("conflicting Quarkus platform release property '" + name + "'");
      }
    }

    private WorkspaceModule workspaceModule(TargetFragment fragment) {
      MutableNode node = nodes.get(fragment.targetId());
      TargetFragment sourceFragment = workspaceSourceFragment(fragment);
      List<String> sourceDirectories = parentDirectories(sourceFragment.sources(), true);
      List<String> resourceDirectories = parentDirectories(sourceFragment.resources(), true);
      List<String> generatedSources = parentDirectories(sourceFragment.sources(), false);
      List<String> generatedResources = parentDirectories(sourceFragment.resources(), false);
      TargetFragment outputFragment =
          fragment.outputDirectories().isEmpty() ? sourceFragment : fragment;
      List<String> outputs =
          outputFragment.outputDirectories().stream().map(FileReference::path).sorted().toList();
      SourceSet main =
          new SourceSet(
              "",
              sourceDirectories,
              resourceDirectories,
              outputs,
              generatedSources,
              generatedResources);
      List<SourceSet> sourceSets = new ArrayList<>();
      sourceSets.add(main);
      if (node.kind == NodeKind.APPLICATION
          && inputs.mode() == Mode.TEST
          && testSourceFragment != null) {
        List<String> testOutputs =
            testSourceFragment.outputDirectories().stream()
                .map(FileReference::path)
                .sorted()
                .toList();
        sourceSets.add(
            new SourceSet(
                "tests",
                parentDirectories(testSourceFragment.sources(), true),
                parentDirectories(testSourceFragment.resources(), true),
                testOutputs,
                parentDirectories(testSourceFragment.sources(), false),
                parentDirectories(testSourceFragment.resources(), false)));
      }
      return new WorkspaceModule(
          fragment.targetId(),
          fragment.bazelLabel(),
          sourceFragment.packageName().isEmpty() ? "." : sourceFragment.packageName(),
          outputs.isEmpty() ? "." : parent(outputs.get(0)),
          sourceFragment.buildFile(),
          sourceSets,
          node.declaredEdges.values().stream()
              .map(DependencyEdge::targetId)
              .distinct()
              .sorted()
              .toList(),
          List.of(),
          List.of(),
          List.of(),
          null);
    }

    private TargetFragment workspaceSourceFragment(TargetFragment fragment) {
      for (Map.Entry<String, String> alias : localRuntimeAliases.entrySet()) {
        if (fragment.targetId().equals(alias.getValue())) {
          TargetFragment rawRuntime = fragments.get(alias.getKey());
          if (rawRuntime == null) {
            fail("local runtime alias source fragment is missing: " + alias.getKey());
          }
          return rawRuntime;
        }
      }
      return fragment;
    }

    private static DependencyRelation relation(TargetEdge edge) {
      return switch (edge.relation()) {
        case "deps" -> DependencyRelation.DEPS;
        case "exports" -> DependencyRelation.EXPORTS;
        case "runtime_deps" -> DependencyRelation.RUNTIME_DEPS;
        default -> throw new BazelApplicationModelException(
            "Unsupported Bazel dependency relation '" + edge.relation() + "'");
      };
    }

    private void addNode(MutableNode node) {
      if (nodes.putIfAbsent(node.id, node) != null) {
        fail("duplicate model node id " + node.id);
      }
      String canonical = BazelArtifactCoordinates.canonical(node.coordinates);
      MutableNode previous = nodesByCoordinates.putIfAbsent(canonical, node);
      if (previous != null) {
        fail("coordinates " + canonical + " map to both " + previous.id + " and " + node.id);
      }
    }

    private void replaceCoordinates(MutableNode node, ArtifactCoordinates coordinates) {
      nodesByCoordinates.remove(BazelArtifactCoordinates.canonical(node.coordinates));
      node.coordinates = coordinates;
      MutableNode collision =
          nodesByCoordinates.putIfAbsent(BazelArtifactCoordinates.canonical(coordinates), node);
      if (collision != null) {
        fail("application coordinate override collides with " + collision.id);
      }
    }
  }

  private record Reachability(Set<String> runtime, Set<String> deployment) {}

  private static final class MutableNode {
    private final String id;
    private NodeKind kind;
    private ArtifactCoordinates coordinates;
    private final List<String> paths;
    private final Map<String, DependencyEdge> edges = new LinkedHashMap<>();
    private final Map<String, DependencyEdge> declaredEdges = new LinkedHashMap<>();
    private final String workspaceId;
    private final String bazelLabel;
    private boolean direct;
    private boolean runtime;
    private boolean deployment;
    private boolean compileOnly;
    private boolean reloadable;
    private boolean topLevelRuntimeExtension;

    private MutableNode(
        String id,
        NodeKind kind,
        ArtifactCoordinates coordinates,
        List<String> paths,
        String workspaceId,
        String bazelLabel) {
      this.id = id;
      this.kind = kind;
      this.coordinates = coordinates;
      this.paths = List.copyOf(paths);
      this.workspaceId = workspaceId;
      this.bazelLabel = bazelLabel;
    }

    private void addEdge(DependencyEdge edge) {
      if (edge.relation() == DependencyRelation.DEPLOYMENT
          && edges.values().stream()
              .anyMatch(existing -> existing.targetId().equals(edge.targetId()))) {
        return;
      }
      if (edge.relation() != DependencyRelation.DEPLOYMENT) {
        edges
            .entrySet()
            .removeIf(
                entry ->
                    entry.getValue().targetId().equals(edge.targetId())
                        && entry.getValue().relation() == DependencyRelation.DEPLOYMENT);
      }
      edges.putIfAbsent(edgeKey(edge), edge);
    }

    private void addDeclaredEdge(DependencyEdge edge) {
      if (edge.relation() == DependencyRelation.DEPLOYMENT) {
        throw new IllegalArgumentException(
            "deployment injection cannot be a declared workspace edge");
      }
      declaredEdges.putIfAbsent(edgeKey(edge), edge);
    }

    private static String edgeKey(DependencyEdge edge) {
      return edge.targetId()
          + "\u0000"
          + edge.relation()
          + "\u0000"
          + edge.scope()
          + "\u0000"
          + edge.optional()
          + "\u0000"
          + edge.exclusions();
    }

    private Node materialize() {
      return new Node(
          id,
          kind,
          coordinates,
          paths,
          edges.values().stream().sorted(Comparator.comparing(DependencyEdge::targetId)).toList(),
          new ClasspathFacts(
              direct,
              runtime,
              deployment,
              compileOnly,
              false,
              reloadable,
              topLevelRuntimeExtension),
          workspaceId,
          bazelLabel);
    }
  }

  private static Optional<Properties> readDescriptor(Path path) throws IOException {
    try (JarFile jar = new JarFile(path.toFile())) {
      var entry = jar.getJarEntry(DESCRIPTOR);
      if (entry == null) {
        return Optional.empty();
      }
      Properties properties = new Properties();
      try (InputStream input = jar.getInputStream(entry)) {
        properties.load(input);
      }
      return Optional.of(properties);
    }
  }

  private static ArtifactCoordinates workspaceCoordinates(TargetFragment fragment) {
    String artifact =
        fragment
            .targetId()
            .replaceFirst("^@@?//", "")
            .replaceAll("[^A-Za-z0-9_.-]", "-")
            .replaceFirst("^-+", "");
    if (artifact.isBlank()) {
      artifact = fragment.targetName();
    }
    return new ArtifactCoordinates("bazel.workspace", artifact, "", "jar", "1.0-SNAPSHOT");
  }

  private static String deploymentId(ArtifactCoordinates coordinates) {
    return "deployment:" + BazelArtifactCoordinates.canonical(coordinates);
  }

  private static String conditionalId(ArtifactCoordinates coordinates) {
    return "conditional:" + BazelArtifactCoordinates.canonical(coordinates);
  }

  private static List<String> parentDirectories(List<FileReference> files, boolean source) {
    return files.stream()
        .filter(file -> file.source() == source)
        .map(FileReference::path)
        .map(BazelApplicationModelAssembler::sourceRoot)
        .distinct()
        .sorted()
        .toList();
  }

  private static String sourceRoot(String path) {
    String normalized = path.replace('\\', '/');
    String[] markers = {
      "/src/main/java/", "/src/main/resources/", "/src/test/java/", "/src/test/resources/"
    };
    for (String marker : markers) {
      int markerIndex = normalized.indexOf(marker);
      if (markerIndex >= 0) {
        return normalized.substring(0, markerIndex + marker.length() - 1);
      }
      String rootMarker = marker.substring(1);
      if (normalized.startsWith(rootMarker)) {
        return rootMarker.substring(0, rootMarker.length() - 1);
      }
    }
    return parent(normalized);
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "." : path.substring(0, slash);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireNonBlank(String value, String description) {
    require(!isBlank(value), description + " must not be blank");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      fail(message);
    }
  }

  private static void fail(String message) {
    throw new BazelApplicationModelException("Cannot assemble Bazel application model: " + message);
  }
}
