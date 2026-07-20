package com.clementguillot.quarkifier.model.conformance;

import com.clementguillot.quarkifier.model.conformance.ApplicationModelDiff.Difference;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import com.clementguillot.quarkifier.model.transport.StrictJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact, value-bound allowlist for reviewed cross-build-tool model differences. */
public final class ApplicationModelDifferenceAllowlist {

  public static final String SCHEMA_VERSION = "application-model-difference-allowlist-v1";
  private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
  private static final String ENTRIES = "entries";
  private static final String PATH = "path";
  private static final String LEFT_SHA_256 = "leftSha256";
  private static final String RIGHT_SHA_256 = "rightSha256";
  private static final String REASON = "reason";
  private static final Set<String> ROOT_FIELDS = Set.of(SCHEMA_VERSION_FIELD, ENTRIES);
  private static final Set<String> ENTRY_FIELDS = Set.of(PATH, LEFT_SHA_256, RIGHT_SHA_256, REASON);

  private ApplicationModelDifferenceAllowlist() {}

  public static Check check(String document, List<Difference> differences) {
    Map<String, Object> root = object(StrictJson.parse(document), "allowlist");
    rejectUnknown(root, ROOT_FIELDS, "allowlist");
    String schema = string(root.get(SCHEMA_VERSION_FIELD), SCHEMA_VERSION_FIELD);
    if (!SCHEMA_VERSION.equals(schema)) {
      throw new BazelApplicationModelException(
          "Unsupported ApplicationModel difference allowlist schema '" + schema + "'");
    }

    var entries = new HashMap<String, Entry>();
    for (Object value : array(root.get(ENTRIES), ENTRIES)) {
      Map<String, Object> source = object(value, "allowlist entry");
      rejectUnknown(source, ENTRY_FIELDS, "allowlist entry");
      Entry entry =
          new Entry(
              string(source.get(PATH), PATH),
              string(source.get(LEFT_SHA_256), LEFT_SHA_256),
              string(source.get(RIGHT_SHA_256), RIGHT_SHA_256),
              string(source.get(REASON), REASON));
      if (entry.path().isBlank() || entry.reason().isBlank()) {
        throw new BazelApplicationModelException("Allowlist path and reason must not be blank");
      }
      if (entries.putIfAbsent(entry.key(), entry) != null) {
        throw new BazelApplicationModelException(
            "Duplicate ApplicationModel allowlist entry for " + entry.path());
      }
    }

    var unapproved = new ArrayList<Difference>();
    int approved = 0;
    for (Difference difference : differences) {
      Entry matched = entries.remove(key(difference));
      if (matched == null) {
        unapproved.add(difference);
      } else {
        approved++;
      }
    }
    List<String> unused = entries.values().stream().map(Entry::path).sorted().toList();
    return new Check(List.copyOf(unapproved), approved, unused);
  }

  public static String template(List<Difference> differences) {
    var entries = new ArrayList<Object>();
    for (Difference difference : differences) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put(PATH, difference.path());
      entry.put(LEFT_SHA_256, fingerprint(difference.left()));
      entry.put(RIGHT_SHA_256, fingerprint(difference.right()));
      entry.put(REASON, "REVIEW REQUIRED");
      entries.add(entry);
    }
    var root = new LinkedHashMap<String, Object>();
    root.put(SCHEMA_VERSION_FIELD, SCHEMA_VERSION);
    root.put(ENTRIES, entries);
    return CanonicalJson.write(root);
  }

  private static String key(Difference difference) {
    return difference.path()
        + '\u0000'
        + fingerprint(difference.left())
        + '\u0000'
        + fingerprint(difference.right());
  }

  private static String fingerprint(Object value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK does not provide SHA-256", exception);
    }
  }

  private static void rejectUnknown(
      Map<String, Object> value, Set<String> allowed, String context) {
    List<String> unknown =
        value.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
    if (!unknown.isEmpty()) {
      throw new BazelApplicationModelException(context + " contains unknown fields " + unknown);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String context) {
    if (!(value instanceof Map<?, ?> result)) {
      throw new BazelApplicationModelException(context + " must be an object");
    }
    return (Map<String, Object>) result;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value, String context) {
    if (!(value instanceof List<?> result)) {
      throw new BazelApplicationModelException(context + " must be an array");
    }
    return (List<Object>) result;
  }

  private static String string(Object value, String context) {
    if (!(value instanceof String result)) {
      throw new BazelApplicationModelException(context + " must be a string");
    }
    return result;
  }

  private record Entry(String path, String leftSha256, String rightSha256, String reason) {
    private String key() {
      return path + '\u0000' + leftSha256 + '\u0000' + rightSha256;
    }
  }

  public record Check(List<Difference> unapproved, int approvedCount, List<String> unusedPaths) {}
}
