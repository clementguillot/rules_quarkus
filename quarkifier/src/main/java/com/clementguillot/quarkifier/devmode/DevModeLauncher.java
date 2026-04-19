package com.clementguillot.quarkifier.devmode;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.util.BootstrapUtils;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathList;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Launches Quarkus in dev mode with full Dev UI support via {@code IsolatedDevModeMain}.
 *
 * <p>Following Maven's {@code DevMojo} approach, this launcher creates a minimal "dev jar"
 * containing the serialized {@link DevModeContext} and a manifest classpath pointing to the
 * deployment jars. A <b>separate JVM process</b> is started with {@code java -jar dev.jar},
 * which invokes {@link DevModeMain#main} to bootstrap {@code IsolatedDevModeMain} inside a
 * clean augment classloader with no overlap from the quarkifier's system classloader.
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
      DevModeContext context = buildDevModeContext(config, appModel);

      Path serializedModel = BootstrapUtils.serializeAppModel(appModel, false);
      Path devJar =
          createDevJar(context, config.deploymentClasspath(), config.applicationClasspath());

      String javaBin = System.getProperty("java.home") + "/bin/java";
      List<String> cmd = new ArrayList<>();
      cmd.add(javaBin);
      cmd.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
      cmd.add(
          "-D"
              + BootstrapConstants.SERIALIZED_APP_MODEL
              + "="
              + serializedModel.toAbsolutePath());
      cmd.add("-jar");
      cmd.add(devJar.toAbsolutePath().toString());

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.inheritIO();
      Process process = pb.start();

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

  private static Path createDevJar(
      DevModeContext context, List<Path> deploymentClasspath, List<Path> applicationClasspath)
      throws Exception {
    Path tempFile = Files.createTempFile("quarkus-dev", ".jar");
    tempFile.toFile().deleteOnExit();

    Set<String> extensionArtifactIds = new HashSet<>();
    for (Path jar : applicationClasspath) {
      try (var jf = new java.util.jar.JarFile(jar.toFile())) {
        if (jf.getEntry("META-INF/quarkus-extension.properties") != null) {
          extensionArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
        }
      } catch (Exception ignored) {
      }
    }
    extensionArtifactIds.add("smallrye-config-inject");
    extensionArtifactIds.add("smallrye-config");
    extensionArtifactIds.add("smallrye-config-core");
    extensionArtifactIds.add("smallrye-config-common");
    extensionArtifactIds.add("microprofile-config-api");

    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempFile))) {
      out.putNextEntry(new ZipEntry("META-INF/"));

      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());

      StringBuilder classPath = new StringBuilder();
      for (Path jar : deploymentClasspath) {
        var coords = MavenCoordinateParser.parse(jar);
        if (!extensionArtifactIds.contains(coords.artifactId())) {
          URI uri = jar.toAbsolutePath().toUri();
          classPath.append(uri).append(" ");
        }
      }
      manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString().trim());

      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      manifest.write(out);

      out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes))) {
        obj.writeObject(context);
      }
      out.write(bytes.toByteArray());
    }

    return tempFile;
  }

  /**
   * Builds a {@link DevModeContext} configured for Bazel-managed dev mode.
   */
  public static DevModeContext buildDevModeContext(
      QuarkifierConfig config, ApplicationModel appModel) {
    var context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName(config.appName() != null ? config.appName() : "quarkus-app");
    context.setArgs(new String[0]);
    context.setProjectDir(config.outputDir().toAbsolutePath().toFile());

    context
        .getBuildSystemProperties()
        .put(
            "platform.quarkus.native.builder-image",
            "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21");
    context.getBuildSystemProperties().put("quarkus.package.jar.type", "fast-jar");

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
