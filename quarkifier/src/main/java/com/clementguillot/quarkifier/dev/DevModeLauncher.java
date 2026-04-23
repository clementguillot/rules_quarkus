package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.util.BootstrapUtils;
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

      // 1. Serialize the ApplicationModel to a temp file
      Path serializedModel = BootstrapUtils.serializeAppModel(appModel, false);

      // 2. Create the dev jar (like Maven's DevMojo / DevModeCommandLineBuilder)
      Path devJar = createDevJar(context, config, appModel);

      // 3. Build the java command
      String javaBin = System.getProperty("java.home") + "/bin/java";
      List<String> cmd = new ArrayList<>();
      cmd.add(javaBin);
      cmd.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
      // Required for jboss-threads on Java 24+
      cmd.add("--add-opens");
      cmd.add("java.base/java.lang=ALL-UNNAMED");
      cmd.add(
          "-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());
      cmd.add("-jar");
      cmd.add(devJar.toAbsolutePath().toString());

      // 4. Start the child process
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.inheritIO();
      Process process = pb.start();

      // 5. Wait for the child process
      Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
      int exitCode = process.waitFor();
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
  static DevModeContext buildDevModeContext(QuarkifierConfig config) {
    var context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName(config.appName() != null ? config.appName() : "quarkus-app");
    context.setArgs(new String[0]);
    context.setProjectDir(config.outputDir().toAbsolutePath().toFile());

    // Platform properties for SmallRye Config expression resolution
    BuildProperties.defaults()
        .forEach((k, v) -> context.getBuildSystemProperties().put((String) k, (String) v));

    // Build ModuleInfo for the application root
    Path appJar = config.applicationClasspath().get(0);
    var coords = MavenCoordinateParser.parse(appJar);

    var moduleInfo =
        new DevModeContext.ModuleInfo.Builder()
            .setArtifactKey(ArtifactKey.ga(coords.groupId(), coords.artifactId()))
            .setName(config.appName() != null ? config.appName() : coords.artifactId())
            .setProjectDirectory(config.outputDir().toAbsolutePath().toString())
            .setSourcePaths(PathList.from(config.sourceDirs()))
            .setClassesPath(appJar.toAbsolutePath().toString())
            .setResourcePaths(PathList.from(config.resources()))
            .setTargetDir(config.outputDir().toAbsolutePath().toString())
            .build();

    context.setApplicationRoot(moduleInfo);

    return context;
  }
}
