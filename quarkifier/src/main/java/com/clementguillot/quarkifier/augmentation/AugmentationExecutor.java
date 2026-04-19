package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.devmode.DevModeLauncher;
import com.clementguillot.quarkifier.extension.ExtensionInfo;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs Quarkus augmentation and produces a runnable Fast_Jar.
 *
 * <p>Orchestrates: {@link ApplicationModelFactory} → Quarkus bootstrap → {@link FastJarAssembler}.
 */
public final class AugmentationExecutor {

  private AugmentationExecutor() {}

  /**
   * Executes the full augmentation pipeline.
   *
   * @param config CLI configuration
   * @param extensions pre-scanned extension list (avoids duplicate scanning)
   */
  public static void execute(QuarkifierConfig config, List<ExtensionInfo> extensions)
      throws AugmentationException {
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
          ApplicationModelFactory.build(
              appJar, runtimeJars, deployCp, extensions,
              config.appName(), config.appVersion());

      if (config.mode() == AugmentationMode.DEV) {
        DevModeLauncher.launch(config, appModel);
        return;
      }

      var buildProps = new java.util.Properties();
      buildProps.setProperty(
          "platform.quarkus.native.builder-image",
          "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
      buildProps.setProperty("quarkus.package.jar.type", "fast-jar");

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
        ClassLoader augmentCl = curatedApp.getOrCreateAugmentClassLoader();
        Class<?> augmentClass =
            augmentCl.loadClass("io.quarkus.runner.bootstrap.AugmentActionImpl");

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

      FastJarAssembler.assemble(outputDir, runtimeJars, config.resources(), appModel);

    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Quarkus augmentation failed: " + e.getMessage(), e);
    }
  }

  private static java.lang.reflect.Constructor<?> findAugmentConstructor(Class<?> augmentClass)
      throws AugmentationException {
    for (java.lang.reflect.Constructor<?> c : augmentClass.getConstructors()) {
      Class<?>[] paramTypes = c.getParameterTypes();
      if (paramTypes.length == 1
          && paramTypes[0].getName().equals("io.quarkus.bootstrap.app.CuratedApplication")) {
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
