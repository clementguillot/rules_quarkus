package com.clementguillot.quarkifier.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cross-version serialization tests for the explicit Quarkus model adapter. */
class ExplicitApplicationModelBuilderTest {

  @Test
  void devModelUsesStableArtifactPathWhenBazelExecrootSymlinkDisappears(@TempDir Path tempDir)
      throws Exception {
    Path repository = Files.createDirectories(tempDir.resolve("output-base/external/repository"));
    Path backingJar = repository.resolve("dependency.jar");
    try (var ignored = new JarOutputStream(Files.newOutputStream(backingJar))) {
      // Empty but valid JAR.
    }
    Path execroot = Files.createDirectories(tempDir.resolve("execroot/workspace"));
    Path externalLink = execroot.resolve("external");
    Files.createSymbolicLink(externalLink, tempDir.resolve("output-base/external"));
    Path logicalJar = externalLink.resolve("repository/dependency.jar");

    var application =
        new BazelApplicationModel.Node(
            "app",
            BazelApplicationModel.NodeKind.APPLICATION,
            new BazelApplicationModel.ArtifactCoordinates("test", "app", "", "jar", "1.0"),
            List.of(logicalJar.toString()),
            List.of(),
            new BazelApplicationModel.ClasspathFacts(
                false, true, false, false, false, false, false),
            null,
            "//:app");
    var model =
        new BazelApplicationModel(
            BazelApplicationModel.SCHEMA_VERSION,
            new BazelApplicationModel.Producer("test", "1"),
            com.clementguillot.quarkifier.QuarkifierVersionProvider.targetedQuarkusVersion(),
            BazelApplicationModel.Mode.DEV,
            application.id(),
            List.of(application),
            List.of(),
            new BazelApplicationModel.Platform(List.of(), Map.of(), List.of()),
            new BazelApplicationModel.Diagnostics(List.of(), List.of()));

    var built = ExplicitApplicationModelBuilder.build(model);
    Path resolved = built.getAppArtifact().getResolvedPaths().getSinglePath();
    Files.delete(externalLink);

    assertEquals(backingJar.toRealPath(), resolved);
    assertTrue(Files.exists(resolved), "resolved model path must survive execroot symlink removal");
  }

  @Test
  void workspaceSourceMetadataSurvivesNativeModelSerialization(@TempDir Path tempDir)
      throws Exception {
    Path appJar = tempDir.resolve("app.jar");
    try (var ignored = new JarOutputStream(Files.newOutputStream(appJar))) {
      // An empty, valid application jar is sufficient for model construction.
    }
    Path sourceDir = Files.createDirectories(tempDir.resolve("src/main/java"));
    Path serialized = tempDir.resolve("application-model.dat");

    var sourceSet =
        new BazelApplicationModel.SourceSet(
            "",
            List.of(sourceDir.toString()),
            List.of(),
            List.of(appJar.toString()),
            List.of(),
            List.of());
    var workspace =
        new BazelApplicationModel.WorkspaceModule(
            "workspace",
            "//:app",
            tempDir.toString(),
            tempDir.resolve("bazel-bin").toString(),
            tempDir.resolve("BUILD.bazel").toString(),
            List.of(sourceSet),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    var application =
        new BazelApplicationModel.Node(
            "app",
            BazelApplicationModel.NodeKind.APPLICATION,
            new BazelApplicationModel.ArtifactCoordinates("test", "app", "", "jar", "1.0"),
            List.of(appJar.toString()),
            List.of(),
            new BazelApplicationModel.ClasspathFacts(
                false, false, false, false, false, false, false),
            workspace.id(),
            "//:app");
    var model =
        new BazelApplicationModel(
            BazelApplicationModel.SCHEMA_VERSION,
            new BazelApplicationModel.Producer("test", "1"),
            com.clementguillot.quarkifier.QuarkifierVersionProvider.targetedQuarkusVersion(),
            BazelApplicationModel.Mode.NORMAL,
            application.id(),
            List.of(application),
            List.of(workspace),
            new BazelApplicationModel.Platform(List.of(), Map.of(), List.of()),
            new BazelApplicationModel.Diagnostics(List.of(), List.of()));

    ExplicitApplicationModelBuilder.serialize(
        ExplicitApplicationModelBuilder.build(model), serialized);

    assertTrue(Files.size(serialized) > 0);
    var roundTripped = ExplicitApplicationModelBuilder.deserialize(serialized);
    assertEquals("test:app::jar:1.0", roundTripped.getAppArtifact().toGACTVString());
    assertEquals(
        "io.quarkus.bootstrap.workspace.LazySourceDir",
        roundTripped
            .getAppArtifact()
            .getWorkspaceModule()
            .getMainSources()
            .getSourceDirs()
            .iterator()
            .next()
            .getClass()
            .getName());
  }
}
