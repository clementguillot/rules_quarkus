package com.clementguillot.quarkifier.augmentation;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link FastJarAssembler} post-processing steps. */
class FastJarAssemblerTest {

  @TempDir Path tempDir;

  // ---- assembleLibDirectories ----

  @Test
  void assembleLibDirectories_classifiesBootAndMainJars() throws IOException {
    Path outputDir = quarkusAppDir();
    Path bootJar = createJar("io.quarkus", "quarkus-bootstrap-runner", "3.33.2");
    Path mainJar = createJar("io.quarkus", "quarkus-arc", "3.33.2");
    ApplicationModel model =
        modelWith(
            dep("io.quarkus", "quarkus-bootstrap-runner", "3.33.2", true),
            dep("io.quarkus", "quarkus-arc", "3.33.2", false));

    FastJarAssembler.assembleLibDirectories(outputDir, List.of(bootJar, mainJar), model);

    Path quarkusApp = outputDir.resolve("quarkus-app");
    assertTrue(
        Files.exists(quarkusApp.resolve("lib/boot/io.quarkus.quarkus-bootstrap-runner-3.33.2.jar")),
        "runner-parent-first jar goes to lib/boot");
    assertTrue(
        Files.exists(quarkusApp.resolve("lib/main/io.quarkus.quarkus-arc-3.33.2.jar")),
        "regular jar goes to lib/main");
  }

  @Test
  void assembleLibDirectories_deduplicatesByGav() throws IOException {
    Path outputDir = quarkusAppDir();
    // Same GAV resolved from two different locations.
    Path first = createJarAt("first/jars", "io.quarkus", "quarkus-arc", "3.33.2");
    Path second = createJarAt("second/jars", "io.quarkus", "quarkus-arc", "3.33.2");
    ApplicationModel model = modelWith(dep("io.quarkus", "quarkus-arc", "3.33.2", false));

    FastJarAssembler.assembleLibDirectories(outputDir, List.of(first, second), model);

    try (var files = Files.list(outputDir.resolve("quarkus-app/lib/main"))) {
      assertEquals(1, files.count(), "duplicate GAV copied only once");
    }
  }

  @Test
  void assembleLibDirectories_keepsSameArtifactAndVersionFromDifferentGroups() throws IOException {
    Path outputDir = quarkusAppDir();
    Path first = createJarAt("first/jars", "com.example.one", "runtime", "1.0.0");
    Path second = createJarAt("second/jars", "com.example.two", "runtime", "1.0.0");
    ApplicationModel model =
        modelWith(
            dep("com.example.one", "runtime", "1.0.0", false),
            dep("com.example.two", "runtime", "1.0.0", false));

    FastJarAssembler.assembleLibDirectories(outputDir, List.of(first, second), model);

    Path libMain = outputDir.resolve("quarkus-app/lib/main");
    assertTrue(Files.exists(libMain.resolve("com.example.one.runtime-1.0.0.jar")));
    assertTrue(Files.exists(libMain.resolve("com.example.two.runtime-1.0.0.jar")));
  }

  @Test
  void assembleLibDirectories_excludesIdeLauncher() throws IOException {
    Path outputDir = quarkusAppDir();
    Path ideJar = createJar("io.quarkus", "quarkus-ide-launcher", "3.33.2");
    ApplicationModel model = modelWith(dep("io.quarkus", "quarkus-ide-launcher", "3.33.2", false));

    FastJarAssembler.assembleLibDirectories(outputDir, List.of(ideJar), model);

    try (var files = Files.list(outputDir.resolve("quarkus-app/lib/main"))) {
      assertEquals(0, files.count(), "quarkus-ide-launcher is dev-mode only");
    }
  }

