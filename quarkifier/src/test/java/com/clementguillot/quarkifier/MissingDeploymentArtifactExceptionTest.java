package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MissingDeploymentArtifactException}. */
class MissingDeploymentArtifactExceptionTest {

  @Test
  void getters_returnConstructorValues() {
    var ex = new MissingDeploymentArtifactException("quarkus-arc-deployment", "quarkus-arc");

    assertEquals("quarkus-arc-deployment", ex.getMissingDeploymentArtifactId());
    assertEquals("quarkus-arc", ex.getOriginatingExtensionArtifactId());
  }

  @Test
  void message_containsBothIds() {
    var ex = new MissingDeploymentArtifactException("quarkus-arc-deployment", "quarkus-arc");

    assertTrue(ex.getMessage().contains("quarkus-arc-deployment"));
    assertTrue(ex.getMessage().contains("quarkus-arc"));
  }
}
