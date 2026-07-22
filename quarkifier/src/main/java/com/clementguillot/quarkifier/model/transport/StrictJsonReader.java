package com.clementguillot.quarkifier.model.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared JSON-to-Java reading primitives for the model transport layer.
 *
 * <p>Both {@link BazelApplicationModelReader} and {@link BazelModelInputReader} parse untyped JSON
 * maps produced by {@link StrictJson}. This class eliminates the duplication of their accessor,
 * validation, and error-reporting helpers.
 */
@SuppressWarnings("PMD.TooManyMethods")
final class StrictJsonReader {

  private StrictJsonReader() {}

  // ---- Type-safe accessors ----

  static String string(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (!(raw instanceof String result)) {
      throw problem(path + "." + field, "expected a string");
    }
    return result;
  }

  static String nullableString(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String result)) {
      throw problem(path + "." + field, "expected a string or null");
    }
    return result;
  }

  static boolean bool(Map<String, Object> value, String field, String path) {
    Object raw = required(value, field, path);
    if (!(raw instanceof Boolean result)) {
      throw problem(path + "." + field, "expected a boolean");
    }
    return result;
  }

  static List<String> stringArray(Map<String, Object> value, String field, String path) {
    List<Object> raw = array(required(value, field, path), path + "." + field);
    var result = new ArrayList<String>(raw.size());
    for (int index = 0; index < raw.size(); index++) {
      if (!(raw.get(index) instanceof String element)) {
        throw problem(path + "." + field + "[" + index + "]", "expected a string");
      }
      result.add(element);
    }
    return result;
  }

  static Map<String, String> stringMap(Map<String, Object> value, String field, String path) {
    Map<String, Object> raw = objectMap(required(value, field, path), path + "." + field);
    var result = new LinkedHashMap<String, String>();
    raw.forEach(
        (key, element) -> {
          if (!(element instanceof String string)) {
            throw problem(path + "." + field + "." + key, "expected a string");
          }
          result.put(key, string);
        });
    return result;
  }

  static <T extends Enum<T>> T enumeration(
      Map<String, Object> value, String field, String path, Class<T> type) {
    String raw = string(value, field, path);
    for (T constant : type.getEnumConstants()) {
      if (constant.name().toLowerCase(Locale.ROOT).equals(raw)) {
        return constant;
      }
    }
    var allowed = new ArrayList<String>();
    for (T constant : type.getEnumConstants()) {
      allowed.add(constant.name().toLowerCase(Locale.ROOT));
    }
    throw problem(path + "." + field, "expected one of " + allowed + ", got '" + raw + "'");
  }

  // ---- Array mapping ----

  static <T> List<T> mapArray(
      Map<String, Object> value, String field, String path, ElementMapper<T> mapper) {
    List<Object> raw = array(required(value, field, path), path + "." + field);
    var result = new ArrayList<T>(raw.size());
    for (int index = 0; index < raw.size(); index++) {
      String elementPath = path + "." + field + "[" + index + "]";
      result.add(mapper.map(objectMap(raw.get(index), elementPath), elementPath));
    }
    return result;
  }

  @FunctionalInterface
  interface ElementMapper<T> {
    T map(Map<String, Object> value, String path);
  }

  // ---- Structural checks ----

  @SuppressWarnings("unchecked")
  static Map<String, Object> objectMap(Object value, String path) {
    if (!(value instanceof Map<?, ?>)) {
      throw problem(path, "expected an object");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(Object value, String path) {
    if (!(value instanceof List<?>)) {
      throw problem(path, "expected an array");
    }
    return (List<Object>) value;
  }

  static Object required(Map<String, Object> value, String field, String path) {
    if (!value.containsKey(field)) {
      throw problem(path, "missing required field '" + field + "'");
    }
    return value.get(field);
  }

  static void fields(Map<String, Object> value, String path, String... expectedFields) {
    Set<String> expected = new LinkedHashSet<>(List.of(expectedFields));
    for (String field : value.keySet()) {
      if (!expected.contains(field)) {
        throw problem(path, "unknown field '" + field + "'");
      }
    }
    for (String field : expected) {
      if (!value.containsKey(field)) {
        throw problem(path, "missing required field '" + field + "'");
      }
    }
  }

  // ---- Root parsing ----

  static Map<String, Object> parseRoot(String document) {
    return objectMap(StrictJson.parse(document), "$");
  }

  static void schema(Map<String, Object> root, String expected) {
    String actual = string(root, "schemaVersion", "$");
    if (!expected.equals(actual)) {
      throw problem("$.schemaVersion", "expected '" + expected + "', got '" + actual + "'");
    }
  }

  // ---- Error reporting ----

  static BazelApplicationModelException problem(String path, String message) {
    return new BazelApplicationModelException("Invalid model document at " + path + ": " + message);
  }

  static void nonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw problem(path, "must not be blank");
    }
  }
}
