package com.clementguillot.quarkifier;

import java.util.Properties;

/**
 * Default Quarkus build system properties shared across augmentation modes.
 *
 * <p>These properties are required by the Quarkus bootstrap API and SmallRye Config expression
 * resolution. They are used identically in both production augmentation and dev mode.
 */
public final class BuildProperties {

  private BuildProperties() {}

  /** Creates a new {@link Properties} instance with the default Quarkus build properties. */
  public static Properties defaults() {
    return defaults(null);
  }

  /**
   * Creates a new {@link Properties} instance with the default Quarkus build properties, optionally
   * including the {@code quarkus.package.main-class} property.
   *
   * @param mainClass the fully-qualified main class name, or {@code null} to omit the property
   */
  public static Properties defaults(String mainClass) {
    var props = new Properties();
    props.setProperty(
        "platform.quarkus.native.builder-image",
        "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
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
   */
  public static Properties nativeSourcesOnly(String mainClass) {
    var props = new Properties();
    props.setProperty("quarkus.native.enabled", "true");
    props.setProperty("quarkus.native.sources-only", "true");
    props.setProperty("quarkus.package.jar.type", "fast-jar");
    props.setProperty(
        "platform.quarkus.native.builder-image",
        "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
    if (mainClass != null) {
      props.setProperty("quarkus.package.main-class", mainClass);
    }
    return props;
  }
}
