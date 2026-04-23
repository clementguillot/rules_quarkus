package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.dev.DevModeLauncher;
import com.clementguillot.quarkifier.model.QuarkusAppModelBuilder;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Orchestrates Quarkus augmentation: builds the ApplicationModel, invokes the Quarkus build API,
 * and delegates post-processing to {@link FastJarAssembler}.
 *
 * <p>For DEV mode, delegates entirely to {@link DevModeLauncher}.
 */
public final class AugmentationExecutor {

  private AugmentationExecutor() {}

  /**
   * Executes the full augmentation pipeline for the given configuration.
   *
   * @param config the parsed CLI configuration
   * @throws AugmentationException if augmentation fails
   */
  public static void execute(QuarkifierConfig config) throws AugmentationException {
    try {
      Path outputDir = config.outputDir();
      Files.createDirectories(outputDir);

      List<Path> appCp = config.applicationClasspath();
      List<Path> deployCp = config.deploymentClasspath();

      if (appCp.isEmpty()) {
        throw new AugmentationException(
            "Application classpath is empty; at least one jar is required.");
      }

      Path appJar = appCp.get(0);
      List<Path> runtimeJars = appCp.subList(1, appCp.size());
      ApplicationModel appModel =
          QuarkusAppModelBuilder.build(
              appJar, runtimeJars, deployCp, config.appName(), config.appVersion());

      // DEV mode: delegate to DevModeLauncher which starts IsolatedDevModeMain
      // with full Dev UI and hot-reload support.
      if (config.mode() == AugmentationMode.DEV) {
        DevModeLauncher.launch(config, appModel);
        return;
      }

      runAugmentation(config, appJar, appModel, outputDir);

      // Post-process: assemble the complete Fast_Jar
      FastJarAssembler.assemble(outputDir, runtimeJars, appModel, config.resources());

    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Quarkus augmentation failed: " + e.getMessage(), e);
    }
  }

  /** Runs the Quarkus bootstrap and augmentation for production/test modes. */
  private static void runAugmentation(
      QuarkifierConfig config, Path appJar, ApplicationModel appModel, Path outputDir)
      throws Exception {

    Properties buildProps = BuildProperties.defaults();

    QuarkusBootstrap bootstrap =
        QuarkusBootstrap.builder()
            .setExistingModel(appModel)
            .setApplicationRoot(appJar)
            .setTargetDirectory(outputDir)
            .setBaseName("quarkus-run")
            .setMode(mapMode(config.mode()))
            .setIsolateDeployment(false)
            .setFlatClassPath(true)
            .setLocalProjectDiscovery(false)
            .setBuildSystemProperties(buildProps)
            .build();

    try (CuratedApplication curatedApp = bootstrap.bootstrap()) {
      // Load AugmentActionImpl from the augment classloader to avoid classloader
      // conflicts. Set the TCCL so ASM's ClassWriter.getCommonSuperClass() can
      // resolve all types during bytecode generation.
      ClassLoader augmentCl = curatedApp.getOrCreateAugmentClassLoader();
      Class<?> augmentClass = augmentCl.loadClass("io.quarkus.runner.bootstrap.AugmentActionImpl");

      java.lang.reflect.Constructor<?> ctor = findAugmentConstructor(augmentClass);

      ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(augmentCl);

        AugmentAction action = (AugmentAction) ctor.newInstance(curatedApp);
        AugmentResult result = action.createProductionApplication();
        if (result == null) {
          throw new AugmentationException(
              "Augmentation produced no result for output directory: " + outputDir);
        }
      } finally {
        Thread.currentThread().setContextClassLoader(originalTccl);
      }
    }
  }

  /**
   * Finds the single-arg AugmentActionImpl constructor. We match by class name string because the
   * constructor's parameter type is loaded by the augment classloader, not our system classloader.
   */
  private static java.lang.reflect.Constructor<?> findAugmentConstructor(Class<?> augmentClass)
      throws AugmentationException {
    for (java.lang.reflect.Constructor<?> c : augmentClass.getConstructors()) {
      Class<?>[] paramTypes = c.getParameterTypes();
      if (paramTypes.length == 1
          && "io.quarkus.bootstrap.app.CuratedApplication".equals(paramTypes[0].getName())) {
        return c;
      }
    }
    throw new AugmentationException(
        "Cannot find AugmentActionImpl(CuratedApplication) constructor");
  }

  private static QuarkusBootstrap.Mode mapMode(AugmentationMode mode) {
    return switch (mode) {
      case NORMAL -> QuarkusBootstrap.Mode.PROD;
      case TEST -> QuarkusBootstrap.Mode.TEST;
      case DEV -> QuarkusBootstrap.Mode.DEV;
    };
  }
}
