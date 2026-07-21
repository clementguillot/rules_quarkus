package com.clementguillot.quarkifier.model.transport;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactKey;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.NodeKind;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.SourceSet;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.WorkspaceModule;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural and semantic validation for the Bazel application-model transport. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.GodClass", "PMD.TooManyMethods"})
public final class BazelApplicationModelValidator {

  private BazelApplicationModelValidator() {}

  public static void validate(BazelApplicationModel model) {
    if (model == null) {
      throw problem("$", "model must not be null");
    }
    if (!BazelApplicationModel.SCHEMA_VERSION.equals(model.schemaVersion())) {
      throw problem(
          "$.schemaVersion",
          "unsupported schema version '"
              + model.schemaVersion()
              + "' (expected '"
              + BazelApplicationModel.SCHEMA_VERSION
              + "')");
    }
    requireNonBlank(model.producer().name(), "$.producer.name");
    requireNonBlank(model.producer().version(), "$.producer.version");
    requireNonBlank(model.quarkusVersion(), "$.quarkusVersion");
    requireNonBlank(model.applicationId(), "$.applicationId");
    if (model.mode() == null) {
      throw problem("$.mode", "must not be null");
    }
    if (model.nodes().isEmpty()) {
      throw problem("$.nodes", "must contain the application node");
    }

    Map<String, Node> nodes = indexNodes(model.nodes());
    Node application = nodes.get(model.applicationId());
    if (application == null) {
      throw problem("$.applicationId", "does not identify a node");
    }
    long applicationCount =
        model.nodes().stream().filter(node -> node.kind() == NodeKind.APPLICATION).count();
    if (applicationCount != 1 || application.kind() != NodeKind.APPLICATION) {
      throw problem("$.nodes", "must contain exactly one application node matching applicationId");
    }

    Map<String, WorkspaceModule> workspaceModules = indexWorkspaceModules(model.workspaceModules());
    validateNodes(model.nodes(), nodes, workspaceModules, application);
    validateWorkspaceModules(model.workspaceModules(), nodes, workspaceModules);
    validateReachability(application, nodes);
    validateAcyclic(nodes);
    validatePlatform(model);
  }

  private static Map<String, Node> indexNodes(List<Node> values) {
    var result = new LinkedHashMap<String, Node>();
    var coordinates = new HashSet<ArtifactCoordinates>();
    for (int index = 0; index < values.size(); index++) {
      Node node = values.get(index);
      String path = "$.nodes[" + index + "]";
      if (node == null) {
        throw problem(path, "must not be null");
      }
      requireNonBlank(node.id(), path + ".id");
      if (result.putIfAbsent(node.id(), node) != null) {
        throw problem(path + ".id", "duplicate node id '" + node.id() + "'");
      }
      validateCoordinates(node.coordinates(), path + ".coordinates");
      if (!coordinates.add(node.coordinates())) {
        throw problem(path + ".coordinates", "duplicate artifact coordinates");
      }
    }
    return result;
  }

  private static Map<String, WorkspaceModule> indexWorkspaceModules(List<WorkspaceModule> values) {
    var result = new LinkedHashMap<String, WorkspaceModule>();
    for (int index = 0; index < values.size(); index++) {
      WorkspaceModule module = values.get(index);
      String path = "$.workspaceModules[" + index + "]";
      if (module == null) {
        throw problem(path, "must not be null");
      }
      requireNonBlank(module.id(), path + ".id");
      if (result.putIfAbsent(module.id(), module) != null) {
        throw problem(path + ".id", "duplicate workspace module id '" + module.id() + "'");
      }
    }
    return result;
  }

