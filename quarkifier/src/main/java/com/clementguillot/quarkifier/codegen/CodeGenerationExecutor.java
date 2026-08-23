package com.clementguillot.quarkifier.codegen;

import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.CodeGenerationException;
import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelReader;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.CodeGenerator;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/** Hermetic adapter around Quarkus' build-tool code-generation entry point. */
public final class CodeGenerationExecutor {

  private CodeGenerationExecutor() {}

  public static void execute(
      Path applicationModelPath,
      List<Path> sourceParents,
      Path generatedSourcesDir,
      Path auxiliaryOutputDir,
      Path workOutputDir,
      Path sourceJar,
      String launchMode,
      boolean test,
      Path propertiesFile)
      throws CodeGenerationException {
    Path bootstrapDir = null;
    Path providerWorkDir = null;
    try {
      BazelApplicationModel explicitModel = BazelApplicationModelReader.read(applicationModelPath);
      validateMode(explicitModel, launchMode, test);
      ApplicationModel applicationModel = ExplicitApplicationModelBuilder.build(explicitModel);
      Properties properties = new Properties();
      properties.putAll(BuildProperties.load(propertiesFile));
      properties.putIfAbsent(
          "quarkus.application.name", applicationModel.getAppArtifact().getArtifactId());
      properties.putIfAbsent(
          "quarkus.application.version", applicationModel.getAppArtifact().getVersion());

      Files.createDirectories(generatedSourcesDir);
      Files.createDirectories(auxiliaryOutputDir);
      Files.createDirectories(workOutputDir);
      if (sourceJar.getParent() != null) {
        Files.createDirectories(sourceJar.getParent());
      }

      // Quarkus bootstrap scratch is not reproducible (randomized temp names,
      // timestamps), so it must stay out of the action's declared outputs.
      // The JVM temp dir is the action's own writable scratch under Bazel.
      bootstrapDir = Files.createTempDirectory("quarkus-codegen-bootstrap");
      providerWorkDir = Files.createTempDirectory("quarkus-codegen-work");

      QuarkusBootstrap.Mode bootstrapMode = bootstrapMode(launchMode);
      QuarkusBootstrap bootstrap =
          QuarkusBootstrap.builder()
              .setExistingModel(applicationModel)
              .setApplicationRoot(applicationModel.getAppArtifact().getResolvedPaths())
              .setTargetDirectory(bootstrapDir)
              .setBaseName("quarkus-codegen")
              .setMode(bootstrapMode)
              .setIsolateDeployment(true)
              .setFlatClassPath(true)
              .setLocalProjectDiscovery(false)
              .setBuildSystemProperties(properties)
              .build();

      try (CuratedApplication curatedApplication = bootstrap.bootstrap();
          QuarkusClassLoader deploymentClassLoader =
              curatedApplication.createDeploymentClassLoader()) {
        Path codeGenerationWorkDir = providerWorkDir;
        // Build-tool properties have higher precedence than application.properties in Maven and
        // Gradle. Scope the explicitly declared map before resolving Quarkus' effective config so
        // code generation observes that same ordering for both Quarkus and application-defined
        // names. Ambient system properties are still filtered from the Properties handed to
        // providers by CodeGenerationProperties.effective.
        BuildProperties.withSystemProperties(
            properties,
            () -> {
              Properties effectiveProperties =
                  CodeGenerationProperties.effective(
                      deploymentClassLoader, applicationModel, properties, launchMode);
              invokeCodeGenerator(
                  deploymentClassLoader,
                  PathList.from(sourceParents),
                  generatedSourcesDir,
                  codeGenerationWorkDir,
                  applicationModel,
                  effectiveProperties,
                  launchMode,
                  test);
              return null;
            });
      }

      CodeGenerationOutputs.packageOutputs(generatedSourcesDir, sourceJar, auxiliaryOutputDir);
      CodeGenerationOutputs.copyStableWorkOutputs(providerWorkDir, workOutputDir);
    } catch (CodeGenerationException e) {
      throw e;
    } catch (Exception e) {
      Throwable cause = unwrapInvocation(e);
      String message = cause.getMessage();
      if (message == null || message.isBlank()) {
        message = cause.getClass().getName();
      }
      throw new CodeGenerationException(message, cause);
    } finally {
      deleteRecursively(providerWorkDir);
      deleteRecursively(bootstrapDir);
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      // Scratch cleanup is best-effort: the directory lives under the action's
      // temp dir, which Bazel discards anyway.
      Logger.getLogger(CodeGenerationExecutor.class)
          .debugf(e, "Failed to clean code-generation scratch directory %s", root);
    }
  }

