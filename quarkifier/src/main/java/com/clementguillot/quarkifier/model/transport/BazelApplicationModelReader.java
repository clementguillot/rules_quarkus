package com.clementguillot.quarkifier.model.transport;

import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.bool;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.enumeration;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.fields;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.mapArray;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.nullableString;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.objectMap;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.parseRoot;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.string;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.stringArray;
import static com.clementguillot.quarkifier.model.transport.StrictJsonReader.stringMap;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactKey;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ClasspathFacts;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyRelation;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Mode;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.NodeKind;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Platform;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Producer;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.SourceSet;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.WorkspaceModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads and validates {@value BazelApplicationModel#SCHEMA_VERSION} JSON documents. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  // Mapper methods are consumed through method references in mapArray calls.
  "PMD.UnusedPrivateMethod"
})
public final class BazelApplicationModelReader {

  private static final long MAX_DOCUMENT_BYTES = 16L * 1024L * 1024L;

  private BazelApplicationModelReader() {}

  public static BazelApplicationModel read(Path path) throws IOException {
    long size = Files.size(path);
    if (size > MAX_DOCUMENT_BYTES) {
      throw new BazelApplicationModelException(
          "Application-model document exceeds " + MAX_DOCUMENT_BYTES + " bytes: " + path);
    }
    return read(Files.readString(path, StandardCharsets.UTF_8));
  }

  public static BazelApplicationModel read(String document) {
    Map<String, Object> root = parseRoot(document);
    fields(
        root,
        "$",
        "schemaVersion",
        "producer",
        "quarkusVersion",
        "mode",
        "applicationId",
        "nodes",
        "workspaceModules",
        "platform");

    var model =
        new BazelApplicationModel(
            string(root, "schemaVersion", "$"),
            producer(objectMap(root.get("producer"), "$.producer")),
            string(root, "quarkusVersion", "$"),
            enumeration(root, "mode", "$", Mode.class),
            string(root, "applicationId", "$"),
            mapArray(root, "nodes", "$", BazelApplicationModelReader::node),
            mapArray(root, "workspaceModules", "$", BazelApplicationModelReader::workspaceModule),
            platform(objectMap(root.get("platform"), "$.platform")));
    BazelApplicationModelValidator.validate(model);
    return model;
  }

  private static Producer producer(Map<String, Object> value) {
    fields(value, "$.producer", "name", "version");
    return new Producer(
        string(value, "name", "$.producer"), string(value, "version", "$.producer"));
  }

  private static Node node(Map<String, Object> value, String path) {
    fields(
        value,
        path,
        "id",
        "kind",
        "coordinates",
        "paths",
        "dependencies",
        "classpath",
        "workspaceModuleId",
        "bazelLabel");
    return new Node(
        string(value, "id", path),
        enumeration(value, "kind", path, NodeKind.class),
        coordinates(objectMap(value.get("coordinates"), path + ".coordinates"), path),
        stringArray(value, "paths", path),
        mapArray(value, "dependencies", path, BazelApplicationModelReader::dependencyEdge),
        classpath(objectMap(value.get("classpath"), path + ".classpath"), path),
        nullableString(value, "workspaceModuleId", path),
        nullableString(value, "bazelLabel", path));
  }

  private static ArtifactCoordinates coordinates(Map<String, Object> value, String parentPath) {
    String path = parentPath + ".coordinates";
    fields(value, path, "groupId", "artifactId", "classifier", "type", "version");
    return new ArtifactCoordinates(
        string(value, "groupId", path),
        string(value, "artifactId", path),
        string(value, "classifier", path),
        string(value, "type", path),
        string(value, "version", path));
  }

  private static ArtifactKey artifactKey(Map<String, Object> value, String path) {
    fields(value, path, "groupId", "artifactId");
    return new ArtifactKey(string(value, "groupId", path), string(value, "artifactId", path));
  }

  private static DependencyEdge dependencyEdge(Map<String, Object> value, String path) {
    fields(value, path, "targetId", "relation", "scope", "optional", "exclusions");
    return new DependencyEdge(
        string(value, "targetId", path),
        enumeration(value, "relation", path, DependencyRelation.class),
        enumeration(value, "scope", path, DependencyScope.class),
        bool(value, "optional", path),
        mapArray(value, "exclusions", path, BazelApplicationModelReader::artifactKey));
  }

  private static ClasspathFacts classpath(Map<String, Object> value, String parentPath) {
    String path = parentPath + ".classpath";
    fields(
        value,
        path,
        "directFromApplication",
        "runtimeClasspath",
        "deploymentClasspath",
        "compileOnly",
        "optional",
        "reloadable",
        "topLevelRuntimeExtension");
    return new ClasspathFacts(
        bool(value, "directFromApplication", path),
        bool(value, "runtimeClasspath", path),
        bool(value, "deploymentClasspath", path),
        bool(value, "compileOnly", path),
        bool(value, "optional", path),
        bool(value, "reloadable", path),
        bool(value, "topLevelRuntimeExtension", path));
  }

  private static WorkspaceModule workspaceModule(Map<String, Object> value, String path) {
    fields(
        value,
        path,
        "id",
        "bazelLabel",
        "moduleDir",
        "buildDir",
        "buildFile",
        "sourceSets",
        "directDependencyIds",
        "dependencyConstraints",
        "testClasspathExclusions",
        "additionalTestClasspathElements",
        "parentId");
    return new WorkspaceModule(
        string(value, "id", path),
        string(value, "bazelLabel", path),
        string(value, "moduleDir", path),
        string(value, "buildDir", path),
        string(value, "buildFile", path),
        mapArray(value, "sourceSets", path, BazelApplicationModelReader::sourceSet),
        stringArray(value, "directDependencyIds", path),
        mapArray(value, "dependencyConstraints", path, BazelApplicationModelReader::artifactKey),
        mapArray(value, "testClasspathExclusions", path, BazelApplicationModelReader::artifactKey),
        stringArray(value, "additionalTestClasspathElements", path),
        nullableString(value, "parentId", path));
  }

  private static SourceSet sourceSet(Map<String, Object> value, String path) {
    fields(
        value,
        path,
        "classifier",
        "sourceDirectories",
        "resourceDirectories",
        "outputDirectories",
        "generatedSourceDirectories",
        "generatedResourceDirectories");
    return new SourceSet(
        string(value, "classifier", path),
        stringArray(value, "sourceDirectories", path),
        stringArray(value, "resourceDirectories", path),
        stringArray(value, "outputDirectories", path),
        stringArray(value, "generatedSourceDirectories", path),
        stringArray(value, "generatedResourceDirectories", path));
  }

  private static Platform platform(Map<String, Object> value) {
    String path = "$.platform";
    fields(value, path, "imports", "properties", "releases");
    return new Platform(
        mapArray(value, "imports", path, BazelApplicationModelReader::coordinates),
        stringMap(value, "properties", path),
        mapArray(value, "releases", path, BazelApplicationModelReader::platformRelease));
  }

  private static BazelApplicationModel.PlatformRelease platformRelease(
      Map<String, Object> value, String path) {
    fields(value, path, "platformKey", "stream", "version", "memberBoms");
    return new BazelApplicationModel.PlatformRelease(
        string(value, "platformKey", path),
        string(value, "stream", path),
        string(value, "version", path),
        mapArray(value, "memberBoms", path, BazelApplicationModelReader::coordinates));
  }
}
