package com.clementguillot.quarkifier.model.conformance;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-aware projections for semantic fields unavailable from a reference build tool. */
@SuppressWarnings("PMD.TooManyMethods")
public final class ApplicationModelComparisonProfile {

  public static final String STRICT = "strict";
  public static final String QUARKUS_3_33_GRADLE = "quarkus-3.33-gradle";
  public static final String BAZEL_RESOLVED_GRAPH = "bazel-resolved-graph";
  private static final String APPLICATION = "application";
  private static final String DEPENDENCIES = "dependencies";
  private static final String DIRECT_DEPENDENCIES = "directDependencies";

  private ApplicationModelComparisonProfile() {}

  public static Models apply(String profile, Object left, Object right) {
    return switch (profile) {
      case STRICT -> new Models(left, right);
      case QUARKUS_3_33_GRADLE -> gradleFlatGraph(left, right);
      case BAZEL_RESOLVED_GRAPH -> bazelResolvedGraph(left, right);
      default -> throw new BazelApplicationModelException(
          "Unknown ApplicationModel comparison profile '"
              + profile
              + "'; expected strict, quarkus-3.33-gradle, or bazel-resolved-graph");
    };
  }

  private static Models gradleFlatGraph(Object left, Object right) {
    Map<String, Object> rightModel = map(right, "right normalized model");
    requireFlatGraph(rightModel);
    Object projectedLeft = mutableCopy(left);
    Object projectedRight = mutableCopy(right);
    removeArtifactGraphs(map(projectedLeft, "left normalized model"));
    removeArtifactGraphs(map(projectedRight, "right normalized model"));
    return new Models(projectedLeft, projectedRight);
  }

  private static Models bazelResolvedGraph(Object left, Object right) {
    Object projectedLeft = mutableCopy(left);
    Object projectedRight = mutableCopy(right);
    removeDependencyGraphs(map(projectedLeft, "left normalized model"));
    removeDependencyGraphs(map(projectedRight, "right normalized model"));
    return new Models(projectedLeft, projectedRight);
  }

  private static void requireFlatGraph(Map<String, Object> model) {
    assertEmptyGraph(map(model.get(APPLICATION), APPLICATION), APPLICATION);
    for (Object value : list(model.get(DEPENDENCIES), DEPENDENCIES)) {
      Map<String, Object> dependency = map(value, "dependency");
      assertEmptyGraph(dependency, String.valueOf(dependency.get("id")));
    }
  }

  private static void assertEmptyGraph(Map<String, Object> artifact, String id) {
    if (!list(artifact.get(DEPENDENCIES), id + '.' + DEPENDENCIES).isEmpty()
        || !list(artifact.get(DIRECT_DEPENDENCIES), id + '.' + DIRECT_DEPENDENCIES).isEmpty()) {
      throw new BazelApplicationModelException(
          "The quarkus-3.33-gradle comparison profile requires a flat right-hand graph; "
              + id
              + " contains dependency edges");
    }
  }

  private static void removeArtifactGraphs(Map<String, Object> model) {
    clearGraph(map(model.get(APPLICATION), APPLICATION));
    removeDependencyGraphs(model);
  }

  private static void removeDependencyGraphs(Map<String, Object> model) {
    for (Object value : list(model.get(DEPENDENCIES), DEPENDENCIES)) {
      clearGraph(map(value, "dependency"));
    }
  }

  private static void clearGraph(Map<String, Object> artifact) {
    artifact.put(DEPENDENCIES, List.of());
    artifact.put(DIRECT_DEPENDENCIES, List.of());
  }

  private static Object mutableCopy(Object value) {
    if (value instanceof Map<?, ?> source) {
      var result = new LinkedHashMap<String, Object>();
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        result.put(String.valueOf(entry.getKey()), mutableCopy(entry.getValue()));
      }
      return result;
    }
    if (value instanceof List<?> source) {
      var result = new ArrayList<Object>(source.size());
      source.forEach(item -> result.add(mutableCopy(item)));
      return result;
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value, String context) {
    if (!(value instanceof Map<?, ?> result)) {
      throw new BazelApplicationModelException(context + " must be an object");
    }
    return (Map<String, Object>) result;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value, String context) {
    if (!(value instanceof List<?> result)) {
      throw new BazelApplicationModelException(context + " must be an array");
    }
    return (List<Object>) result;
  }

  public record Models(Object left, Object right) {}
}
