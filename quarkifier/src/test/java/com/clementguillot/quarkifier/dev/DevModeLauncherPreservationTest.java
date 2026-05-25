package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.model.QuarkusAppModelBuilder;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.dev.DevModeContext;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Preservation property tests for DevModeLauncher.
 *
 * <p><b>Validates: Requirements 3.1, 3.3, 3.4</b>
 *
 * <p>Property 2: Preservation - DevModeContext and Main Model Unchanged
 *
 * <p>These tests observe the UNFIXED code behavior and establish a baseline that must be preserved
 * after the fix is applied. They verify:
 *
 * <ul>
 *   <li>{@code buildDevModeContext()} produces consistent DevModeContext structure
 *   <li>The child JVM command always contains the main model system property
 *   <li>All existing JVM flags (logging manager, add-opens, jar) remain in child command
 *   <li>Dev jar manifest Class-Path contains expected core deployment + parent-first jars
 * </ul>
 */
class DevModeLauncherPreservationTest {

  private static final String MAIN_MODEL_PROPERTY_PREFIX =
      "-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=";

  @TempDir Path tempDir;

  // ---- Test data provider ----

  /**
   * Generates multiple QuarkifierConfig variations to test preservation across different
   * configurations.
   */
  static Stream<Arguments> configVariations() {
    return Stream.of(
        Arguments.of(
            "with-source-dirs-and-classes-dir",
            /* classpathSize= */ 3,
            /* hasSourceDirs= */ true,
            /* hasClassesDir= */ true,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 1),
        Arguments.of(
            "without-source-dirs",
            /* classpathSize= */ 2,
            /* hasSourceDirs= */ false,
            /* hasClassesDir= */ false,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 1),
        Arguments.of(
            "without-workspace-dir",
            /* classpathSize= */ 2,
            /* hasSourceDirs= */ true,
            /* hasClassesDir= */ false,
            /* hasWorkspaceDir= */ false,
            /* coreDeploymentSize= */ 1),
        Arguments.of(
            "single-jar-classpath",
            /* classpathSize= */ 1,
            /* hasSourceDirs= */ true,
            /* hasClassesDir= */ true,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 1),
        Arguments.of(
            "five-jar-classpath",
            /* classpathSize= */ 5,
            /* hasSourceDirs= */ true,
            /* hasClassesDir= */ true,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 2),
        Arguments.of(
            "ten-jar-classpath",
            /* classpathSize= */ 10,
            /* hasSourceDirs= */ false,
            /* hasClassesDir= */ false,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 3),
        Arguments.of(
            "empty-core-deployment-classpath",
            /* classpathSize= */ 3,
            /* hasSourceDirs= */ true,
            /* hasClassesDir= */ true,
            /* hasWorkspaceDir= */ true,
            /* coreDeploymentSize= */ 0));
  }

  // ---- DevModeContext preservation tests ----

  @ParameterizedTest(name = "{0}: DevModeContext has mode=DEV")
  @MethodSource("configVariations")
  void devModeContext_modeIsDev(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(QuarkusBootstrap.Mode.DEV, context.getMode());
  }

  @ParameterizedTest(name = "{0}: DevModeContext has abortOnFailedStart=true")
  @MethodSource("configVariations")
  void devModeContext_abortOnFailedStartIsTrue(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    assertTrue(context.isAbortOnFailedStart());
  }

  @ParameterizedTest(name = "{0}: DevModeContext has localProjectDiscovery=false")
  @MethodSource("configVariations")
  void devModeContext_localProjectDiscoveryIsFalse(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    assertFalse(context.isLocalProjectDiscovery());
  }

  @ParameterizedTest(name = "{0}: DevModeContext has correct baseName")
  @MethodSource("configVariations")
  void devModeContext_correctBaseName(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    assertEquals("test-app", context.getBaseName());
  }

  @ParameterizedTest(name = "{0}: DevModeContext has correct projectDir")
  @MethodSource("configVariations")
  void devModeContext_correctProjectDir(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    if (hasWorkspaceDir) {
      assertEquals(config.workspaceDir().toAbsolutePath().toFile(), context.getProjectDir());
    } else {
      assertEquals(config.outputDir().toAbsolutePath().toFile(), context.getProjectDir());
    }
  }

  @ParameterizedTest(name = "{0}: DevModeContext has correct sourcePaths")
  @MethodSource("configVariations")
  void devModeContext_correctSourcePaths(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    var sourcePaths = context.getApplicationRoot().getMain().getSourcePaths();
    if (hasSourceDirs) {
      assertNotNull(sourcePaths);
      int count = 0;
      for (var ignored : sourcePaths) {
        count++;
      }
      assertEquals(config.sourceDirs().size(), count);
    } else {
      // Empty source dirs → empty or null source paths
      if (sourcePaths != null) {
        assertFalse(sourcePaths.iterator().hasNext());
      }
    }
  }