  @Test
  void assembleLibDirectories_renamesStaleAugmentationJars() throws IOException {
    Path outputDir = quarkusAppDir();
    Path libMain = outputDir.resolve("quarkus-app/lib/main");
    Files.createDirectories(libMain);
    // Simulate a jar placed by augmentation with a "processed_" filename.
    // Write it in a Maven-layout temp dir so MavenCoordinateParser can resolve its GAV.
    Path augmentedJar =
        createJarAt(
            "lib-stage/io/quarkus/quarkus-arc/3.33.2", "io.quarkus", "quarkus-arc", "3.33.2");
    Files.copy(augmentedJar, libMain.resolve("processed_quarkus-arc-3.33.2.jar"));
    ApplicationModel model = modelWith(dep("io.quarkus", "quarkus-arc", "3.33.2", false));

    FastJarAssembler.assembleLibDirectories(
        outputDir, List.of(createJar("io.quarkus", "quarkus-arc", "3.33.2")), model);

    assertFalse(
        Files.exists(libMain.resolve("processed_quarkus-arc-3.33.2.jar")),
        "stale processed_ jar from augmentation must be renamed");
    assertTrue(Files.exists(libMain.resolve("io.quarkus.quarkus-arc-3.33.2.jar")));
  }

  @Test
  void assembleLibDirectories_preservesAugmentedJarContent() throws IOException {
    // Regression test: if augmentation removed an entry from a jar,
    // assembleLibDirectories must NOT overwrite it with the pristine original.
    Path outputDir = quarkusAppDir();
    Path libMain = outputDir.resolve("quarkus-app/lib/main");
    Files.createDirectories(libMain);

    // Create a "pristine" runtime jar WITH a service file entry.
    Path pristineJar =
        createJarWithEntry(
            "pristine/io/fabric8/kubernetes-httpclient-vertx/6.13.0",
            "io.fabric8",
            "kubernetes-httpclient-vertx",
            "6.13.0",
            "META-INF/services/io.fabric8.kubernetes.client.http.HttpClient$Factory",
            "io.fabric8.kubernetes.client.vertx.VertxHttpClientFactory");

    // Create an "augmented" jar (placed by Quarkus) WITHOUT the service file.
    // This simulates RemovedResourceBuildItem having stripped the entry.
    Path augmentedJar =
        createJarAt(
            "augmented/io/fabric8/kubernetes-httpclient-vertx/6.13.0",
            "io.fabric8",
            "kubernetes-httpclient-vertx",
            "6.13.0");
    Files.copy(augmentedJar, libMain.resolve("processed_kubernetes-httpclient-vertx-6.13.0.jar"));

    ApplicationModel model =
        modelWith(dep("io.fabric8", "kubernetes-httpclient-vertx", "6.13.0", false));

    FastJarAssembler.assembleLibDirectories(outputDir, List.of(pristineJar), model);

    // The assembled jar must come from augmentation (no service file), not the pristine original.
    Path resultJar = libMain.resolve("io.fabric8.kubernetes-httpclient-vertx-6.13.0.jar");
    assertTrue(Files.exists(resultJar), "jar should be present with Maven-convention name");
    try (var jar = new JarFile(resultJar.toFile())) {
      assertNull(
          jar.getEntry("META-INF/services/io.fabric8.kubernetes.client.http.HttpClient$Factory"),
          "removed service file must NOT be resurrected from the pristine classpath jar");
    }
  }

  @Test
  void assembleLibDirectories_reclassifiesBootToMainWithoutDeletion() throws IOException {
    // Regression: a jar placed in lib/boot by augmentation but classified as main by the model
    // must be moved to lib/main, not deleted by the dedupe check on the second directory pass.
    Path outputDir = quarkusAppDir();
    Path libBoot = outputDir.resolve("quarkus-app/lib/boot");
    Path libMain = outputDir.resolve("quarkus-app/lib/main");
    Files.createDirectories(libBoot);
    Files.createDirectories(libMain);

    // Augmentation placed this jar in boot, but the model says it belongs in main.
    Path augmentedJar =
        createJarAt("stage/io/quarkus/quarkus-arc/3.33.2", "io.quarkus", "quarkus-arc", "3.33.2");
    Files.copy(augmentedJar, libBoot.resolve("processed_quarkus-arc-3.33.2.jar"));

    ApplicationModel model = modelWith(dep("io.quarkus", "quarkus-arc", "3.33.2", false));

    FastJarAssembler.assembleLibDirectories(
        outputDir, List.of(createJar("io.quarkus", "quarkus-arc", "3.33.2")), model);

    Path expectedTarget = libMain.resolve("io.quarkus.quarkus-arc-3.33.2.jar");
    assertTrue(Files.exists(expectedTarget), "jar must be reclassified from boot to main");
    assertFalse(
        Files.exists(libBoot.resolve("processed_quarkus-arc-3.33.2.jar")),
        "original boot location must be vacated");
    assertFalse(
        Files.exists(libBoot.resolve("io.quarkus.quarkus-arc-3.33.2.jar")),
        "jar must not remain in boot under its new name");
  }

