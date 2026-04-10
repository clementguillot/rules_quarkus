package com.clementguillot.quarkifier;

import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.runner.SerializedApplication;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Runs Quarkus augmentation and produces a runnable Fast_Jar.
 *
 * <p>Builds an {@link ApplicationModel} from the provided classpath jars (bypassing Maven/Gradle
 * resolution), invokes the Quarkus build API, then post-processes the output into a complete
 * Fast_Jar directory with all runtime jars in the correct {@code lib/} subdirectories.
 */
public final class AugmentationExecutor {

  private AugmentationExecutor() {}

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
          buildApplicationModel(
              appJar, runtimeJars, deployCp, config.appName(), config.appVersion());

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
              .setBaseClassLoader(AugmentationExecutor.class.getClassLoader())
              .setIsolateDeployment(false)
              .setFlatClassPath(true)
              .setLocalProjectDiscovery(false)
              .setBuildSystemProperties(buildProps)
              .build();

      try (CuratedApplication curatedApp = bootstrap.bootstrap()) {
        AugmentAction action = curatedApp.createAugmentor();
        AugmentResult result = action.createProductionApplication();
        if (result == null) {
          throw new AugmentationException(
              "Augmentation produced no result for output directory: " + outputDir);
        }
      }

      // Post-process: assemble the complete Fast_Jar
      assembleLibDirectories(outputDir, runtimeJars, appModel);
      assembleResourcesJar(outputDir, config.resources());
      regenerateApplicationDat(outputDir);
      fixRunnerManifest(outputDir);

    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Quarkus augmentation failed: " + e.getMessage(), e);
    }
  }

  // ---- Post-processing: assemble the Fast_Jar from augmentation output ----

  /**
   * Copies runtime jars into {@code lib/boot/} or {@code lib/main/} based on the {@code
   * CLASSLOADER_RUNNER_PARENT_FIRST} flag from extension properties.
   *
   * <p>First clears any jars placed by the Quarkus augmentation step (which may use raw classpath
   * filenames with {@code processed_} prefixes), then copies jars with clean Maven-convention
   * names. Deduplicates by {@code artifactId:version} so that jars from different sources are only
   * copied once.
   */
  private static void assembleLibDirectories(
      Path outputDir, List<Path> runtimeJars, ApplicationModel model) throws IOException {

    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir;

    Path libBoot = quarkusAppDir.resolve("lib").resolve("boot");
    Path libMain = quarkusAppDir.resolve("lib").resolve("main");

    // Clear jars placed by Quarkus augmentation (they use raw classpath filenames
    // which may include "processed_" prefixes and inconsistent naming).
    clearJars(libBoot);
    clearJars(libMain);
    Files.createDirectories(libBoot);
    Files.createDirectories(libMain);

    Set<String> bootArtifactIds = new java.util.HashSet<>();
    for (var dep : model.getDependencies()) {
      if (dep.isFlagSet(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST)) {
        bootArtifactIds.add(dep.getArtifactId());
      }
    }
    bootArtifactIds.add("quarkus-bootstrap-runner");
    bootArtifactIds.add("quarkus-classloader-commons");

    // Track what we copy to avoid duplicates from different source paths.
    Set<String> copiedKeys = new java.util.HashSet<>();

    // Artifacts that should never appear in a production Fast_Jar.
    // quarkus-ide-launcher is an IDE/dev-mode helper that shades Maven/Gradle
    // resolver classes; Maven's build excludes it from production output.
    Set<String> excludedArtifactIds = Set.of("quarkus-ide-launcher");

    for (Path jar : runtimeJars) {
      var coords = MavenCoordinateParser.parse(jar);
      String dedupeKey = coords.artifactId() + ":" + coords.version();

      if (copiedKeys.contains(dedupeKey)) {
        continue;
      }
      if (excludedArtifactIds.contains(coords.artifactId())) {
        continue;
      }
      copiedKeys.add(dedupeKey);

      String mavenName =
          coords.groupId() + "." + coords.artifactId() + "-" + coords.version() + ".jar";
      Path targetDir = bootArtifactIds.contains(coords.artifactId()) ? libBoot : libMain;
      Path target = targetDir.resolve(mavenName);
      Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Removes all .jar files from a directory. */
  private static void clearJars(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return;
    try (var stream = Files.list(dir)) {
      stream
          .filter(p -> p.toString().endsWith(".jar"))
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  // Best effort — the file may be read-only from Quarkus output
                  p.toFile().setWritable(true);
                  try {
                    Files.delete(p);
                  } catch (IOException ignored) {
                    // Skip if still can't delete
                  }
                }
              });
    }
  }

  /**
   * Creates a resources jar in {@code app/} containing the user's resource files
   * (application.properties, etc.). The RunnerClassLoader picks these up at runtime.
   */
  private static void assembleResourcesJar(Path outputDir, List<Path> resources)
      throws IOException {
    if (resources == null || resources.isEmpty()) return;

    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir;

    Path appDir = quarkusAppDir.resolve("app");
    Files.createDirectories(appDir);

    Path resourcesJar = appDir.resolve("resources.jar");
    try (var jos = new JarOutputStream(Files.newOutputStream(resourcesJar))) {
      Set<String> addedEntries = new java.util.HashSet<>();
      for (Path resource : resources) {
        if (!Files.exists(resource)) continue;
        // Use just the filename as the entry name (e.g., "application.properties")
        String entryName = resource.getFileName().toString();
        if (addedEntries.contains(entryName)) continue;
        addedEntries.add(entryName);

        jos.putNextEntry(new ZipEntry(entryName));
        jos.write(Files.readAllBytes(resource));
        jos.closeEntry();
      }
    }
  }

  /**
   * Regenerates {@code quarkus-application.dat} with correct relative paths.
   *
   * <p>Jars in {@code lib/boot/} are registered as parent-first (loaded by the boot classloader),
   * which is critical for the JBoss LogManager to intercept JUL before any other logging framework
   * initializes. All other jars are indexed normally for the RunnerClassLoader.
   */
  private static void regenerateApplicationDat(Path outputDir) throws IOException {
    Path quarkusAppDir = outputDir.resolve("quarkus-app").toAbsolutePath();
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir.toAbsolutePath();

    Path datFile = quarkusAppDir.resolve("quarkus").resolve("quarkus-application.dat");
    if (!Files.exists(datFile)) return;

    List<Path> allJars = new java.util.ArrayList<>();
    List<Path> parentFirstJars = new java.util.ArrayList<>();

    for (String subdir : List.of("quarkus", "app", "lib/boot", "lib/main")) {
      Path dir = quarkusAppDir.resolve(subdir);
      if (Files.isDirectory(dir)) {
        try (var s = Files.list(dir)) {
          s.filter(p -> p.toString().endsWith(".jar"))
              .map(Path::toAbsolutePath)
              .sorted()
              .forEach(
                  jar -> {
                    allJars.add(jar);
                    if (subdir.equals("lib/boot")) {
                      parentFirstJars.add(jar);
                    }
                  });
        }
      }
    }

    datFile.toFile().setWritable(true);
    try (OutputStream os = Files.newOutputStream(datFile)) {
      SerializedApplication.write(
          os,
          "io.quarkus.runner.GeneratedMain",
          quarkusAppDir,
          allJars,
          parentFirstJars,
          List.of());
    }
  }

  /** Rewrites the {@code quarkus-run.jar} manifest to include boot jars in {@code Class-Path}. */
  private static void fixRunnerManifest(Path outputDir) throws IOException {
    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir;

    Path runnerJar = quarkusAppDir.resolve("quarkus-run.jar");
    if (!Files.exists(runnerJar)) return;

    Path bootDir = quarkusAppDir.resolve("lib").resolve("boot");
    if (!Files.isDirectory(bootDir)) return;

    String classPath;
    try (Stream<Path> bootJars = Files.list(bootDir)) {
      classPath =
          bootJars
              .filter(p -> p.toString().endsWith(".jar"))
              .map(p -> "lib/boot/" + p.getFileName())
              .sorted()
              .collect(Collectors.joining(" "));
    }
    if (classPath.isEmpty()) return;

    runnerJar.toFile().setWritable(true);

    Manifest manifest;
    byte[][] entries;
    String[] entryNames;
    try (JarFile jar = new JarFile(runnerJar.toFile())) {
      manifest = jar.getManifest();
      var entryList =
          jar.stream().filter(e -> !e.getName().equals("META-INF/MANIFEST.MF")).toList();
      entryNames = new String[entryList.size()];
      entries = new byte[entryList.size()][];
      for (int i = 0; i < entryList.size(); i++) {
        entryNames[i] = entryList.get(i).getName();
        entries[i] = jar.getInputStream(entryList.get(i)).readAllBytes();
      }
    }

    manifest.getMainAttributes().putValue("Class-Path", classPath);

    Path tempJar = runnerJar.getParent().resolve("quarkus-run.jar.tmp");
    try (var jos = new JarOutputStream(Files.newOutputStream(tempJar), manifest)) {
      for (int i = 0; i < entryNames.length; i++) {
        jos.putNextEntry(new ZipEntry(entryNames[i]));
        jos.write(entries[i]);
        jos.closeEntry();
      }
    }
    Files.move(tempJar, runnerJar, StandardCopyOption.REPLACE_EXISTING);
  }

  // ---- ApplicationModel construction ----

  /**
   * Builds an {@link ApplicationModel} from classpath jars, detecting Quarkus extensions and
   * setting the appropriate dependency flags.
   */
  private static ApplicationModel buildApplicationModel(
      Path appJar,
      List<Path> runtimeJars,
      List<Path> deployClasspath,
      String appName,
      String appVersion)
      throws IOException {

    var modelBuilder = new ApplicationModelBuilder();
    Set<Path> extensionJars = registerExtensions(modelBuilder, runtimeJars);
    setAppArtifact(modelBuilder, appJar, appName, appVersion);
    Set<ArtifactKey> addedKeys = addRuntimeDependencies(modelBuilder, runtimeJars, extensionJars);
    addDeploymentDependencies(modelBuilder, deployClasspath, addedKeys);
    return modelBuilder.build();
  }

  /** Scans runtime jars for Quarkus extensions and registers their properties on the model. */
  private static Set<Path> registerExtensions(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars) throws IOException {
    Set<Path> extensionJars = new java.util.HashSet<>();
    for (ExtensionInfo ext : ExtensionScanner.scan(runtimeJars)) {
      extensionJars.add(ext.sourceJar());
      try (var jf = new java.util.jar.JarFile(ext.sourceJar().toFile())) {
        var entry = jf.getEntry("META-INF/quarkus-extension.properties");
        if (entry != null) {
          var props = new java.util.Properties();
          try (var is = jf.getInputStream(entry)) {
            props.load(is);
          }
          modelBuilder.handleExtensionProperties(
              props, ArtifactKey.ga(ext.groupId(), ext.artifactId()));
        }
      }
    }
    return extensionJars;
  }

  private static void setAppArtifact(
      ApplicationModelBuilder modelBuilder, Path appJar, String appName, String appVersion) {
    var coords = MavenCoordinateParser.parse(appJar);
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(coords.groupId())
            .setArtifactId(appName != null ? appName : coords.artifactId())
            .setVersion(appVersion != null ? appVersion : coords.version())
            .setResolvedPath(appJar)
            .setRuntimeCp()
            .setDeploymentCp());
  }

  /** Adds runtime jars as dependencies, marking extension jars appropriately. */
  private static Set<ArtifactKey> addRuntimeDependencies(
      ApplicationModelBuilder modelBuilder, List<Path> runtimeJars, Set<Path> extensionJars) {
    Set<ArtifactKey> addedKeys = new java.util.HashSet<>();
    for (Path jar : runtimeJars) {
      var coords = MavenCoordinateParser.parse(jar);
      var dep =
          ResolvedDependencyBuilder.newInstance()
              .setGroupId(coords.groupId())
              .setArtifactId(coords.artifactId())
              .setVersion(coords.version())
              .setResolvedPath(jar)
              .setRuntimeCp()
              .setDeploymentCp()
              .setDirect(true);
      if (extensionJars.contains(jar)) dep.setRuntimeExtensionArtifact();
      modelBuilder.addDependency(dep);
      addedKeys.add(dep.getKey());
    }
    return addedKeys;
  }

  /**
   * Adds deployment-only jars, deduplicating by both ArtifactKey and artifactId to handle
   * the same jar resolved from different sources (e.g., @maven vs Coursier cache).
   */
  private static void addDeploymentDependencies(
      ApplicationModelBuilder modelBuilder, List<Path> deployClasspath, Set<ArtifactKey> addedKeys) {
    Set<String> addedArtifactIds = new java.util.HashSet<>();
    for (var key : addedKeys) addedArtifactIds.add(key.getArtifactId());

    for (Path jar : deployClasspath) {
      var coords = MavenCoordinateParser.parse(jar);
      if (addedArtifactIds.contains(coords.artifactId())) continue;

      var dep =
          ResolvedDependencyBuilder.newInstance()
              .setGroupId(coords.groupId())
              .setArtifactId(coords.artifactId())
              .setVersion(coords.version())
              .setResolvedPath(jar)
              .setDeploymentCp()
              .setDirect(true);

      if (!addedKeys.contains(dep.getKey())) {
        modelBuilder.addDependency(dep);
        addedKeys.add(dep.getKey());
        addedArtifactIds.add(coords.artifactId());
      }
    }
  }

  private static QuarkusBootstrap.Mode mapMode(AugmentationMode mode) {
    return switch (mode) {
      case NORMAL -> QuarkusBootstrap.Mode.PROD;
      case TEST -> QuarkusBootstrap.Mode.TEST;
    };
  }
}
