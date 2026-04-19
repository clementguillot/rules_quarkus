package com.clementguillot.quarkifier.maven;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Checks Quarkus extension versions against the expected toolchain version and emits warnings to
 * stderr for any mismatches.
 *
 * <p>The build continues on mismatch (exit 0) — this is a warning-only check.
 */
public final class VersionChecker {

  private VersionChecker() {}

  /**
   * Checks each extension's version against the expected Quarkus version.
   *
   * @param extensions the discovered Quarkus extensions
   * @param expectedVersion the expected Quarkus toolchain version (may be {@code null})
   */
  public static void check(List<ExtensionInfo> extensions, String expectedVersion) {
    check(extensions, expectedVersion, System.err);
  }

  /**
   * Checks each extension's version against the expected Quarkus version, writing warnings to the
   * given {@link PrintStream}.
   *
   * <p>Package-private overload for testability.
   */
  static List<ExtensionInfo> check(
      List<ExtensionInfo> extensions, String expectedVersion, PrintStream errStream) {
    if (expectedVersion == null) {
      return List.of();
    }

    List<ExtensionInfo> mismatched = new ArrayList<>();
    for (ExtensionInfo ext : extensions) {
      if (!expectedVersion.equals(ext.version())) {
        errStream.println(
            "WARNING: Quarkus artifact "
                + ext.groupId()
                + ":"
                + ext.artifactId()
                + ":"
                + ext.version()
                + " does not match toolchain version "
                + expectedVersion);
        mismatched.add(ext);
      }
    }
    return Collections.unmodifiableList(mismatched);
  }
}
