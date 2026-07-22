package com.clementguillot.quarkifier.model.transport;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;

/** Coordinate parsing and canonicalization shared by model assembly and validation. */
public final class BazelArtifactCoordinates {

  private BazelArtifactCoordinates() {}

  /** Parses Quarkus compact coordinates: G:A:V, G:A:T:V, or G:A:C:T:V. */
  public static ArtifactCoordinates parse(String value) {
    String[] parts = value.split(":", -1);
    ArtifactCoordinates result =
        switch (parts.length) {
          case 3 -> new ArtifactCoordinates(parts[0], parts[1], "", "jar", parts[2]);
          case 4 -> new ArtifactCoordinates(parts[0], parts[1], "", parts[2], parts[3]);
          case 5 -> new ArtifactCoordinates(parts[0], parts[1], parts[2], parts[3], parts[4]);
          default -> throw new BazelApplicationModelException(
              "Invalid artifact coordinates '"
                  + value
                  + "': expected G:A:V, G:A:T:V, or G:A:C:T:V");
        };
    if (result.groupId().isBlank()
        || result.artifactId().isBlank()
        || result.type().isBlank()
        || result.version().isBlank()) {
      throw new BazelApplicationModelException(
          "Invalid artifact coordinates '" + value + "': required component is blank");
    }
    return result;
  }

  public static String canonical(ArtifactCoordinates value) {
    return value.groupId()
        + ":"
        + value.artifactId()
        + ":"
        + value.classifier()
        + ":"
        + value.type()
        + ":"
        + value.version();
  }
}
