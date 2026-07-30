package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.codegen.CodeGenerationExecutor;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.jboss.logging.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Runs Quarkus {@code CodeGenProvider} implementations before Java compilation. */
@Command(
    name = "codegen",
    description = "Run extension-provided Quarkus code generators.",
    mixinStandardHelpOptions = true)
public final class CodeGenerationCommand implements Callable<Integer> {

  @Spec private CommandSpec commandSpec;

  @Option(
      names = "--application-model",
      required = true,
      description = "Validated quarkus-bazel-model-v1 JSON.")
  private Path applicationModel;

  @Option(
      names = "--source-parent",
      required = true,
      description = "Source parent directory; may be repeated.")
  private List<Path> sourceParents;

  @Option(names = "--generated-sources-dir", required = true)
  private Path generatedSourcesDir;

  @Option(names = "--aux-output-dir", required = true)
  private Path auxiliaryOutputDir;

  @Option(names = "--work-output-dir", required = true)
  private Path workOutputDir;

  @Option(names = "--source-jar", required = true)
  private Path sourceJar;

  @Option(
      names = "--launch-mode",
      defaultValue = "NORMAL",
      description = "Quarkus launch mode: NORMAL, TEST, or DEVELOPMENT.")
  private String launchMode;

  @Option(names = "--test", defaultValue = "false")
  private boolean test;

  @Option(names = "--properties-file")
  private Path propertiesFile;

  @Override
  @SuppressWarnings("PMD.CloseResource")
  public Integer call() {
    try {
      CodeGenerationExecutor.execute(
          applicationModel,
          sourceParents,
          generatedSourcesDir,
          auxiliaryOutputDir,
          workOutputDir,
          sourceJar,
          launchMode,
          test,
          propertiesFile);
      return 0;
    } catch (CodeGenerationException e) {
      Logger.getLogger(CodeGenerationCommand.class).error("Code generation failed", e);
      PrintWriter error = commandSpec.commandLine().getErr();
      error.println("Code generation failed: " + e.getMessage());
      e.printStackTrace(error);
      error.flush();
      return 1;
    }
  }
}
