package com.clementguillot.quarkifier.model.conformance;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import com.clementguillot.quarkifier.model.transport.BazelArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.StrictJson;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Canonicalizes Quarkus's JSON ApplicationModel representation for cross-build-tool comparison. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CyclomaticComplexity",
  "PMD.GodClass",
  "PMD.TooManyMethods"
})
public final class ApplicationModelNormalizer {

  private static final Set<String> TOP_LEVEL_FIELDS =
      Set.of(
          "app-artifact",
          "dependencies",
          "platform-imports",
          "capabilities",
          "local-projects",
          "removed-resources",
          "extension-dev-config");
  private static final List<Flag> FLAGS =
      List.of(
          new Flag(1, "OPTIONAL"),
          new Flag(2, "DIRECT"),
          new Flag(4, "RUNTIME_CP"),
          new Flag(8, "DEPLOYMENT_CP"),
          new Flag(16, "RUNTIME_EXTENSION_ARTIFACT"),
          new Flag(32, "WORKSPACE_MODULE"),
          new Flag(64, "RELOADABLE"),
          new Flag(128, "TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT"),
          new Flag(256, "CLASSLOADER_PARENT_FIRST"),
          new Flag(512, "CLASSLOADER_RUNNER_PARENT_FIRST"),
          new Flag(1024, "CLASSLOADER_LESSER_PRIORITY"),
          new Flag(2048, "VISITED"),
          new Flag(4096, "COMPILE_ONLY"),
          new Flag(8192, "MISSING_FROM_APPLICATION"));

  private final List<PathRoot> roots;
  private String applicationCoordinates;
  private String applicationKey;
  private Map<String, String> resolvedCoordinatesByKey = Map.of();

  public ApplicationModelNormalizer(Map<String, Path> pathRoots) {
    this(
        pathRoots.entrySet().stream()
            .map(entry -> new PathMapping(entry.getKey(), entry.getValue()))
            .toList());
  }

  public ApplicationModelNormalizer(List<PathMapping> pathRoots) {
    roots =
        pathRoots.stream()
            .map(root -> new PathRoot(root.token(), root.path().toAbsolutePath().normalize()))
            .sorted(
                Comparator.comparingInt((PathRoot root) -> root.path().toString().length())
                    .reversed())
            .toList();
  }

  /** One stable normalization token may intentionally identify multiple build-system roots. */
  public record PathMapping(String token, Path path) {}

  public String normalize(String json) {
    return CanonicalJson.write(normalizeValue(json));
  }

  public static String writeNormalized(Object value) {
    return CanonicalJson.write(value);
  }

  public Object normalizeValue(String json) {
    Map<String, Object> source = object(StrictJson.parse(json), "application model");
    rejectUnknown(source, TOP_LEVEL_FIELDS, "application model");
    Map<String, Object> app = object(required(source, "app-artifact"), "app-artifact");
    applicationCoordinates =
        canonicalCoordinate(string(required(app, "maven-artifact"), "app-artifact.maven-artifact"));
    applicationKey = artifactKey(applicationCoordinates);
    var resolved = new LinkedHashMap<String, String>();
    for (Object value : array(source.get("dependencies"))) {
      Map<String, Object> dependency = object(value, "dependency");
      String coordinates =
          canonicalCoordinate(
              string(required(dependency, "maven-artifact"), "dependency.maven-artifact"));
      resolved.put(artifactKey(coordinates), coordinates);
    }
    resolvedCoordinatesByKey = Map.copyOf(resolved);

    var result = new LinkedHashMap<String, Object>();
    result.put("application", normalizeArtifact(app, true));
    result.put(
        "dependencies",
        normalizeObjects(array(source.get("dependencies")), this::normalizeDependencyArtifact));
    result.put(
        "platform", normalizePlatform(object(source.get("platform-imports"), "platform-imports")));
    result.put("capabilities", normalizeCapabilities(array(source.get("capabilities"))));
    result.put(
        "reloadableWorkspaceModules", normalizeCoordinates(array(source.get("local-projects"))));
    result.put("removedResources", normalizeRemovedResources(source.get("removed-resources")));
    result.put(
        "extensionDevConfig",
        normalizeExtensionDevConfig(array(source.get("extension-dev-config"))));
    return result;
  }

  private Object normalizeDependencyArtifact(Object value) {
    return normalizeArtifact(object(value, "dependency"), false);
  }

