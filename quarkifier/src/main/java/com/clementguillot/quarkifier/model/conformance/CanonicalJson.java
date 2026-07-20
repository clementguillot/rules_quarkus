package com.clementguillot.quarkifier.model.conformance;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic pretty JSON writer for normalized conformance snapshots. */
final class CanonicalJson {

  private CanonicalJson() {}

  static String write(Object value) {
    var result = new StringBuilder(8192);
    append(result, value, 0);
    result.append('\n');
    return result.toString();
  }

  private static void append(StringBuilder result, Object value, int depth) {
    if (value == null) {
      result.append("null");
    } else if (value instanceof String string) {
      quote(result, string);
    } else if (value instanceof Boolean || value instanceof BigDecimal) {
      result.append(value);
    } else if (value instanceof Map<?, ?> map) {
      object(result, map, depth);
    } else if (value instanceof List<?> list) {
      array(result, list, depth);
    } else {
      throw new BazelApplicationModelException(
          "Cannot serialize normalized JSON value of type " + value.getClass().getName());
    }
  }

  private static void object(StringBuilder result, Map<?, ?> value, int depth) {
    var ordered = new TreeMap<String, Object>();
    for (Map.Entry<?, ?> entry : value.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new BazelApplicationModelException("Normalized JSON object key is not a string");
      }
      ordered.put(key, entry.getValue());
    }
    if (ordered.isEmpty()) {
      result.append("{}");
      return;
    }
    result.append("{\n");
    int index = 0;
    for (Map.Entry<String, Object> entry : ordered.entrySet()) {
      indent(result, depth + 1);
      quote(result, entry.getKey());
      result.append(": ");
      append(result, entry.getValue(), depth + 1);
      index++;
      if (index < ordered.size()) {
        result.append(',');
      }
      result.append('\n');
    }
    indent(result, depth);
    result.append('}');
  }

  private static void array(StringBuilder result, List<?> value, int depth) {
    if (value.isEmpty()) {
      result.append("[]");
      return;
    }
    result.append("[\n");
    for (int index = 0; index < value.size(); index++) {
      indent(result, depth + 1);
      append(result, value.get(index), depth + 1);
      if (index + 1 < value.size()) {
        result.append(',');
      }
      result.append('\n');
    }
    indent(result, depth);
    result.append(']');
  }

  private static void quote(StringBuilder result, String value) {
    result.append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (current < 0x20) {
            result.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
          } else {
            result.append(current);
          }
        }
      }
    }
    result.append('"');
  }

  private static void indent(StringBuilder result, int depth) {
    result.append("  ".repeat(depth));
  }
}