  private static void validateNodes(
      List<Node> values,
      Map<String, Node> nodes,
      Map<String, WorkspaceModule> modules,
      Node application) {
    for (int index = 0; index < values.size(); index++) {
      Node node = values.get(index);
      String path = "$.nodes[" + index + "]";
      if (node.kind() == null) {
        throw problem(path + ".kind", "must not be null");
      }
      requireUniqueNonBlank(node.paths(), path + ".paths", false);
      validateEdges(node, nodes, path);
      if (node.classpath() == null) {
        throw problem(path + ".classpath", "must not be null");
      }
      if (node.classpath().compileOnly() && node.classpath().runtimeClasspath()) {
        throw problem(path + ".classpath", "compileOnly and runtimeClasspath cannot both be true");
      }
      if (node != application
          && !node.classpath().runtimeClasspath()
          && !node.classpath().deploymentClasspath()
          && !node.classpath().compileOnly()) {
        throw problem(path + ".classpath", "dependency is on no classpath");
      }
      if (node == application
          && (node.classpath().directFromApplication() || node.classpath().optional())) {
        throw problem(path + ".classpath", "application node cannot be direct or optional");
      }
      if (node.workspaceModuleId() != null) {
        requireNonBlank(node.workspaceModuleId(), path + ".workspaceModuleId");
        if (!modules.containsKey(node.workspaceModuleId())) {
          throw problem(path + ".workspaceModuleId", "does not identify a workspace module");
        }
      }
      if (node.kind() == NodeKind.WORKSPACE && node.workspaceModuleId() == null) {
        throw problem(path + ".workspaceModuleId", "workspace nodes require workspace metadata");
      }
      if (node.classpath().reloadable()
          && (node.workspaceModuleId() == null || node == application)) {
        throw problem(
            path + ".classpath.reloadable",
            "only non-application workspace dependencies can be reloadable");
      }
      if (node.kind() == NodeKind.WORKSPACE && node.bazelLabel() == null) {
        throw problem(path + ".bazelLabel", "workspace nodes require a Bazel label");
      }
      if (node.bazelLabel() != null) {
        requireNonBlank(node.bazelLabel(), path + ".bazelLabel");
      }
    }
  }

  private static void validateEdges(Node node, Map<String, Node> nodes, String path) {
    var edges = new HashSet<String>();
    for (int index = 0; index < node.dependencies().size(); index++) {
      DependencyEdge edge = node.dependencies().get(index);
      String edgePath = path + ".dependencies[" + index + "]";
      if (edge == null) {
        throw problem(edgePath, "must not be null");
      }
      requireNonBlank(edge.targetId(), edgePath + ".targetId");
      if (!nodes.containsKey(edge.targetId())) {
        throw problem(
            edgePath + ".targetId", "does not identify a node: '" + edge.targetId() + "'");
      }
      if (node.id().equals(edge.targetId())) {
        throw problem(edgePath + ".targetId", "node '" + node.id() + "' cannot depend on itself");
      }
      if (edge.relation() == null) {
        throw problem(edgePath + ".relation", "must not be null");
      }
      String edgeKey = edge.targetId() + "\u0000" + edge.relation() + "\u0000" + edge.scope();
      if (!edges.add(edgeKey)) {
        throw problem(edgePath, "duplicate dependency edge");
      }
      if (edge.scope() == null) {
        throw problem(edgePath + ".scope", "must not be null");
      }
      validateArtifactKeys(edge.exclusions(), edgePath + ".exclusions");
    }
  }