  // ---- assembleResourcesJar ----

  @Test
  void assembleResourcesJar_packsRegularFilesOnly() throws IOException {
    Path outputDir = quarkusAppDir();
    Path props = Files.writeString(tempDir.resolve("application.properties"), "key=value");
    Path dir = Files.createDirectories(tempDir.resolve("a-directory"));
    Path missing = tempDir.resolve("missing.properties");

    FastJarAssembler.assembleResourcesJar(outputDir, List.of(props, dir, missing));

    Path resourcesJar = outputDir.resolve("quarkus-app/app/resources.jar");
    assertTrue(Files.exists(resourcesJar));
    try (var jar = new JarFile(resourcesJar.toFile())) {
      assertNotNull(jar.getEntry("application.properties"));
      assertEquals(1, jar.stream().count(), "directories and missing files are skipped");
    }
  }

  @Test
  void assembleResourcesJar_deduplicatesByFileName() throws IOException {
    Path outputDir = quarkusAppDir();
    Files.createDirectories(tempDir.resolve("one"));
    Files.createDirectories(tempDir.resolve("two"));
    Path first = Files.writeString(tempDir.resolve("one/app.properties"), "first");
    Path second = Files.writeString(tempDir.resolve("two/app.properties"), "second");

    FastJarAssembler.assembleResourcesJar(outputDir, List.of(first, second));

    try (var jar = new JarFile(outputDir.resolve("quarkus-app/app/resources.jar").toFile())) {
      assertEquals(1, jar.stream().count());
      assertEquals(
          "first", new String(jar.getInputStream(jar.getEntry("app.properties")).readAllBytes()));
    }
  }

  @Test
  void assembleResourcesJar_noResources_noJar() throws IOException {
    Path outputDir = quarkusAppDir();

    FastJarAssembler.assembleResourcesJar(outputDir, List.of());

    assertFalse(Files.exists(outputDir.resolve("quarkus-app/app/resources.jar")));
  }

  // ---- fixRunnerManifest ----

  @Test
  void fixRunnerManifest_addsBootJarsToClassPath() throws IOException {
    Path outputDir = quarkusAppDir();
    Path quarkusApp = outputDir.resolve("quarkus-app");
    Path bootDir = Files.createDirectories(quarkusApp.resolve("lib/boot"));
    Files.writeString(bootDir.resolve("io.quarkus.quarkus-bootstrap-runner-3.33.2.jar"), "x");
    Files.writeString(bootDir.resolve("org.jboss.logging.jboss-logging-3.6.0.jar"), "x");
    createRunnerJar(quarkusApp.resolve("quarkus-run.jar"));

    FastJarAssembler.fixRunnerManifest(outputDir);

    try (var jar = new JarFile(quarkusApp.resolve("quarkus-run.jar").toFile())) {
      String classPath = jar.getManifest().getMainAttributes().getValue("Class-Path");
      assertEquals(
          "lib/boot/io.quarkus.quarkus-bootstrap-runner-3.33.2.jar"
              + " lib/boot/org.jboss.logging.jboss-logging-3.6.0.jar",
          classPath,
          "boot jars sorted and space-separated");
      assertEquals(
          "io.quarkus.bootstrap.runner.QuarkusEntryPoint",
          jar.getManifest().getMainAttributes().getValue("Main-Class"),
          "existing manifest attributes preserved");
      assertNotNull(jar.getEntry("some/Entry.class"), "existing jar entries preserved");
    }
  }

