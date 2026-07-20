package com.clementguillot.quarkifier.model.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Structural, JSON-pointer-like diff for normalized application models. */
public final class ApplicationModelDiff {

  private ApplicationModelDiff() {}

  public static List<Difference> compare(Object left, Object right) {
    var result = new ArrayList<Difference>();
    compare("", left, right, result);
    return List.copyOf(result);
  }

  public static String render(List<Difference> differences) {
    if (differences.isEmpty()) {
      return "ApplicationModels are semantically equal\n";
    }
    var result = new StringBuilder(256);
    result.append(differences.size()).append(" semantic ApplicationModel difference(s):\n");
    for (Difference difference : differences) {
      result
          .append("- ")
          .append(difference.path().isEmpty() ? "/" : difference.path())
          .append("\n    left: ")
          .append(difference.left())
          .append("\n   right: ")
          .append(difference.right())
          .append('\n');
    }
    return result.toString();
  }

  private static void compare(String path, Object left, Object right, List<Difference> result) {
    if (java.util.Objects.equals(left, right)) {
      return;
    }
    if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
      var keys = new TreeSet<String>();
      leftMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
      rightMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
      for (String key : keys) {
        compare(path + "/" + escape(key), leftMap.get(key), rightMap.get(key), result);
      }
      return;
    }
    if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
      Optional<Map<String, Object>> keyedLeft = keyed(leftList);
      Optional<Map<String, Object>> keyedRight = keyed(rightList);
      if (keyedLeft.isPresent() && keyedRight.isPresent()) {
        compare(path, keyedLeft.orElseThrow(), keyedRight.orElseThrow(), result);
      } else {
        result.add(new Difference(path, left, right));
      }
      return;
    }
    result.add(new Difference(path, left, right));
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static Optional<Map<String, Object>> keyed(List<?> values) {
    var result = new java.util.LinkedHashMap<String, Object>();
    for (Object value : values) {
      if (!(value instanceof Map<?, ?> map)) {
        return Optional.empty();
      }
      String key = identity(map);
      if (key == null || result.putIfAbsent(key, value) != null) {
        return Optional.empty();
      }
    }
    return result.isEmpty() ? Optional.empty() : Optional.of(result);
  }

  private static String identity(Map<?, ?> value) {
    for (String field : List.of("id", "extension", "target", "classifier", "dir")) {
      Object identity = value.get(field);
      if (identity != null) {
        return field + "=" + identity;
      }
    }
    if (value.containsKey("platformKey")) {
      return "release="
          + value.get("platformKey")
          + ':'
          + value.get("stream")
          + ':'
          + value.get("version");
    }
    return null;
  }

  public record Difference(String path, Object left, Object right) {}
}
