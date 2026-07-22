package com.clementguillot.quarkifier.model.transport;

import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.bool;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.enumeration;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.fields;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.mapArray;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.nonBlank;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.nullableString;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.objectMap;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.parseRoot;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.problem;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.required;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.schema;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.string;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.stringArray;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.stringMap;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactKey;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict readers for aspect fragments and generated runtime/deployment catalogs. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  // Mapper methods are consumed through method references in mapArray calls.
  "PMD.UnusedPrivateMethod"
})
public final class BazelModelInputReader {

  private static final long MAX_DOCUMENT_BYTES = 64L * 1024L * 1024L;

  private BazelModelInputReader() {}

  // ---- Public entry points ----

  public static Roots readRoots(Path path) throws IOException {
    return readRoots(readDocument(path));
  }

  public static Roots readRoots(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(root, "$", "schemaVersion", "applicationLabel", "rootIds");
    schema(root, BazelModelInputs.ROOTS_SCHEMA);
    var result =
        new Roots(string(root, "applicationLabel", "$"), stringArray(root, "rootIds", "$"));
    nonBlank(result.applicationLabel(), "$.applicationLabel");
    uniqueNonBlank(result.rootIds(), "$.rootIds", false);
    return result;
  }

  public static TargetFragment readTargetFragment(Path path) throws IOException {
    return readTargetFragment(readDocument(path));
  }

  public static TargetFragment readTargetFragment(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(
        root,
        "$",
        "schemaVersion",
        "targetId",
        "bazelLabel",
        "workspaceName",
        "package",
        "targetName",
        "ruleKind",
        "buildFile",
        "neverlink",
        "coordinates",
        "runtimeOutputJars",
        "outputDirectories",
        "sourceJars",
        "sources",
        "resources",
        "edges");
    schema(root, BazelModelInputs.TARGET_SCHEMA);
    var result =
        new TargetFragment(
            string(root, "targetId", "$"),
            string(root, "bazelLabel", "$"),
            string(root, "workspaceName", "$"),
            string(root, "package", "$"),
            string(root, "targetName", "$"),
            string(root, "ruleKind", "$"),
            string(root, "buildFile", "$"),
            bool(root, "neverlink", "$"),
            nullableCoordinates(root, "coordinates", "$"),
            mapArray(root, "runtimeOutputJars", "$", BazelModelInputReader::fileReference),
            mapArray(root, "outputDirectories", "$", BazelModelInputReader::fileReference),
            mapArray(root, "sourceJars", "$", BazelModelInputReader::fileReference),
            mapArray(root, "sources", "$", BazelModelInputReader::fileReference),
            mapArray(root, "resources", "$", BazelModelInputReader::fileReference),
            mapArray(root, "edges", "$", BazelModelInputReader::targetEdge));
    validateTargetFragment(result);
    return result;
  }

  public static RuntimeCatalog readRuntimeCatalog(Path path) throws IOException {
    return readRuntimeCatalog(readDocument(path));
  }

  public static RuntimeCatalog readRuntimeCatalog(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(root, "$", "schemaVersion", "nodes", "directArtifacts", "conflictResolution");
    schema(root, BazelModelInputs.RUNTIME_CATALOG_SCHEMA);
    var result =
        new RuntimeCatalog(
            mapArray(root, "nodes", "$", BazelModelInputReader::runtimeNode),
            stringArray(root, "directArtifacts", "$"),
            stringMap(root, "conflictResolution", "$"));
    validateRuntimeCatalog(result);
    return result;
  }

  public static DeploymentCatalog readDeploymentCatalog(Path path) throws IOException {
    return readDeploymentCatalog(readDocument(path));
  }

  public static DeploymentCatalog readDeploymentCatalog(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(
        root,
        "$",
        "schemaVersion",
        "resolver",
        "resolverReportVersion",
        "roots",
        "droppedRoots",
        "nodes",
        "conflictResolution");
    schema(root, BazelModelInputs.DEPLOYMENT_CATALOG_SCHEMA);
    var result =
        new DeploymentCatalog(
            string(root, "resolver", "$"),
            string(root, "resolverReportVersion", "$"),
            stringArray(root, "roots", "$"),
            stringArray(root, "droppedRoots", "$"),
            mapArray(root, "nodes", "$", BazelModelInputReader::deploymentNode),
            stringMap(root, "conflictResolution", "$"));
    validateDeploymentCatalog(result);
    return result;
  }

  public static ConditionalCatalog readConditionalCatalog(Path path) throws IOException {
    return readConditionalCatalog(readDocument(path));
  }

