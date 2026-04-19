package com.clementguillot.quarkifier.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeploymentArtifactResolver}. */
class DeploymentArtifactResolverTest {

  @Test
  void deploymentArtifactId_standardExtension() {
    var ext =
        new ExtensionInfo(
            "io.quarkus",
            "quarkus-resteasy-reactive",
            "3.20.6",
            Path.of("quarkus-resteasy-reactive-3.20.6.jar"));
    assertEquals(
        "quarkus-resteasy-reactive-deployment",
        DeploymentArtifactResolver.deploymentArtifactId(ext));
  }

  @Test
  void resolveAll_singleExtension() {
    var ext =
        new ExtensionInfo(
            "io.quarkus", "quarkus-arc", "3.20.6", Path.of("quarkus-arc-3.20.6.jar"));
    Path deployJar = Path.of("lib/quarkus-arc-deployment-3.20.6.jar");

    assertDoesNotThrow(
        () -> DeploymentArtifactResolver.resolveAll(List.of(ext), List.of(deployJar)));
  }

  @Test
  void resolveAll_multipleExtensions() {
    var arc =
        new ExtensionInfo(
            "io.quarkus", "quarkus-arc", "3.20.6", Path.of("quarkus-arc-3.20.6.jar"));
    var resteasy =
        new ExtensionInfo(
            "io.quarkus",
            "quarkus-resteasy-reactive",
            "3.20.6",
            Path.of("quarkus-resteasy-reactive-3.20.6.jar"));

    Path arcDeploy = Path.of("lib/quarkus-arc-deployment-3.20.6.jar");
    Path resteasyDeploy = Path.of("lib/quarkus-resteasy-reactive-deployment-3.20.6.jar");

    assertDoesNotThrow(
        () ->
            DeploymentArtifactResolver.resolveAll(
                List.of(arc, resteasy), List.of(arcDeploy, resteasyDeploy)));
  }

  @Test
  void resolveAll_missingDeploymentArtifact_throws() {
    var ext =
        new ExtensionInfo(
            "io.quarkus", "quarkus-arc", "3.20.6", Path.of("quarkus-arc-3.20.6.jar"));
    assertThrows(
        MissingDeploymentArtifactException.class,
        () -> DeploymentArtifactResolver.resolveAll(List.of(ext), List.of()));
  }

  @Test
  void resolveAll_missingDeploymentArtifact_messageContainsBothIds() {
    var ext =
        new ExtensionInfo(
            "io.quarkus", "quarkus-arc", "3.20.6", Path.of("quarkus-arc-3.20.6.jar"));

    var ex =
        assertThrows(
            MissingDeploymentArtifactException.class,
            () -> DeploymentArtifactResolver.resolveAll(List.of(ext), List.of()));

    assertTrue(ex.getMessage().contains("quarkus-arc-deployment"));
    assertTrue(ex.getMessage().contains("quarkus-arc"));
  }

  @Test
  void resolveAll_emptyExtensionList() {
    assertDoesNotThrow(() -> DeploymentArtifactResolver.resolveAll(List.of(), List.of()));
  }

  @Test
  void resolveAll_partialMatch_firstMissing() {
    var arc =
        new ExtensionInfo(
            "io.quarkus", "quarkus-arc", "3.20.6", Path.of("quarkus-arc-3.20.6.jar"));
    var resteasy =
        new ExtensionInfo(
            "io.quarkus",
            "quarkus-resteasy-reactive",
            "3.20.6",
            Path.of("quarkus-resteasy-reactive-3.20.6.jar"));
    Path resteasyDeploy = Path.of("lib/quarkus-resteasy-reactive-deployment-3.20.6.jar");

    var ex =
        assertThrows(
            MissingDeploymentArtifactException.class,
            () ->
                DeploymentArtifactResolver.resolveAll(
                    List.of(arc, resteasy), List.of(resteasyDeploy)));

    assertTrue(ex.getMessage().contains("quarkus-arc-deployment"));
  }
}
