package com.clementguillot.quarkifier.model;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Platform;
import io.quarkus.bootstrap.app.ApplicationModelSerializer;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Quarkus 3.33 application-model API calls that do not exist on older supported lines. */
@SuppressWarnings("PMD.UnusedPrivateMethod")
final class QuarkusModelVersionAdapter {

  private QuarkusModelVersionAdapter() {}

  static void setDirectDependencies(
      ResolvedDependencyBuilder dependency, List<Dependency> directDependencies) {
    dependency.setDirectDependencies(directDependencies);
  }

  static boolean isMissingFromApplication(ResolvedDependency dependency) {
    return dependency.isFlagSet(DependencyFlags.MISSING_FROM_APPLICATION);
  }

  static void serialize(ApplicationModel model, Path output) throws IOException {
    ApplicationModelSerializer.serialize(model, output);
  }

  static ApplicationModel deserialize(Path input) throws IOException {
    return ApplicationModelSerializer.deserialize(input);
  }

  static PlatformImports platformImports(Platform source) {
    var platformMap = new LinkedHashMap<String, Object>();
    platformMap.put("platform-properties", source.properties());
    platformMap.put(
        "imported-boms",
        source.imports().stream().map(QuarkusModelVersionAdapter::coordinates).toList());
    platformMap.put(
        "release-info",
        source.releases().stream()
            .map(
                release -> {
                  var releaseMap = new LinkedHashMap<String, Object>();
                  releaseMap.put("platform-key", release.platformKey());
                  releaseMap.put("stream", release.stream());
                  releaseMap.put("version", release.version());
                  releaseMap.put(
                      "boms",
                      release.memberBoms().stream()
                          .map(QuarkusModelVersionAdapter::coordinates)
                          .toList());
                  return releaseMap;
                })
            .toList());
    return PlatformImports.fromMap(platformMap);
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