  @ParameterizedTest(name = "{0}: DevModeContext has correct classesPath")
  @MethodSource("configVariations")
  void devModeContext_correctClassesPath(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);

    DevModeContext context = DevModeLauncher.buildDevModeContext(config);

    String classesPath = context.getApplicationRoot().getMain().getClassesPath();
    if (hasClassesDir) {
      assertEquals(config.classesDir().toAbsolutePath().toString(), classesPath);
    } else {
      // Falls back to the first application classpath jar
      assertEquals(config.applicationClasspath().get(0).toAbsolutePath().toString(), classesPath);
    }
  }

  // ---- Child JVM command preservation tests ----

  @ParameterizedTest(name = "{0}: child command contains main model system property")
  @MethodSource("configVariations")
  void childCommand_containsMainModelSystemProperty(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    boolean hasMainModelProperty =
        cmd.stream().anyMatch(arg -> arg.startsWith(MAIN_MODEL_PROPERTY_PREFIX));
    assertTrue(
        hasMainModelProperty,
        "Child JVM command must contain main model system property ("
            + BootstrapConstants.SERIALIZED_APP_MODEL
            + ").\nFull command: "
            + cmd);
  }

  @ParameterizedTest(name = "{0}: child command contains logging manager")
  @MethodSource("configVariations")
  void childCommand_containsLoggingManager(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    assertTrue(
        cmd.contains("-Djava.util.logging.manager=org.jboss.logmanager.LogManager"),
        "Child JVM command must contain logging manager property.\nFull command: " + cmd);
  }

  @ParameterizedTest(name = "{0}: child command contains add-opens java.lang")
  @MethodSource("configVariations")
  void childCommand_containsAddOpensJavaLang(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    // Check --add-opens java.base/java.lang=ALL-UNNAMED
    int addOpensIdx = cmd.indexOf("--add-opens");
    assertNotEquals(-1, addOpensIdx, "Child JVM command must contain --add-opens");
    assertTrue(addOpensIdx + 1 < cmd.size(), "--add-opens must be followed by a value");
    assertEquals(
        "java.base/java.lang=ALL-UNNAMED",
        cmd.get(addOpensIdx + 1),
        "First --add-opens must open java.base/java.lang");
  }

  @ParameterizedTest(name = "{0}: child command contains add-opens java.lang.invoke")
  @MethodSource("configVariations")
  void childCommand_containsAddOpensJavaLangInvoke(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    // Check --add-opens java.base/java.lang.invoke=ALL-UNNAMED
    boolean hasLangInvoke = cmd.contains("java.base/java.lang.invoke=ALL-UNNAMED");
    assertTrue(
        hasLangInvoke,
        "Child JVM command must contain --add-opens java.base/java.lang.invoke=ALL-UNNAMED.\n"
            + "Full command: "
            + cmd);
  }

  @ParameterizedTest(name = "{0}: child command contains -jar with dev jar path")
  @MethodSource("configVariations")
  void childCommand_containsJarFlag(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    int jarIdx = cmd.indexOf("-jar");
    assertNotEquals(-1, jarIdx, "Child JVM command must contain -jar flag");
    assertTrue(jarIdx + 1 < cmd.size(), "-jar must be followed by a path");
    String devJarPath = cmd.get(jarIdx + 1);
    assertTrue(devJarPath.endsWith(".jar"), "Dev jar path must end with .jar, got: " + devJarPath);
    assertTrue(Files.exists(Path.of(devJarPath)), "Dev jar file must exist at: " + devJarPath);
  }

  @ParameterizedTest(name = "{0}: main model file exists and is non-empty")
  @MethodSource("configVariations")
  void childCommand_mainModelFileExists(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    String mainModelArg =
        cmd.stream()
            .filter(arg -> arg.startsWith(MAIN_MODEL_PROPERTY_PREFIX))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Main model property not found"));
    String mainModelPath = mainModelArg.substring(MAIN_MODEL_PROPERTY_PREFIX.length());
    Path mainModelFile = Path.of(mainModelPath);
    assertTrue(Files.exists(mainModelFile), "Main model file must exist at: " + mainModelPath);
    assertTrue(Files.size(mainModelFile) > 0, "Main model file must be non-empty");
  }

  @ParameterizedTest(name = "{0}: dev jar manifest contains core deployment jars")
  @MethodSource("configVariations")
  void childCommand_devJarManifestContainsCoreDeployment(
      String description,
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws Exception {
    QuarkifierConfig config =
        buildConfig(
            classpathSize, hasSourceDirs, hasClassesDir, hasWorkspaceDir, coreDeploymentSize);
    ApplicationModel appModel = buildAppModel(config);

    List<String> cmd = DevModeLauncher.buildChildCommand(config, appModel);

    // Extract the dev jar path and read its manifest
    int jarIdx = cmd.indexOf("-jar");
    String devJarPath = cmd.get(jarIdx + 1);
    try (var jarFile = new java.util.jar.JarFile(Path.of(devJarPath).toFile())) {
      var manifest = jarFile.getManifest();
      assertNotNull(manifest, "Dev jar must have a manifest");
      String classPath =
          manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.CLASS_PATH);

      // If there are core deployment jars, they should appear in the manifest classpath
      if (coreDeploymentSize > 0) {
        assertNotNull(
            classPath, "Manifest Class-Path must not be null when core deployment jars exist");
        assertTrue(classPath.length() > 0, "Manifest Class-Path must not be empty");
        // Verify core deployment jar artifact IDs appear in the classpath
        for (Path coreJar : config.coreDeploymentClasspath()) {
          String jarName = coreJar.getFileName().toString();
          // The classpath uses URIs, so check for the artifact name
          String artifactId = jarName.replace("-3.33.1.jar", "");
          assertTrue(
              classPath.contains(artifactId),
              "Manifest Class-Path must contain core deployment artifact '"
                  + artifactId
                  + "'.\nClass-Path: "
                  + classPath);
        }
      }

      // Verify main class is set
      String mainClass =
          manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
      assertEquals(
          "io.quarkus.deployment.dev.DevModeMain",
          mainClass,
          "Dev jar main class must be DevModeMain");
    }
  }

  // ---- Helpers ----

  private QuarkifierConfig buildConfig(
      int classpathSize,
      boolean hasSourceDirs,
      boolean hasClassesDir,
      boolean hasWorkspaceDir,
      int coreDeploymentSize)
      throws IOException {
    List<Path> appCp = createApplicationClasspath(classpathSize);
    List<Path> deployCp = createDeploymentClasspath();
    List<Path> coreDeployCp = createCoreDeploymentClasspath(coreDeploymentSize);

    Path outputDir = tempDir.resolve("output");
    Files.createDirectories(outputDir);

    Path workspaceDir = null;
    if (hasWorkspaceDir) {
      workspaceDir = tempDir.resolve("workspace");
      Files.createDirectories(workspaceDir);
    }

    List<Path> sourceDirs = List.of();
    if (hasSourceDirs) {
      Path srcDir = tempDir.resolve("src/main/java");
      Files.createDirectories(srcDir);
      sourceDirs = List.of(srcDir);
    }

    Path classesDir = null;
    if (hasClassesDir) {
      classesDir = tempDir.resolve("classes");
      Files.createDirectories(classesDir);
    }

    return new QuarkifierConfig(
        appCp,
        deployCp,
        coreDeployCp,
        outputDir,
        List.of(),
        AugmentationMode.DEV,
        "3.33.1",
        "test-app",
        "1.0.0",
        null,
        sourceDirs,
        classesDir,
        List.of(),
        List.of(),
        workspaceDir,
        60,
        List.of());
  }

  private ApplicationModel buildAppModel(QuarkifierConfig config) throws IOException {
    return QuarkusAppModelBuilder.build(
        config.applicationClasspath().get(0),
        config.applicationClasspath().subList(1, config.applicationClasspath().size()),
        config.deploymentClasspath(),
        config.appName(),
        config.appVersion());
  }

  private List<Path> createApplicationClasspath(int size) throws IOException {
    List<Path> jars = new ArrayList<>();
    jars.add(createJar("com.example", "test-app", "1.0.0"));
    for (int i = 1; i < size; i++) {
      jars.add(createJar("io.quarkus", "quarkus-dep-" + i, "3.33.1"));
    }
    return jars;
  }

  private List<Path> createDeploymentClasspath() throws IOException {
    return List.of(
        createJar("io.quarkus", "quarkus-core-deployment", "3.33.1"),
        createJar("io.quarkus", "quarkus-arc-deployment", "3.33.1"));
  }

  private List<Path> createCoreDeploymentClasspath(int size) throws IOException {
    if (size == 0) {
      return List.of();
    }
    List<Path> jars = new ArrayList<>();
    jars.add(createJar("io.quarkus", "quarkus-core-deployment", "3.33.1"));
    for (int i = 1; i < size; i++) {
      jars.add(createJar("io.quarkus", "quarkus-core-dep-" + i, "3.33.1"));
    }
    return jars;
  }

  private Path createJar(String groupId, String artifactId, String version) throws IOException {
    Path dir =
        tempDir.resolve("jars/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/"));
      jos.closeEntry();
    }
    return jar;
  }
}
