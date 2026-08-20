package com.clementguillot.quarkifier;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Default Quarkus build system properties shared across augmentation modes.
 *
 * <p>These properties are required by the Quarkus bootstrap API and SmallRye Config expression
 * resolution. They are used identically in both production augmentation and dev mode.
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
      result.put(name, loaded.getProperty(name));
    }
    return Map.copyOf(result);
  }

  /**
   * Creates a new {@link Properties} instance with the default Quarkus build properties.
   *
   * @param mainClass the fully-qualified main class name, or {@code null} to omit the property
   * @param builderImage the native builder image, or {@code null} to use the default
   * @param packageType the JVM package layout to request from Quarkus
   */
  public static Properties defaults(
      String mainClass, String builderImage, JarPackageType packageType) {
    return defaults(Map.of(), mainClass, builderImage, packageType);
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

  /**
   * Creates a new {@link Properties} instance configured for native-sources-only augmentation.
   *
   * @param mainClass the fully-qualified main class name, or {@code null} to omit the property
   * @param builderImage the native builder image, or {@code null} to use the default
   */
  public static Properties nativeSourcesOnly(String mainClass, String builderImage) {
    return nativeSourcesOnly(Map.of(), mainClass, builderImage);
  }

  /** Creates native-sources properties from the declared lifecycle configuration. */
  public static Properties nativeSourcesOnly(
      Map<String, String> declaredProperties, String mainClass, String builderImage) {
    var props = defaults(declaredProperties, mainClass, builderImage, JarPackageType.FAST_JAR);
    props.setProperty("quarkus.package.jar.add-runner-suffix", "true");
    props.setProperty("quarkus.native.enabled", "true");
    props.setProperty("quarkus.native.sources-only", "true");
    return props;
  }
}