  /**
   * Resolves Quarkus' build-tool code-generation entry point on {@code codeGenerator}.
   *
   * <p>Package-private so a unit test can pin the exact signature against the compiled
   * quarkus-core-deployment of each supported minor: at runtime the class comes from the isolated
   * deployment classloader while the parameter types come from this one, so a signature change or a
   * bootstrap class that stops being parent-first only surfaces as a {@link NoSuchMethodException}
   * once the action already runs.
   *
   * @param codeGenerator the {@code io.quarkus.deployment.CodeGenerator} class to reflect on
   * @return the resolved {@code initAndRun} method
   * @throws NoSuchMethodException if the expected signature is absent
   */
  static Method codeGeneratorEntryPoint(Class<?> codeGenerator) throws NoSuchMethodException {
    return codeGenerator.getMethod(
        "initAndRun",
        QuarkusClassLoader.class,
        PathCollection.class,
        Path.class,
        Path.class,
        Consumer.class,
        ApplicationModel.class,
        Properties.class,
        String.class,
        boolean.class);
  }

  private static void invokeCodeGenerator(
      QuarkusClassLoader deploymentClassLoader,
      PathCollection sourceParents,
      Path generatedSourcesDir,
      Path buildDir,
      ApplicationModel applicationModel,
      Properties properties,
      String launchMode,
      boolean test)
      throws ReflectiveOperationException {
    Class<?> codeGenerator = deploymentClassLoader.loadClass(CodeGenerator.class.getName());
    Method initAndRun = codeGeneratorEntryPoint(codeGenerator);
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(deploymentClassLoader);
      initAndRun.invoke(
          null,
          deploymentClassLoader,
          sourceParents,
          generatedSourcesDir,
          buildDir,
          (Consumer<Path>) ignored -> {},
          applicationModel,
          properties,
          launchMode,
          test);
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static QuarkusBootstrap.Mode bootstrapMode(String launchMode)
      throws CodeGenerationException {
    return switch (launchMode.toUpperCase(Locale.ROOT)) {
      case "NORMAL" -> QuarkusBootstrap.Mode.PROD;
      case "TEST" -> QuarkusBootstrap.Mode.TEST;
      case "DEVELOPMENT" -> QuarkusBootstrap.Mode.DEV;
      default -> throw new CodeGenerationException("Unsupported launch mode: " + launchMode);
    };
  }

  private static void validateMode(BazelApplicationModel model, String launchMode, boolean test)
      throws CodeGenerationException {
    BazelApplicationModel.Mode expected =
        switch (launchMode.toUpperCase(Locale.ROOT)) {
          case "NORMAL" -> BazelApplicationModel.Mode.NORMAL;
          case "TEST" -> BazelApplicationModel.Mode.TEST;
          case "DEVELOPMENT" -> BazelApplicationModel.Mode.DEV;
          default -> throw new CodeGenerationException("Unsupported launch mode: " + launchMode);
        };
    if (model.mode() != expected) {
      throw new CodeGenerationException(
          "Application model mode " + model.mode() + " does not match " + expected);
    }
    if (test != (expected == BazelApplicationModel.Mode.TEST)) {
      throw new CodeGenerationException("--test must be true exactly when --launch-mode is TEST");
    }
  }

  private static Throwable unwrapInvocation(Exception exception) {
    if (exception instanceof InvocationTargetException invocation
        && invocation.getCause() != null) {
      return invocation.getCause();
    }
    return exception;
  }
}
