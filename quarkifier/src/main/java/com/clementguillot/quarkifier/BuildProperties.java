package com.clementguillot.quarkifier;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Builds, loads, and temporarily scopes Quarkus build-system properties.
 *
 * <p>Production, native, and code-generation actions scope the properties around their in-process
 * Quarkus work. Dev mode additionally forwards the same merged values to its long-lived child JVM
 * and serialized context, where they remain visible for the dev session.
 */
public final class BuildProperties {

  /**
   * Default Mandrel builder image used when none is specified. Digest-pinned to match {@code
   * DEFAULT_NATIVE_BUILDER_IMAGE} in {@code quarkus/private/versions.bzl} — a mutable tag would let
   * the recorded toolchain drift underneath identical build keys.
   */
  public static final String DEFAULT_BUILDER_IMAGE =
      "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25"
          + "@sha256:4dda6a3d677b57614849557d0d18aac7326c4f30175142b0f1bb91bdcfc5c29a";

  private BuildProperties() {}

  /**
   * Runs an augmentation operation with declared build configuration at system-property precedence,
   * then restores the process exactly to its prior state.
   *
   * <p>Maven and Gradle perform augmentation in a worker JVM whose system properties contain the
   * effective build configuration. Quarkus' build-system-properties map alone does not override
   * every extension configuration key from {@code application.properties} — Quarkus registers it as
   * a {@code PropertiesConfigSource} at ordinal 100, below the 250 of {@code
   * application.properties} — so the standalone Quarkifier must reproduce the worker-JVM boundary
   * explicitly.
   *
   * <p>Every declared name is scoped, not just {@code quarkus.*}: applications may declare
   * arbitrary keys to satisfy {@code ${...}} expressions inside Quarkus configuration.
   */
  public static synchronized <T> T withSystemProperties(Properties properties, Callable<T> action)
      throws Exception {
    var previous = new HashMap<String, String>();
    var absent = new HashSet<String>();
    try {
      // The mutation loop lives inside the try so a failure partway through —
      // System.setProperty rejects an empty name — still restores the names
      // already overwritten instead of leaking them for the rest of the JVM.
      for (String name : properties.stringPropertyNames()) {
        String oldValue = System.getProperty(name);
        if (oldValue == null) {
          absent.add(name);
        } else {
          previous.put(name, oldValue);
        }
        System.setProperty(name, properties.getProperty(name));
      }
      return action.call();
    } finally {
      for (String name : absent) {
        System.clearProperty(name);
      }
      previous.forEach(System::setProperty);
    }
  }

  /** Loads a declared UTF-8 properties file without consulting ambient process state. */
  public static Map<String, String> load(Path propertiesFile) throws IOException {
    if (propertiesFile == null) {
      return Map.of();
    }
    var loaded = new Properties();
    try (var reader = Files.newBufferedReader(propertiesFile, UTF_8)) {
      loaded.load(reader);
    }
    var result = new HashMap<String, String>();
    for (String name : loaded.stringPropertyNames()) {
      validateName(name, propertiesFile);
      result.put(name, loaded.getProperty(name));
    }
    return Map.copyOf(result);
  }

  private static void validateName(String name, Path propertiesFile) throws IOException {
    if (name.isEmpty()) {
      throw new IOException(
          "Build properties file contains an empty property name: " + propertiesFile);
    }
    if (name.indexOf('=') >= 0) {
      throw new IOException(
          "Build property name '"
              + name
              + "' contains '=' and cannot be represented as a JVM -D flag: "
              + propertiesFile);
    }
  }

  /**
   * Merges declared properties with Bazel rule invariants.
   *
   * <p>Dedicated rule attributes win over identically named entries in {@code declaredProperties}
   * so the action's package layout, main class, and native toolchain cannot disagree with its
   * declared outputs.
   */
  public static Properties defaults(
      Map<String, String> declaredProperties,
      String mainClass,
      String builderImage,
      JarPackageType packageType) {
    var props = new Properties();
    declaredProperties.forEach(props::setProperty);
    props.setProperty(
        "platform.quarkus.native.builder-image",
        builderImage != null ? builderImage : DEFAULT_BUILDER_IMAGE);
    props.setProperty("quarkus.package.jar.type", packageType.configValue());
    // A stable file name lets Bazel launch every non-fast layout without
    // guessing Quarkus' configurable runner suffix.
    props.setProperty("quarkus.package.jar.add-runner-suffix", "false");
    if (mainClass != null) {
      props.setProperty("quarkus.package.main-class", mainClass);
    }
    return props;
  }

  /** Retained source-compatible entry point for callers without declared properties. */
  public static Properties defaults(
      String mainClass, String builderImage, JarPackageType packageType) {
    return defaults(Map.of(), mainClass, builderImage, packageType);
  }

  /**
   * Creates native-sources properties from the declared lifecycle configuration.
   *
   * @param declaredProperties the declared hermetic build configuration
   * @param mainClass the fully-qualified main class name, or {@code null} to omit the property
   * @param builderImage the native builder image, or {@code null} to use the default
   */
  public static Properties nativeSourcesOnly(
      Map<String, String> declaredProperties, String mainClass, String builderImage) {
    var props = defaults(declaredProperties, mainClass, builderImage, JarPackageType.FAST_JAR);
    props.setProperty("quarkus.package.jar.add-runner-suffix", "true");
    props.setProperty("quarkus.native.enabled", "true");
    props.setProperty("quarkus.native.sources-only", "true");
    return props;
  }

  /** Retained source-compatible entry point for callers without declared properties. */
  public static Properties nativeSourcesOnly(String mainClass, String builderImage) {
    return nativeSourcesOnly(Map.of(), mainClass, builderImage);
  }
}
