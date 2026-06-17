package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
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
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
      DevModeContext context = buildDevModeContext(config);

      // Ensure the target directory exists — Quarkus writes build-metrics.json there.
      // In a Bazel workspace this directory doesn't exist by default (unlike Maven's target/).
      Files.createDirectories(Path.of(context.getApplicationRoot().getTargetDir()));

      // AppModelSerializerImpl is version-specific:
      // - 3.27: Java Object Serialization (BootstrapUtils)
      // - 3.33+: JSON format (ApplicationModelSerializer)
      AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
      Path serializedModel = serializer.serialize(appModel);

      // Dev jar mirrors Maven's DevMojo / DevModeCommandLineBuilder.
      Path devJar = createDevJar(context, config, appModel);
      Process process = startDevProcess(config, serializedModel, devJar);
      BazelFileWatcher watcher = startWatcherIfConfigured(config);

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

  /** Starts the child JVM running {@link DevModeMain} from the dev jar. */
  private static Process startDevProcess(QuarkifierConfig config, Path serializedModel, Path devJar)
      throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(System.getProperty("java.home") + "/bin/java");
    cmd.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
    // Required for jboss-threads on Java 24+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang=ALL-UNNAMED");
    // Required for Quarkus 3.33+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang.invoke=ALL-UNNAMED");
    cmd.add(
        "-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());
    cmd.add("-jar");
    cmd.add(devJar.toAbsolutePath().toString());

    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (config.workspaceDir() != null) {
      pb.directory(config.workspaceDir().toFile());
    }
    pb.inheritIO();
    return pb.start();
  }

  /** Starts the hot-reload file watcher when classes dir, targets, and source dirs are set. */
  private static BazelFileWatcher startWatcherIfConfigured(QuarkifierConfig config)
      throws Exception {
    if (config.classesDir() == null
        || config.bazelTargets().isEmpty()
        || config.sourceDirs().isEmpty()) {
      return null;
    }
    LOGGER.debug("[hot-reload] Starting file watcher...");
    return BazelFileWatcher.startInBackground(config);
  }

  /**
   * Creates a minimal JAR with a serialized {@link DevModeContext} and a manifest {@code
   * Class-Path} containing core deployment infrastructure jars and parent-first runtime artifacts.
   */
  private static Path createDevJar(
      DevModeContext context, QuarkifierConfig config, ApplicationModel appModel) throws Exception {
    Path tempFile = Files.createTempFile("quarkus-dev", ".jar");
    tempFile.toFile().deleteOnExit();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());
    manifest
        .getMainAttributes()
        .put(Attributes.Name.CLASS_PATH, buildManifestClassPath(config, appModel));

    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempFile))) {
      out.putNextEntry(new ZipEntry("META-INF/"));
      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      manifest.write(out);

      out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream obj = new ObjectOutputStream(bytes)) {
        obj.writeObject(context);
      }
      out.write(bytes.toByteArray());
    }

    return tempFile;
  }

  /**
   * Builds the dev jar's {@code Class-Path}: core deployment infrastructure jars (deduplicated by
   * artifactId) followed by parent-first runtime artifacts not already covered by the core set.
   *
   * <p>For jars that also exist on the application classpath, the application classpath version is
   * preferred — the ApplicationModel references that jar file, and the system classloader must use
   * the same file to avoid dual-classloader class identity conflicts.
   */
  private static String buildManifestClassPath(QuarkifierConfig config, ApplicationModel appModel) {
    Map<String, Path> appCpByArtifactId = new LinkedHashMap<>();
    for (Path jar : config.applicationClasspath()) {
      appCpByArtifactId.put(MavenCoordinateParser.parse(jar).artifactId(), jar);
    }

    StringBuilder classPath = new StringBuilder();
    Set<String> addedToManifest = new HashSet<>();
    for (Path jar : config.coreDeploymentClasspath()) {
      var coords = MavenCoordinateParser.parse(jar);
      if (addedToManifest.contains(coords.artifactId())) {
        continue;
      }
      Path effectiveJar = appCpByArtifactId.getOrDefault(coords.artifactId(), jar);
      classPath.append(effectiveJar.toAbsolutePath().toUri()).append(' ');
      addedToManifest.add(coords.artifactId());
    }

    for (Path jar : collectParentFirstRuntimeJars(config, appModel)) {
      var coords = MavenCoordinateParser.parse(jar);
      if (!addedToManifest.contains(coords.artifactId())) {
        classPath.append(jar.toAbsolutePath().toUri()).append(' ');
        addedToManifest.add(coords.artifactId());
      }
    }

    return classPath.toString().trim();
  }

  /**
   * Finds runtime jars that extensions declare must be on the parent classloader, excluding those
   * already covered by the core deployment classpath. Matches Maven's {@code
   * ConfiguredClassLoading.getParentFirstArtifacts()}.
   */
  private static List<Path> collectParentFirstRuntimeJars(
      QuarkifierConfig config, ApplicationModel appModel) {
    Set<ArtifactKey> parentFirstKeys = new HashSet<>();
    for (ResolvedDependency dep : appModel.getDependencies()) {
      if (dep.isClassLoaderParentFirst()) {
        parentFirstKeys.add(dep.getKey());
      }
    }

    Set<String> coreArtifactIds = new HashSet<>();
    for (Path jar : config.coreDeploymentClasspath()) {
      coreArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }

    List<Path> parentFirstJars = new ArrayList<>();
    for (Path jar : config.applicationClasspath()) {
      var coords = MavenCoordinateParser.parse(jar);
      ArtifactKey key = ArtifactKey.ga(coords.groupId(), coords.artifactId());
      if (parentFirstKeys.contains(key) && !coreArtifactIds.contains(coords.artifactId())) {
        parentFirstJars.add(jar);
      }
    }
    return parentFirstJars;
  }

  /** Builds a {@link DevModeContext} configured for Bazel-managed dev mode. */
  static DevModeContext buildDevModeContext(QuarkifierConfig config) {
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
    BuildProperties.defaults(config.mainClass(), null)
        .forEach((k, v) -> context.getBuildSystemProperties().put((String) k, (String) v));

    context.setApplicationRoot(buildAppModuleInfo(config, projectRoot));
    return context;
  }

  /** Builds the {@link DevModeContext.ModuleInfo} for the application root. */
  private static DevModeContext.ModuleInfo buildAppModuleInfo(
      QuarkifierConfig config, Path projectRoot) {
    Path appJar = config.applicationClasspath().get(0);
    var coords = MavenCoordinateParser.parse(appJar);

    // Key: classesPath points to mutable directory when available, otherwise the jar
    Path classesPath = config.classesDir() != null ? config.classesDir() : appJar;

    // targetDir must be a child of projectRoot so that WorkspaceProcessor (which does
    // targetDir.getParent() to find the project root) shows the correct source tree.
    // We create the directory in launch() since Bazel workspaces don't have a target/ dir.
    Path targetDir = projectRoot.resolve("target");

    return new DevModeContext.ModuleInfo.Builder()
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
        .setTargetDir(targetDir.toAbsolutePath().toString())
        .build();
  }
}
