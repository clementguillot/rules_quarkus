package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.runner.SerializedApplication;
import io.quarkus.maven.dependency.DependencyFlags;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
public final class FastJarAssembler {

  private static final String JAR_EXTENSION = ".jar";
  private static final Logger LOGGER = Logger.getLogger(FastJarAssembler.class);

  private FastJarAssembler() {}

  /**
   * Runs all four post-processing steps to produce a complete Fast_Jar.
   *
   * @param outputDir the augmentation output directory
   * @param runtimeJars runtime dependency jars (excluding the app jar itself)
   * @param appModel the ApplicationModel used during augmentation
   * @param resources user resource files (e.g., application.properties)
   * @throws IOException if any I/O operation fails
   */
  public static void assemble(
      Path outputDir, List<Path> runtimeJars, ApplicationModel appModel, List<Path> resources)
      throws IOException {
    assembleLibDirectories(outputDir, runtimeJars, appModel);
    assembleResourcesJar(outputDir, resources);
    regenerateApplicationDat(outputDir);
    fixRunnerManifest(outputDir);
  }

  /**
   * Copies runtime jars into {@code lib/boot/} or {@code lib/main/} based on the {@code
   * CLASSLOADER_RUNNER_PARENT_FIRST} flag from extension properties.
   *
   * <p>First clears any jars placed by the Quarkus augmentation step (which may use raw classpath
   * filenames with {@code processed_} prefixes), then copies jars with clean Maven-convention
   * names. Deduplicates by {@code artifactId:version} so that jars from different sources are only
   * copied once.
   */
  static void assembleLibDirectories(Path outputDir, List<Path> runtimeJars, ApplicationModel model)
      throws IOException {

    Path quarkusAppDir = resolveQuarkusAppDir(outputDir);
    Path libBoot = quarkusAppDir.resolve("lib").resolve("boot");
    Path libMain = quarkusAppDir.resolve("lib").resolve("main");

    // Clear jars placed by Quarkus augmentation (they use raw classpath filenames
    // which may include "processed_" prefixes and inconsistent naming).
    clearJars(libBoot);
    clearJars(libMain);
    Files.createDirectories(libBoot);
    Files.createDirectories(libMain);

    Set<String> bootArtifactIds = new HashSet<>();
    for (var dep : model.getDependencies()) {
      if (dep.isFlagSet(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST)) {
        bootArtifactIds.add(dep.getArtifactId());
      }
    }
    bootArtifactIds.add("quarkus-bootstrap-runner");
    bootArtifactIds.add("quarkus-classloader-commons");

    Set<String> copiedKeys = new HashSet<>();
    Set<String> excludedArtifactIds = Set.of("quarkus-ide-launcher"); // IDE/dev-mode only

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
        if (!Files.exists(resource)) {
          continue;
        }
        String entryName = resource.getFileName().toString();
        if (addedEntries.contains(entryName)) {
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
   * Regenerates {@code quarkus-application.dat} with correct relative paths.
   *
   * <p>Jars in {@code lib/boot/} are registered as parent-first (loaded by the boot classloader),
   * which is critical for the JBoss LogManager to intercept JUL before any other logging framework
   * initializes. All other jars are indexed normally for the RunnerClassLoader.
   */
  static void regenerateApplicationDat(Path outputDir) throws IOException {
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

    makeWritable(datFile);
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

    makeWritable(runnerJar);

    Manifest manifest;
    byte[][] entries;
    String[] entryNames;
    try (JarFile jar = new JarFile(runnerJar.toFile())) {
      manifest = jar.getManifest();
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

  // ---- Internal helpers ----

  /** Resolves the quarkus-app subdirectory, falling back to the output dir itself. */
  private static Path resolveQuarkusAppDir(Path outputDir) {
    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    return Files.isDirectory(quarkusAppDir) ? quarkusAppDir : outputDir;
  }

  /**
   * Makes a file writable, throwing {@link IOException} if the permission change fails.
   *
   * <p>Quarkus augmentation output files are sometimes read-only. We need to make them writable
   * before overwriting or deleting them.
   */
  private static void makeWritable(Path file) throws IOException {
    if (!file.toFile().setWritable(true)) {
      throw new IOException("Cannot make file writable: " + file);
    }
  }

  /** Removes all .jar files from a directory. */
  private static void clearJars(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.list(dir)) {
      stream
          .filter(p -> p.toString().endsWith(JAR_EXTENSION))
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  // Retry after making writable — Quarkus output jars may be read-only.
                  if (!p.toFile().setWritable(true)) {
                    LOGGER.warnf("Cannot make file writable, skipping delete: %s", p);
                    return;
                  }
                  try {
                    Files.delete(p);
                  } catch (IOException retryFailed) {
                    LOGGER.warnf("Cannot delete file after making writable: %s", p);
                  }
                }
              });
    }
  }
}
