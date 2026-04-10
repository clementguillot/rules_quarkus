package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@code -deployment} counterpart for each Quarkus extension discovered on the
 * application classpath.
 *
 * <p>For a given extension with coordinates {@code groupId:artifactId:version}, the expected
 * deployment artifact ID is {@code artifactId + "-deployment"} with the same {@code groupId} and
 * {@code version}. The resolver searches the deployment classpath for a jar whose filename contains
 * the deployment artifact ID (e.g. {@code quarkus-resteasy-reactive-deployment-3.20.6.jar}).
 *
 * <p>If any deployment artifact cannot be found, a {@link MissingDeploymentArtifactException} is
 * thrown whose message contains both the missing deployment artifact ID and the originating
 * extension artifact ID.
 */
public final class DeploymentArtifactResolver {

  private static final String DEPLOYMENT_SUFFIX = "-deployment";

  private DeploymentArtifactResolver() {}

  /**
   * Computes the expected deployment artifact ID for the given extension.
   *
   * @param extension the scanned extension info
   * @return the deployment artifact ID ({@code artifactId + "-deployment"})
   */
  public static String deploymentArtifactId(ExtensionInfo extension) {
    return extension.artifactId() + DEPLOYMENT_SUFFIX;
  }

  /**
   * Resolves deployment jars for all provided extensions.
   *
   * @param extensions extensions discovered by {@link ExtensionScanner}
   * @param deploymentClasspath jars available on the deployment classpath
   * @return an ordered map from each extension to its resolved deployment jar
   * @throws MissingDeploymentArtifactException if any deployment jar is missing
   */
  public static Map<ExtensionInfo, Path> resolveAll(
      List<ExtensionInfo> extensions, List<Path> deploymentClasspath) {

    Map<ExtensionInfo, Path> resolved = new LinkedHashMap<>();
    for (ExtensionInfo ext : extensions) {
      String expectedId = deploymentArtifactId(ext);
      Path deploymentJar = findOnClasspath(expectedId, deploymentClasspath);
      if (deploymentJar == null) {
        throw new MissingDeploymentArtifactException(expectedId, ext.artifactId());
      }
      resolved.put(ext, deploymentJar);
    }
    return resolved;
  }

  /**
   * Searches the deployment classpath for a jar whose filename contains the given deployment
   * artifact ID.
   *
   * @return the matching path, or {@code null} if not found
   */
  private static Path findOnClasspath(String deploymentArtifactId, List<Path> classpath) {
    for (Path jar : classpath) {
      String filename = jar.getFileName().toString();
      if (filename.contains(deploymentArtifactId)) {
        return jar;
      }
    }
    return null;
  }
}
