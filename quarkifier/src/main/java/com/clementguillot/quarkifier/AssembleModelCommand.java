package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelAssembler;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelException;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelWriter;
import com.clementguillot.quarkifier.model.transport.BazelModelInputReader;
import com.clementguillot.quarkifier.model.transport.BazelModelInputs.TargetFragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Hermetic command that assembles the Bazel-owned application-model transport. */
@Command(
    name = "assemble-model",
    description = "Assemble and validate the explicit Bazel application model.",
    mixinStandardHelpOptions = true)
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.SystemPrintln", "PMD.TooManyFields"})
public final class AssembleModelCommand implements Callable<Integer> {

  @Option(names = "--roots", required = true, description = "Application roots JSON file.")
  private Path roots;

  @Option(
      names = "--target-fragments-file",
      required = true,
      description = "Newline-delimited target fragment paths.")
  private Path targetFragmentsFile;

  @Option(
      names = "--runtime-catalog",
      required = true,
      description = "Runtime resolver catalog JSON file.")
  private Path runtimeCatalog;

  @Option(
      names = "--conditional-catalog",
      required = true,
      description = "Conditional candidate resolver catalog JSON file.")
  private Path conditionalCatalog;

  @Option(
      names = "--deployment-catalog",
      required = true,
      description = "Deployment resolver catalog JSON file.")
  private Path deploymentCatalog;

  @Option(
      names = "--platform-catalog",
      required = true,
      description = "Platform BOM and properties catalog JSON file.")
  private Path platformCatalog;

  @Option(
      names = "--deployment-paths-file",
      required = true,
      description = "Tab-delimited deployment repoPath/action-path manifest.")
  private Path deploymentPathsFile;

  @Option(
      names = "--conditional-paths-file",
      required = true,
      description = "Tab-delimited conditional repoPath/action-path manifest.")
  private Path conditionalPathsFile;

  @Option(
      names = "--platform-property-paths-file",
      required = true,
      description = "Tab-delimited platform property repoPath/action-path manifest.")
  private Path platformPropertyPathsFile;

  @Option(
      names = "--runtime-classpath-paths-file",
      required = true,
      description = "Newline-delimited resolved runtime classpath paths.")
  private Path runtimeClasspathPathsFile;

  @Option(
      names = "--deployment-classpath-paths-file",
      required = true,
      description = "Newline-delimited resolved deployment classpath paths.")
  private Path deploymentClasspathPathsFile;

  @Option(
      names = "--model-private-targets-file",
      required = true,
      description =
          "Newline-delimited Bazel targets used to compile/launch tests but omitted from the"
              + " model.")
  private Path modelPrivateTargetsFile;

  @Option(
      names = "--local-deployments-file",
      required = true,
      description = "Tab-delimited local deployment coordinate/target-id manifest.")
  private Path localDeploymentsFile;

  @Option(
      names = "--local-runtime-aliases-file",
      required = true,
      description = "Tab-delimited raw-runtime/packaged-runtime target aliases.")
  private Path localRuntimeAliasesFile;

  @Option(names = "--quarkus-version", required = true)
  private String quarkusVersion;

  @Option(names = "--mode", required = true)
  private String mode;

  @Option(names = "--application-name")
  private String applicationName;

  @Option(names = "--application-version")
  private String applicationVersion;

  @Option(names = "--producer-version", required = true)
  private String producerVersion;

  @Option(
      names = "--strict",
      arity = "1",
      defaultValue = "true",
      description = "Fail closed on every missing or ambiguous model fact (required for v1).")
  private boolean strict;

  @Option(
      names = "--explain",
      description = "Print deterministic provenance and classpath reasoning for one node ID.")
  private String explain;

  @Option(names = "--output", required = true)
  private Path output;

