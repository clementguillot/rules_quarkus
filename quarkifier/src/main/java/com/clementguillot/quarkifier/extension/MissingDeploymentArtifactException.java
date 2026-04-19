package com.clementguillot.quarkifier.extension;

/**
 * Thrown when a Quarkus extension's corresponding {@code -deployment} artifact cannot be found on
 * the deployment classpath.
 *
 * <p>The message always contains both the missing deployment artifact ID and the originating
 * extension artifact ID so the user knows exactly what to add to their {@code maven_install}
 * repository.
 */
public final class MissingDeploymentArtifactException extends RuntimeException {

  /**
   * @param missingDeploymentArtifactId the deployment artifact that was expected (e.g. {@code
   *     quarkus-resteasy-reactive-deployment})
   * @param originatingExtensionArtifactId the runtime extension that requires it (e.g. {@code
   *     quarkus-resteasy-reactive})
   */
  public MissingDeploymentArtifactException(
      String missingDeploymentArtifactId, String originatingExtensionArtifactId) {
    super(
        "Missing deployment artifact '"
            + missingDeploymentArtifactId
            + "' required by extension '"
            + originatingExtensionArtifactId
            + "'");
  }
}
