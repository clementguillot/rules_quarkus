package com.clementguillot.quarkifier.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import java.util.List;
import org.junit.jupiter.api.Test;

class BazelModelInputReaderTest {

  @Test
  void readsRootsWithoutReorderingPublicDeps() {
    var roots =
        BazelModelInputReader.readRoots(
            """
            {"schemaVersion":"quarkus-bazel-roots-v1",\
            "applicationLabel":"@@//:app","rootIds":["@@//:z","@@//:a"]}
            """);

    assertEquals("@@//:app", roots.applicationLabel());
    assertEquals("@@//:z", roots.rootIds().get(0));
    assertEquals("@@//:a", roots.rootIds().get(1));
  }

  @Test
  void readsCompleteTargetFragment() {
    var fragment = BazelModelInputReader.readTargetFragment(targetFragment());

    assertTrue(fragment.workspaceTarget());
    assertEquals("java_library", fragment.ruleKind());
    assertEquals("bazel-bin/lib.jar", fragment.runtimeOutputJars().get(0).path());
    assertEquals("bazel-bin/lib.quarkus-classes", fragment.outputDirectories().get(0).path());
    assertEquals(DependencyScope.COMPILE, fragment.edges().get(0).scope());
    assertEquals("deps", fragment.edges().get(0).relation());
  }