  private static void validateWorkspaceModules(
      List<WorkspaceModule> values, Map<String, Node> nodes, Map<String, WorkspaceModule> modules) {
    Set<String> referenced = new HashSet<>();
    nodes.values().stream()
        .map(Node::workspaceModuleId)
        .filter(id -> id != null)
        .forEach(referenced::add);
    for (int index = 0; index < values.size(); index++) {
      WorkspaceModule module = values.get(index);
      String path = "$.workspaceModules[" + index + "]";
      requireNonBlank(module.bazelLabel(), path + ".bazelLabel");
      requireNonBlank(module.moduleDir(), path + ".moduleDir");
      requireNonBlank(module.buildDir(), path + ".buildDir");
      requireNonBlank(module.buildFile(), path + ".buildFile");
      requireUniqueNonBlank(module.directDependencyIds(), path + ".directDependencyIds", true);
      for (String dependencyId : module.directDependencyIds()) {
        if (!nodes.containsKey(dependencyId)) {
          throw problem(path + ".directDependencyIds", "unknown node id '" + dependencyId + "'");
        }
      }
      validateArtifactKeys(module.dependencyConstraints(), path + ".dependencyConstraints");
      validateArtifactKeys(module.testClasspathExclusions(), path + ".testClasspathExclusions");
      requireUniqueNonBlank(
          module.additionalTestClasspathElements(),
          path + ".additionalTestClasspathElements",
          true);
      validateSourceSets(module.sourceSets(), path);
      if (module.parentId() != null) {
        requireNonBlank(module.parentId(), path + ".parentId");
        if (module.id().equals(module.parentId()) || !modules.containsKey(module.parentId())) {
          throw problem(path + ".parentId", "must identify a different workspace module");
        }
      }
      if (!referenced.contains(module.id())) {
        throw problem(path + ".id", "workspace module is not referenced by any node");
      }
    }
  }

  private static void validateSourceSets(List<SourceSet> values, String modulePath) {
    var classifiers = new HashSet<String>();
    for (int index = 0; index < values.size(); index++) {
      SourceSet sourceSet = values.get(index);
      String path = modulePath + ".sourceSets[" + index + "]";
      if (sourceSet == null) {
        throw problem(path, "must not be null");
      }
      if (sourceSet.classifier() == null) {
        throw problem(path + ".classifier", "must not be null");
      }
      if (!classifiers.add(sourceSet.classifier())) {
        throw problem(path + ".classifier", "duplicate source-set classifier");
      }
      requireUniqueNonBlank(sourceSet.sourceDirectories(), path + ".sourceDirectories", true);
      requireUniqueNonBlank(sourceSet.resourceDirectories(), path + ".resourceDirectories", true);
      requireUniqueNonBlank(sourceSet.outputDirectories(), path + ".outputDirectories", true);
      requireUniqueNonBlank(
          sourceSet.generatedSourceDirectories(), path + ".generatedSourceDirectories", true);
      requireUniqueNonBlank(
          sourceSet.generatedResourceDirectories(), path + ".generatedResourceDirectories", true);
    }
  }

