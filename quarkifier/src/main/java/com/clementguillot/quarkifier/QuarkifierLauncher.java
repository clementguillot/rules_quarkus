package com.clementguillot.quarkifier;

import com.clementguillot.quarkifier.augmentation.AugmentationExecutor;
import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import com.clementguillot.quarkifier.extension.VersionChecker;
import java.io.IOException;
import java.util.List;
import org.jboss.logging.Logger;

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

  private static final Logger LOGGER = Logger.getLogger(QuarkifierLauncher.class);

  private QuarkifierLauncher() {}

  public static void main(String... args) {
    // 1. Parse CLI arguments
    QuarkifierConfig config;
    try {
      config = QuarkifierConfig.parse(args);
    } catch (QuarkifierConfig.InvalidArgumentsException e) {
      LOGGER.error(QuarkifierConfig.usage());
      LOGGER.errorf("Error: %s", e.getMessage());
      System.exit(2);
      return; // unreachable, but keeps the compiler happy
    }

    // 2. Scan application classpath for Quarkus extensions and check versions
    try {
      List<ExtensionInfo> extensions = ExtensionScanner.scan(config.applicationClasspath());
      VersionChecker.check(extensions, config.expectedQuarkusVersion());
    } catch (IOException e) {
      LOGGER.errorf("Error scanning extensions: %s", e.getMessage());
      System.exit(1);
      return;
    }

    // 3. Execute augmentation
    try {
      AugmentationExecutor.execute(config);
    } catch (AugmentationException e) {
      LOGGER.error("Augmentation failed", e);
      System.exit(1);
      return;
    }

    // 4. Success
    System.exit(0);
  }
}