  public static ConditionalCatalog readConditionalCatalog(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(
        root,
        "$",
        "schemaVersion",
        "resolver",
        "resolverReportVersion",
        "roots",
        "nodes",
        "extensions",
        "conflictResolution");
    schema(root, BazelModelInputs.CONDITIONAL_CATALOG_SCHEMA);
    var result =
        new ConditionalCatalog(
            string(root, "resolver", "$"),
            string(root, "resolverReportVersion", "$"),
            stringArray(root, "roots", "$"),
            mapArray(root, "nodes", "$", BazelModelInputReader::conditionalNode),
            mapArray(root, "extensions", "$", BazelModelInputReader::extensionDescriptor),
            stringMap(root, "conflictResolution", "$"));
    validateConditionalCatalog(result);
    return result;
  }

  public static PlatformCatalog readPlatformCatalog(Path path) throws IOException {
    return readPlatformCatalog(readDocument(path));
  }

  public static PlatformCatalog readPlatformCatalog(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(root, "$", "schemaVersion", "imports", "propertyFiles", "properties");
    schema(root, BazelModelInputs.PLATFORM_CATALOG_SCHEMA);
    var result =
        new PlatformCatalog(
            mapArray(root, "imports", "$", BazelModelInputReader::coordinates),
            stringArray(root, "propertyFiles", "$"),
            stringMap(root, "properties", "$"));
    validatePlatformCatalog(result);
    return result;
  }

  // ---- Object mappers ----

  private static FileReference fileReference(Map<String, Object> value, String path) {
    fields(value, path, "path", "shortPath", "owner", "isSource");
    return new FileReference(
        string(value, "path", path),
        string(value, "shortPath", path),
        nullableString(value, "owner", path),
        bool(value, "isSource", path));
  }

  private static TargetEdge targetEdge(Map<String, Object> value, String path) {
    fields(value, path, "targetId", "relation", "scope", "optional", "exclusions");
    return new TargetEdge(
        string(value, "targetId", path),
        string(value, "relation", path),
        enumeration(value, "scope", path, DependencyScope.class),
        bool(value, "optional", path),
        mapArray(value, "exclusions", path, BazelModelInputReader::artifactKey));
  }

  private static ArtifactKey artifactKey(Map<String, Object> value, String path) {
    fields(value, path, "groupId", "artifactId");
    return new ArtifactKey(string(value, "groupId", path), string(value, "artifactId", path));
  }

  private static RuntimeCatalogNode runtimeNode(Map<String, Object> value, String path) {
    fields(
        value,
        path,
        "coordinateKey",
        "targetName",
        "coordinates",
        "dependencies",
        "optional",
        "exclusions");
    return new RuntimeCatalogNode(
        string(value, "coordinateKey", path),
        string(value, "targetName", path),
        coordinates(
            objectMap(required(value, "coordinates", path), path + ".coordinates"),
            path + ".coordinates"),
        stringArray(value, "dependencies", path),
        bool(value, "optional", path),
        stringArray(value, "exclusions", path));
  }

  static ArtifactCoordinates coordinates(Map<String, Object> value, String path) {
    fields(value, path, "groupId", "artifactId", "classifier", "type", "version");
    return new ArtifactCoordinates(
        string(value, "groupId", path),
        string(value, "artifactId", path),
        string(value, "classifier", path),
        string(value, "type", path),
        string(value, "version", path));
  }

  private static ArtifactCoordinates nullableCoordinates(
      Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (raw == null) {
      return null;
    }
    return coordinates(objectMap(raw, path + "." + field), path + "." + field);
  }

  private static DeploymentCatalogNode deploymentNode(Map<String, Object> value, String path) {
    fields(value, path, "coordinate", "repoPath", "dependencies", "exclusions");
    return new DeploymentCatalogNode(
        string(value, "coordinate", path),
        string(value, "repoPath", path),
        stringArray(value, "dependencies", path),
        stringArray(value, "exclusions", path));
  }

  private static ConditionalCatalogNode conditionalNode(Map<String, Object> value, String path) {
    fields(value, path, "coordinate", "repoPath", "dependencies", "exclusions");
    return new ConditionalCatalogNode(
        string(value, "coordinate", path),
        string(value, "repoPath", path),
        stringArray(value, "dependencies", path),
        stringArray(value, "exclusions", path));
  }