  @Test
  void fixRunnerManifest_noRunnerJar_noop() throws IOException {
    assertDoesNotThrow(() -> FastJarAssembler.fixRunnerManifest(quarkusAppDir()));
  }

  // ---- regenerateApplicationDat ----

  @Test
  void regenerateApplicationDat_rewritesDatFromLibDirectories() throws IOException {
    Path outputDir = quarkusAppDir();
    Path quarkusApp = outputDir.resolve("quarkus-app");
    Files.createDirectories(quarkusApp.resolve("quarkus"));
    Path datFile = quarkusApp.resolve("quarkus/quarkus-application.dat");
    Files.write(datFile, new byte[] {0});
    Path bootDir = Files.createDirectories(quarkusApp.resolve("lib/boot"));
    writeEmptyJar(bootDir.resolve("io.quarkus.quarkus-bootstrap-runner-3.33.2.jar"));

    FastJarAssembler.regenerateApplicationDat(outputDir, "com.example.Main");

    assertTrue(Files.size(datFile) > 1, "dat file should be rewritten with real content");
  }

  // ---- helpers ----

  private Path quarkusAppDir() throws IOException {
    Path outputDir = Files.createDirectories(tempDir.resolve("output"));
    Files.createDirectories(outputDir.resolve("quarkus-app"));
    return outputDir;
  }

  private Path createJar(String groupId, String artifactId, String version) throws IOException {
    return createJarAt("jars", groupId, artifactId, version);
  }

  private Path createJarAt(String root, String groupId, String artifactId, String version)
      throws IOException {
    Path dir =
        tempDir.resolve(root + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/"));
      jos.closeEntry();
    }
    return jar;
  }

  /** Creates a jar at a Maven-layout path with a specific entry and content. */
  private Path createJarWithEntry(
      String root,
      String groupId,
      String artifactId,
      String version,
      String entryName,
      String entryContent)
      throws IOException {
    Path dir =
        tempDir.resolve(root + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/"));
      jos.closeEntry();
      jos.putNextEntry(new JarEntry(entryName));
      jos.write(entryContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      jos.closeEntry();
    }
    return jar;
  }

  private void writeEmptyJar(Path target) throws IOException {
    try (var jos = new JarOutputStream(new FileOutputStream(target.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/"));
      jos.closeEntry();
    }
  }

  private void createRunnerJar(Path target) throws IOException {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest
        .getMainAttributes()
        .put(Attributes.Name.MAIN_CLASS, "io.quarkus.bootstrap.runner.QuarkusEntryPoint");
    try (var jos = new JarOutputStream(new FileOutputStream(target.toFile()), manifest)) {
      jos.putNextEntry(new JarEntry("some/Entry.class"));
      jos.write(new byte[] {1, 2, 3});
      jos.closeEntry();
    }
  }

  private static ResolvedDependencyBuilder dep(
      String groupId, String artifactId, String version, boolean runnerParentFirst) {
    var builder =
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(groupId)
            .setArtifactId(artifactId)
            .setVersion(version)
            .setType("jar")
            .setRuntimeCp()
            .setDeploymentCp();
    if (runnerParentFirst) {
      builder.setFlags(DependencyFlags.CLASSLOADER_RUNNER_PARENT_FIRST);
    }
    return builder;
  }

  private static ApplicationModel modelWith(ResolvedDependencyBuilder... deps) {
    var builder = new ApplicationModelBuilder();
    builder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("com.example")
            .setArtifactId("app")
            .setVersion("1.0")
            .setType("jar")
            .setRuntimeCp()
            .setDeploymentCp());
    for (ResolvedDependencyBuilder dep : deps) {
      builder.addDependency(dep);
    }
    return builder.build();
  }
}
