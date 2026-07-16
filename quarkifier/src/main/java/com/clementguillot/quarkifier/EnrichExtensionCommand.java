package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.extension.ExtensionYamlEnricher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Subcommand that enriches a {@code quarkus-extension.yaml} with build metadata.
 *
 * <p>Delegates to {@link ExtensionYamlEnricher} for the actual processing.
 */
@Command(
    name = "enrich-extension",
    description = "Enrich a quarkus-extension.yaml with build metadata.",
    mixinStandardHelpOptions = true)
public final class EnrichExtensionCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Runtime jar containing the base extension yaml.")
  private Path runtimeJar;

  @Parameters(index = "1", description = "Output path for the enriched yaml file.")
  private Path output;

  @Parameters(index = "2", description = "Quarkus core version (e.g. 3.33.2).")
  private String quarkusVersion;

  @Parameters(index = "3", description = "File listing the compile classpath (one jar per line).")
  private Path classpathFile;

  @Parameters(index = "4", description = "Extension display name.")
  private String extensionName;

  @Override
  public Integer call() throws IOException {
    ExtensionYamlEnricher.enrich(runtimeJar, output, quarkusVersion, classpathFile, extensionName);
    return 0;
  }
}