  private static ExtensionDescriptor extensionDescriptor(Map<String, Object> value, String path) {
    fields(
        value,
        path,
        "runtimeArtifact",
        "deploymentArtifact",
        "conditionalDependencies",
        "conditionalDevDependencies",
        "dependencyConditions");
    return new ExtensionDescriptor(
        string(value, "runtimeArtifact", path),
        string(value, "deploymentArtifact", path),
        stringArray(value, "conditionalDependencies", path),
        stringArray(value, "conditionalDevDependencies", path),
        stringArray(value, "dependencyConditions", path));
  }

  // ---- Validation ----

  private static void validateTargetFragment(TargetFragment value) {
    nonBlank(value.targetId(), "$.targetId");
    nonBlank(value.bazelLabel(), "$.bazelLabel");
    if (!value.targetId().equals(value.bazelLabel())
        && !value.targetId().equals("local-deployment:" + value.bazelLabel())) {
      throw problem("$.targetId", "must equal bazelLabel or its local-deployment id");
    }
    nonBlank(value.targetName(), "$.targetName");
    nonBlank(value.ruleKind(), "$.ruleKind");
    nonBlank(value.buildFile(), "$.buildFile");
    if (value.coordinates() != null) {
      validateCoordinates(value.coordinates(), "$.coordinates");
    }
    validateFiles(value.runtimeOutputJars(), "$.runtimeOutputJars", false);
    boolean outputOptional =
        !value.workspaceTarget() || value.targetId().startsWith("local-deployment:");
    validateFiles(value.outputDirectories(), "$.outputDirectories", outputOptional);
    validateFiles(value.sourceJars(), "$.sourceJars", true);
    validateFiles(value.sources(), "$.sources", true);
    validateFiles(value.resources(), "$.resources", true);
    validateEdges(value);
  }

  private static void validateEdges(TargetFragment value) {
    var edgeKeys = new HashSet<String>();
    for (int index = 0; index < value.edges().size(); index++) {
      TargetEdge edge = value.edges().get(index);
      String path = "$.edges[" + index + "]";
      nonBlank(edge.targetId(), path + ".targetId");
      if (value.targetId().equals(edge.targetId())) {
        throw problem(path + ".targetId", "self-dependencies are not allowed");
      }
      if (!Set.of("deps", "exports", "runtime_deps").contains(edge.relation())) {
        throw problem(path + ".relation", "unsupported Bazel edge relation");
      }
      String edgeKey = edge.targetId() + "\u0000" + edge.relation();
      if (!edgeKeys.add(edgeKey)) {
        throw problem(path, "duplicate target edge");
      }
      validateArtifactKeys(edge.exclusions(), path + ".exclusions");
    }
  }

  private static void validateRuntimeCatalog(RuntimeCatalog value) {
    var coordinates = new HashSet<String>();
    var targetNames = new HashSet<String>();
    for (int index = 0; index < value.nodes().size(); index++) {
      RuntimeCatalogNode node = value.nodes().get(index);
      String path = "$.nodes[" + index + "]";
      nonBlank(node.coordinateKey(), path + ".coordinateKey");
      nonBlank(node.targetName(), path + ".targetName");
      validateCoordinates(node.coordinates(), path + ".coordinates");
      if (!coordinates.add(node.coordinateKey())) {
        throw problem(path + ".coordinateKey", "duplicate runtime coordinate key");
      }
      if (!targetNames.add(node.targetName())) {
        throw problem(path + ".targetName", "duplicate runtime target name");
      }
      uniqueNonBlank(node.dependencies(), path + ".dependencies", true);
      uniqueNonBlank(node.exclusions(), path + ".exclusions", true);
    }
    for (RuntimeCatalogNode node : value.nodes()) {
      for (String dependency : node.dependencies()) {
        if (!coordinates.contains(dependency)) {
          throw problem("$.nodes", "dangling runtime catalog edge to '" + dependency + "'");
        }
      }
    }
    uniqueNonBlank(value.directArtifacts(), "$.directArtifacts", true);
    for (String direct : value.directArtifacts()) {
      if (!coordinates.contains(direct)) {
        throw problem("$.directArtifacts", "unknown runtime coordinate '" + direct + "'");
      }
    }
    validateStringMap(value.conflictResolution(), "$.conflictResolution");
  }

