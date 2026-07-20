package com.clementguillot.quarkifier.extension;

import java.nio.file.Path;
import java.util.List;

/**
 * Metadata for a Quarkus extension discovered on the application classpath.
 *
 * <p>The exact runtime coordinate is supplied by the resolver when available. The compatibility
 * scanner derives it from {@code deployment-artifact}, but model assembly must not rely on that
 * convention: Quarkus descriptors are allowed to use an unrelated deployment artifact name.
 *
 * @param groupId Maven group ID (e.g. {@code io.quarkus})
 * @param artifactId Maven runtime artifact ID (e.g. {@code quarkus-resteasy-reactive})
 * @param version Maven version (e.g. {@code 3.27.4})
 * @param sourceJar the classpath jar that contained the extension metadata
 * @param runtimeArtifact exact runtime coordinate supplied by the resolver
 * @param deploymentArtifact exact deployment coordinate declared by the extension descriptor
 * @param conditionalDependencies production/test conditional runtime coordinates
 * @param conditionalDevDependencies development-only conditional runtime coordinates
 * @param dependencyConditions artifact keys which must already be on the runtime graph
 */
public record ExtensionInfo(
    String groupId,
    String artifactId,
    String version,
    Path sourceJar,
    String runtimeArtifact,
    String deploymentArtifact,
    List<String> conditionalDependencies,
    List<String> conditionalDevDependencies,
    List<String> dependencyConditions) {

  public ExtensionInfo {
    conditionalDependencies = List.copyOf(conditionalDependencies);
    conditionalDevDependencies = List.copyOf(conditionalDevDependencies);
    dependencyConditions = List.copyOf(dependencyConditions);
  }
}
