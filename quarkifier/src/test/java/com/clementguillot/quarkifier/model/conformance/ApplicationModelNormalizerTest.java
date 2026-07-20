package com.clementguillot.quarkifier.model.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationModelNormalizerTest {

  private final ApplicationModelNormalizer normalizer =
      new ApplicationModelNormalizer(
          Map.of(
              "$WORKSPACE", Path.of("/fixture/workspace"),
              "$EXECROOT", Path.of("/fixture/execroot")));

  @Test
  void normalizesApplicationIdentityPathsOrderingAndTransientVisitedFlag() {
    Object left =
        normalizer.normalizeValue(model("com.example:maven-app::jar:1", "/fixture/workspace"));
    Object right =
        normalizer.normalizeValue(
            model("bazel.workspace:bazel-app::jar:1", "/fixture/workspace")
                .replace("/fixture/workspace/app", "app"));

    assertEquals(left, right);
    assertTrue(
        normalizer
            .normalize(model("com.example:app::jar:1", "/fixture/workspace"))
            .contains("\"RUNTIME_CP\""));
  }

  @Test
  void normalizesMavenGradleAndBazelExternalArtifactPaths() {
    Object maven = normalizer.normalizeValue(model("com.example:app::jar:1", "/fixture/workspace"));
    Object gradle =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace(
                    "/home/user/.m2/repository/g/a/1/a-1.jar",
                    "/home/user/.gradle/caches/modules-2/files-2.1/g/a/1/hash/a-1.jar"));
    Object bazel =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace(
                    "/home/user/.m2/repository/g/a/1/a-1.jar",
                    "external/rules_jvm_external~~maven~maven/g/a/1/processed_a-1.jar"));

    assertEquals(maven, gradle);
    assertEquals(maven, bazel);
  }

  @Test
  void normalizesConfigurationSpecificBazelBinPath() {
    String normalized =
        normalizer.normalize(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace(
                    "/fixture/workspace/app/target/app.jar",
                    "bazel-out/darwin_arm64-fastbuild/bin/app/lib.jar"));

    assertTrue(normalized.contains("$BAZEL_BIN/app/lib.jar"));
    assertFalse(normalized.contains("darwin_arm64-fastbuild"));
  }

  @Test
  void normalizesAbsoluteBazelSandboxExecrootPaths() {
    String normalized =
        normalizer.normalize(
            model("com.example:app::jar:1", "/private/tmp/out/execroot/_main")
                .replace(
                    "/private/tmp/out/execroot/_main/app/target/app.jar",
                    "/private/tmp/out/execroot/_main/bazel-out/darwin_arm64-fastbuild/bin/app/lib.jar"));

    assertTrue(normalized.contains("$BAZEL_BIN/app/lib.jar"));
    assertTrue(normalized.contains("$WORKSPACE/app"));
    assertFalse(normalized.contains("/private/tmp/out/execroot/_main"));
  }

  @Test
  void mapsMultipleBuildSystemRootsToTheSameToken() {
    var aliases =
        new ApplicationModelNormalizer(
            List.of(
                new ApplicationModelNormalizer.PathMapping("$WORKSPACE", Path.of("/fixture/maven")),
                new ApplicationModelNormalizer.PathMapping(
                    "$WORKSPACE", Path.of("/fixture/bazel"))));

    assertEquals(
        aliases.normalizeValue(model("com.example:app::jar:1", "/fixture/maven")),
        aliases.normalizeValue(model("com.example:app::jar:1", "/fixture/bazel")));
  }

  @Test
  void resolvesWorkspaceCoordinateReferencesAgainstCuratedArtifacts() {
    Object resolved =
        normalizer.normalizeValue(model("com.example:app::jar:1", "/fixture/workspace"));
    Object unresolved =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace("g:a::jar:1", "g:a::jar:null")
                .replaceFirst("g:a::jar:null", "g:a::jar:1")
                .replace(
                    "com.example:app::jar:1\",\"module\":{\"id\":\"com.example:app::jar:1",
                    "com.example:app::jar:1\",\"module\":{\"id\":\"com.example:app:1"));

    assertEquals(resolved, unresolved);
  }

  @Test
  void reportsAReadableNamedFlagMutation() {
    Object expected =
        normalizer.normalizeValue(model("com.example:app::jar:1", "/fixture/workspace"));
    Object mutated =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace("\"flags\":14", "\"flags\":12"));

    String diff = ApplicationModelDiff.render(ApplicationModelDiff.compare(expected, mutated));

    assertTrue(diff.contains("semantic ApplicationModel difference"));
    assertTrue(diff.contains("/flags"));
    assertTrue(diff.contains("DIRECT"));
  }

  @Test
  void reportsAReadableMissingEdgeMutation() {
    Object expected =
        normalizer.normalizeValue(model("com.example:app::jar:1", "/fixture/workspace"));
    Object mutated =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace(
                    "\"direct-deps\":[{\"maven-artifact\":\"g:a::jar:1\",\"scope\":\"compile\",\"flags\":14}],",
                    "\"direct-deps\":[],"));

    String diff = ApplicationModelDiff.render(ApplicationModelDiff.compare(expected, mutated));

    assertTrue(diff.contains("/directDependencies"));
    assertTrue(diff.contains("g:a::jar:1"));
  }

  @Test
  void gradleProfileProjectsOnlyAnActuallyFlatRightHandGraph() {
    Object graph = normalizer.normalizeValue(model("com.example:app::jar:1", "/fixture/workspace"));
    Object flat =
        normalizer.normalizeValue(
            model("com.example:app::jar:1", "/fixture/workspace")
                .replace("\"dependencies\":[\"g:a::jar:1\"]", "\"dependencies\":[]")
                .replace(
                    "\"direct-deps\":[{\"maven-artifact\":\"g:a::jar:1\",\"scope\":\"compile\",\"flags\":14}]",
                    "\"direct-deps\":[]"));

    var projected =
        ApplicationModelComparisonProfile.apply(
            ApplicationModelComparisonProfile.QUARKUS_3_33_GRADLE, graph, flat);

    assertTrue(ApplicationModelDiff.compare(projected.left(), projected.right()).isEmpty());
    assertThrows(
        BazelApplicationModelException.class,
        () ->
            ApplicationModelComparisonProfile.apply(
                ApplicationModelComparisonProfile.QUARKUS_3_33_GRADLE, flat, graph));
  }

  @Test
  void allowlistIsBoundToExactDifferenceValuesAndRejectsStaleEntries() {
    var difference = new ApplicationModelDiff.Difference("/flags", List.of("DIRECT"), List.of());
    String allowlist =
        ApplicationModelDifferenceAllowlist.template(List.of(difference))
            .replace("REVIEW REQUIRED", "Gradle does not expose this flag in 3.33");

    var approved = ApplicationModelDifferenceAllowlist.check(allowlist, List.of(difference));
    var changed =
        ApplicationModelDifferenceAllowlist.check(
            allowlist,
            List.of(
                new ApplicationModelDiff.Difference(
                    "/flags", List.of("DIRECT", "RUNTIME_CP"), List.of())));

    assertEquals(1, approved.approvedCount());
    assertTrue(approved.unapproved().isEmpty());
    assertEquals(1, changed.unapproved().size());
    assertEquals(List.of("/flags"), changed.unusedPaths());
  }

  private static String model(String application, String workspace) {
    return """
        {
          "platform-imports": {"platform-properties":{},"imported-boms":[],"release-info":[]},
          "dependencies": [{
            "maven-artifact":"g:a::jar:1",
            "resolved-paths":["/home/user/.m2/repository/g/a/1/a-1.jar"],
            "scope":"compile",
            "flags":14,
            "dependencies":[],
            "direct-deps":[]
          }],
          "app-artifact": {
            "maven-artifact":"%s",
            "resolved-paths":["%s/app/target/app.jar"],
            "scope":"compile",
            "flags":2052,
            "dependencies":["g:a::jar:1"],
            "direct-deps":[{"maven-artifact":"g:a::jar:1","scope":"compile","flags":14}],
            "module": {
              "id":"%s",
              "module-dir":"%s/app",
              "build-dir":"%s/app/target",
              "build-files":["%s/app/pom.xml"],
              "artifact-sources":[{
                "classifier":"",
                "sources":[{"dir":"%s/app/src/main/java","dest-dir":"%s/app/target/classes"}],
                "resources":[]
              }]
            }
          },
          "capabilities":[],
          "local-projects":["%s"],
          "extension-dev-config":[]
        }
        """
        .formatted(
            application,
            workspace,
            application.substring(0, application.lastIndexOf(':')),
            workspace,
            workspace,
            workspace,
            workspace,
            workspace,
            application.substring(0, application.lastIndexOf(':')));
  }
}