  @Test
  void rejectsFragmentTargetIdThatDisagreesWithLabel() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelModelInputReader.readTargetFragment(
                    targetFragment()
                        .replace("\"targetId\":\"@@//:lib\"", "\"targetId\":\"other\"")));

    assertTrue(exception.getMessage().contains("must equal bazelLabel"));
  }

  @Test
  void readsRuntimeCatalogWithClassifierAndConflict() {
    var catalog = BazelModelInputReader.readRuntimeCatalog(runtimeCatalog());

    assertEquals(2, catalog.nodes().size());
    assertEquals("tests", catalog.nodes().get(0).coordinates().classifier());
    assertEquals("z.group:z-artifact", catalog.nodes().get(0).dependencies().get(0));
    assertTrue(catalog.nodes().get(0).optional());
    assertEquals(List.of("excluded.group:excluded"), catalog.nodes().get(0).exclusions());
    assertEquals(
        "z.group:z-artifact:2.0", catalog.conflictResolution().get("z.group:z-artifact:1.0"));
  }

  @Test
  void rejectsDanglingRuntimeCatalogEdge() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelModelInputReader.readRuntimeCatalog(
                    runtimeCatalog().replace("\"z.group:z-artifact\"]", "\"missing:artifact\"]")));

    assertTrue(exception.getMessage().contains("dangling runtime catalog edge"));
  }

  @Test
  void readsDeploymentCatalog() {
    var catalog = BazelModelInputReader.readDeploymentCatalog(deploymentCatalog());

    assertEquals("coursier", catalog.resolver());
    assertEquals("g:a:1.0", catalog.roots().get(0));
    assertEquals("g:b:2.0", catalog.nodes().get(0).dependencies().get(0));
    assertEquals("deployment/jars/g/a/1.0/a-1.0.jar", catalog.nodes().get(0).repoPath());
  }

  @Test
  void rejectsMachineLocalDeploymentPath() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelModelInputReader.readDeploymentCatalog(
                    deploymentCatalog()
                        .replace(
                            "deployment/jars/g/a/1.0/a-1.0.jar",
                            "/Users/person/.cache/a-1.0.jar")));

    assertTrue(exception.getMessage().contains("repository-owned deployment/jars path"));
  }

  @Test
  void readsConditionalCatalogWithModeSpecificDescriptorFacts() {
    var catalog = BazelModelInputReader.readConditionalCatalog(conditionalCatalog());

    assertEquals("g:feature:1.0", catalog.roots().get(0));
    assertEquals(
        "conditional/jars/g/feature/1.0/feature-1.0.jar", catalog.nodes().get(0).repoPath());
    assertEquals("g:feature:1.0", catalog.extensions().get(0).conditionalDependencies().get(0));
    assertEquals("g:dev:1.0", catalog.extensions().get(0).conditionalDevDependencies().get(0));
    assertEquals("g:trigger", catalog.extensions().get(1).dependencyConditions().get(0));
  }

  @Test
  void rejectsConditionalCatalogPathOutsideOwnedRepository() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelModelInputReader.readConditionalCatalog(
                    conditionalCatalog()
                        .replace(
                            "conditional/jars/g/feature/1.0/feature-1.0.jar",
                            "/home/person/.cache/feature-1.0.jar")));

    assertTrue(exception.getMessage().contains("repository-owned conditional/jars path"));
  }

  @Test
  void readsPlatformCatalog() {
    var catalog =
        BazelModelInputReader.readPlatformCatalog(
            """
            {
              "schemaVersion":"quarkus-bazel-platform-catalog-v1",
              "imports":[{"groupId":"io.quarkus.platform","artifactId":"quarkus-bom",\
                "classifier":"","type":"pom","version":"3.33.2"}],
              "propertyFiles":["model/platform-properties/io/quarkus.properties"],
              "properties":{"platform.custom":"value"}
            }
            """);

    assertEquals("quarkus-bom", catalog.imports().get(0).artifactId());
    assertEquals("value", catalog.properties().get("platform.custom"));
  }

  @Test
  void rejectsNonPomPlatformImport() {
    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () ->
                BazelModelInputReader.readPlatformCatalog(
                    """
                    {"schemaVersion":"quarkus-bazel-platform-catalog-v1",\
                    "imports":[{"groupId":"g","artifactId":"a","classifier":"",\
                    "type":"jar","version":"1"}],"propertyFiles":[],"properties":{}}
                    """));

    assertTrue(exception.getMessage().contains("unclassified POMs"));
  }

  private static String targetFragment() {
    return """
        {
          "schemaVersion":"quarkus-bazel-target-v1",
          "targetId":"@@//:lib",
          "bazelLabel":"@@//:lib",
          "workspaceName":"",
          "package":"",
          "targetName":"lib",
          "ruleKind":"java_library",
          "buildFile":"BUILD.bazel",
          "neverlink":false,
          "coordinates":null,
          "runtimeOutputJars":[{
            "path":"bazel-bin/lib.jar","shortPath":"lib.jar",\
            "owner":"@@//:lib","isSource":false
          }],
          "outputDirectories":[{
            "path":"bazel-bin/lib.quarkus-classes",\
            "shortPath":"lib.quarkus-classes",\
            "owner":"@@//:lib","isSource":false
          }],
          "sourceJars":[],
          "sources":[{
            "path":"src/main/java/App.java","shortPath":"src/main/java/App.java",\
            "owner":"@@//:App.java","isSource":true
          }],
          "resources":[],
          "edges":[{
            "targetId":"@@maven//:g_a","relation":"deps","scope":"compile",\
            "optional":false,"exclusions":[]
          }]
        }
        """;
  }

  private static String runtimeCatalog() {
    return """
        {
          "schemaVersion":"quarkus-bazel-runtime-catalog-v1",
          "nodes":[
            {
              "coordinateKey":"a.group:a-artifact:jar:tests",
              "targetName":"a_group_a_artifact_jar_tests",
              "coordinates":{
                "groupId":"a.group","artifactId":"a-artifact","classifier":"tests",\
                "type":"jar","version":"1.0"
              },
              "dependencies":["z.group:z-artifact"],
              "optional":true,
              "exclusions":["excluded.group:excluded"]
            },
            {
              "coordinateKey":"z.group:z-artifact",
              "targetName":"z_group_z_artifact",
              "coordinates":{
                "groupId":"z.group","artifactId":"z-artifact","classifier":"",\
                "type":"jar","version":"2.0"
              },
              "dependencies":[],
              "optional":false,
              "exclusions":[]
            }
          ],
          "directArtifacts":["a.group:a-artifact:jar:tests"],
          "conflictResolution":{"z.group:z-artifact:1.0":"z.group:z-artifact:2.0"}
        }
        """;
  }

  private static String deploymentCatalog() {
    return """
        {
          "schemaVersion":"quarkus-bazel-deployment-catalog-v1",
          "resolver":"coursier",
          "resolverReportVersion":"0.1.0",
          "roots":["g:a:1.0"],
          "droppedRoots":["g:missing:1.0"],
          "nodes":[
            {
              "coordinate":"g:a:1.0",
              "repoPath":"deployment/jars/g/a/1.0/a-1.0.jar",
              "dependencies":["g:b:2.0"],
              "exclusions":[]
            },
            {
              "coordinate":"g:b:2.0",
              "repoPath":"deployment/jars/g/b/2.0/b-2.0.jar",
              "dependencies":[],
              "exclusions":[]
            }
          ],
          "conflictResolution":{}
        }
        """;
  }

  private static String conditionalCatalog() {
    return """
        {
          "schemaVersion":"quarkus-bazel-conditional-catalog-v1",
          "resolver":"coursier",
          "resolverReportVersion":"0.1.0",
          "roots":["g:feature:1.0","g:dev:1.0"],
          "nodes":[
            {
              "coordinate":"g:feature:1.0",
              "repoPath":"conditional/jars/g/feature/1.0/feature-1.0.jar",
              "dependencies":[],
              "exclusions":[]
            },
            {
              "coordinate":"g:dev:1.0",
              "repoPath":"conditional/jars/g/dev/1.0/dev-1.0.jar",
              "dependencies":[],
              "exclusions":[]
            }
          ],
          "extensions":[
            {
              "runtimeArtifact":"g:base:1.0",
              "deploymentArtifact":"g:base-deployment:1.0",
              "conditionalDependencies":["g:feature:1.0"],
              "conditionalDevDependencies":["g:dev:1.0"],
              "dependencyConditions":[]
            },
            {
              "runtimeArtifact":"g:feature:1.0",
              "deploymentArtifact":"g:feature-deployment:1.0",
              "conditionalDependencies":[],
              "conditionalDevDependencies":[],
              "dependencyConditions":["g:trigger"]
            }
          ],
          "conflictResolution":{}
        }
        """;
  }
}
