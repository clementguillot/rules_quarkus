package com.clementguillot.quarkifier;

/** Test fixture for parsing augmentation options into immutable configuration. */
public final class TestQuarkifierConfig {

  private TestQuarkifierConfig() {}

  public static QuarkifierConfig parse(String... args) {
    var commandLine = QuarkifierCommand.createCommandLine();
    String[] fullArgs = new String[args.length + 1];
    fullArgs[0] = "augmentation";
    System.arraycopy(args, 0, fullArgs, 1, args.length);
    commandLine.parseArgs(fullArgs);
    var augmentation = commandLine.getSubcommands().get("augmentation").getCommand();
    return ((AugmentationCommand) augmentation).toConfig();
  }
}
