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

/**
 * Post-processes Quarkus augmentation output into a complete Fast_Jar directory.
 *
 * <p>Handles: copying runtime jars into {@code lib/boot/} and {@code lib/main/}, creating a
 * resources jar, regenerating {@code quarkus-application.dat}, and fixing the runner manifest.
 */
public final class FastJarAssembler {

  private FastJarAssembler() {}

  /**
   * Assembles the complete Fast_Jar from augmentation output.
   *
   * @param outputDir the augmentation output directory
   * @param runtimeJars runtime jars to copy into lib/
   * @param resources resource files to package
   * @param model the application model (for boot/main classification)
   */
  public static void assemble(
      Path outputDir, List<Path> runtimeJars, List<Path> resources, ApplicationModel model)
      throws IOException {
    assembleLibDirectories(outputDir, runtimeJars, model);
    assembleResourcesJar(outputDir, resources);
    regenerateApplicationDat(outputDir);
    fixRunnerManifest(outputDir);
  }

  private static void assembleLibDirectories(
      Path outputDir, List<Path> runtimeJars, ApplicationModel model) throws IOException {

    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir;

    Path libBoot = quarkusAppDir.resolve("lib").resolve("boot");
    Path libMain = quarkusAppDir.resolve("lib").resolve("main");

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
    Set<String> excludedArtifactIds = Set.of("quarkus-ide-launcher");

    for (Path jar : runtimeJars) {
      var coords = MavenCoordinateParser.parse(jar);
      String dedupeKey = coords.artifactId() + ":" + coords.version();

      if (copiedKeys.contains(dedupeKey)) continue;
      if (excludedArtifactIds.contains(coords.artifactId())) continue;
      copiedKeys.add(dedupeKey);

      String mavenName =
          coords.groupId() + "." + coords.artifactId() + "-" + coords.version() + ".jar";
      Path targetDir = bootArtifactIds.contains(coords.artifactId()) ? libBoot : libMain;
      Files.copy(jar, targetDir.resolve(mavenName), StandardCopyOption.REPLACE_EXISTING);
    }
  }

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
                  p.toFile().setWritable(true);
                  try {
                    Files.delete(p);
                  } catch (IOException ignored) {
                  }
                }
              });
    }
  }

  private static void assembleResourcesJar(Path outputDir, List<Path> resources)
      throws IOException {
    if (resources == null || resources.isEmpty()) return;

    Path quarkusAppDir = outputDir.resolve("quarkus-app");
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir;

    Path appDir = quarkusAppDir.resolve("app");
    Files.createDirectories(appDir);

    Path resourcesJar = appDir.resolve("resources.jar");
    try (var jos = new JarOutputStream(Files.newOutputStream(resourcesJar))) {
      Set<String> addedEntries = new HashSet<>();
      for (Path resource : resources) {
        if (!Files.exists(resource)) continue;
        String entryName = resource.getFileName().toString();
        if (addedEntries.contains(entryName)) continue;
        addedEntries.add(entryName);

        jos.putNextEntry(new ZipEntry(entryName));
        jos.write(Files.readAllBytes(resource));
        jos.closeEntry();
      }
    }
  }

  private static void regenerateApplicationDat(Path outputDir) throws IOException {
    Path quarkusAppDir = outputDir.resolve("quarkus-app").toAbsolutePath();
    if (!Files.isDirectory(quarkusAppDir)) quarkusAppDir = outputDir.toAbsolutePath();

    Path datFile = quarkusAppDir.resolve("quarkus").resolve("quarkus-application.dat");
    if (!Files.exists(datFile)) return;

    List<Path> allJars = new ArrayList<>();
    List<Path> parentFirstJars = new ArrayList<>();

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
}
