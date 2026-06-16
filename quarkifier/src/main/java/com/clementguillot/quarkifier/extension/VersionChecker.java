package com.clementguillot.quarkifier.extension;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Checks Quarkus extension versions against the expected toolchain version.
 *
 * <p>Only {@code io.quarkus}-group extensions are checked: platform artifacts version together with
 * Quarkus itself, and a mismatch means the bytecode generated during augmentation may be
 * incompatible at runtime. Extensions in other groups (e.g. Quarkiverse) version independently of
 * Quarkus, so no meaningful comparison against the toolchain version exists and they are skipped.
 *
 * <p>Mismatches are emitted as warnings to stderr. The build continues (exit 0) — this is a
 * warning-only check.
 */
public final class VersionChecker {

  /** Artifacts in this group must match the Quarkus platform version exactly. */
  private static final String PLATFORM_GROUP_ID = "io.quarkus";

  private VersionChecker() {}

  /**
   * Checks each platform extension's version against the expected Quarkus version. If {@code
   * expectedVersion} is {@code null}, the check is skipped.
   *
   * @param extensions the discovered Quarkus extensions
   * @param expectedVersion the expected Quarkus toolchain version (may be {@code null})
   */
  public static void check(List<ExtensionInfo> extensions, String expectedVersion) {
    check(extensions, expectedVersion, System.err);
  }

  /**
   * Checks platform extension versions, writing diagnostics to the given {@link PrintStream}.
   * Returns the list of mismatched extensions.
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
      if (PLATFORM_GROUP_ID.equals(ext.groupId()) && !expectedVersion.equals(ext.version())) {
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
