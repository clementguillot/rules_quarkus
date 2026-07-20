package com.clementguillot.quarkifier.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ClasspathFacts;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyRelation;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Mode;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.NodeKind;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Producer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BazelApplicationModelReaderTest {

  @Test
  void packagedSchemaExampleIsAcceptedByTheStrictReader() throws IOException {
    String example = resource("/model/quarkus-bazel-model-v1.minimal.json");
    String schema = resource("/model/quarkus-bazel-model-v1.schema.json");

    assertEquals("@@//:app", BazelApplicationModelReader.read(example).applicationId());
    assertTrue(schema.contains("\"additionalProperties\": false"));
    assertTrue(schema.contains("\"topLevelRuntimeExtension\""));
  }

  @Test
  void readsCompleteVersionOneDocument() {
    BazelApplicationModel model = BazelApplicationModelReader.read(validDocument());

    assertEquals(BazelApplicationModel.SCHEMA_VERSION, model.schemaVersion());
    assertEquals("3.33.2", model.quarkusVersion());
    assertEquals(Mode.NORMAL, model.mode());
    assertEquals("app", model.applicationId());
    assertEquals(2, model.nodes().size());
    assertEquals(NodeKind.APPLICATION, model.nodes().get(0).kind());
    assertEquals(DependencyScope.COMPILE, model.nodes().get(0).dependencies().get(0).scope());
    assertEquals("//:app", model.workspaceModules().get(0).bazelLabel());
    assertEquals("io.quarkus.platform", model.platform().imports().get(0).groupId());
    assertEquals("runtime lock + Bazel aspect", model.diagnostics().provenance().get(0));
  }

  @Test
  void canonicalWriterRoundTripsAndIgnoresInputNodeOrder() {
    BazelApplicationModel model = BazelApplicationModelReader.read(validDocument());
    String canonical = BazelApplicationModelWriter.toJson(model);
    var reordered =
        new BazelApplicationModel(
            model.schemaVersion(),
            model.producer(),
            model.quarkusVersion(),
            model.mode(),
            model.applicationId(),
            List.of(model.nodes().get(1), model.nodes().get(0)),
            model.workspaceModules(),
            model.platform(),
            model.diagnostics());

    assertEquals(model, BazelApplicationModelReader.read(canonical));
    assertEquals(canonical, BazelApplicationModelWriter.toJson(reordered));
    assertTrue(canonical.endsWith("\n"));
  }

  @Test
  void canonicalWriterEscapesStringsAndPreservesUnicode() {
    BazelApplicationModel model = BazelApplicationModelReader.read(validDocument());
    var escaped =
        new BazelApplicationModel(
            model.schemaVersion(),
            new Producer("rules_\"quarkus\n🚀", model.producer().version()),
            model.quarkusVersion(),
            model.mode(),
            model.applicationId(),
            model.nodes(),
            model.workspaceModules(),
            model.platform(),
            model.diagnostics());

    String canonical = BazelApplicationModelWriter.toJson(escaped);

    assertTrue(canonical.contains("rules_\\\"quarkus\\n🚀"));
    assertEquals(escaped, BazelApplicationModelReader.read(canonical));
  }

  @Test
  void rejectsUnknownSchemaVersion() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelApplicationModelReader.read(
                    validDocument().replace("quarkus-bazel-model-v1", "future-v2")));

    assertTrue(exception.getMessage().contains("unsupported schema version"));
  }

  @Test
  void rejectsUnknownFieldsInsteadOfSilentlyIgnoringProducerDrift() {
    String document =
        validDocument().replace("\"schemaVersion\":", "\"unexpected\":true,\"schemaVersion\":");

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class, () -> BazelApplicationModelReader.read(document));

    assertTrue(exception.getMessage().contains("unknown field 'unexpected'"));
  }

  @Test
  void rejectsDuplicateJsonMembers() {
    String document =
        validDocument()
            .replace(
                "\"schemaVersion\":\"quarkus-bazel-model-v1\"",
                "\"schemaVersion\":\"quarkus-bazel-model-v1\","
                    + "\"schemaVersion\":\"quarkus-bazel-model-v1\"");

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class, () -> BazelApplicationModelReader.read(document));

    assertTrue(exception.getMessage().contains("duplicate object member 'schemaVersion'"));
  }

  @Test
  void rejectsTrailingJsonContent() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () -> BazelApplicationModelReader.read(validDocument() + " true"));

    assertTrue(exception.getMessage().contains("unexpected trailing content"));
  }

  @Test
  void rejectsDanglingDependencyEdges() {
    String document = validDocument().replace("\"targetId\":\"rest\"", "\"targetId\":\"missing\"");

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class, () -> BazelApplicationModelReader.read(document));

    assertTrue(exception.getMessage().contains("does not identify a node"));
  }

  @Test
  void rejectsOrphanNodesRatherThanAdoptingThem() {
    String document =
        validDocument()
            .replace(
                "\n  ],\n  \"workspaceModules\":[",
                """
                ,
                            {
                              "id":"orphan",
                              "kind":"maven",
                              "coordinates":{
                                "groupId":"org.example",
                                "artifactId":"orphan",
                                "classifier":"",
                                "type":"jar",
                                "version":"1.0"
                              },
                              "paths":["external/maven/org/example/orphan/1.0/orphan-1.0.jar"],
                              "dependencies":[],
                              "classpath":{
                                "directFromApplication":false,
                                "runtimeClasspath":true,
                                "deploymentClasspath":true,
                                "compileOnly":false,
                                "optional":false,
                                "reloadable":false,
                                "topLevelRuntimeExtension":false
                              },
                              "workspaceModuleId":null,
                              "bazelLabel":"@maven//:org_example_orphan"
                            }
                          ],
                          "workspaceModules":[
                """);

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class, () -> BazelApplicationModelReader.read(document));

    assertTrue(exception.getMessage().contains("unreachable from the application"));
  }

  @Test
  void acceptsQuarkusDeploymentInjectionShape() {
    BazelApplicationModel model = BazelApplicationModelReader.read(validDocument());
    Node app = model.nodes().get(0);
    Node runtimeExtension = model.nodes().get(1);
    var deployment =
        new Node(
            "rest-deployment",
            NodeKind.DEPLOYMENT,
            new ArtifactCoordinates("io.quarkus", "quarkus-rest-deployment", "", "jar", "3.33.2"),
            List.of("deployment/quarkus-rest-deployment-3.33.2.jar"),
            List.of(
                new DependencyEdge(
                    runtimeExtension.id(),
                    DependencyRelation.DEPLOYMENT,
                    DependencyScope.COMPILE,
                    false,
                    List.of())),
            new ClasspathFacts(false, false, true, false, false, false, false),
            null,
            null);
    var injectedApp =
        new Node(
            app.id(),
            app.kind(),
            app.coordinates(),
            app.paths(),
            List.of(
                new DependencyEdge(
                    deployment.id(),
                    DependencyRelation.DEPLOYMENT,
                    DependencyScope.COMPILE,
                    false,
                    List.of())),
            app.classpath(),
            app.workspaceModuleId(),
            app.bazelLabel());
    var injected =
        new BazelApplicationModel(
            model.schemaVersion(),
            model.producer(),
            model.quarkusVersion(),
            model.mode(),
            model.applicationId(),
            List.of(injectedApp, runtimeExtension, deployment),
            model.workspaceModules(),
            model.platform(),
            model.diagnostics());

    BazelApplicationModelValidator.validate(injected);
  }

  @Test
  void rejectsCompileOnlyRuntimeContradiction() {
    String document = validDocument().replace("\"compileOnly\":false", "\"compileOnly\":true");

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class, () -> BazelApplicationModelReader.read(document));

    assertTrue(exception.getMessage().contains("cannot both be true"));
  }

  private static String validDocument() {
    return """
        {
          "schemaVersion":"quarkus-bazel-model-v1",
          "producer":{"name":"rules_quarkus","version":"0.5.0"},
          "quarkusVersion":"3.33.2",
          "mode":"normal",
          "applicationId":"app",
          "nodes":[
            {
              "id":"app",
              "kind":"application",
              "coordinates":{
                "groupId":"bazel.workspace",
                "artifactId":"app",
                "classifier":"",
                "type":"jar",
                "version":"1.0"
              },
              "paths":["bazel-bin/app.jar"],
              "dependencies":[{
                "targetId":"rest",
                "relation":"deps",
                "scope":"compile",
                "optional":false,
                "exclusions":[]
              }],
              "classpath":{
                "directFromApplication":false,
                "runtimeClasspath":false,
                "deploymentClasspath":false,
                "compileOnly":false,
                "optional":false,
                "reloadable":false,
                "topLevelRuntimeExtension":false
              },
              "workspaceModuleId":"module-app",
              "bazelLabel":"//:app"
            },
            {
              "id":"rest",
              "kind":"maven",
              "coordinates":{
                "groupId":"io.quarkus",
                "artifactId":"quarkus-rest",
                "classifier":"",
                "type":"jar",
                "version":"3.33.2"
              },
              "paths":["external/maven/io/quarkus/quarkus-rest/3.33.2/quarkus-rest-3.33.2.jar"],
              "dependencies":[],
              "classpath":{
                "directFromApplication":true,
                "runtimeClasspath":true,
                "deploymentClasspath":true,
                "compileOnly":false,
                "optional":false,
                "reloadable":false,
                "topLevelRuntimeExtension":false
              },
              "workspaceModuleId":null,
              "bazelLabel":"@maven//:io_quarkus_quarkus_rest"
            }
          ],
          "workspaceModules":[{
            "id":"module-app",
            "bazelLabel":"//:app",
            "moduleDir":".",
            "buildDir":"bazel-bin",
            "buildFile":"BUILD.bazel",
            "sourceSets":[{
              "classifier":"",
              "sourceDirectories":["src/main/java"],
              "resourceDirectories":["src/main/resources"],
              "outputDirectories":["bazel-bin/app.jar"],
              "generatedSourceDirectories":[],
              "generatedResourceDirectories":[]
            }],
            "directDependencyIds":["rest"],
            "dependencyConstraints":[],
            "testClasspathExclusions":[],
            "additionalTestClasspathElements":[],
            "parentId":null
          }],
          "platform":{
            "imports":[{
              "groupId":"io.quarkus.platform",
              "artifactId":"quarkus-bom",
              "classifier":"",
              "type":"pom",
              "version":"3.33.2"
            }],
            "properties":{"platform.quarkus.native.builder-image":"mandrel"},
            "releases":[{
              "platformKey":"io.quarkus.platform",
              "stream":"3.33",
              "version":"3.33.2",
              "memberBoms":[{
                "groupId":"io.quarkus.platform",
                "artifactId":"quarkus-bom",
                "classifier":"",
                "type":"pom",
                "version":"3.33.2"
              }]
            }]
          },
          "diagnostics":{
            "warnings":[],
            "provenance":["runtime lock + Bazel aspect"]
          }
        }
        """;
  }

  private static String resource(String name) throws IOException {
    try (var input = BazelApplicationModelReaderTest.class.getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("Missing test resource " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
