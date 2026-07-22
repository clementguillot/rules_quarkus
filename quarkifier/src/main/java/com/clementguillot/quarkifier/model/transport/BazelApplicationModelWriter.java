package com.clementguillot.quarkifier.model.transport;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactKey;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.PlatformRelease;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.SourceSet;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.WorkspaceModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON writer for the Bazel application-model transport. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods"
})
public final class BazelApplicationModelWriter {

  private static final Comparator<ArtifactCoordinates> COORDINATE_ORDER =
      Comparator.comparing(ArtifactCoordinates::groupId)
          .thenComparing(ArtifactCoordinates::artifactId)
          .thenComparing(ArtifactCoordinates::classifier)
          .thenComparing(ArtifactCoordinates::type)
          .thenComparing(ArtifactCoordinates::version);
  private static final Comparator<ArtifactKey> ARTIFACT_KEY_ORDER =
      Comparator.comparing(ArtifactKey::groupId).thenComparing(ArtifactKey::artifactId);

  private BazelApplicationModelWriter() {}

  public static void write(BazelApplicationModel model, Path path) throws IOException {
    Files.writeString(path, toJson(model), StandardCharsets.UTF_8);
  }

  public static String toJson(BazelApplicationModel model) {
    BazelApplicationModelValidator.validate(model);
    var json = new StringBuilder(4096);
    json.append('{');
    member(json, "schemaVersion", model.schemaVersion());
    comma(json);
    json.append("\"producer\":{");
    member(json, "name", model.producer().name());
    comma(json);
    member(json, "version", model.producer().version());
    json.append('}');
    comma(json);
    member(json, "quarkusVersion", model.quarkusVersion());
    comma(json);
    member(json, "mode", wireName(model.mode()));
    comma(json);
    member(json, "applicationId", model.applicationId());
    comma(json);
    json.append("\"nodes\":");
    nodes(json, model.nodes());
    comma(json);
    json.append("\"workspaceModules\":");
    workspaceModules(json, model.workspaceModules());
    comma(json);
    json.append("\"platform\":{\"imports\":");
    coordinatesArray(json, model.platform().imports());
    comma(json);
    json.append("\"properties\":");
    stringMap(json, model.platform().properties());
    comma(json);
    json.append("\"releases\":");
    platformReleases(json, model.platform().releases());
    json.append("}}\n");
    return json.toString();
  }