  private static void validateReachability(Node application, Map<String, Node> nodes) {
    var reachable = new HashSet<String>();
    var pending = new ArrayDeque<String>();
    pending.add(application.id());
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      if (!reachable.add(id)) {
        continue;
      }
      for (DependencyEdge edge : nodes.get(id).dependencies()) {
        pending.addLast(edge.targetId());
      }
    }
    if (reachable.size() != nodes.size()) {
      var orphaned = new LinkedHashSet<>(nodes.keySet());
      orphaned.removeAll(reachable);
      throw problem("$.nodes", "nodes are unreachable from the application: " + orphaned);
    }
  }

  private static void validateAcyclic(Map<String, Node> nodes) {
    var state = new HashMap<String, VisitState>();
    var stack = new ArrayDeque<String>();
    for (String id : nodes.keySet()) {
      visit(id, nodes, state, stack);
    }
  }

  private static void visit(
      String id, Map<String, Node> nodes, Map<String, VisitState> state, Deque<String> stack) {
    VisitState current = state.get(id);
    if (current == VisitState.VISITED) {
      return;
    }
    if (current == VisitState.VISITING) {
      stack.addLast(id);
      throw problem("$.nodes", "dependency cycle detected: " + String.join(" -> ", stack));
    }
    state.put(id, VisitState.VISITING);
    stack.addLast(id);
    for (DependencyEdge edge : nodes.get(id).dependencies()) {
      visit(edge.targetId(), nodes, state, stack);
    }
    stack.removeLast();
    state.put(id, VisitState.VISITED);
  }

  private static void validatePlatform(BazelApplicationModel model) {
    if (model.platform() == null) {
      throw problem("$.platform", "must not be null");
    }
    var imports = new HashSet<ArtifactCoordinates>();
    for (int index = 0; index < model.platform().imports().size(); index++) {
      ArtifactCoordinates coordinates = model.platform().imports().get(index);
      String path = "$.platform.imports[" + index + "]";
      validateCoordinates(coordinates, path);
      if (!"pom".equals(coordinates.type()) || !coordinates.classifier().isEmpty()) {
        throw problem(path, "platform imports must be unclassified POMs");
      }
      if (!imports.add(coordinates)) {
        throw problem(path, "duplicate platform import");
      }
    }
    model
        .platform()
        .properties()
        .forEach(
            (key, value) -> {
              requireNonBlank(key, "$.platform.properties key");
              if (value == null) {
                throw problem("$.platform.properties." + key, "must not be null");
              }
            });
    var releases = new HashSet<String>();
    for (int index = 0; index < model.platform().releases().size(); index++) {
      var release = model.platform().releases().get(index);
      String path = "$.platform.releases[" + index + "]";
      requireNonBlank(release.platformKey(), path + ".platformKey");
      requireNonBlank(release.stream(), path + ".stream");
      requireNonBlank(release.version(), path + ".version");
      String key =
          release.platformKey() + "\u0000" + release.stream() + "\u0000" + release.version();
      if (!releases.add(key)) {
        throw problem(path, "duplicate platform release");
      }
      var memberBoms = new HashSet<ArtifactCoordinates>();
      for (int bomIndex = 0; bomIndex < release.memberBoms().size(); bomIndex++) {
        ArtifactCoordinates coordinates = release.memberBoms().get(bomIndex);
        String bomPath = path + ".memberBoms[" + bomIndex + "]";
        validateCoordinates(coordinates, bomPath);
        if (!"pom".equals(coordinates.type()) || !coordinates.classifier().isEmpty()) {
          throw problem(bomPath, "platform member BOMs must be unclassified POMs");
        }
        if (!memberBoms.add(coordinates)) {
          throw problem(bomPath, "duplicate platform member BOM");
        }
      }
    }
  }

  private static void validateCoordinates(ArtifactCoordinates value, String path) {
    if (value == null) {
      throw problem(path, "must not be null");
    }
    requireNonBlank(value.groupId(), path + ".groupId");
    requireNonBlank(value.artifactId(), path + ".artifactId");
    if (value.classifier() == null) {
      throw problem(path + ".classifier", "must not be null; use the empty string");
    }
    requireNonBlank(value.type(), path + ".type");
    requireNonBlank(value.version(), path + ".version");
  }

  private static void validateArtifactKeys(List<ArtifactKey> values, String path) {
    var unique = new HashSet<ArtifactKey>();
    for (int index = 0; index < values.size(); index++) {
      ArtifactKey key = values.get(index);
      String keyPath = path + "[" + index + "]";
      if (key == null) {
        throw problem(keyPath, "must not be null");
      }
      requireNonBlank(key.groupId(), keyPath + ".groupId");
      requireNonBlank(key.artifactId(), keyPath + ".artifactId");
      if (!unique.add(key)) {
        throw problem(keyPath, "duplicate artifact key");
      }
    }
  }

  private static void requireUniqueNonBlank(List<String> values, String path, boolean allowEmpty) {
    if (values == null) {
      throw problem(path, "must not be null");
    }
    if (!allowEmpty && values.isEmpty()) {
      throw problem(path, "must not be empty");
    }
    var unique = new HashSet<String>();
    for (int index = 0; index < values.size(); index++) {
      String value = values.get(index);
      requireNonBlank(value, path + "[" + index + "]");
      if (!unique.add(value)) {
        throw problem(path + "[" + index + "]", "duplicate value");
      }
    }
  }

  private static void requireNonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw problem(path, "must not be blank");
    }
  }

  private static BazelApplicationModelException problem(String path, String message) {
    return new BazelApplicationModelException(
        "Invalid application model at " + path + ": " + message);
  }

  private enum VisitState {
    VISITING,
    VISITED
  }
}
