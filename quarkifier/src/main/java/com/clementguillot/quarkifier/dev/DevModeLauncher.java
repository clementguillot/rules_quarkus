package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import com.clementguillot.quarkifier.model.QuarkusAppModelBuilder;
import com.clementguillot.quarkifier.watcher.BazelFileWatcher;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathList;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jboss.logging.Logger;

/**
 * Launches Quarkus in dev mode with full Dev UI support via {@code IsolatedDevModeMain}.
 *
 * <p>Creates a minimal "dev jar" with a serialized {@link DevModeContext} and a manifest classpath
 * pointing to core deployment infrastructure jars + parent-first runtime artifacts. A separate JVM
 * process runs {@link DevModeMain#main} which bootstraps {@code IsolatedDevModeMain} inside a clean
 * augment classloader.
 *
 * @see <a href="../../../../../../docs/dev-mode.md">docs/dev-mode.md</a> for the full architecture
 */
public final class DevModeLauncher {

  private static final Logger LOGGER = Logger.getLogger(DevModeLauncher.class);

  private DevModeLauncher() {}

  /**
   * Launches Quarkus dev mode in a separate JVM process.
   *
   * @param config CLI configuration including source dirs, classpath, output dir
   * @param appModel the ApplicationModel built from classpath jars
   * @throws AugmentationException if dev mode fails to start
   */
  public static void launch(QuarkifierConfig config, ApplicationModel appModel)
      throws AugmentationException {
    try {
      List<String> cmd = buildChildCommand(config, appModel);

      // Start the child process
      ProcessBuilder pb = new ProcessBuilder(cmd);
      if (config.workspaceDir() != null) {
        pb.directory(config.workspaceDir().toFile());
      }
      pb.inheritIO();
      Process process = pb.start();

      // Start file watcher for hot-reload (if configured)
      final BazelFileWatcher watcher;
      if (config.classesDir() != null
          && !config.bazelTargets().isEmpty()
          && !config.sourceDirs().isEmpty()) {
        LOGGER.debug("[hot-reload] Starting file watcher...");
        watcher = BazelFileWatcher.startInBackground(config);
      } else {
        watcher = null;
      }

      // Wait for the child process
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    process.destroyForcibly();
                    if (watcher != null) {
                      watcher.close();
                    }
                  }));
      int exitCode = process.waitFor();

      // Close watcher after child exits
      if (watcher != null) {
        watcher.close();
      }

      if (exitCode != 0) {
        throw new AugmentationException("Dev mode process exited with code " + exitCode);
      }

    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Failed to launch dev mode: " + e.getMessage(), e);
    }
  }

  /**
   * Builds the child JVM command list for launching dev mode without actually starting the process.
   *
   * <p>This method is package-private to allow unit testing of the command construction logic.
   *
   * @param config CLI configuration including source dirs, classpath, output dir
   * @param appModel the ApplicationModel built from classpath jars
   * @return the full command list (java binary, JVM flags, system properties, -jar, dev jar path)
   * @throws Exception if serialization or dev jar creation fails
   */
  static List<String> buildChildCommand(QuarkifierConfig config, ApplicationModel appModel)
      throws Exception {
    DevModeContext context = buildDevModeContext(config);

    // 1. Serialize the ApplicationModel to a temp file.
    // Uses AppModelSerializerImpl which is version-specific:
    // - 3.27: Java Object Serialization (BootstrapUtils)
    // - 3.33+: JSON format (ApplicationModelSerializer)
    AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
    Path serializedModel = serializer.serialize(appModel);

    // 2. Build and serialize a test ApplicationModel for Continuous Testing support.
    // This allows TestSupport.init() to find the test model via system property
    // instead of falling through to Maven resolution (which fails under Bazel).
    Path appJar = config.applicationClasspath().get(0);
    List<Path> runtimeJars =
        config.applicationClasspath().subList(1, config.applicationClasspath().size());

    // For the test model, use the test jar as the app artifact so Quarkus scans it
    // for @QuarkusTest annotated classes. The main app jar becomes a runtime dependency.
    List<Path> testRuntimeJars;
    Path testAppJar;
    if (!config.testClasspath().isEmpty()) {
      testAppJar = config.testClasspath().get(0);
      testRuntimeJars = new ArrayList<>();
      testRuntimeJars.add(appJar); // main app jar becomes a runtime dep
      testRuntimeJars.addAll(runtimeJars);
      testRuntimeJars.addAll(config.testClasspath().subList(1, config.testClasspath().size()));
    } else {
      testAppJar = appJar;
      testRuntimeJars = runtimeJars;
    }

    ApplicationModel testAppModel =
        QuarkusAppModelBuilder.buildForTest(
            testAppJar,
            testRuntimeJars,
            config.deploymentClasspath(),
            config.appName(),
            config.appVersion());
    Path serializedTestModel = serializer.serialize(testAppModel);

    // 3. Create the dev jar (like Maven's DevMojo / DevModeCommandLineBuilder)
    Path devJar = createDevJar(context, config, appModel);

    // 4. Build the java command
    String javaBin = System.getProperty("java.home") + "/bin/java";
    List<String> cmd = new ArrayList<>();
    cmd.add(javaBin);
    cmd.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
    // Required for jboss-threads on Java 24+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang=ALL-UNNAMED");
    // Required for Quarkus 3.33+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang.invoke=ALL-UNNAMED");
    cmd.add(
        "-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());
    cmd.add(
        "-D"
            + BootstrapConstants.SERIALIZED_TEST_APP_MODEL
            + "="
            + serializedTestModel.toAbsolutePath());
    cmd.add("-jar");
    cmd.add(devJar.toAbsolutePath().toString());

    return cmd;
  }

  /**
   * Creates a minimal JAR with a serialized {@link DevModeContext} and a manifest {@code
   * Class-Path} containing core deployment infrastructure jars and parent-first runtime artifacts.
   */
  private static Path createDevJar(
      DevModeContext context, QuarkifierConfig config, ApplicationModel appModel) throws Exception {
    Path tempFile = Files.createTempFile("quarkus-dev", ".jar");
    tempFile.toFile().deleteOnExit();

    // Collect parent-first artifact keys from the ApplicationModel.
    // This matches Maven's ConfiguredClassLoading.getParentFirstArtifacts().
    Set<ArtifactKey> parentFirstKeys = new HashSet<>();
    for (ResolvedDependency dep : appModel.getDependencies()) {
      if (dep.isClassLoaderParentFirst()) {
        parentFirstKeys.add(dep.getKey());
      }
    }

    // Build a set of jar filenames already covered by core deployment classpath,
    // so we don't duplicate them when adding parent-first runtime jars.
    Set<String> coreArtifactIds = new HashSet<>();
    for (Path jar : config.coreDeploymentClasspath()) {
      coreArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }

    // Find parent-first runtime jars that aren't already in the core deployment set.
    // These are runtime jars that extensions declare must be on the parent classloader.
    List<Path> parentFirstJars = new ArrayList<>();
    for (Path jar : config.applicationClasspath()) {
      var coords = MavenCoordinateParser.parse(jar);
      ArtifactKey key = ArtifactKey.ga(coords.groupId(), coords.artifactId());
      if (parentFirstKeys.contains(key) && !coreArtifactIds.contains(coords.artifactId())) {
        parentFirstJars.add(jar);
      }
    }

    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempFile))) {
      out.putNextEntry(new ZipEntry("META-INF/"));

      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());

      // Build the Class-Path: core deployment jars (deduped) + parent-first runtime jars
      StringBuilder classPath = new StringBuilder();

      // 1. Core deployment infrastructure (quarkus-core-deployment transitive closure).
      //    For jars that also exist on the application classpath, use the application
      //    classpath version to avoid dual-classloader conflicts. The ApplicationModel
      //    references the @maven version, so the system CL must use the same jar file.
      Map<String, Path> appCpByArtifactId = new java.util.LinkedHashMap<>();
      for (Path jar : config.applicationClasspath()) {
        appCpByArtifactId.put(MavenCoordinateParser.parse(jar).artifactId(), jar);
      }

      Set<String> addedToManifest = new HashSet<>();
      for (Path jar : config.coreDeploymentClasspath()) {
        var coords = MavenCoordinateParser.parse(jar);
        if (addedToManifest.contains(coords.artifactId())) {
          continue;
        }
        // Prefer the application classpath version if available (same jar file
        // that the ApplicationModel references, avoiding class identity conflicts)
        Path effectiveJar = appCpByArtifactId.getOrDefault(coords.artifactId(), jar);
        classPath.append(effectiveJar.toAbsolutePath().toUri()).append(' ');
        addedToManifest.add(coords.artifactId());
      }

      // 2. Parent-first runtime artifacts not already in core
      for (Path jar : parentFirstJars) {
        var coords = MavenCoordinateParser.parse(jar);
        if (!addedToManifest.contains(coords.artifactId())) {
          classPath.append(jar.toAbsolutePath().toUri()).append(' ');
          addedToManifest.add(coords.artifactId());
        }
      }

      manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString().trim());

      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      manifest.write(out);

      out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes));
      obj.writeObject(context);
      obj.close();
      out.write(bytes.toByteArray());
    }

    return tempFile;
  }

  /** Builds a {@link DevModeContext} configured for Bazel-managed dev mode. */
  static DevModeContext buildDevModeContext(QuarkifierConfig config) throws Exception {
    var context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName(config.appName() != null ? config.appName() : "quarkus-app");
    context.setArgs(new String[0]);

    // Use workspaceDir as the project root so the Dev UI "Workspace" tab
    // displays the user's actual source tree instead of Bazel's output directory.
    Path projectRoot = config.workspaceDir() != null ? config.workspaceDir() : config.outputDir();
    if (config.workspaceDir() == null) {
      LOGGER.warn(
          "Workspace directory not set. Dev UI workspace tab will not show source files."
              + " Use 'bazel run' to launch dev mode.");
    }
    context.setProjectDir(projectRoot.toAbsolutePath().toFile());

    // Platform properties for SmallRye Config expression resolution
    BuildProperties.defaults(config.mainClass())
        .forEach((k, v) -> context.getBuildSystemProperties().put((String) k, (String) v));

    // Build ModuleInfo for the application root
    Path appJar = config.applicationClasspath().get(0);
    var coords = MavenCoordinateParser.parse(appJar);

    // Key: classesPath points to mutable directory when available, otherwise the jar
    Path classesPath = config.classesDir() != null ? config.classesDir() : appJar;

    var moduleInfo =
        new DevModeContext.ModuleInfo.Builder()
            .setArtifactKey(ArtifactKey.ga(coords.groupId(), coords.artifactId()))
            .setName(config.appName() != null ? config.appName() : coords.artifactId())
            .setProjectDirectory(projectRoot.toAbsolutePath().toString())
            .setSourcePaths(PathList.from(config.sourceDirs()))
            .setClassesPath(classesPath.toAbsolutePath().toString())
            .setResourcePaths(PathList.from(config.resources()))
            .setResourcesOutputPath(
                config.resources().isEmpty()
                    ? null
                    : config.resources().get(0).toAbsolutePath().toString())
            .setTargetDir(projectRoot.resolve("target").toAbsolutePath().toString());

    // Set test classes path for Continuous Testing support.
    // TestSupport.init() uses module.getTest().classesPath to discover @QuarkusTest classes.
    // JunitTestRunner.discoverTestClasses() uses Files.walk() on this path, so it MUST be
    // a directory of .class files, not a JAR. In Bazel, test classes are in JARs, so we
    // extract them to a temp directory.
    if (!config.testClasspath().isEmpty()) {
      Path testClassesJar = config.testClasspath().get(0);
      Path testClassesDir = java.nio.file.Files.createTempDirectory("quarkus-test-classes");
      try (var jarFile = new java.util.jar.JarFile(testClassesJar.toFile())) {
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          var entry = entries.nextElement();
          if (entry.getName().endsWith(".class")) {
            Path target = testClassesDir.resolve(entry.getName());
            java.nio.file.Files.createDirectories(target.getParent());
            try (var is = jarFile.getInputStream(entry)) {
              java.nio.file.Files.copy(is, target);
            }
          }
        }
      }
      moduleInfo.setTestClassesPath(testClassesDir.toAbsolutePath().toString());
      moduleInfo.setTestSourcePaths(PathList.from(config.sourceDirs()));
    }

    context.setApplicationRoot(moduleInfo.build());

    return context;
  }
}
