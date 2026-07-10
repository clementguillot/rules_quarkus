package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.maven.dependency.DependencyFlags;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.jboss.logging.Logger;

/**
 * Post-processes Quarkus augmentation output into a complete, runnable Fast_Jar directory.
 *
 * <p>After Quarkus augmentation produces raw output, four steps normalize it:
 *
 * <ol>
 *   <li>{@link #assembleLibDirectories} — classify jars into {@code lib/boot/} vs {@code lib/main/}
 *   <li>{@link #assembleResourcesJar} — create {@code app/resources.jar} from user resources
 *   <li>{@link #regenerateApplicationDat} — regenerate {@code quarkus-application.dat} with correct
 *       paths
 *   <li>{@link #fixRunnerManifest} — rewrite {@code quarkus-run.jar} manifest with boot jar
 *       classpath
 * </ol>
 */
@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods"})
public final class FastJarAssembler {

  private static final String JAR_EXTENSION = ".jar";
  private static final Set<String> EXCLUDED_ARTIFACT_IDS = Set.of("quarkus-ide-launcher");
  private static final Logger LOGGER = Logger.getLogger(FastJarAssembler.class);

  private FastJarAssembler() {}

  /**
   * Runs all four post-processing steps to produce a complete Fast_Jar.
   *
   * @param outputDir the augmentation output directory
   * @param runtimeJars runtime dependency jars (excluding the app jar itself)
   * @param appModel the ApplicationModel used during augmentation
   * @param resources user resource files (e.g., application.properties)
   * @param mainClass the fully-qualified main class name, or {@code null} to use the default {@code
   *     io.quarkus.runner.GeneratedMain}
   * @throws IOException if any I/O operation fails
   */
  public static void assemble(
      Path outputDir,
      List<Path> runtimeJars,
      ApplicationModel appModel,
      List<Path> resources,
      String mainClass)
      throws IOException {
    assembleLibDirectories(outputDir, runtimeJars, appModel);
    assembleResourcesJar(outputDir, resources);
    regenerateApplicationDat(outputDir, mainClass);
    fixRunnerManifest(outputDir);
  }

  /**
   * Normalizes jar filenames in {@code lib/boot/} and {@code lib/main/} to Maven-convention names,
   * preserving the jar content that Quarkus augmentation placed (which may have removed entries via
   * {@code RemovedResourceBuildItem} or class-removing transformers).
   *
   * <p>Strategy: rename augmented jars in-place, reclassify between boot/main based on the model's
   * {@code CLASSLOADER_RUNNER_PARENT_FIRST} flag, and fill in any jars not placed by augmentation
   * from the original runtime classpath. This avoids overwriting filtered jars with pristine
   * originals.
   */
  static void assembleLibDirectories(Path outputDir, List<Path> runtimeJars, ApplicationModel model)
      throws IOException {

    Path quarkusAppDir = resolveQuarkusAppDir(outputDir);
    Path libBoot = quarkusAppDir.resolve("lib").resolve("boot");
    Path libMain = quarkusAppDir.resolve("lib").resolve("main");
    Files.createDirectories(libBoot);
    Files.createDirectories(libMain);

    Set<String> bootArtifactIds = collectBootArtifactIds(model);
    Map<String, MavenCoordinateParser.Coordinates> coordsByArtVer =
        buildRuntimeCoordsLookup(runtimeJars);

    Set<String> presentKeys = new HashSet<>();
    renameAugmentedJars(libBoot, libMain, bootArtifactIds, coordsByArtVer, presentKeys);
    fillMissingJars(runtimeJars, libBoot, libMain, bootArtifactIds, presentKeys);
  }

  /** Collects artifact IDs that belong in the boot classloader. */
  private static Set<String> collectBootArtifactIds(ApplicationModel model) {
    Set<String> ids = new HashSet<>();
    for (var dep : model.getDependencies()) {
      if (dep.isFlagSet(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST)) {
        ids.add(dep.getArtifactId());
      }
    }
    ids.add("quarkus-bootstrap-runner");
    ids.add("quarkus-classloader-commons");
    return ids;
  }

  /**
   * Builds a lookup from {@code artifactId:version} → full coordinates from the runtime classpath.
   * Augmented jars in lib/ often lack a groupId in their filename, so we match on
   * artifactId:version and recover the groupId from the classpath entry.
   */
  private static Map<String, MavenCoordinateParser.Coordinates> buildRuntimeCoordsLookup(
      List<Path> runtimeJars) {
    Map<String, MavenCoordinateParser.Coordinates> lookup = new HashMap<>();
    for (Path jar : runtimeJars) {
      var coords = MavenCoordinateParser.parse(jar);
      lookup.putIfAbsent(coords.artifactId() + ":" + coords.version(), coords);
    }
    return lookup;
  }