  @Override
  public Integer call() throws IOException {
    if (!strict) {
      throw new BazelApplicationModelException(
          "quarkus-bazel-model-v1 is fail-closed; --no-strict is not supported");
    }
    BazelApplicationModel.Mode parsedMode;
    try {
      parsedMode = BazelApplicationModel.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new BazelApplicationModelException("Unsupported model mode '" + mode + "'", exception);
    }
    List<TargetFragment> fragments = new ArrayList<>();
    for (String line : readManifestLines(targetFragmentsFile)) {
      fragments.add(BazelModelInputReader.readTargetFragment(Path.of(line)));
    }
    Map<String, String> deploymentPaths = readDeploymentPaths(deploymentPathsFile);
    BazelApplicationModel model =
        BazelApplicationModelAssembler.assemble(
            new BazelApplicationModelAssembler.Inputs(
                BazelModelInputReader.readRoots(roots),
                fragments,
                BazelModelInputReader.readRuntimeCatalog(runtimeCatalog),
                BazelModelInputReader.readConditionalCatalog(conditionalCatalog),
                BazelModelInputReader.readDeploymentCatalog(deploymentCatalog),
                BazelModelInputReader.readPlatformCatalog(platformCatalog),
                readMappings(localDeploymentsFile, "local deployment coordinate"),
                readMappings(localRuntimeAliasesFile, "raw local runtime target"),
                readMappings(conditionalPathsFile, "conditional repoPath"),
                deploymentPaths,
                readMappings(platformPropertyPathsFile, "platform properties repoPath"),
                Set.copyOf(readManifestLines(runtimeClasspathPathsFile)),
                Set.copyOf(readManifestLines(deploymentClasspathPathsFile)),
                Set.copyOf(readManifestLines(modelPrivateTargetsFile)),
                quarkusVersion,
                parsedMode,
                applicationName,
                applicationVersion,
                producerVersion));
    BazelApplicationModelWriter.write(model, output);
    if (explain != null) {
      printExplanation(model, explain);
    }
    return 0;
  }

  private static void printExplanation(BazelApplicationModel model, String nodeId) {
    var node =
        model.nodes().stream()
            .filter(candidate -> nodeId.equals(candidate.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new BazelApplicationModelException(
                        "Cannot explain unknown node '" + nodeId + "'"));
    var parents = new ArrayList<String>();
    for (var parent : model.nodes()) {
      for (var edge : parent.dependencies()) {
        if (nodeId.equals(edge.targetId())) {
          parents.add(
              parent.id()
                  + " --"
                  + edge.relation().name().toLowerCase(Locale.ROOT)
                  + ","
                  + edge.scope().name().toLowerCase(Locale.ROOT)
                  + (edge.optional() ? ",optional" : "")
                  + "--> "
                  + nodeId);
        }
      }
    }
    parents.sort(String::compareTo);
    System.out.println("node: " + node.id());
    System.out.println("coordinates: " + canonical(node.coordinates()));
    System.out.println("bazel-label: " + node.bazelLabel());
    System.out.println("paths: " + node.paths());
    System.out.println("parents: " + parents);
    System.out.println("classpath: " + node.classpath());
    System.out.println("provenance: " + model.diagnostics().provenance());
  }

  private static String canonical(BazelApplicationModel.ArtifactCoordinates coordinates) {
    return coordinates.groupId()
        + ":"
        + coordinates.artifactId()
        + ":"
        + coordinates.classifier()
        + ":"
        + coordinates.type()
        + ":"
        + coordinates.version();
  }

  private static Map<String, String> readDeploymentPaths(Path path) throws IOException {
    return readMappings(path, "deployment repoPath");
  }

  private static Map<String, String> readMappings(Path path, String keyDescription)
      throws IOException {
    var result = new LinkedHashMap<String, String>();
    for (String line : readManifestLines(path)) {
      int separator = line.indexOf('\t');
      if (separator <= 0
          || separator == line.length() - 1
          || line.indexOf('\t', separator + 1) >= 0) {
        throw new BazelApplicationModelException(
            "Invalid deployment path manifest entry in " + path + ": " + line);
      }
      String repoPath = line.substring(0, separator);
      String actionPath = line.substring(separator + 1);
      if (result.putIfAbsent(repoPath, actionPath) != null) {
        throw new BazelApplicationModelException(
            "Duplicate " + keyDescription + " '" + repoPath + "' in " + path);
      }
    }
    return result;
  }

  private static List<String> readManifestLines(Path path) throws IOException {
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    var unique = new LinkedHashMap<String, Boolean>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.isBlank() || !line.equals(line.trim())) {
        throw new BazelApplicationModelException(
            "Invalid blank or padded manifest entry at " + path + ":" + (index + 1));
      }
      if (line.indexOf('\u0000') >= 0) {
        throw new BazelApplicationModelException(
            "Invalid NUL byte in manifest entry at " + path + ":" + (index + 1));
      }
      if (unique.putIfAbsent(line, Boolean.TRUE) != null) {
        throw new BazelApplicationModelException(
            "Duplicate manifest entry at " + path + ":" + (index + 1) + ": " + line);
      }
    }
    return List.copyOf(unique.keySet());
  }
}
