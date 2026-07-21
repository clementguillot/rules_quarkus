package com.clementguillot.quarkifier.model.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Reads the Quarkus extension descriptor ({@code META-INF/quarkus-extension.properties}) from a JAR
 * file.
 *
 * <p>Shared between the assembler (which validates descriptor/catalog agreement) and the Quarkus
 * model adapter (which registers capabilities and flags).
 */
public final class ExtensionDescriptorReader {

  private static final String DESCRIPTOR_PATH = "META-INF/quarkus-extension.properties";

  private ExtensionDescriptorReader() {}

  /** Returns the descriptor properties if the JAR contains one, empty otherwise. */
  public static Optional<Properties> readFromJar(Path jarPath) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      var entry = jar.getJarEntry(DESCRIPTOR_PATH);
      if (entry == null) {
        return Optional.empty();
      }
      Properties properties = new Properties();
      try (InputStream input = jar.getInputStream(entry)) {
        properties.load(input);
      }
      return Optional.of(properties);
    }
  }
}