  private static void nodes(StringBuilder json, List<Node> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(Comparator.comparing(Node::id));
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      node(json, ordered.get(index));
    }
    json.append(']');
  }

  private static void node(StringBuilder json, Node value) {
    json.append('{');
    member(json, "id", value.id());
    comma(json);
    member(json, "kind", wireName(value.kind()));
    comma(json);
    json.append("\"coordinates\":");
    coordinates(json, value.coordinates());
    comma(json);
    json.append("\"paths\":");
    stringArray(json, value.paths());
    comma(json);
    json.append("\"dependencies\":");
    dependencyEdges(json, value.dependencies());
    comma(json);
    json.append("\"classpath\":{");
    booleanMember(json, "directFromApplication", value.classpath().directFromApplication());
    comma(json);
    booleanMember(json, "runtimeClasspath", value.classpath().runtimeClasspath());
    comma(json);
    booleanMember(json, "deploymentClasspath", value.classpath().deploymentClasspath());
    comma(json);
    booleanMember(json, "compileOnly", value.classpath().compileOnly());
    comma(json);
    booleanMember(json, "optional", value.classpath().optional());
    comma(json);
    booleanMember(json, "reloadable", value.classpath().reloadable());
    comma(json);
    booleanMember(json, "topLevelRuntimeExtension", value.classpath().topLevelRuntimeExtension());
    json.append('}');
    comma(json);
    nullableMember(json, "workspaceModuleId", value.workspaceModuleId());
    comma(json);
    nullableMember(json, "bazelLabel", value.bazelLabel());
    json.append('}');
  }

  private static void dependencyEdges(StringBuilder json, List<DependencyEdge> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(
        Comparator.comparing(DependencyEdge::targetId)
            .thenComparing(edge -> wireName(edge.relation()))
            .thenComparing(edge -> wireName(edge.scope())));
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      DependencyEdge value = ordered.get(index);
      json.append('{');
      member(json, "targetId", value.targetId());
      comma(json);
      member(json, "relation", wireName(value.relation()));
      comma(json);
      member(json, "scope", wireName(value.scope()));
      comma(json);
      booleanMember(json, "optional", value.optional());
      comma(json);
      json.append("\"exclusions\":");
      artifactKeys(json, value.exclusions());
      json.append('}');
    }
    json.append(']');
  }

  private static void workspaceModules(StringBuilder json, List<WorkspaceModule> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(Comparator.comparing(WorkspaceModule::id));
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      workspaceModule(json, ordered.get(index));
    }
    json.append(']');
  }

  private static void workspaceModule(StringBuilder json, WorkspaceModule value) {
    json.append('{');
    member(json, "id", value.id());
    comma(json);
    member(json, "bazelLabel", value.bazelLabel());
    comma(json);
    member(json, "moduleDir", value.moduleDir());
    comma(json);
    member(json, "buildDir", value.buildDir());
    comma(json);
    member(json, "buildFile", value.buildFile());
    comma(json);
    json.append("\"sourceSets\":");
    sourceSets(json, value.sourceSets());
    comma(json);
    json.append("\"directDependencyIds\":");
    stringArray(json, sorted(value.directDependencyIds()));
    comma(json);
    json.append("\"dependencyConstraints\":");
    artifactKeys(json, value.dependencyConstraints());
    comma(json);
    json.append("\"testClasspathExclusions\":");
    artifactKeys(json, value.testClasspathExclusions());
    comma(json);
    json.append("\"additionalTestClasspathElements\":");
    stringArray(json, sorted(value.additionalTestClasspathElements()));
    comma(json);
    nullableMember(json, "parentId", value.parentId());
    json.append('}');
  }

  private static void sourceSets(StringBuilder json, List<SourceSet> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(Comparator.comparing(SourceSet::classifier));
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      SourceSet value = ordered.get(index);
      json.append('{');
      member(json, "classifier", value.classifier());
      comma(json);
      json.append("\"sourceDirectories\":");
      stringArray(json, sorted(value.sourceDirectories()));
      comma(json);
      json.append("\"resourceDirectories\":");
      stringArray(json, sorted(value.resourceDirectories()));
      comma(json);
      json.append("\"outputDirectories\":");
      stringArray(json, sorted(value.outputDirectories()));
      comma(json);
      json.append("\"generatedSourceDirectories\":");
      stringArray(json, sorted(value.generatedSourceDirectories()));
      comma(json);
      json.append("\"generatedResourceDirectories\":");
      stringArray(json, sorted(value.generatedResourceDirectories()));
      json.append('}');
    }
    json.append(']');
  }

  private static void coordinatesArray(StringBuilder json, List<ArtifactCoordinates> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(COORDINATE_ORDER);
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      coordinates(json, ordered.get(index));
    }
    json.append(']');
  }

  private static void platformReleases(StringBuilder json, List<PlatformRelease> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(
        Comparator.comparing(PlatformRelease::platformKey)
            .thenComparing(PlatformRelease::stream)
            .thenComparing(PlatformRelease::version));
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      PlatformRelease value = ordered.get(index);
      json.append('{');
      member(json, "platformKey", value.platformKey());
      comma(json);
      member(json, "stream", value.stream());
      comma(json);
      member(json, "version", value.version());
      comma(json);
      json.append("\"memberBoms\":");
      coordinatesArray(json, value.memberBoms());
      json.append('}');
    }
    json.append(']');
  }

  private static void coordinates(StringBuilder json, ArtifactCoordinates value) {
    json.append('{');
    member(json, "groupId", value.groupId());
    comma(json);
    member(json, "artifactId", value.artifactId());
    comma(json);
    member(json, "classifier", value.classifier());
    comma(json);
    member(json, "type", value.type());
    comma(json);
    member(json, "version", value.version());
    json.append('}');
  }

  private static void artifactKeys(StringBuilder json, List<ArtifactKey> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(ARTIFACT_KEY_ORDER);
    json.append('[');
    for (int index = 0; index < ordered.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      ArtifactKey value = ordered.get(index);
      json.append('{');
      member(json, "groupId", value.groupId());
      comma(json);
      member(json, "artifactId", value.artifactId());
      json.append('}');
    }
    json.append(']');
  }

  private static void stringMap(StringBuilder json, Map<String, String> values) {
    json.append('{');
    int index = 0;
    for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
      if (index > 0) {
        comma(json);
      }
      member(json, entry.getKey(), entry.getValue());
      index++;
    }
    json.append('}');
  }

  private static void stringArray(StringBuilder json, List<String> values) {
    json.append('[');
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        comma(json);
      }
      StrictJson.appendQuoted(json, values.get(index));
    }
    json.append(']');
  }

  private static List<String> sorted(List<String> values) {
    var ordered = new ArrayList<>(values);
    ordered.sort(String::compareTo);
    return ordered;
  }

  private static String wireName(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static void member(StringBuilder json, String name, String value) {
    StrictJson.appendQuoted(json, name);
    json.append(':');
    StrictJson.appendQuoted(json, value);
  }

  private static void nullableMember(StringBuilder json, String name, String value) {
    StrictJson.appendQuoted(json, name);
    json.append(':');
    if (value == null) {
      json.append("null");
    } else {
      StrictJson.appendQuoted(json, value);
    }
  }

  private static void booleanMember(StringBuilder json, String name, boolean value) {
    StrictJson.appendQuoted(json, name);
    json.append(':').append(value);
  }

  private static void comma(StringBuilder json) {
    json.append(',');
  }
}
