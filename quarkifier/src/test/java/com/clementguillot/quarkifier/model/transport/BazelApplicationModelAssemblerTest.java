package com.clementguillot.quarkifier.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactKey;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Mode;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.NodeKind;
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
import io.quarkus.maven.dependency.DependencyFlags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BazelApplicationModelAssemblerTest {

  private static final String APP = "@@//:app";
  private static final String EXT = "@@maven//:io_quarkus_example";
  private static final String COMMON = "@@maven//:org_example_common";
  private static final String TEST_ONLY = "@@maven//:org_example_test_only";
  private static final String TEST_PRIVATE = "@@maven//:org_example_test_private";
  private static final String DEPLOYMENT = "io.quarkus:example-deployment::jar:3.33.2";
  private static final String LOCAL_EXTENSION = "@@//extension:greeting";
  private static final String RAW_LOCAL_RUNTIME = "@@//extension/runtime:runtime";
  private static final String LOCAL_DEPLOYMENT = "local-deployment:@@//extension:greeting";

  @TempDir Path tempDir;

  @Test
  void preservesRuntimeExtensionEdgeAndAddsDeploymentGraphAtParent() throws IOException {
    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(inputs(true, DEPLOYMENT));

    Node app = node(model, APP);
    Node runtimeExtension = node(model, EXT);
    Node deployment = node(model, "deployment:" + DEPLOYMENT);

    assertEquals(NodeKind.APPLICATION, app.kind());
    assertEquals(List.of(EXT, deployment.id()), targets(app));
    assertEquals(List.of(COMMON), targets(runtimeExtension));
    assertTrue(targets(deployment).contains(EXT));
    assertTrue(targets(deployment).contains("deployment:io.quarkus:helper::jar:3.33.2"));
    assertEquals(
        List.of(new ArtifactKey("bad.group", "excluded")),
        deployment.dependencies().stream()
            .filter(edge -> edge.targetId().equals("deployment:io.quarkus:helper::jar:3.33.2"))
            .findFirst()
            .orElseThrow()
            .exclusions());
    assertTrue(runtimeExtension.classpath().directFromApplication());
    assertTrue(runtimeExtension.classpath().runtimeClasspath());
    assertTrue(runtimeExtension.classpath().deploymentClasspath());
    assertFalse(deployment.classpath().runtimeClasspath());
    assertEquals(1, model.workspaceModules().size());
    assertEquals(APP, model.workspaceModules().get(0).id());
    assertEquals(
        List.of("src/main/java"),
        model.workspaceModules().get(0).sourceSets().get(0).sourceDirectories());
    assertTrue(
        model
            .workspaceModules()
            .get(0)
            .sourceSets()
            .get(0)
            .outputDirectories()
            .get(0)
            .endsWith(".quarkus-classes"));
    assertEquals(
        model, BazelApplicationModelReader.read(BazelApplicationModelWriter.toJson(model)));

    var quarkusModel = ExplicitApplicationModelBuilder.build(model);
    var quarkusRuntimeExtension =
        quarkusModel.getDependencies().stream()
            .filter(
                dependency ->
                    "io.quarkus".equals(dependency.getGroupId())
                        && "example".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow();
    assertEquals("demo", quarkusModel.getAppArtifact().getArtifactId());
    assertTrue(quarkusRuntimeExtension.isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT));
    assertTrue(
        quarkusRuntimeExtension.isFlagSet(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT));
    assertTrue(quarkusRuntimeExtension.isFlagSet(DependencyFlags.DIRECT));
    assertTrue(quarkusRuntimeExtension.isRuntimeCp());
    assertTrue(quarkusRuntimeExtension.isDeploymentCp());
    assertEquals(1, quarkusRuntimeExtension.getDependencies().size());
  }

  @Test
  void failsWhenDescriptorDeploymentArtifactIsMissingFromResolverGraph() throws IOException {
    var inputs = inputs(false, "io.quarkus:missing-deployment:3.33.2");

    BazelApplicationModelException exception =
        assertThrows(
            BazelApplicationModelException.class,
            () -> BazelApplicationModelAssembler.assemble(inputs));

    assertTrue(exception.getMessage().contains("descriptor-declared artifact"));
    assertTrue(exception.getMessage().contains("missing-deployment"));
  }

  @Test
  void retainsAspectRelationshipWhenBothEndpointsSurviveResolverReachability() throws IOException {
    var base = inputs(true, DEPLOYMENT);
    var fragments = new java.util.ArrayList<>(base.targetFragments());
    TargetFragment app = fragments.remove(0);
    fragments.add(
        0,
        new TargetFragment(
            app.targetId(),
            app.bazelLabel(),
            app.workspaceName(),
            app.packageName(),
            app.targetName(),
            app.ruleKind(),
            app.buildFile(),
            app.neverlink(),
            app.coordinates(),
            app.runtimeOutputJars(),
            app.outputDirectories(),
            app.sourceJars(),
            app.sources(),
            app.resources(),
            List.of(edge(EXT), edge(COMMON))));
    var prunedCatalog =
        new RuntimeCatalog(
            List.of(
                new RuntimeCatalogNode(
                    "io.quarkus:example",
                    "io_quarkus_example",
                    coords("io.quarkus", "example", "3.33.2"),
                    List.of()),
                base.runtimeCatalog().nodes().get(1)),
            List.of("io.quarkus:example"),
            Map.of());
    var contextInputs =
        new BazelApplicationModelAssembler.Inputs(
            base.roots(),
            fragments,
            prunedCatalog,
            emptyConditionalCatalog(),
            base.deploymentCatalog(),
            base.platformCatalog(),
            Map.of(),
            Map.of(),
            Map.of(),
            base.deploymentPaths(),
            base.platformPropertyPaths(),
            base.runtimeClasspathPaths(),
            base.deploymentClasspathPaths(),
            base.modelPrivateTargetIds(),
            base.quarkusVersion(),
            base.mode(),
            base.applicationName(),
            base.applicationVersion(),
            base.producerVersion());

    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(contextInputs);

    assertEquals(List.of(COMMON), targets(node(model, EXT)));
  }

  @Test
  void transportsMavenOptionalityAndExclusions() throws IOException {
    var base = inputs(true, DEPLOYMENT);
    RuntimeCatalogNode common = base.runtimeCatalog().nodes().get(1);
    var runtime =
        new RuntimeCatalog(
            List.of(
                base.runtimeCatalog().nodes().get(0),
                new RuntimeCatalogNode(
                    common.coordinateKey(),
                    common.targetName(),
                    common.coordinates(),
                    common.dependencies(),
                    true,
                    List.of("excluded.group:excluded"))),
            base.runtimeCatalog().directArtifacts(),
            base.runtimeCatalog().conflictResolution());

    BazelApplicationModel model =
        BazelApplicationModelAssembler.assemble(withRuntimeCatalog(base, runtime));

    var edge = node(model, EXT).dependencies().get(0);
    assertTrue(edge.optional());
    assertEquals(List.of(new ArtifactKey("excluded.group", "excluded")), edge.exclusions());
    assertTrue(node(model, COMMON).classpath().optional());

    var quarkusModel = ExplicitApplicationModelBuilder.build(model);
    var quarkusCommon =
        quarkusModel.getDependencies().stream()
            .filter(dependency -> "common".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow();
    assertTrue(quarkusCommon.isFlagSet(DependencyFlags.OPTIONAL));
  }

  @Test
  void resolvesLocalDeploymentRuntimeAliasWithoutCreatingPhantomWorkspaceModule()
      throws IOException {
    Path appJar = jar("local-app.jar", null);
    Path runtimeJar =
        jar("greeting-extension.jar", "com.example:greeting-extension-deployment:1.0.0");
    Path deploymentJar = jar("greeting-extension-deployment.jar", null);
    List<TargetFragment> fragments =
        List.of(
            local(APP, appJar, List.of(edge(LOCAL_EXTENSION))),
            withOutputDirectory(
                explicitLocal(
                    LOCAL_EXTENSION,
                    "greeting-extension",
                    runtimeJar,
                    coords("com.example", "greeting-extension", "1.0.0"),
                    List.of()),
                "bazel-bin/extension/runtime/packaged.quarkus-classes"),
            withOutputDirectory(
                local(RAW_LOCAL_RUNTIME, runtimeJar, List.of()),
                "bazel-bin/extension/runtime/raw.quarkus-classes"),
            explicitLocal(
                LOCAL_DEPLOYMENT,
                "greeting-extension-deployment",
                deploymentJar,
                coords("com.example", "greeting-extension-deployment", "1.0.0"),
                List.of(edge(RAW_LOCAL_RUNTIME))));
    var inputs =
        new BazelApplicationModelAssembler.Inputs(
            new Roots("@@//:quarkus", List.of(APP)),
            fragments,
            new RuntimeCatalog(List.of(), List.of(), Map.of()),
            emptyConditionalCatalog(),
            new DeploymentCatalog("coursier", "0.1.0", List.of(), List.of(), List.of(), Map.of()),
            new PlatformCatalog(List.of(), List.of(), Map.of()),
            Map.of("com.example:greeting-extension-deployment:1.0.0", LOCAL_DEPLOYMENT),
            Map.of(RAW_LOCAL_RUNTIME, LOCAL_EXTENSION),
            Map.of(),
            Map.of(),
            Map.of(),
            Set.of(appJar.toString(), runtimeJar.toString()),
            Set.of(appJar.toString(), runtimeJar.toString(), deploymentJar.toString()),
            Set.of(),
            "3.33.2",
            Mode.NORMAL,
            "demo",
            "1.0.0",
            "test");

    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(inputs);

    assertEquals(List.of(LOCAL_EXTENSION, LOCAL_DEPLOYMENT), targets(node(model, APP)));
    assertEquals(List.of(LOCAL_EXTENSION), targets(node(model, LOCAL_DEPLOYMENT)));
    assertTrue(node(model, LOCAL_EXTENSION).classpath().directFromApplication());
    assertFalse(model.nodes().stream().anyMatch(node -> RAW_LOCAL_RUNTIME.equals(node.id())));
    assertEquals(
        List.of(APP, LOCAL_EXTENSION, LOCAL_DEPLOYMENT),
        model.workspaceModules().stream().map(module -> module.id()).sorted().toList());
    assertEquals(
        List.of("bazel-bin/extension/runtime/packaged.quarkus-classes"),
        model.workspaceModules().stream()
            .filter(module -> LOCAL_EXTENSION.equals(module.id()))
            .findFirst()
            .orElseThrow()
            .sourceSets()
            .get(0)
            .outputDirectories());

    var quarkusModel = ExplicitApplicationModelBuilder.build(model);
    var runtimeExtension =
        quarkusModel.getDependencies().stream()
            .filter(dependency -> "greeting-extension".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow();
    assertTrue(runtimeExtension.isWorkspaceModule());
    assertFalse(runtimeExtension.isReloadable());
    assertFalse(
        quarkusModel.getReloadableWorkspaceDependencies().contains(runtimeExtension.getKey()));
    assertTrue(
        quarkusModel.getDependencies().stream()
            .filter(
                dependency -> "greeting-extension-deployment".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow()
            .isWorkspaceModule());
  }

  @Test
  void preservesPlatformImportsPropertiesAndReleaseMetadata() throws IOException {
    Path propertiesFile = tempDir.resolve("quarkus-platform.properties");
    Properties platformProperties = new Properties();
    platformProperties.setProperty("platform.quarkus.native.builder-image", "mandrel");
    platformProperties.setProperty(
        "platform.release-info@io.quarkus.platform$3.33#3.33.2",
        "io.quarkus.platform:quarkus-bom::pom:3.33.2,"
            + "io.quarkus.platform:quarkus-camel-bom::pom:3.33.2");
    try (var output = Files.newOutputStream(propertiesFile)) {
      platformProperties.store(output, null);
    }

    var base = inputs(true, DEPLOYMENT);
    var platformCatalog =
        new PlatformCatalog(
            List.of(pomCoords("io.quarkus.platform", "quarkus-bom", "3.33.2")),
            List.of("model/platform-properties/quarkus.properties"),
            Map.of("platform.custom", "override"));
    var platformInputs =
        new BazelApplicationModelAssembler.Inputs(
            base.roots(),
            base.targetFragments(),
            base.runtimeCatalog(),
            emptyConditionalCatalog(),
            base.deploymentCatalog(),
            platformCatalog,
            base.localDeployments(),
            base.localRuntimeAliases(),
            Map.of(),
            base.deploymentPaths(),
            Map.of("model/platform-properties/quarkus.properties", propertiesFile.toString()),
            base.runtimeClasspathPaths(),
            base.deploymentClasspathPaths(),
            base.modelPrivateTargetIds(),
            base.quarkusVersion(),
            base.mode(),
            base.applicationName(),
            base.applicationVersion(),
            base.producerVersion());

    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(platformInputs);

    assertEquals(platformCatalog.imports(), model.platform().imports());
    assertEquals(
        "mandrel", model.platform().properties().get("platform.quarkus.native.builder-image"));
    assertEquals("override", model.platform().properties().get("platform.custom"));
    assertEquals(1, model.platform().releases().size());
    assertEquals(2, model.platform().releases().get(0).memberBoms().size());

    var platforms = ExplicitApplicationModelBuilder.build(model).getPlatforms();
    assertEquals(1, platforms.getImportedPlatformBoms().size());
    assertEquals(1, platforms.getPlatformReleaseInfo().size());
    assertEquals(
        "mandrel", platforms.getPlatformProperties().get("platform.quarkus.native.builder-image"));
  }

  @Test
  void marksFirstExtensionOnAWorkspaceBranchTopLevelAndStopsReloadabilityAtIt() throws IOException {
    var base = inputs(true, DEPLOYMENT);
    Path middleJar = jar("middle.jar", null);
    var fragments = new java.util.ArrayList<TargetFragment>();
    for (TargetFragment fragment : base.targetFragments()) {
      if (APP.equals(fragment.targetId())) {
        Path appJar = Path.of(fragment.runtimeOutputJars().get(0).path());
        fragments.add(local(APP, appJar, List.of(edge("@@//:middle"))));
      } else {
        fragments.add(fragment);
      }
    }
    fragments.add(local("@@//:middle", middleJar, List.of(edge(EXT))));
    var runtimePaths = new java.util.HashSet<>(base.runtimeClasspathPaths());
    runtimePaths.add(middleJar.toString());
    var deploymentPaths = new java.util.HashSet<>(base.deploymentClasspathPaths());
    deploymentPaths.add(middleJar.toString());
    var branchInputs =
        new BazelApplicationModelAssembler.Inputs(
            base.roots(),
            fragments,
            base.runtimeCatalog(),
            emptyConditionalCatalog(),
            base.deploymentCatalog(),
            base.platformCatalog(),
            base.localDeployments(),
            base.localRuntimeAliases(),
            Map.of(),
            base.deploymentPaths(),
            base.platformPropertyPaths(),
            runtimePaths,
            deploymentPaths,
            base.modelPrivateTargetIds(),
            base.quarkusVersion(),
            base.mode(),
            base.applicationName(),
            base.applicationVersion(),
            base.producerVersion());

    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(branchInputs);

    assertTrue(node(model, "@@//:middle").classpath().reloadable());
    assertFalse(node(model, EXT).classpath().directFromApplication());
    assertTrue(node(model, EXT).classpath().topLevelRuntimeExtension());
    var extension =
        ExplicitApplicationModelBuilder.build(model).getDependencies().stream()
            .filter(dependency -> "example".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow();
    assertTrue(extension.isFlagSet(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT));
    assertFalse(extension.isReloadable());
  }

  @Test
  void testModePromotesMainLibraryAndAttachesTestSources() throws IOException {
    var base = inputs(true, DEPLOYMENT);
    String testRoot = "@@//:test_lib";
    Path testJar = jar("test-lib.jar", null);
    Path additionalRootJar = jar("additional-test-root.jar", null);
    Path testOnlyJar = jar("test-only.jar", null);
    Path testPrivateJar = jar("test-private.jar", null);
    var fragments = new java.util.ArrayList<>(base.targetFragments());
    fragments.add(
        testLocal(
            testRoot,
            testJar,
            List.of(edge(APP), edge(COMMON), edge(TEST_ONLY), edge(TEST_PRIVATE))));
    String additionalRoot = "@@//:additional_test_root";
    fragments.add(
        explicitLocal(
            additionalRoot,
            "additional-test-root",
            additionalRootJar,
            coords("bazel.workspace", "additional-test-root", "unspecified"),
            List.of()));
    fragments.add(external(TEST_ONLY, "org_example_test_only", testOnlyJar, List.of()));
    fragments.add(external(TEST_PRIVATE, "org_example_test_private", testPrivateJar, List.of()));
    var runtimeNodes = new java.util.ArrayList<>(base.runtimeCatalog().nodes());
    runtimeNodes.add(
        new RuntimeCatalogNode(
            "org.example:test-only",
            "org_example_test_only",
            coords("org.example", "test-only", "1.0"),
            List.of()));
    runtimeNodes.add(
        new RuntimeCatalogNode(
            "org.example:test-private",
            "org_example_test_private",
            coords("org.example", "test-private", "1.0"),
            List.of()));
    var runtimeCatalog =
        new RuntimeCatalog(
            runtimeNodes,
            base.runtimeCatalog().directArtifacts(),
            base.runtimeCatalog().conflictResolution());
    var runtimePaths = new java.util.HashSet<>(base.runtimeClasspathPaths());
    runtimePaths.add(testJar.toString());
    runtimePaths.add(additionalRootJar.toString());
    runtimePaths.add(testOnlyJar.toString());
    runtimePaths.add(testPrivateJar.toString());
    var deploymentPaths = new java.util.HashSet<>(base.deploymentClasspathPaths());
    deploymentPaths.add(testJar.toString());
    deploymentPaths.add(additionalRootJar.toString());
    deploymentPaths.add(testOnlyJar.toString());
    deploymentPaths.add(testPrivateJar.toString());
    var testInputs =
        new BazelApplicationModelAssembler.Inputs(
            new Roots("@@//:test", List.of(additionalRoot, testRoot)),
            fragments,
            runtimeCatalog,
            emptyConditionalCatalog(),
            base.deploymentCatalog(),
            base.platformCatalog(),
            base.localDeployments(),
            base.localRuntimeAliases(),
            Map.of(),
            base.deploymentPaths(),
            base.platformPropertyPaths(),
            runtimePaths,
            deploymentPaths,
            Set.of(TEST_PRIVATE),
            base.quarkusVersion(),
            Mode.TEST,
            "test",
            "ignored-test-version",
            base.producerVersion());

    BazelApplicationModel model = BazelApplicationModelAssembler.assemble(testInputs);

    assertEquals(APP, model.applicationId());
    assertEquals(
        DependencyScope.TEST,
        node(model, APP).dependencies().stream()
            .filter(edge -> additionalRoot.equals(edge.targetId()))
            .findFirst()
            .orElseThrow()
            .scope());
    assertFalse(model.nodes().stream().anyMatch(node -> testRoot.equals(node.id())));
    assertFalse(model.nodes().stream().anyMatch(node -> TEST_PRIVATE.equals(node.id())));
    assertEquals("app", node(model, APP).coordinates().artifactId());
    assertEquals(
        DependencyScope.TEST,
        node(model, APP).dependencies().stream()
            .filter(edge -> COMMON.equals(edge.targetId()))
            .findFirst()
            .orElseThrow()
            .scope());
    assertEquals(
        "test",
        ExplicitApplicationModelBuilder.build(model).getDependencies().stream()
            .filter(dependency -> "test-only".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow()
            .getScope());
    assertEquals(
        List.of("", "tests"),
        model.workspaceModules().stream()
            .filter(module -> APP.equals(module.id()))
            .findFirst()
            .orElseThrow()
            .sourceSets()
            .stream()
            .map(sourceSet -> sourceSet.classifier())
            .toList());
    assertEquals(
        List.of("src/test/java"),
        model.workspaceModules().stream()
            .filter(module -> APP.equals(module.id()))
            .findFirst()
            .orElseThrow()
            .sourceSets()
            .get(1)
            .sourceDirectories());

    var quarkusModel = ExplicitApplicationModelBuilder.build(model);
    assertEquals("app", quarkusModel.getAppArtifact().getArtifactId());
    assertEquals(
        "test",
        quarkusModel.getAppArtifact().getWorkspaceModule().getDirectDependencies().stream()
            .filter(dependency -> "test-only".equals(dependency.getArtifactId()))
            .findFirst()
            .orElseThrow()
            .getScope());
    assertTrue(
        quarkusModel.getAppArtifact().getWorkspaceModule().getDirectDependencies().stream()
            .noneMatch(dependency -> dependency.getArtifactId().endsWith("-deployment")));
  }

  @Test
  void parsesEveryQuarkusCompactCoordinateForm() {
    assertEquals(
        "g:a::jar:1", BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse("g:a:1")));
    assertEquals(
        "g:a::pom:1",
        BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse("g:a:pom:1")));
    assertEquals(
        "g:a:tests:jar:1",
        BazelArtifactCoordinates.canonical(BazelArtifactCoordinates.parse("g:a:tests:jar:1")));
  }

  @Test
  void activatesNormalAndNestedConditionalsToAFixpointButKeepsDevCandidatesModeSpecific()
      throws IOException {
    BazelApplicationModel normal =
        BazelApplicationModelAssembler.assemble(conditionalInputs(Mode.NORMAL));

    String featureA = "conditional:io.quarkus:feature-a::jar:3.33.2";
    String featureB = "conditional:io.quarkus:feature-b::jar:3.33.2";
    String devHelper = "conditional:io.quarkus:dev-helper::jar:3.33.2";
    assertTrue(normal.nodes().stream().anyMatch(node -> featureA.equals(node.id())));
    assertTrue(normal.nodes().stream().anyMatch(node -> featureB.equals(node.id())));
    assertFalse(normal.nodes().stream().anyMatch(node -> devHelper.equals(node.id())));
    assertTrue(targets(node(normal, EXT)).contains(featureA));
    assertTrue(
        targets(node(normal, EXT))
            .contains("deployment:io.quarkus:feature-a-deployment::jar:3.33.2"));
    assertTrue(targets(node(normal, featureA)).contains(featureB));
    assertEquals(
        List.of(new ArtifactKey("excluded.group", "excluded")),
        node(normal, featureA).dependencies().stream()
            .filter(edge -> edge.targetId().equals(featureB))
            .findFirst()
            .orElseThrow()
            .exclusions());
    assertTrue(
        targets(node(normal, featureA))
            .contains("deployment:io.quarkus:feature-b-deployment::jar:3.33.2"));
    assertFalse(node(normal, featureA).classpath().topLevelRuntimeExtension());

    BazelApplicationModel dev =
        BazelApplicationModelAssembler.assemble(conditionalInputs(Mode.DEV));
    assertTrue(dev.nodes().stream().anyMatch(node -> devHelper.equals(node.id())));

    BazelApplicationModel nativeModel =
        BazelApplicationModelAssembler.assemble(conditionalInputs(Mode.NATIVE));
    assertFalse(nativeModel.nodes().stream().anyMatch(node -> devHelper.equals(node.id())));
  }

  private BazelApplicationModelAssembler.Inputs conditionalInputs(Mode mode) throws IOException {
    var base = inputs(true, DEPLOYMENT);
    Path featureA = jar("feature-a.jar", "io.quarkus:feature-a-deployment:3.33.2");
    Path featureB = jar("feature-b.jar", "io.quarkus:feature-b-deployment:3.33.2");
    Path devHelper = jar("dev-helper.jar", null);
    Path blocked = jar("blocked.jar", null);
    Map<String, String> conditionalPaths =
        Map.of(
            "conditional/jars/io/quarkus/feature-a/3.33.2/feature-a.jar", featureA.toString(),
            "conditional/jars/io/quarkus/feature-b/3.33.2/feature-b.jar", featureB.toString(),
            "conditional/jars/io/quarkus/dev-helper/3.33.2/dev-helper.jar", devHelper.toString(),
            "conditional/jars/io/quarkus/blocked/3.33.2/blocked.jar", blocked.toString(),
            "conditional/jars/io/quarkus/example/3.33.2/example.jar", featureA.toString());
    ConditionalCatalog conditional =
        new ConditionalCatalog(
            "coursier",
            "0.1.0",
            List.of(
                "io.quarkus:blocked:3.33.2",
                "io.quarkus:dev-helper:3.33.2",
                "io.quarkus:feature-a:3.33.2",
                "io.quarkus:feature-b:3.33.2"),
            List.of(
                conditionalNode("blocked"),
                conditionalNode("dev-helper"),
                conditionalNode("feature-a", List.of("io.quarkus:example:3.33.2")),
                conditionalNode("feature-b", List.of(), List.of("excluded.group:excluded")),
                conditionalNode("example")),
            List.of(
                new ExtensionDescriptor(
                    "io.quarkus:example:3.33.2",
                    "io.quarkus:example-deployment:3.33.2",
                    List.of("io.quarkus:feature-a:3.33.2", "io.quarkus:blocked:3.33.2"),
                    List.of("io.quarkus:dev-helper:3.33.2"),
                    List.of()),
                new ExtensionDescriptor(
                    "io.quarkus:feature-a:3.33.2",
                    "io.quarkus:feature-a-deployment:3.33.2",
                    List.of("io.quarkus:feature-b:3.33.2"),
                    List.of(),
                    List.of("org.example:common")),
                new ExtensionDescriptor(
                    "io.quarkus:feature-b:3.33.2",
                    "io.quarkus:feature-b-deployment:3.33.2",
                    List.of(),
                    List.of(),
                    List.of("io.quarkus:feature-a")),
                new ExtensionDescriptor(
                    "io.quarkus:blocked:3.33.2",
                    "io.quarkus:blocked-deployment:3.33.2",
                    List.of(),
                    List.of(),
                    List.of("missing.group:trigger"))),
            Map.of());

    Path featureADeployment = jar("feature-a-deployment.jar", null);
    Path featureBDeployment = jar("feature-b-deployment.jar", null);
    var deploymentNodes = new java.util.ArrayList<>(base.deploymentCatalog().nodes());
    deploymentNodes.add(
        new DeploymentCatalogNode(
            "io.quarkus:feature-a-deployment:3.33.2",
            "deployment/jars/io/quarkus/feature-a-deployment/3.33.2/feature-a-deployment.jar",
            List.of("io.quarkus:feature-a:3.33.2"),
            List.of()));
    deploymentNodes.add(
        new DeploymentCatalogNode(
            "io.quarkus:feature-b-deployment:3.33.2",
            "deployment/jars/io/quarkus/feature-b-deployment/3.33.2/feature-b-deployment.jar",
            List.of("io.quarkus:feature-b:3.33.2"),
            List.of()));
    deploymentNodes.add(
        new DeploymentCatalogNode(
            "io.quarkus:feature-a:3.33.2",
            "deployment/jars/io/quarkus/feature-a/3.33.2/feature-a.jar",
            List.of(),
            List.of()));
    deploymentNodes.add(
        new DeploymentCatalogNode(
            "io.quarkus:feature-b:3.33.2",
            "deployment/jars/io/quarkus/feature-b/3.33.2/feature-b.jar",
            List.of(),
            List.of()));
    var deploymentPaths = new java.util.HashMap<>(base.deploymentPaths());
    deploymentPaths.put(
        "deployment/jars/io/quarkus/feature-a-deployment/3.33.2/feature-a-deployment.jar",
        featureADeployment.toString());
    deploymentPaths.put(
        "deployment/jars/io/quarkus/feature-b-deployment/3.33.2/feature-b-deployment.jar",
        featureBDeployment.toString());
    deploymentPaths.put(
        "deployment/jars/io/quarkus/feature-a/3.33.2/feature-a.jar", featureA.toString());
    deploymentPaths.put(
        "deployment/jars/io/quarkus/feature-b/3.33.2/feature-b.jar", featureB.toString());
    var deploymentClasspath = new java.util.HashSet<>(base.deploymentClasspathPaths());
    deploymentClasspath.add(featureADeployment.toString());
    deploymentClasspath.add(featureBDeployment.toString());

    return new BazelApplicationModelAssembler.Inputs(
        base.roots(),
        base.targetFragments(),
        base.runtimeCatalog(),
        conditional,
        new DeploymentCatalog(
            "coursier",
            "0.1.0",
            List.of(
                "io.quarkus:example-deployment:3.33.2",
                "io.quarkus:feature-a-deployment:3.33.2",
                "io.quarkus:feature-b-deployment:3.33.2"),
            List.of(),
            deploymentNodes,
            Map.of()),
        base.platformCatalog(),
        base.localDeployments(),
        base.localRuntimeAliases(),
        conditionalPaths,
        deploymentPaths,
        base.platformPropertyPaths(),
        base.runtimeClasspathPaths(),
        deploymentClasspath,
        base.modelPrivateTargetIds(),
        base.quarkusVersion(),
        mode,
        base.applicationName(),
        base.applicationVersion(),
        base.producerVersion());
  }

  private static ConditionalCatalogNode conditionalNode(String artifactId) {
    return conditionalNode(artifactId, List.of());
  }

  private static ConditionalCatalogNode conditionalNode(
      String artifactId, List<String> dependencies) {
    return conditionalNode(artifactId, dependencies, List.of());
  }

  private static ConditionalCatalogNode conditionalNode(
      String artifactId, List<String> dependencies, List<String> exclusions) {
    return new ConditionalCatalogNode(
        "io.quarkus:" + artifactId + ":3.33.2",
        "conditional/jars/io/quarkus/" + artifactId + "/3.33.2/" + artifactId + ".jar",
        dependencies,
        exclusions);
  }

  private BazelApplicationModelAssembler.Inputs inputs(
      boolean includeDeployment, String descriptorCoordinate) throws IOException {
    Path appJar = jar("app.jar", null);
    Path extensionJar = jar("example.jar", descriptorCoordinate);
    Path commonJar = jar("common.jar", null);
    Path deploymentJar = jar("example-deployment.jar", null);
    Path helperJar = jar("helper.jar", null);

    List<TargetFragment> fragments =
        List.of(
            local(APP, appJar, List.of(edge(EXT))),
            external(EXT, "io_quarkus_example", extensionJar, List.of(edge(COMMON))),
            external(COMMON, "org_example_common", commonJar, List.of()));
    RuntimeCatalog runtime =
        new RuntimeCatalog(
            List.of(
                new RuntimeCatalogNode(
                    "io.quarkus:example",
                    "io_quarkus_example",
                    coords("io.quarkus", "example", "3.33.2"),
                    List.of("org.example:common")),
                new RuntimeCatalogNode(
                    "org.example:common",
                    "org_example_common",
                    coords("org.example", "common", "1.0"),
                    List.of())),
            List.of("io.quarkus:example"),
            Map.of());
    DeploymentCatalog deployment =
        new DeploymentCatalog(
            "coursier",
            "0.1.0",
            includeDeployment ? List.of("io.quarkus:example-deployment:3.33.2") : List.of(),
            List.of(),
            includeDeployment
                ? List.of(
                    new DeploymentCatalogNode(
                        "io.quarkus:example-deployment:3.33.2",
                        "deployment/jars/io/quarkus/example-deployment/3.33.2/example-deployment.jar",
                        List.of("io.quarkus:example:3.33.2", "io.quarkus:helper:3.33.2"),
                        List.of("bad.group:excluded")),
                    new DeploymentCatalogNode(
                        "io.quarkus:example:3.33.2",
                        "deployment/jars/io/quarkus/example/3.33.2/example.jar",
                        List.of("org.example:common:1.0"),
                        List.of()),
                    new DeploymentCatalogNode(
                        "org.example:common:1.0",
                        "deployment/jars/org/example/common/1.0/common.jar",
                        List.of(),
                        List.of()),
                    new DeploymentCatalogNode(
                        "io.quarkus:helper:3.33.2",
                        "deployment/jars/io/quarkus/helper/3.33.2/helper.jar",
                        List.of(),
                        List.of("bad.group:excluded")))
                : List.of(),
            Map.of());
    Map<String, String> paths =
        includeDeployment
            ? Map.of(
                "deployment/jars/io/quarkus/example-deployment/3.33.2/example-deployment.jar",
                deploymentJar.toString(),
                "deployment/jars/io/quarkus/example/3.33.2/example.jar",
                extensionJar.toString(),
                "deployment/jars/org/example/common/1.0/common.jar",
                commonJar.toString(),
                "deployment/jars/io/quarkus/helper/3.33.2/helper.jar",
                helperJar.toString())
            : Map.of();
    Set<String> runtimePaths =
        fragments.stream()
            .flatMap(fragment -> fragment.runtimeOutputJars().stream())
            .map(FileReference::path)
            .collect(java.util.stream.Collectors.toSet());
    var deploymentClasspathPaths = new java.util.HashSet<>(runtimePaths);
    deploymentClasspathPaths.addAll(paths.values());
    return new BazelApplicationModelAssembler.Inputs(
        new Roots("@@//:quarkus", List.of(APP)),
        fragments,
        runtime,
        emptyConditionalCatalog(),
        deployment,
        new PlatformCatalog(List.of(), List.of(), Map.of()),
        Map.of(),
        Map.of(),
        Map.of(),
        paths,
        Map.of(),
        runtimePaths,
        deploymentClasspathPaths,
        Set.of(),
        "3.33.2",
        Mode.NORMAL,
        "demo",
        "1.2.3",
        "test");
  }

  private static ConditionalCatalog emptyConditionalCatalog() {
    return new ConditionalCatalog("coursier", "", List.of(), List.of(), List.of(), Map.of());
  }

  private static BazelApplicationModelAssembler.Inputs withRuntimeCatalog(
      BazelApplicationModelAssembler.Inputs base, RuntimeCatalog runtime) {
    return new BazelApplicationModelAssembler.Inputs(
        base.roots(),
        base.targetFragments(),
        runtime,
        base.conditionalCatalog(),
        base.deploymentCatalog(),
        base.platformCatalog(),
        base.localDeployments(),
        base.localRuntimeAliases(),
        base.conditionalPaths(),
        base.deploymentPaths(),
        base.platformPropertyPaths(),
        base.runtimeClasspathPaths(),
        base.deploymentClasspathPaths(),
        base.modelPrivateTargetIds(),
        base.quarkusVersion(),
        base.mode(),
        base.applicationName(),
        base.applicationVersion(),
        base.producerVersion());
  }

  private Path jar(String name, String deploymentArtifact) throws IOException {
    Path path = tempDir.resolve(name);
    try (var output = new JarOutputStream(Files.newOutputStream(path))) {
      output.putNextEntry(new JarEntry("marker"));
      output.write(1);
      output.closeEntry();
      if (deploymentArtifact != null) {
        Properties properties = new Properties();
        properties.setProperty("deployment-artifact", deploymentArtifact);
        output.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
        properties.store(output, null);
        output.closeEntry();
      }
    }
    return path;
  }

  private static TargetFragment local(String id, Path jar, List<TargetEdge> edges) {
    return fragment(id, "", "app", jar, edges);
  }

  private static TargetFragment testLocal(String id, Path jar, List<TargetEdge> edges) {
    FileReference output =
        new FileReference(jar.toString(), jar.getFileName().toString(), id, false);
    return new TargetFragment(
        id,
        id,
        "",
        "",
        "test_lib",
        "java_library",
        "BUILD.bazel",
        false,
        null,
        List.of(output),
        List.of(
            new FileReference(
                jar + ".quarkus-classes", jar.getFileName() + ".quarkus-classes", id, false)),
        List.of(),
        List.of(
            new FileReference(
                "src/test/java/com/example/AppTest.java",
                "src/test/java/com/example/AppTest.java",
                id,
                true)),
        List.of(),
        edges);
  }

  private static TargetFragment external(
      String id, String targetName, Path jar, List<TargetEdge> edges) {
    return fragment(id, "maven", targetName, jar, edges);
  }

  private static TargetFragment explicitLocal(
      String id,
      String targetName,
      Path jar,
      ArtifactCoordinates coordinates,
      List<TargetEdge> edges) {
    FileReference output =
        new FileReference(jar.toString(), jar.getFileName().toString(), id, false);
    return new TargetFragment(
        id,
        id,
        "",
        "",
        targetName,
        "java_library",
        "BUILD.bazel",
        false,
        coordinates,
        List.of(output),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        edges);
  }

  private static TargetFragment withOutputDirectory(TargetFragment fragment, String outputPath) {
    return new TargetFragment(
        fragment.targetId(),
        fragment.bazelLabel(),
        fragment.workspaceName(),
        fragment.packageName(),
        fragment.targetName(),
        fragment.ruleKind(),
        fragment.buildFile(),
        fragment.neverlink(),
        fragment.coordinates(),
        fragment.runtimeOutputJars(),
        List.of(new FileReference(outputPath, outputPath, fragment.targetId(), false)),
        fragment.sourceJars(),
        fragment.sources(),
        fragment.resources(),
        fragment.edges());
  }

  private static TargetFragment fragment(
      String id, String workspace, String targetName, Path jar, List<TargetEdge> edges) {
    FileReference output =
        new FileReference(jar.toString(), jar.getFileName().toString(), id, false);
    List<FileReference> outputDirectories =
        workspace.isEmpty()
            ? List.of(
                new FileReference(
                    jar + ".quarkus-classes", jar.getFileName() + ".quarkus-classes", id, false))
            : List.of();
    List<FileReference> sources =
        workspace.isEmpty()
            ? List.of(
                new FileReference(
                    "src/main/java/com/example/App.java",
                    "src/main/java/com/example/App.java",
                    id,
                    true))
            : List.of();
    return new TargetFragment(
        id,
        id,
        workspace,
        "",
        targetName,
        "java_library",
        "BUILD.bazel",
        false,
        null,
        List.of(output),
        outputDirectories,
        List.of(),
        sources,
        List.of(),
        edges);
  }

  private static TargetEdge edge(String target) {
    return new TargetEdge(target, "deps", DependencyScope.COMPILE, false, List.of());
  }

  private static ArtifactCoordinates coords(String group, String artifact, String version) {
    return new ArtifactCoordinates(group, artifact, "", "jar", version);
  }

  private static ArtifactCoordinates pomCoords(String group, String artifact, String version) {
    return new ArtifactCoordinates(group, artifact, "", "pom", version);
  }

  private static Node node(BazelApplicationModel model, String id) {
    return model.nodes().stream().filter(node -> id.equals(node.id())).findFirst().orElseThrow();
  }

  private static List<String> targets(Node node) {
    return node.dependencies().stream().map(dependency -> dependency.targetId()).sorted().toList();
  }
}
