package com.clementguillot.quarkifier;

import java.nio.file.Path;

/**
 * Metadata for a Quarkus extension discovered on the application classpath.
 *
 * <p>Coordinates are derived from the {@code deployment-artifact} property found in {@code
 * META-INF/quarkus-extension.properties}. The runtime artifact ID is obtained by stripping the
 * {@code -deployment} suffix from the deployment artifact ID.
 *
 * @param groupId Maven group ID (e.g. {@code io.quarkus})
 * @param artifactId Maven runtime artifact ID (e.g. {@code quarkus-resteasy-reactive})
 * @param version Maven version (e.g. {@code 3.20.6})
 * @param sourceJar the classpath jar that contained the extension metadata
 */
public record ExtensionInfo(String groupId, String artifactId, String version, Path sourceJar) {}
