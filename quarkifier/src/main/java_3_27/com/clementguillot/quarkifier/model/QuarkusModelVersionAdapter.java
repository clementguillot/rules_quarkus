package com.clementguillot.quarkifier.model;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Platform;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.util.BootstrapUtils;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Compatibility boundary for Quarkus 3.27, whose model has no direct-dependency collection. */
@SuppressWarnings("PMD.UnusedPrivateMethod")
final class QuarkusModelVersionAdapter {

  private QuarkusModelVersionAdapter() {}

  static void setDirectDependencies(
      ResolvedDependencyBuilder dependency, List<Dependency> directDependencies) {
    // Quarkus 3.27 serializes only the coordinate dependency links.
  }

  static boolean isMissingFromApplication(ResolvedDependency dependency) {
    return false;
  }

  @SuppressWarnings("deprecation")
  static void serialize(ApplicationModel model, Path output) throws IOException {
    BootstrapUtils.serializeAppModel(model, output);
  }

  static ApplicationModel deserialize(Path input) throws IOException {
    try (var objectInput = new ObjectInputStream(Files.newInputStream(input))) {
      return (ApplicationModel) objectInput.readObject();
    } catch (ClassNotFoundException exception) {
      throw new IOException("Failed to deserialize the Quarkus 3.27 application model", exception);
    }
  }

  static PlatformImports platformImports(Platform source) throws IOException {
    var result = new PlatformImportsImpl();
    result.setPlatformProperties(source.properties());
    if (source.imports().isEmpty()) {
      return result;
    }

    Properties properties = new Properties();
    properties.putAll(source.properties());
    source
        .releases()
        .forEach(
            release ->
                properties.setProperty(
                    "platform.release-info@"
                        + release.platformKey()
                        + "$"
                        + release.stream()
                        + "#"
                        + release.version(),
                    release.memberBoms().stream()
                        .map(QuarkusModelVersionAdapter::coordinates)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")));
    Path temporary = Files.createTempFile("rules-quarkus-platform-", ".properties");
    try {
      try (var output = Files.newOutputStream(temporary)) {
        properties.store(output, "rules_quarkus explicit application model");
      }
      for (ArtifactCoordinates bom : source.imports()) {
        result.addPlatformDescriptor(
            bom.groupId(),
            bom.artifactId() + "-quarkus-platform-descriptor",
            "",
            "json",
            bom.version());
        result.addPlatformProperties(
            bom.groupId(),
            bom.artifactId() + "-quarkus-platform-properties",
            "",
            "properties",
            bom.version(),
            temporary);
      }
    } catch (AppModelResolverException exception) {
      throw new IOException("Failed to construct Quarkus 3.27 platform imports", exception);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return result;
  }

  private static String coordinates(ArtifactCoordinates coordinates) {
    return coordinates.groupId()
        + ":"
        + coordinates.artifactId()
        + ":"
        + coordinates.classifier()
        + ":"
        + coordinates.type()
        + ":"
        + coordinates.version();
  }
}
