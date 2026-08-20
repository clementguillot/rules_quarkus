package com.clementguillot.quarkifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Quarkus JVM package types supported by {@code quarkus_app}. */
public enum JarPackageType {
  FAST_JAR("fast-jar", true),
  UBER_JAR("uber-jar", false),
  MUTABLE_JAR("mutable-jar", true),
  LEGACY_JAR("legacy-jar", false),
  AOT_JAR("aot-jar", true);

  private final String value;
  private final boolean fastJarLayout;

  JarPackageType(String configValue, boolean fastJarLayout) {
    this.value = configValue;
    this.fastJarLayout = fastJarLayout;
  }

  /** Value accepted by {@code quarkus.package.jar.type}. */
  public String configValue() {
    return value;
  }

  /** Path to the executable JAR relative to the augmentation output directory. */
  public String runnerPath() {
    return fastJarLayout ? "quarkus-app/quarkus-run.jar" : "quarkus-run.jar";
  }

  /** Whether this package type exists in the given supported Quarkus release. */
  public boolean supports(String quarkusVersion) {
    return this != AOT_JAR || !quarkusVersion.startsWith("3.27.");
  }

  /** Validates this package type against the requested lifecycle and Quarkus release. */
  public void validateCompatibility(AugmentationMode mode, String quarkusVersion)
      throws AugmentationException {
    if (mode != AugmentationMode.NORMAL && this != FAST_JAR) {
      throw new AugmentationException("Package type " + value + " is only valid in normal mode");
    }
    if (!supports(quarkusVersion)) {
      throw new AugmentationException(
          "Package type " + value + " is not supported by Quarkus " + quarkusVersion);
    }
  }

  /** Verifies that Quarkus created the runner at this package type's stable path. */
  public void validateOutput(Path outputDir) throws AugmentationException {
    Path runner = outputDir.resolve(runnerPath());
    if (!Files.isRegularFile(runner)) {
      throw new AugmentationException(
          "Quarkus " + value + " augmentation did not produce the expected runner: " + runner);
    }
  }

  /** Parses a canonical package type name, case-insensitively. */
  public static JarPackageType parse(String value) {
    if (value != null) {
      String normalized = value.toLowerCase(Locale.ROOT);
      for (JarPackageType type : values()) {
        if (type.value.equals(normalized)) {
          return type;
        }
      }
    }
    throw new IllegalArgumentException(
        "Invalid package type: '%s'. Must be one of: %s"
            .formatted(
                value,
                Arrays.stream(values())
                    .map(JarPackageType::configValue)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow()));
  }
}
