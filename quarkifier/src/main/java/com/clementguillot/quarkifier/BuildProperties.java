package com.clementguillot.quarkifier;

import java.util.Properties;

/**
 * Default Quarkus build system properties shared across augmentation modes.
 *
 * <p>These properties are required by the Quarkus bootstrap API and SmallRye Config expression
 * resolution. They are used identically in both production augmentation and dev mode.
 */
public final class BuildProperties {

  /** Default Mandrel builder image used when none is specified. */
  public static final String DEFAULT_BUILDER_IMAGE =
      "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25";

  private BuildProperties() {}

  /**
   * Creates a new {@link Properties} instance with the default Quarkus build properties.
   *
   * @param mainClass the fully-qualified main class name, or {@code null} to omit the property
   * @param builderImage the native builder image, or {@code null} to use the default
   */
  public static Properties defaults(String mainClass, String builderImage) {
    var props = new Properties();
    props.setProperty(
        "platform.quarkus.native.builder-image",
        builderImage != null ? builderImage : DEFAULT_BUILDER_IMAGE);
    props.setProperty("quarkus.package.jar.type", "fast-jar");
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
    var props = defaults(mainClass, builderImage);
    props.setProperty("quarkus.native.enabled", "true");
    props.setProperty("quarkus.native.sources-only", "true");
    return props;
  }
}
