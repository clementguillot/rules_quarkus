package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.model.conformance.ApplicationModelComparisonProfile;
import com.clementguillot.quarkifier.model.conformance.ApplicationModelDiff;
import com.clementguillot.quarkifier.model.conformance.ApplicationModelDifferenceAllowlist;
import com.clementguillot.quarkifier.model.conformance.ApplicationModelNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Normalizes and compares two Quarkus JSON ApplicationModel snapshots. */
@Command(
    name = "compare-models",
    description = "Compare two post-curation Quarkus ApplicationModel JSON snapshots.",
    mixinStandardHelpOptions = true)
@SuppressWarnings({"PMD.ImmutableField", "PMD.SystemPrintln"})
public final class CompareModelsCommand implements Callable<Integer> {

  @Option(names = "--left", required = true, description = "Left ApplicationModel JSON snapshot.")
  private Path left;

  @Option(names = "--right", required = true, description = "Right ApplicationModel JSON snapshot.")
  private Path right;

  @Option(
      names = "--path-root",
      description =
          "Path normalization root as TOKEN=PATH; repeat tokens to map Maven/Gradle/Bazel roots to"
              + " the same value.")
  private List<String> pathRoots = new ArrayList<>();

  @Option(names = "--left-normalized-output")
  private Path leftNormalizedOutput;

  @Option(names = "--right-normalized-output")
  private Path rightNormalizedOutput;

  @Option(names = "--diff-output")
  private Path diffOutput;

  @Option(names = "--allowlist", description = "Reviewed exact-value difference allowlist.")
  private Path allowlist;

  @Option(
      names = "--allowlist-template-output",
      description =
          "Write exact hashes for every current difference; entries still require review.")
  private Path allowlistTemplateOutput;

  @Option(
      names = "--comparison-profile",
      defaultValue = ApplicationModelComparisonProfile.STRICT,
      description = "Comparison profile: strict, quarkus-3.33-gradle, or bazel-resolved-graph.")
  private String comparisonProfile;

  @Override
  public Integer call() throws IOException {
    Object leftModel =
        new ApplicationModelNormalizer(parsePathRoots())
            .normalizeValue(Files.readString(left, StandardCharsets.UTF_8));
    Object rightModel =
        new ApplicationModelNormalizer(parsePathRoots())
            .normalizeValue(Files.readString(right, StandardCharsets.UTF_8));
    String leftJson = ApplicationModelNormalizer.writeNormalized(leftModel);
    String rightJson = ApplicationModelNormalizer.writeNormalized(rightModel);
    writeIfRequested(leftNormalizedOutput, leftJson);
    writeIfRequested(rightNormalizedOutput, rightJson);

    var models = ApplicationModelComparisonProfile.apply(comparisonProfile, leftModel, rightModel);
    var differences = ApplicationModelDiff.compare(models.left(), models.right());
    writeIfRequested(
        allowlistTemplateOutput, ApplicationModelDifferenceAllowlist.template(differences));
    int approved = 0;
    List<String> unused = List.of();
    if (allowlist != null) {
      var check =
          ApplicationModelDifferenceAllowlist.check(
              Files.readString(allowlist, StandardCharsets.UTF_8), differences);
      differences = check.unapproved();
      approved = check.approvedCount();
      unused = check.unusedPaths();
    }
    String rendered = render(differences, approved, unused);
    writeIfRequested(diffOutput, rendered);
    System.out.print(rendered);
    return differences.isEmpty() && unused.isEmpty() ? 0 : 1;
  }

  private List<ApplicationModelNormalizer.PathMapping> parsePathRoots() {
    var result = new ArrayList<ApplicationModelNormalizer.PathMapping>();
    for (String specification : pathRoots) {
      int separator = specification.indexOf('=');
      if (separator <= 0 || separator == specification.length() - 1) {
        throw new IllegalArgumentException(
            "Invalid --path-root '" + specification + "': expected TOKEN=PATH");
      }
      result.add(
          new ApplicationModelNormalizer.PathMapping(
              specification.substring(0, separator),
              Path.of(specification.substring(separator + 1))));
    }
    return result;
  }

  private static String render(
      List<ApplicationModelDiff.Difference> differences, int approved, List<String> unused) {
    var rendered = new StringBuilder(ApplicationModelDiff.render(differences));
    if (approved > 0) {
      rendered.append(approved).append(" exact reviewed difference(s) allowlisted\n");
    }
    if (!unused.isEmpty()) {
      rendered.append(unused.size()).append(" stale allowlist entry/entries:\n");
      unused.forEach(path -> rendered.append("- ").append(path).append('\n'));
    }
    return rendered.toString();
  }

  private static void writeIfRequested(Path output, String content) throws IOException {
    if (output == null) {
      return;
    }
    Path parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(output, content, StandardCharsets.UTF_8);
  }
}
