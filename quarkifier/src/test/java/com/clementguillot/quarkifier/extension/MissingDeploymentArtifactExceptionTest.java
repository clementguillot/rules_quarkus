package com.clementguillot.quarkifier.extension;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MissingDeploymentArtifactException}. */
class MissingDeploymentArtifactExceptionTest {

  @Test
  void message_containsBothIds() {
    var ex = new MissingDeploymentArtifactException("quarkus-arc-deployment", "quarkus-arc");

    assertTrue(ex.getMessage().contains("quarkus-arc-deployment"));
    assertTrue(ex.getMessage().contains("quarkus-arc"));
  }
}