  /**
   * Renames augmented jars already in lib/ to Maven-convention names, reclassifying between boot
   * and main as needed. Populates {@code presentKeys} with GAVs already handled.
   */
  private static void renameAugmentedJars(
      Path libBoot,
      Path libMain,
      Set<String> bootArtifactIds,
      Map<String, MavenCoordinateParser.Coordinates> coordsByArtVer,
      Set<String> presentKeys)
      throws IOException {

    List<Path> snapshot = new ArrayList<>();
    for (Path dir : List.of(libBoot, libMain)) {
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var s = Files.list(dir)) {
        s.filter(p -> p.toString().endsWith(JAR_EXTENSION)).forEach(snapshot::add);
      }
    }
    for (Path jar : snapshot) {
      renameOneAugmentedJar(jar, libBoot, libMain, bootArtifactIds, coordsByArtVer, presentKeys);
    }
  }

  /** Renames (or deletes) a single augmented jar to its Maven-convention location. */
  private static void renameOneAugmentedJar(
      Path jar,
      Path libBoot,
      Path libMain,
      Set<String> bootArtifactIds,
      Map<String, MavenCoordinateParser.Coordinates> coordsByArtVer,
      Set<String> presentKeys)
      throws IOException {

    var coords = MavenCoordinateParser.parse(jar);
    var fullCoords =
        coordsByArtVer.getOrDefault(coords.artifactId() + ":" + coords.version(), coords);
    String dedupeKey =
        fullCoords.groupId() + ":" + fullCoords.artifactId() + ":" + fullCoords.version();

    if (EXCLUDED_ARTIFACT_IDS.contains(fullCoords.artifactId())
        || presentKeys.contains(dedupeKey)) {
      deleteQuietly(jar);
      return;
    }
    presentKeys.add(dedupeKey);

    String mavenName =
        fullCoords.groupId()
            + "."
            + fullCoords.artifactId()
            + "-"
            + fullCoords.version()
            + JAR_EXTENSION;
    Path targetDir = bootArtifactIds.contains(fullCoords.artifactId()) ? libBoot : libMain;
    Path targetPath = targetDir.resolve(mavenName);

    if (!jar.equals(targetPath)) {
      makeWritableIfExists(jar);
      Files.move(jar, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Copies jars from the runtime classpath that augmentation didn't place (e.g. jars with no
   * Quarkus dependency mapping).
   */
  private static void fillMissingJars(
      List<Path> runtimeJars,
      Path libBoot,
      Path libMain,
      Set<String> bootArtifactIds,
      Set<String> presentKeys)
      throws IOException {

    for (Path jar : runtimeJars) {
      var coords = MavenCoordinateParser.parse(jar);
      String dedupeKey = coords.groupId() + ":" + coords.artifactId() + ":" + coords.version();

      if (presentKeys.contains(dedupeKey) || EXCLUDED_ARTIFACT_IDS.contains(coords.artifactId())) {
        continue;
      }
      presentKeys.add(dedupeKey);

      String mavenName =
          coords.groupId() + "." + coords.artifactId() + "-" + coords.version() + JAR_EXTENSION;
      Path targetDir = bootArtifactIds.contains(coords.artifactId()) ? libBoot : libMain;
      Files.copy(jar, targetDir.resolve(mavenName), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Creates a resources jar in {@code app/} containing the user's resource files
   * (application.properties, etc.). The RunnerClassLoader picks these up at runtime.
   */
  static void assembleResourcesJar(Path outputDir, List<Path> resources) throws IOException {
    if (resources == null || resources.isEmpty()) {
      return;
    }

    Path appDir = resolveQuarkusAppDir(outputDir).resolve("app");
    Files.createDirectories(appDir);

    Path resourcesJar = appDir.resolve("resources.jar");
    try (var jos = new JarOutputStream(Files.newOutputStream(resourcesJar))) {
      Set<String> addedEntries = new HashSet<>();
      for (Path resource : resources) {
        if (!Files.isRegularFile(resource)) {
          continue;
        }
        String entryName = resource.getFileName().toString();
        if (addedEntries.contains(entryName)) {
          LOGGER.warnf(
              "Duplicate resource file name '%s' (%s); keeping the first occurrence —"
                  + " resource paths are flattened to their file name.",
              entryName, resource);
          continue;
        }
        addedEntries.add(entryName);

        jos.putNextEntry(new ZipEntry(entryName));
        jos.write(Files.readAllBytes(resource));
        jos.closeEntry();
      }
    }
  }

  /**
   * Regenerates {@code quarkus-application.dat} with correct relative paths and the specified main
   * class.
   *
   * <p>Jars in {@code lib/boot/} are registered as parent-first (loaded by the boot classloader),
   * which is critical for the JBoss LogManager to intercept JUL before any other logging framework
   * initializes. All other jars are indexed normally for the RunnerClassLoader.
   */
  static void regenerateApplicationDat(Path outputDir, String mainClass) throws IOException {
    Path quarkusAppDir = resolveQuarkusAppDir(outputDir).toAbsolutePath();
    Path datFile = quarkusAppDir.resolve("quarkus").resolve("quarkus-application.dat");
    if (!Files.exists(datFile)) {
      return;
    }

    List<Path> allJars = new ArrayList<>();
    List<Path> parentFirstJars = new ArrayList<>();

    for (String subdir : List.of("quarkus", "app", "lib/boot", "lib/main")) {
      Path dir = quarkusAppDir.resolve(subdir);
      if (Files.isDirectory(dir)) {
        try (var s = Files.list(dir)) {
          s.filter(p -> p.toString().endsWith(JAR_EXTENSION))
              .map(Path::toAbsolutePath)
              .sorted()
              .forEach(
                  jar -> {
                    allJars.add(jar);
                    if ("lib/boot".equals(subdir)) {
                      parentFirstJars.add(jar);
                    }
                  });
        }
      }
    }

    String effectiveMainClass =
        (mainClass != null && !mainClass.isEmpty()) ? mainClass : "io.quarkus.runner.GeneratedMain";

    makeWritable(datFile);
    try (OutputStream os = Files.newOutputStream(datFile)) {
      ApplicationDatWriter.INSTANCE.write(
          os, effectiveMainClass, quarkusAppDir, allJars, parentFirstJars);
    }
  }

  /** Rewrites the {@code quarkus-run.jar} manifest to include boot jars in {@code Class-Path}. */
  static void fixRunnerManifest(Path outputDir) throws IOException {
    Path quarkusAppDir = resolveQuarkusAppDir(outputDir);
    Path runnerJar = quarkusAppDir.resolve("quarkus-run.jar");
    if (!Files.exists(runnerJar)) {
      return;
    }

    Path bootDir = quarkusAppDir.resolve("lib").resolve("boot");
    if (!Files.isDirectory(bootDir)) {
      return;
    }

    String classPath;
    try (Stream<Path> bootJars = Files.list(bootDir)) {
      classPath =
          bootJars
              .filter(p -> p.toString().endsWith(JAR_EXTENSION))
              .map(p -> "lib/boot/" + p.getFileName())
              .sorted()
              .collect(Collectors.joining(" "));
    }
    if (classPath.isEmpty()) {
      return;
    }

    rewriteJarManifest(runnerJar, classPath);
  }

  /** Rewrites a jar in place, replacing its manifest's {@code Class-Path} attribute. */
  private static void rewriteJarManifest(Path jarPath, String classPath) throws IOException {
    makeWritable(jarPath);

    Manifest manifest;
    byte[][] entries;
    String[] entryNames;
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      manifest = jar.getManifest();
      if (manifest == null) {
        throw new IOException("Missing manifest in jar: " + jarPath);
      }
      var entryList =
          jar.stream().filter(e -> !"META-INF/MANIFEST.MF".equals(e.getName())).toList();
      entryNames = new String[entryList.size()];
      entries = new byte[entryList.size()][];
      for (int i = 0; i < entryList.size(); i++) {
        entryNames[i] = entryList.get(i).getName();
        entries[i] = jar.getInputStream(entryList.get(i)).readAllBytes();
      }
    }

    manifest.getMainAttributes().putValue("Class-Path", classPath);

    Path tempJar = jarPath.getParent().resolve(jarPath.getFileName() + ".tmp");
    tempJar.toFile().deleteOnExit();
    try (var jos = new JarOutputStream(Files.newOutputStream(tempJar), manifest)) {
      for (int i = 0; i < entryNames.length; i++) {
        jos.putNextEntry(new ZipEntry(entryNames[i]));
        jos.write(entries[i]);
        jos.closeEntry();
      }
    }
    Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
  }

  // ---- Internal helpers ----

  /** Resolves the quarkus-app subdirectory, falling back to the output dir itself. */
  private static Path resolveQuarkusAppDir(Path outputDir) {
    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    return Files.isDirectory(quarkusAppDir) ? quarkusAppDir : outputDir;
  }

  /** Makes a file writable, throwing {@link IOException} if the permission change fails. */
  private static void makeWritable(Path file) throws IOException {
    if (!file.toFile().setWritable(true)) {
      throw new IOException("Cannot make file writable: " + file);
    }
  }

  /** Deletes a file, making it writable first if needed. Logs and continues on failure. */
  private static void deleteQuietly(Path file) {
    try {
      Files.delete(file);
    } catch (IOException e) {
      if (!file.toFile().setWritable(true)) {
        LOGGER.warnf("Cannot make file writable, skipping delete: %s", file);
        return;
      }
      try {
        Files.delete(file);
      } catch (IOException retryFailed) {
        LOGGER.warnf("Cannot delete file after making writable: %s", file);
      }
    }
  }

  /** Makes a file writable if it exists. No-op for missing files. */
  private static void makeWritableIfExists(Path file) throws IOException {
    if (Files.exists(file) && !file.toFile().setWritable(true)) {
      throw new IOException("Cannot make file writable: " + file);
    }
  }
}