  private Map<String, Object> normalizeArtifact(Map<String, Object> source, boolean application) {
    var result = new LinkedHashMap<String, Object>();
    String coordinates = string(required(source, "maven-artifact"), "maven-artifact");
    result.put("id", application ? "$APPLICATION" : normalizeCoordinate(coordinates));
    result.put("scope", string(source.getOrDefault("scope", "compile"), "scope"));
    result.put("flags", flagNames(source.get("flags")));
    result.put("paths", normalizePaths(array(source.get("resolved-paths"))));
    result.put("dependencies", normalizeCoordinates(array(source.get("dependencies"))));
    result.put("directDependencies", normalizeDirectDependencies(array(source.get("direct-deps"))));
    result.put("workspaceModule", normalizeWorkspaceModule(source.get("module")));
    return result;
  }

  private List<Object> normalizeDirectDependencies(List<Object> values) {
    var result = new ArrayList<Object>();
    for (Object value : values) {
      Map<String, Object> source = object(value, "direct dependency");
      var edge = new LinkedHashMap<String, Object>();
      edge.put(
          "target",
          normalizeCoordinate(string(required(source, "maven-artifact"), "maven-artifact")));
      edge.put("scope", string(source.getOrDefault("scope", "compile"), "scope"));
      edge.put("flags", flagNames(source.get("flags")));
      edge.put("exclusions", normalizeCoordinates(array(source.get("exclusions"))));
      result.add(edge);
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private Object normalizeWorkspaceModule(Object value) {
    if (value == null) {
      return null;
    }
    Map<String, Object> source = object(value, "workspace module");
    var result = new LinkedHashMap<String, Object>();
    result.put("id", normalizeCoordinate(string(required(source, "id"), "module.id")));
    result.put("moduleDir", normalizeNullablePath(source.get("module-dir")));
    result.put("buildDir", normalizeNullablePath(source.get("build-dir")));
    result.put("buildFiles", normalizePaths(array(source.get("build-files"))));
    result.put("sources", normalizeArtifactSources(array(source.get("artifact-sources"))));
    result.put("parent", normalizeNullableCoordinate(source.get("parent")));
    result.put("directDependencies", normalizeDirectDependencies(array(source.get("direct-deps"))));
    result.put(
        "dependencyConstraints",
        normalizeDirectDependencies(array(source.get("direct-dependency-constraints"))));
    result.put(
        "testClasspathExclusions",
        sortedStrings(array(source.get("test-classpath-dependency-exclusions"))));
    result.put(
        "additionalTestClasspathElements",
        normalizePaths(array(source.get("additional-test-classpath-elements"))));
    return result;
  }

  private List<Object> normalizeArtifactSources(List<Object> values) {
    var result = new ArrayList<Object>();
    for (Object value : values) {
      Map<String, Object> source = object(value, "artifact sources");
      var normalized = new LinkedHashMap<String, Object>();
      normalized.put("classifier", string(source.getOrDefault("classifier", ""), "classifier"));
      normalized.put("sources", normalizeSourceDirs(array(source.get("sources"))));
      normalized.put("resources", normalizeSourceDirs(array(source.get("resources"))));
      result.add(normalized);
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private List<Object> normalizeSourceDirs(List<Object> values) {
    var result = new ArrayList<Object>();
    for (Object value : values) {
      Map<String, Object> source = object(value, "source directory");
      var normalized = new LinkedHashMap<String, Object>();
      normalized.put("dir", normalizeNullablePath(source.get("dir")));
      normalized.put("outputDir", normalizeNullablePath(source.get("dest-dir")));
      normalized.put("generatedDir", normalizeNullablePath(source.get("apt-sources-dir")));
      result.add(normalized);
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private Object normalizePlatform(Map<String, Object> source) {
    var result = new LinkedHashMap<String, Object>();
    result.put("imports", normalizeCoordinates(array(source.get("imported-boms"))));
    result.put("properties", stringMap(source.get("platform-properties"), "platform properties"));
    var releases = new ArrayList<Object>();
    for (Object value : array(source.get("release-info"))) {
      Map<String, Object> release = object(value, "platform release");
      var normalized = new LinkedHashMap<String, Object>();
      normalized.put("platformKey", string(required(release, "platform-key"), "platform-key"));
      normalized.put("stream", string(required(release, "stream"), "stream"));
      normalized.put("version", string(required(release, "version"), "version"));
      normalized.put("boms", normalizeCoordinates(array(release.get("boms"))));
      releases.add(normalized);
    }
    releases.sort(Comparator.comparing(Object::toString));
    result.put("releases", releases);
    return result;
  }

  private List<Object> normalizeCapabilities(List<Object> values) {
    var result = new ArrayList<Object>();
    for (Object value : values) {
      Map<String, Object> source = object(value, "capability contract");
      var normalized = new LinkedHashMap<String, Object>();
      normalized.put(
          "extension", normalizeCoordinate(string(required(source, "extension"), "extension")));
      normalized.put("provided", sortedStrings(array(source.get("provided"))));
      normalized.put("required", sortedStrings(array(source.get("required"))));
      result.add(normalized);
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private Object normalizeRemovedResources(Object value) {
    if (value == null) {
      return Map.of();
    }
    var result = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> entry : object(value, "removed resources").entrySet()) {
      result.put(normalizeCoordinate(entry.getKey()), sortedStrings(array(entry.getValue())));
    }
    return result;
  }

  private List<Object> normalizeExtensionDevConfig(List<Object> values) {
    var result = new ArrayList<Object>();
    for (Object value : values) {
      Map<String, Object> source = object(value, "extension dev config");
      var normalized = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, Object> entry : source.entrySet()) {
        normalized.put(
            entry.getKey(),
            "extension".equals(entry.getKey())
                ? normalizeCoordinate(string(entry.getValue(), "extension"))
                : normalizeGeneric(entry.getValue()));
      }
      result.add(normalized);
    }
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private Object normalizeGeneric(Object value) {
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        result.put(string(entry.getKey(), "map key"), normalizeGeneric(entry.getValue()));
      }
      return result;
    }
    if (value instanceof List<?> list) {
      var result =
          list.stream()
              .map(this::normalizeGeneric)
              .collect(Collectors.toCollection(ArrayList::new));
      result.sort(Comparator.comparing(Object::toString));
      return result;
    }
    return value;
  }

  private List<String> flagNames(Object value) {
    int mask = value == null ? 0 : number(value, "flags").intValueExact();
    var result = new ArrayList<String>();
    int known = 0;
    for (Flag flag : FLAGS) {
      known |= flag.mask();
      if ((mask & flag.mask()) != 0 && !"VISITED".equals(flag.name())) {
        result.add(flag.name());
      }
    }
    if ((mask & ~known) != 0) {
      throw new BazelApplicationModelException(
          "ApplicationModel contains unknown dependency flag bits: " + (mask & ~known));
    }
    return result;
  }

  private List<String> normalizeCoordinates(List<Object> values) {
    var result =
        values.stream()
            .map(value -> normalizeCoordinate(string(value, "coordinate")))
            .sorted()
            .toList();
    return new ArrayList<>(result);
  }

  private String normalizeNullableCoordinate(Object value) {
    return value == null ? null : normalizeCoordinate(string(value, "coordinate"));
  }

  private String normalizeCoordinate(String value) {
    if (value.equals(applicationCoordinates) || value.equals(applicationKey)) {
      return "$APPLICATION";
    }
    String canonical;
    try {
      canonical = canonicalCoordinate(value);
    } catch (BazelApplicationModelException ignored) {
      // Artifact keys and non-coordinate diagnostic identifiers remain unchanged.
      return value;
    }
    String key = artifactKey(canonical);
    if (key.equals(applicationKey)) {
      return "$APPLICATION";
    }
    return resolvedCoordinatesByKey.getOrDefault(key, canonical);
  }

  private static String artifactKey(String coordinates) {
    String[] parts = canonicalCoordinate(coordinates).split(":", -1);
    return String.join(":", parts[0], parts[1], parts[2], parts[3]);
  }

  private static String canonicalCoordinate(String value) {
    return BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse(value));
  }

  private List<String> normalizePaths(List<Object> values) {
    return values.stream().map(value -> normalizePath(string(value, "path"))).sorted().toList();
  }

  private String normalizeNullablePath(Object value) {
    return value == null ? null : normalizePath(string(value, "path"));
  }

  private String normalizePath(String raw) {
    String slash = raw.replace('\\', '/');
    Path path = Path.of(raw);
    int repository = slash.indexOf("/.m2/repository/");
    if (repository >= 0) {
      return "$M2/" + slash.substring(repository + "/.m2/repository/".length());
    }
    int gradleModules = slash.indexOf("/.gradle/caches/modules-2/files-2.1/");
    if (gradleModules >= 0) {
      String[] segments =
          slash
              .substring(gradleModules + "/.gradle/caches/modules-2/files-2.1/".length())
              .split("/", -1);
      if (segments.length >= 5) {
        return "$M2/"
            + segments[0].replace('.', '/')
            + '/'
            + segments[1]
            + '/'
            + segments[2]
            + '/'
            + segments[4];
      }
    }
    int rulesJvmExternal = slash.indexOf("rules_jvm_external");
    if (rulesJvmExternal >= 0) {
      int repositoryPath = slash.indexOf('/', rulesJvmExternal);
      if (repositoryPath >= 0 && repositoryPath + 1 < slash.length()) {
        return "$M2/" + slash.substring(repositoryPath + 1).replace("/processed_", "/");
      }
    }
    int deploymentJars = slash.indexOf("/deployment/jars/");
    if (deploymentJars >= 0) {
      return "$M2/" + slash.substring(deploymentJars + "/deployment/jars/".length());
    }
    if (slash.startsWith("bazel-out/")) {
      int bin = slash.indexOf("/bin/");
      if (bin >= 0) {
        return "$BAZEL_BIN/" + slash.substring(bin + "/bin/".length());
      }
      return "$EXECROOT/" + slash;
    }
    if (slash.startsWith("external/")) {
      return "$EXECROOT/" + slash;
    }
    if (!path.isAbsolute()) {
      String relative = slash.startsWith("./") ? slash.substring(2) : slash;
      return relative.isEmpty() || ".".equals(relative) ? "$WORKSPACE" : "$WORKSPACE/" + relative;
    }
    String execrootPath = normalizeAbsoluteExecrootPath(slash);
    if (execrootPath != null) {
      return execrootPath;
    }
    Path normalized = path.normalize();
    for (PathRoot root : roots) {
      if (normalized.startsWith(root.path())) {
        String relative = root.path().relativize(normalized).toString().replace('\\', '/');
        return relative.isEmpty() ? root.token() : root.token() + "/" + relative;
      }
    }
    return slash;
  }

  private static String normalizeAbsoluteExecrootPath(String slash) {
    int execroot = slash.indexOf("/execroot/");
    if (execroot < 0) {
      return null;
    }
    String withinExecroot = slash.substring(execroot + "/execroot/".length());
    int workspaceSeparator = withinExecroot.indexOf('/');
    if (workspaceSeparator < 0 || workspaceSeparator + 1 >= withinExecroot.length()) {
      return null;
    }
    String relative = withinExecroot.substring(workspaceSeparator + 1);
    if (relative.startsWith("bazel-out/")) {
      int bin = relative.indexOf("/bin/");
      return bin >= 0
          ? "$BAZEL_BIN/" + relative.substring(bin + "/bin/".length())
          : "$EXECROOT/" + relative;
    }
    return relative.startsWith("external/") ? "$EXECROOT/" + relative : "$WORKSPACE/" + relative;
  }

  private static List<String> sortedStrings(List<Object> values) {
    return values.stream().map(value -> string(value, "string collection value")).sorted().toList();
  }

  private static Map<String, String> stringMap(Object value, String context) {
    if (value == null) {
      return Map.of();
    }
    var result = new LinkedHashMap<String, String>();
    for (Map.Entry<String, Object> entry : object(value, context).entrySet()) {
      result.put(entry.getKey(), string(entry.getValue(), context + " value"));
    }
    return result;
  }

  private static List<Object> normalizeObjects(
      List<Object> values, java.util.function.Function<Object, Object> normalizer) {
    var result = values.stream().map(normalizer).collect(Collectors.toCollection(ArrayList::new));
    result.sort(Comparator.comparing(Object::toString));
    return result;
  }

  private static void rejectUnknown(
      Map<String, Object> value, Set<String> allowed, String context) {
    var unknown = value.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
    if (!unknown.isEmpty()) {
      throw new BazelApplicationModelException(context + " contains unknown fields " + unknown);
    }
  }

  private static Object required(Map<String, Object> value, String name) {
    if (!value.containsKey(name) || value.get(name) == null) {
      throw new BazelApplicationModelException(
          "ApplicationModel is missing required field '" + name + "'");
    }
    return value.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String context) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new BazelApplicationModelException(context + " must be a JSON object");
    }
    return (Map<String, Object>) map;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new BazelApplicationModelException("ApplicationModel value must be a JSON array");
    }
    return (List<Object>) list;
  }

  private static String string(Object value, String context) {
    if (!(value instanceof String result)) {
      throw new BazelApplicationModelException(context + " must be a string");
    }
    return result;
  }

  private static BigDecimal number(Object value, String context) {
    if (!(value instanceof BigDecimal result)) {
      throw new BazelApplicationModelException(context + " must be a number");
    }
    return result;
  }

  private record Flag(int mask, String name) {}

  private record PathRoot(String token, Path path) {}
}
