package com.clementguillot.quarkifier;

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
 * by stripping the {@code -deployment} suffix.
 */
public final class ExtensionScanner {

  private static final String EXTENSION_PROPERTIES_PATH = "META-INF/quarkus-extension.properties";
  private static final String DEPLOYMENT_ARTIFACT_KEY = "deployment-artifact";
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
   * Scans a single jar for Quarkus extension metadata.
   *
   * @return the extension info, or {@code null} if the jar is not a Quarkus extension
   */
  private static ExtensionInfo scanJar(Path jarPath) throws IOException {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      ZipEntry entry = jarFile.getEntry(EXTENSION_PROPERTIES_PATH);
      if (entry == null) {
        return null;
      }
      try (InputStream is = jarFile.getInputStream(entry)) {
        Properties props = new Properties();
        props.load(is);
        return parseDeploymentArtifact(props, jarPath);
      }
    }
  }

  /**
   * Parses the {@code deployment-artifact} property value (GAV format) and derives the runtime
   * extension coordinates.
   *
   * <p>Expected format: {@code groupId:artifactId-deployment:version}
   *
   * @return the extension info, or {@code null} if the property is missing or malformed
   */
  static ExtensionInfo parseDeploymentArtifact(Properties props, Path sourceJar) {
    String gav = props.getProperty(DEPLOYMENT_ARTIFACT_KEY);
    if (gav == null || gav.isBlank()) {
      return null;
    }

    String[] parts = gav.split(":");
    if (parts.length < 3) {
      return null;
    }

    String groupId = parts[0];
    String deploymentArtifactId = parts[1];
    String version = parts[2];

    // Derive the runtime artifact ID by stripping the -deployment suffix
    String artifactId =
        deploymentArtifactId.endsWith(DEPLOYMENT_SUFFIX)
            ? deploymentArtifactId.substring(
                0, deploymentArtifactId.length() - DEPLOYMENT_SUFFIX.length())
            : deploymentArtifactId;

    return new ExtensionInfo(groupId, artifactId, version, sourceJar);
  }
}
