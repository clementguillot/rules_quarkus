package com.clementguillot.quarkifier;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Top-level picocli command that dispatches to subcommands.
 *
 * <p>Running without a subcommand prints usage help. Available subcommands:
 *
 * <ul>
 *   <li>{@code augmentation} — run Quarkus build-time augmentation
 *   <li>{@code enrich-extension} — enrich a quarkus-extension.yaml with build metadata
 * </ul>
 */
@Command(
    name = "quarkifier",
    mixinStandardHelpOptions = true,
    versionProvider = QuarkifierVersionProvider.class,
    description = "Quarkus build tooling for Bazel.",
    subcommands = {AugmentationCommand.class, EnrichExtensionCommand.class})
public final class QuarkifierCommand implements Runnable {

  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

  /** Creates a {@link CommandLine} configured for quarkifier conventions. */
  public static CommandLine createCommandLine() {
    var commandLine = new CommandLine(new QuarkifierCommand());
    commandLine.setOverwrittenOptionsAllowed(true);
    return commandLine;
  }

  /** Prints usage when invoked without a subcommand. */
  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }
}
