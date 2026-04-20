package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.augmentation.AugmentationExecutor;
import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.extension.VersionChecker;
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
      return; // unreachable, but keeps the compiler happy
    }

    // 2. Scan application classpath for Quarkus extensions and check versions
    try {
      List<ExtensionInfo> extensions = ExtensionScanner.scan(config.applicationClasspath());
      VersionChecker.check(extensions, config.expectedQuarkusVersion());
    } catch (IOException e) {
      System.err.println("Error scanning extensions: " + e.getMessage());
      System.exit(1);
      return;
    }

    // 3. Execute augmentation
    try {
      AugmentationExecutor.execute(config);
    } catch (AugmentationException e) {
      e.printStackTrace(System.err);
      System.exit(1);
      return;
    }

    // 4. Success
    System.exit(0);
  }
}