  private static void validateDeploymentCatalog(DeploymentCatalog value) {
    nonBlank(value.resolver(), "$.resolver");
    nonBlank(value.resolverReportVersion(), "$.resolverReportVersion");
    uniqueNonBlank(value.roots(), "$.roots", true);
    uniqueNonBlank(value.droppedRoots(), "$.droppedRoots", true);
    var coordinates = new HashSet<String>();
    var paths = new HashSet<String>();
    for (int index = 0; index < value.nodes().size(); index++) {
      DeploymentCatalogNode node = value.nodes().get(index);
      String path = "$.nodes[" + index + "]";
      nonBlank(node.coordinate(), path + ".coordinate");
      if (!coordinates.add(node.coordinate())) {
        throw problem(path + ".coordinate", "duplicate deployment coordinate");
      }
      validateRepoPath(node.repoPath(), path + ".repoPath");
      if (!paths.add(node.repoPath())) {
        throw problem(path + ".repoPath", "duplicate deployment repository path");
      }
      uniqueNonBlank(node.dependencies(), path + ".dependencies", true);
      uniqueNonBlank(node.exclusions(), path + ".exclusions", true);
    }
    for (DeploymentCatalogNode node : value.nodes()) {
      for (String dependency : node.dependencies()) {
        if (!coordinates.contains(dependency)) {
          throw problem("$.nodes", "dangling deployment catalog edge to '" + dependency + "'");
        }
      }
    }
    for (String root : value.roots()) {
      if (!coordinates.contains(root)) {
        throw problem("$.roots", "unknown deployment coordinate '" + root + "'");
      }
    }
    validateStringMap(value.conflictResolution(), "$.conflictResolution");
  }

