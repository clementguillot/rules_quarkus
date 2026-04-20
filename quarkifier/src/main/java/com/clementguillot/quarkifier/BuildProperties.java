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
    var props = new Properties();
    props.setProperty(
        "platform.quarkus.native.builder-image",
        "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
    props.setProperty("quarkus.package.jar.type", "fast-jar");
    return props;
  }
}
