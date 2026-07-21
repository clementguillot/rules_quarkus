package com.clementguillot.quarkifier.model.transport;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads and validates {@value BazelApplicationModel#SCHEMA_VERSION} JSON documents. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods"
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
    Map<String, Object> root = object(StrictJson.parse(document), "$", false);
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
            producer(object(root.get("producer"), "$.producer", false)),
            string(root, "quarkusVersion", "$"),
            enumeration(root, "mode", "$", Mode.class),
            string(root, "applicationId", "$"),
            mapArray(root, "nodes", "$", BazelApplicationModelReader::node),
            mapArray(root, "workspaceModules", "$", BazelApplicationModelReader::workspaceModule),
            platform(object(root.get("platform"), "$.platform", false)));
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
        coordinates(object(value.get("coordinates"), path + ".coordinates", false), path),
        stringArray(value, "paths", path),
        mapArray(value, "dependencies", path, BazelApplicationModelReader::dependencyEdge),
        classpath(object(value.get("classpath"), path + ".classpath", false), path),
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

  private static <T> T enumeration(
      Map<String, Object> value, String field, String path, Class<T> type) {
    String raw = string(value, field, path);
    for (T constant : type.getEnumConstants()) {
      if (((Enum<?>) constant).name().toLowerCase(Locale.ROOT).equals(raw)) {
        return constant;
      }
    }
    var allowed = new ArrayList<String>();
    for (T constant : type.getEnumConstants()) {
      allowed.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
    }
    throw problem(path + "." + field, "expected one of " + allowed + ", got '" + raw + "'");
  }

  private static String string(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (!(raw instanceof String result)) {
      throw problem(path + "." + field, "expected a string");
    }
    return result;
  }

  private static String nullableString(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String result)) {
      throw problem(path + "." + field, "expected a string or null");
    }
    return result;
  }

  private static boolean bool(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (!(raw instanceof Boolean result)) {
      throw problem(path + "." + field, "expected a boolean");
    }
    return result;
  }

  private static List<String> stringArray(Map<String, Object> value, String field, String path) {
    List<Object> raw = array(required(value, field, path), path + "." + field);
    var result = new ArrayList<String>(raw.size());
    for (int index = 0; index < raw.size(); index++) {
      Object element = raw.get(index);
      if (!(element instanceof String string)) {
        throw problem(path + "." + field + "[" + index + "]", "expected a string");
      }
      result.add(string);
    }
    return result;
  }

  private static Map<String, String> stringMap(
      Map<String, Object> value, String field, String path) {
    Map<String, Object> raw = object(required(value, field, path), path + "." + field, true);
    var result = new LinkedHashMap<String, String>();
    raw.forEach(
        (key, element) -> {
          if (!(element instanceof String string)) {
            throw problem(path + "." + field + "." + key, "expected a string");
          }
          result.put(key, string);
        });
    return result;
  }

  private static <T> List<T> mapArray(
      Map<String, Object> value, String field, String path, ElementMapper<T> mapper) {
    List<Object> raw = array(required(value, field, path), path + "." + field);
    var result = new ArrayList<T>(raw.size());
    for (int index = 0; index < raw.size(); index++) {
      String elementPath = path + "." + field + "[" + index + "]";
      result.add(mapper.map(object(raw.get(index), elementPath, false), elementPath));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String path, boolean allowAnyFields) {
    if (!(value instanceof Map<?, ?>)) {
      throw problem(path, "expected an object");
    }
    Map<String, Object> result = (Map<String, Object>) value;
    if (!allowAnyFields && result.isEmpty()) {
      throw problem(path, "object must not be empty");
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value, String path) {
    if (!(value instanceof List<?>)) {
      throw problem(path, "expected an array");
    }
    return (List<Object>) value;
  }

  private static Object required(Map<String, Object> value, String field, String path) {
    if (!value.containsKey(field)) {
      throw problem(path, "missing required field '" + field + "'");
    }
    return value.get(field);
  }

  private static void fields(Map<String, Object> value, String path, String... allowed) {
    Set<String> expected = new LinkedHashSet<>(List.of(allowed));
    for (String field : value.keySet()) {
      if (!expected.contains(field)) {
        throw problem(path, "unknown field '" + field + "'");
      }
    }
    for (String field : expected) {
      if (!value.containsKey(field)) {
        throw problem(path, "missing required field '" + field + "'");
      }
    }
  }

  private static BazelApplicationModelException problem(String path, String message) {
    return new BazelApplicationModelException(
        "Invalid application model at " + path + ": " + message);
  }

  @FunctionalInterface
  private interface ElementMapper<T> {
    T map(Map<String, Object> value, String path);
  }
}
