package com.clementguillot.quarkifier.extension;

/** Build-system project metadata used to complete an extension descriptor. */
public record ExtensionProjectInfo(String name, String groupId, String artifactId, String version) {

  /** Creates validated project metadata. */
  public ExtensionProjectInfo {
    requireNonBlank("name", name);
    requireNonBlank("group ID", groupId);
    requireNonBlank("artifact ID", artifactId);
    requireNonBlank("version", version);
  }

  private static void requireNonBlank(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Extension " + field + " must not be blank");
    }
  }
}
