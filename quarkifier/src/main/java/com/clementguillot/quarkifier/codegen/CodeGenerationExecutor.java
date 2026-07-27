package com.clementguillot.quarkifier.codegen;

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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Hermetic adapter around Quarkus' build-tool code-generation entry point. */
public final class CodeGenerationExecutor {

  private static final long DETERMINISTIC_ZIP_TIME = 0L;

  private CodeGenerationExecutor() {}

  public static void execute(
      Path applicationModelPath,
      List<Path> sourceParents,
      Path generatedSourcesDir,
      Path auxiliaryOutputDir,
      Path sourceJar,
      Path buildDir,
      String launchMode,
      boolean test,
      Path propertiesFile)
      throws CodeGenerationException {
    try {
      BazelApplicationModel explicitModel = BazelApplicationModelReader.read(applicationModelPath);
      validateMode(explicitModel, launchMode, test);
      ApplicationModel applicationModel = ExplicitApplicationModelBuilder.build(explicitModel);
      Properties properties = loadProperties(propertiesFile);

      Files.createDirectories(generatedSourcesDir);
      Files.createDirectories(auxiliaryOutputDir);
      Files.createDirectories(buildDir);
      if (sourceJar.getParent() != null) {
        Files.createDirectories(sourceJar.getParent());
      }

      QuarkusBootstrap.Mode bootstrapMode = bootstrapMode(launchMode);
      QuarkusBootstrap bootstrap =
          QuarkusBootstrap.builder()
              .setExistingModel(applicationModel)
              .setApplicationRoot(applicationModel.getAppArtifact().getResolvedPaths())
              .setTargetDirectory(buildDir)
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
        invokeCodeGenerator(
            deploymentClassLoader,
            PathList.from(sourceParents),
            generatedSourcesDir,
            buildDir,
            applicationModel,
            properties,
            launchMode,
            test);
      }

      packageOutputs(generatedSourcesDir, sourceJar, auxiliaryOutputDir);
    } catch (CodeGenerationException e) {
      throw e;
    } catch (Exception e) {
      Throwable cause = unwrapInvocation(e);
      String message = cause.getMessage();
      if (message == null || message.isBlank()) {
        message = cause.getClass().getName();
      }
      throw new CodeGenerationException(message, cause);
    }
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
    Method initAndRun =
        codeGenerator.getMethod(
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

  static void packageOutputs(Path generatedSourcesDir, Path sourceJar, Path auxiliaryOutputDir)
      throws IOException, CodeGenerationException {
    List<Path> javaSources = new ArrayList<>();
    List<Path> auxiliaryFiles = new ArrayList<>();
    List<Path> unsupportedSources = new ArrayList<>();
    try (var paths = Files.walk(generatedSourcesDir)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".java")) {
                  javaSources.add(path);
                } else if (name.endsWith(".kt") || name.endsWith(".scala")) {
                  unsupportedSources.add(path);
                } else {
                  auxiliaryFiles.add(path);
                }
              });
    }
    if (!unsupportedSources.isEmpty()) {
      throw new CodeGenerationException(
          "Generated Kotlin/Scala sources are not supported yet: " + unsupportedSources);
    }
    if (javaSources.isEmpty()) {
      throw new CodeGenerationException(
          "No Java sources were generated; verify that a CodeGenProvider is present and that"
              + " --source-parent contains its input directory");
    }

    Comparator<Path> relativeOrder =
        Comparator.comparing(path -> generatedSourcesDir.relativize(path).toString());
    javaSources.sort(relativeOrder);
    auxiliaryFiles.sort(relativeOrder);

    try (JarOutputStream jar =
        new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(sourceJar)))) {
      for (Path source : javaSources) {
        String entryName = generatedSourcesDir.relativize(source).toString().replace('\\', '/');
        JarEntry entry = new JarEntry(entryName);
        entry.setTime(DETERMINISTIC_ZIP_TIME);
        jar.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
          input.transferTo(jar);
        }
        jar.closeEntry();
      }
    }

    for (Path auxiliary : auxiliaryFiles) {
      Path relative = generatedSourcesDir.relativize(auxiliary);
      Path destination = auxiliaryOutputDir.resolve(relative);
      Files.createDirectories(destination.getParent());
      Files.copy(auxiliary, destination);
    }
  }

  private static Properties loadProperties(Path propertiesFile) throws IOException {
    Properties properties = new Properties();
    if (propertiesFile != null) {
      try (InputStream input = Files.newInputStream(propertiesFile)) {
        properties.load(input);
      }
    }
    return properties;
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
