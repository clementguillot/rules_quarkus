package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.augmentation.AugmentationExecutor;
import com.clementguillot.quarkifier.extension.DeploymentArtifactResolver;
import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.extension.MissingDeploymentArtifactException;
import com.clementguillot.quarkifier.maven.VersionChecker;
import java.io.IOException;
import java.util.List;

/**
 * Main entry point for Quarkifier CLI.
 *
 * <p>Orchestrates the full augmentation pipeline:
 *
 * <ol>
 *   <li>Parse CLI arguments
 *   <li>Scan application classpath for Quarkus extensions
 *   <li>Resolve deployment artifacts for each extension
 *   <li>Check extension versions against the expected toolchain version
 *   <li>Execute Quarkus augmentation to produce Fast_Jar output
 * </ol>
 */
public final class QuarkifierLauncher {

  private QuarkifierLauncher() {}

  public static void main(String[] args) {
    // 1. Parse CLI arguments
    QuarkifierConfig config;
    try {
      config = QuarkifierConfig.parse(args);
    } catch (QuarkifierConfig.InvalidArgumentsException e) {
      System.err.println(QuarkifierConfig.usage());
      System.err.println("Error: " + e.getMessage());
      System.exit(2);
      return;
    }

    // 2. Scan application classpath for Quarkus extensions
    List<ExtensionInfo> extensions;
    try {
      extensions = ExtensionScanner.scan(config.applicationClasspath());
    } catch (IOException e) {
      System.err.println("Error scanning extensions: " + e.getMessage());
      System.exit(1);
      return;
    }

    // 3. Resolve deployment artifacts (warn on missing, don't fail)
    try {
      DeploymentArtifactResolver.resolveAll(extensions, config.deploymentClasspath());
    } catch (MissingDeploymentArtifactException e) {
      System.err.println(
          "WARNING: "
              + e.getMessage()
              + ". The extension's build-time processing may be incomplete.");
    }

    // 4. Check version mismatches (warnings only, don't exit)
    VersionChecker.check(extensions, config.expectedQuarkusVersion());

    // 5. Execute augmentation (pass extensions to avoid duplicate scanning)
    try {
      AugmentationExecutor.execute(config, extensions);
    } catch (AugmentationException e) {
      e.printStackTrace(System.err);
      System.exit(1);
      return;
    }

    // 6. Success
    System.exit(0);
  }
}
