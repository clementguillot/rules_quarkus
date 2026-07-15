package com.clementguillot.quarkifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine;

/**
 * Picocli version provider that reports the quarkifier version and targeted Quarkus version.
 *
 * <p>The quarkifier version is read from {@code quarkifier-version.properties} on the classpath
 * (stamped at build time by Bazel). The Quarkus version is read from the same properties file.
 */
public final class QuarkifierVersionProvider implements CommandLine.IVersionProvider {

  private static final String RESOURCE = "quarkifier-version.properties";

  @Override
  public String[] getVersion() {
    Properties props = loadProperties();
    String quarkifierVersion = props.getProperty("quarkifier.version", "dev");
    String quarkusVersion = props.getProperty("quarkus.version", "unknown");
    return new String[] {
      "quarkifier " + quarkifierVersion, "quarkus " + quarkusVersion,
    };
  }

  @SuppressWarnings("PMD.UseProperClassLoader") // fallback when context CL is null (bootstrap)
  private static Properties loadProperties() {
    var props = new Properties();
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = QuarkifierVersionProvider.class.getClassLoader();
    }
    try (InputStream in = cl.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException ignored) {
      // best-effort — return defaults if resource missing
    }
    return props;
  }
}
