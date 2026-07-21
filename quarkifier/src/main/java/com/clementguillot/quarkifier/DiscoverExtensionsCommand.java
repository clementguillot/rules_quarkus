package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.extension.ExtensionScanner.ArtifactInput;
import com.clementguillot.quarkifier.model.transport.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Finds exact descriptor metadata declared by runtime extension jars. */
@Command(
    name = "discover-extensions",
    description = "Read exact metadata from runtime extension descriptors.",
    mixinStandardHelpOptions = true)
@SuppressWarnings({"PMD.ConsecutiveAppendsShouldReuse", "PMD.ConsecutiveLiteralAppends"})
public final class DiscoverExtensionsCommand implements Callable<Integer> {

  @Option(
      names = "--classpath-file",
      description = "UTF-8 file containing one runtime jar path per line.")
  private Path classpathFile;

  @Option(
      names = "--artifacts-file",
      description = "UTF-8 tab-delimited runtime coordinate/jar path manifest.")
  private Path artifactsFile;

  @Option(
      names = "--output",
      required = true,
      description = "Output file containing one exact deployment coordinate per line.")
  private Path output;

  @Option(
      names = "--descriptor-output",
      description = "Optional deterministic JSON catalog containing all descriptor metadata.")
  private Path descriptorOutput;

  @Override
  public Integer call() throws IOException {
    if ((classpathFile == null) == (artifactsFile == null)) {
      throw new IllegalArgumentException(
          "Exactly one of --classpath-file or --artifacts-file must be supplied");
    }
    List<ExtensionInfo> extensions;
    if (artifactsFile != null) {
      extensions = ExtensionScanner.scanArtifacts(readArtifacts(artifactsFile));
    } else {
      List<Path> runtimeJars =
          Files.readAllLines(classpathFile, StandardCharsets.UTF_8).stream()
              .map(String::trim)
              .filter(line -> !line.isEmpty())
              .map(Path::of)
              .toList();
      extensions = ExtensionScanner.scan(runtimeJars);
    }
    List<String> deploymentArtifacts =
        extensions.stream()
            .map(extension -> extension.deploymentArtifact())
            .distinct()
            .sorted()
            .toList();
    Path parent = output.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(output, deploymentArtifacts, StandardCharsets.UTF_8);
    if (descriptorOutput != null) {
      writeDescriptorCatalog(extensions, descriptorOutput);
    }
    return 0;
  }

  private static List<ArtifactInput> readArtifacts(Path path) throws IOException {
    var result = new java.util.ArrayList<ArtifactInput>();
    for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      int separator = line.indexOf('\t');
      if (separator <= 0
          || separator == line.length() - 1
          || line.indexOf('\t', separator + 1) >= 0) {
        throw new IllegalArgumentException(
            "Invalid artifact manifest entry in " + path + ": " + line);
      }
      result.add(
          new ArtifactInput(
              line.substring(0, separator).trim(), Path.of(line.substring(separator + 1).trim())));
    }
    return List.copyOf(result);
  }

  private static void writeDescriptorCatalog(List<ExtensionInfo> extensions, Path path)
      throws IOException {
    Map<String, ExtensionInfo> byRuntime = new TreeMap<>();
    for (ExtensionInfo extension : extensions) {
      ExtensionInfo previous = byRuntime.putIfAbsent(extension.runtimeArtifact(), extension);
      if (previous != null && !sameDescriptor(previous, extension)) {
        throw new IllegalArgumentException(
            "Runtime artifact "
                + extension.runtimeArtifact()
                + " resolves to conflicting Quarkus extension descriptors");
      }
    }
    StringBuilder json =
        new StringBuilder(4096)
            .append(
                "{\n  \"schemaVersion\": \"quarkus-extension-descriptors-v1\",\n"
                    + "  \"extensions\": [");
    int index = 0;
    for (ExtensionInfo extension : byRuntime.values()) {
      json.append(index++ == 0 ? "\n" : ",\n");
      json.append("    {\n");
      member(json, "runtimeArtifact", extension.runtimeArtifact(), true);
      member(json, "deploymentArtifact", extension.deploymentArtifact(), true);
      array(json, "conditionalDependencies", extension.conditionalDependencies(), true);
      array(json, "conditionalDevDependencies", extension.conditionalDevDependencies(), true);
      array(json, "dependencyConditions", extension.dependencyConditions(), false);
      json.append("    }");
    }
    json.append(index == 0 ? "]\n}\n" : "\n  ]\n}\n");
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(path, json, StandardCharsets.UTF_8);
  }

  private static boolean sameDescriptor(ExtensionInfo left, ExtensionInfo right) {
    return left.deploymentArtifact().equals(right.deploymentArtifact())
        && left.conditionalDependencies().equals(right.conditionalDependencies())
        && left.conditionalDevDependencies().equals(right.conditionalDevDependencies())
        && left.dependencyConditions().equals(right.dependencyConditions());
  }

  private static void member(StringBuilder json, String name, String value, boolean comma) {
    json.append("      \"")
        .append(name)
        .append("\": \"")
        .append(StrictJson.escape(value))
        .append('"');
    json.append(comma ? ",\n" : "\n");
  }

  private static void array(StringBuilder json, String name, List<String> values, boolean comma) {
    json.append("      \"").append(name).append("\": [");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        json.append(", ");
      }
      json.append('"').append(StrictJson.escape(values.get(index))).append('"');
    }
    json.append(']').append(comma ? ",\n" : "\n");
  }
}