  private static void validateConditionalCatalog(ConditionalCatalog value) {
    nonBlank(value.resolver(), "$.resolver");
    uniqueNonBlank(value.roots(), "$.roots", true);
    var coordinates = new HashSet<String>();
    var paths = new HashSet<String>();
    for (int index = 0; index < value.nodes().size(); index++) {
      ConditionalCatalogNode node = value.nodes().get(index);
      String path = "$.nodes[" + index + "]";
      String canonical =
          BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(node.coordinate()));
      if (!coordinates.add(canonical)) {
        throw problem(path + ".coordinate", "duplicate conditional coordinate");
      }
      validateConditionalRepoPath(node.repoPath(), path + ".repoPath");
      if (!paths.add(node.repoPath())) {
        throw problem(path + ".repoPath", "duplicate conditional repository path");
      }
      uniqueNonBlank(node.dependencies(), path + ".dependencies", true);
      uniqueNonBlank(node.exclusions(), path + ".exclusions", true);
    }
    for (ConditionalCatalogNode node : value.nodes()) {
      for (String dependency : node.dependencies()) {
        String canonical =
            BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(dependency));
        if (!coordinates.contains(canonical)) {
          throw problem("$.nodes", "dangling conditional catalog edge to '" + dependency + "'");
        }
      }
    }
    for (String root : value.roots()) {
      String canonical = BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(root));
      if (!coordinates.contains(canonical)) {
        throw problem("$.roots", "unknown conditional coordinate '" + root + "'");
      }
    }
    validateExtensions(value.extensions(), coordinates);
    validateStringMap(value.conflictResolution(), "$.conflictResolution");
  }

  private static void validateExtensions(
      List<ExtensionDescriptor> extensions, Set<String> resolvedCoordinates) {
    var runtimes = new HashSet<String>();
    for (int index = 0; index < extensions.size(); index++) {
      ExtensionDescriptor descriptor = extensions.get(index);
      String path = "$.extensions[" + index + "]";
      String runtime =
          BazelArtifactCoordinates.canonical(
              BazelArtifactCoordinates.parse(descriptor.runtimeArtifact()));
      BazelArtifactCoordinates.parse(descriptor.deploymentArtifact());
      if (!runtimes.add(runtime)) {
        throw problem(path + ".runtimeArtifact", "duplicate extension descriptor");
      }
      validateConditionalCoordinates(
          descriptor.conditionalDependencies(),
          path + ".conditionalDependencies",
          resolvedCoordinates);
      validateConditionalCoordinates(
          descriptor.conditionalDevDependencies(),
          path + ".conditionalDevDependencies",
          resolvedCoordinates);
      uniqueNonBlank(descriptor.dependencyConditions(), path + ".dependencyConditions", true);
      descriptor.dependencyConditions().forEach(condition -> validateArtifactKey(condition, path));
    }
  }

  private static void validatePlatformCatalog(PlatformCatalog result) {
    var imports = new HashSet<String>();
    for (int index = 0; index < result.imports().size(); index++) {
      ArtifactCoordinates coord = result.imports().get(index);
      validateCoordinates(coord, "$.imports[" + index + "]");
      if (!"pom".equals(coord.type()) || !coord.classifier().isEmpty()) {
        throw problem("$.imports[" + index + "]", "platform imports must be unclassified POMs");
      }
      if (!imports.add(BazelArtifactCoordinates.canonical(coord))) {
        throw problem("$.imports[" + index + "]", "duplicate platform import");
      }
    }
    uniqueNonBlank(result.propertyFiles(), "$.propertyFiles", false);
    result
        .properties()
        .forEach(
            (key, value) -> {
              nonBlank(key, "$.properties key");
              if (value == null) {
                throw problem("$.properties." + key, "must not be null");
              }
            });
  }

  // ---- Validation helpers ----

  private static void validateConditionalCoordinates(
      List<String> values, String path, Set<String> resolvedCoordinates) {
    uniqueNonBlank(values, path, true);
    for (String value : values) {
      String canonical = BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(value));
      if (!resolvedCoordinates.contains(canonical)) {
        throw problem(path, "unresolved conditional artifact '" + value + "'");
      }
    }
  }

  private static void validateArtifactKey(String value, String path) {
    String[] parts = value.split(":", -1);
    if (parts.length < 2
        || parts.length > 4
        || parts[0].isBlank()
        || parts[1].isBlank()
        || (parts.length == 3 && parts[2].isBlank())
        || (parts.length == 4 && parts[3].isBlank())) {
      throw problem(path, "invalid dependency-condition artifact key '" + value + "'");
    }
  }

  private static void validateConditionalRepoPath(String value, String path) {
    nonBlank(value, path);
    if (!value.startsWith("conditional/jars/")
        || value.startsWith("/")
        || value.contains("\\")
        || value.contains("/../")
        || value.endsWith("/..")) {
      throw problem(path, "must be a normalized repository-owned conditional/jars path");
    }
  }

  private static void validateRepoPath(String value, String path) {
    nonBlank(value, path);
    if (!value.startsWith("deployment/jars/")
        || value.startsWith("/")
        || value.contains("\\")
        || value.contains("/../")
        || value.endsWith("/..")) {
      throw problem(path, "must be a normalized repository-owned deployment/jars path");
    }
  }

  private static void validateFiles(List<FileReference> values, String path, boolean allowEmpty) {
    if (!allowEmpty && values.isEmpty()) {
      throw problem(path, "must not be empty");
    }
    var paths = new HashSet<String>();
    for (int index = 0; index < values.size(); index++) {
      FileReference value = values.get(index);
      String itemPath = path + "[" + index + "]";
      nonBlank(value.path(), itemPath + ".path");
      nonBlank(value.shortPath(), itemPath + ".shortPath");
      if (!paths.add(value.path())) {
        throw problem(itemPath + ".path", "duplicate file path");
      }
      if (value.owner() != null) {
        nonBlank(value.owner(), itemPath + ".owner");
      }
    }
  }

  private static void validateCoordinates(ArtifactCoordinates value, String path) {
    nonBlank(value.groupId(), path + ".groupId");
    nonBlank(value.artifactId(), path + ".artifactId");
    if (value.classifier() == null) {
      throw problem(path + ".classifier", "must not be null");
    }
    nonBlank(value.type(), path + ".type");
    nonBlank(value.version(), path + ".version");
  }

  private static void validateArtifactKeys(List<ArtifactKey> values, String path) {
    var unique = new HashSet<ArtifactKey>();
    for (int index = 0; index < values.size(); index++) {
      ArtifactKey value = values.get(index);
      nonBlank(value.groupId(), path + "[" + index + "].groupId");
      nonBlank(value.artifactId(), path + "[" + index + "].artifactId");
      if (!unique.add(value)) {
        throw problem(path + "[" + index + "]", "duplicate artifact key");
      }
    }
  }

  private static void uniqueNonBlank(List<String> values, String path, boolean allowEmpty) {
    if (!allowEmpty && values.isEmpty()) {
      throw problem(path, "must not be empty");
    }
    var unique = new HashSet<String>();
    for (int index = 0; index < values.size(); index++) {
      String value = values.get(index);
      nonBlank(value, path + "[" + index + "]");
      if (!unique.add(value)) {
        throw problem(path + "[" + index + "]", "duplicate value");
      }
    }
  }

  private static void validateStringMap(Map<String, String> value, String path) {
    value.forEach(
        (key, element) -> {
          nonBlank(key, path + " key");
          nonBlank(element, path + "." + key);
        });
  }

  private static String readDocument(Path path) throws IOException {
    if (Files.size(path) > MAX_DOCUMENT_BYTES) {
      throw new BazelApplicationModelException("Model input exceeds size limit: " + path);
    }
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
