package com.clementguillot.quarkifier.extension;

import com.clementguillot.quarkifier.model.transport.BazelArtifactCoordinates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Scans application classpath jars for Quarkus extension metadata.
 *
 * <p>Each Quarkus extension jar contains {@code META-INF/quarkus-extension.properties} with a
 * {@code deployment-artifact} key in GAV format: {@code groupId:artifactId-deployment:version}.
 * This scanner reads that property, extracts the coordinates, and derives the runtime artifact ID
 * by stripping the {@code -deployment} suffix. The exact descriptor coordinate is retained: a
 * deployment artifact is allowed to use a custom artifact ID, classifier, or type.
 */
public final class ExtensionScanner {

  private static final String EXTENSION_PROPERTIES_PATH = "META-INF/quarkus-extension.properties";
  private static final String DEPLOYMENT_ARTIFACT_KEY = "deployment-artifact";
  private static final String CONDITIONAL_DEPENDENCIES_KEY = "conditional-dependencies";
  private static final String CONDITIONAL_DEV_DEPENDENCIES_KEY = "conditional-dev-dependencies";
  private static final String DEPENDENCY_CONDITION_KEY = "dependency-condition";
  private static final String DEPLOYMENT_SUFFIX = "-deployment";

  private ExtensionScanner() {}

  /**
   * Scans the given classpath jars and returns metadata for every Quarkus extension found.
   *
   * @param classpathJars jar files to scan
   * @return unmodifiable list of discovered extensions
   * @throws IOException if a jar cannot be read
   */
  public static List<ExtensionInfo> scan(List<Path> classpathJars) throws IOException {
    List<ExtensionInfo> extensions = new ArrayList<>();
    for (Path jar : classpathJars) {
      ExtensionInfo info = scanJar(jar);
      if (info != null) {
        extensions.add(info);
      }
    }
    return Collections.unmodifiableList(extensions);
  }

  /**
   * Scans resolver-identified artifacts. Runtime identity is never inferred from the deployment
   * coordinate on this path.
   */
  public static List<ExtensionInfo> scanArtifacts(List<ArtifactInput> artifacts)
      throws IOException {
    List<ExtensionInfo> extensions = new ArrayList<>();
    for (ArtifactInput artifact : artifacts) {
      ExtensionInfo info = scanJar(artifact.path(), artifact.coordinate());
      if (info != null) {
        extensions.add(info);
      }
    }
    return Collections.unmodifiableList(extensions);
  }

  /** Exact resolver coordinate paired with the jar that was resolved for it. */
  public record ArtifactInput(String coordinate, Path path) {}

  /**
   * Scans a single jar for Quarkus extension metadata.
   *
   * @return the extension info, or {@code null} if the jar is not a Quarkus extension
   */
  private static ExtensionInfo scanJar(Path jarPath) throws IOException {
    return scanJar(jarPath, null);
  }

  private static ExtensionInfo scanJar(Path jarPath, String runtimeCoordinate) throws IOException {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      ZipEntry entry = jarFile.getEntry(EXTENSION_PROPERTIES_PATH);
      if (entry == null) {
        return null;
      }
      try (InputStream is = jarFile.getInputStream(entry)) {
        Properties props = new Properties();
        props.load(is);
        ExtensionInfo extension = parseDescriptor(props, jarPath, runtimeCoordinate);
        if (extension == null) {
          throw new IOException(
              "Quarkus extension descriptor in "
                  + jarPath
                  + " has no deployment-artifact property");
        }
        return extension;
      }
    }
  }

  /**
   * Parses the {@code deployment-artifact} property value (GAV format) and derives the runtime
   * extension coordinates.
   *
   * <p>Accepts the Quarkus coordinate forms {@code G:A:V}, {@code G:A:T:V}, and {@code G:A:C:T:V}.
   *
   * @return the extension info, or {@code null} if the property is missing or malformed
   */
  static ExtensionInfo parseDeploymentArtifact(Properties props, Path sourceJar) {
    return parseDescriptor(props, sourceJar, null);
  }

  static ExtensionInfo parseDescriptor(Properties props, Path sourceJar, String runtimeCoordinate) {
    String gav = props.getProperty(DEPLOYMENT_ARTIFACT_KEY);
    if (gav == null || gav.isBlank()) {
      return null;
    }

    var deployment = BazelArtifactCoordinates.parse(gav.trim());
    String groupId;
    String artifactId;
    String version;
    if (runtimeCoordinate == null) {
      groupId = deployment.groupId();
      String deploymentArtifactId = deployment.artifactId();
      artifactId =
          deploymentArtifactId.endsWith(DEPLOYMENT_SUFFIX)
              ? deploymentArtifactId.substring(
                  0, deploymentArtifactId.length() - DEPLOYMENT_SUFFIX.length())
              : deploymentArtifactId;
      version = deployment.version();
    } else {
      var runtime = BazelArtifactCoordinates.parse(runtimeCoordinate.trim());
      groupId = runtime.groupId();
      artifactId = runtime.artifactId();
      version = runtime.version();
    }

    return new ExtensionInfo(
        groupId,
        artifactId,
        version,
        sourceJar,
        runtimeCoordinate == null
            ? groupId + ":" + artifactId + ":" + version
            : runtimeCoordinate.trim(),
        gav.trim(),
        coordinates(props, CONDITIONAL_DEPENDENCIES_KEY),
        coordinates(props, CONDITIONAL_DEV_DEPENDENCIES_KEY),
        artifactKeys(props, DEPENDENCY_CONDITION_KEY));
  }

  private static List<String> coordinates(Properties props, String key) {
    List<String> values = whitespaceValues(props.getProperty(key));
    values.forEach(BazelArtifactCoordinates::parse);
    return values;
  }

  private static List<String> artifactKeys(Properties props, String key) {
    List<String> values = whitespaceValues(props.getProperty(key));
    for (String value : values) {
      String[] parts = value.split(":", -1);
      if (parts.length < 2
          || parts.length > 4
          || parts[0].isBlank()
          || parts[1].isBlank()
          || (parts.length == 3 && parts[2].isBlank())
          || (parts.length == 4 && parts[3].isBlank())) {
        throw new IllegalArgumentException(
            "Invalid dependency-condition artifact key '" + value + "'");
      }
    }
    return values;
  }

  private static List<String> whitespaceValues(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.trim().split("\\s+"));
  }
}
