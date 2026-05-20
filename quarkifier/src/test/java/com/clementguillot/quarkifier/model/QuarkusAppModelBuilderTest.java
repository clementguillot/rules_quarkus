package com.clementguillot.quarkifier.model;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link QuarkusAppModelBuilder} dependency graph population. */
class QuarkusAppModelBuilderTest {

  @TempDir Path tempDir;

  @Test
  void buildSetsTypeJarOnAllDependencies() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    Path depJar = createJar("dep", "io.quarkus", "quarkus-core", "3.33.1");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(appJar, List.of(depJar), List.of(), "myapp", "1.0");

    assertEquals("jar", model.getAppArtifact().getType());
    for (ResolvedDependency dep : model.getDependencies()) {
      assertEquals("jar", dep.getType(), "type should be jar for " + dep.getArtifactId());
    }
  }

  @Test
  void buildPopulatesDependencyLinksFromEmbeddedPom() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    // quarkus-rest depends on quarkus-core (declared in its embedded POM)
    Path coreJar = createJar("core", "io.quarkus", "quarkus-core", "3.33.1");
    Path restJar =
        createJarWithPom(
            "rest",
            "io.quarkus",
            "quarkus-rest",
            "3.33.1",
            "<dependency><groupId>io.quarkus</groupId>"
                + "<artifactId>quarkus-core</artifactId><version>3.33.1</version></dependency>");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(appJar, List.of(coreJar, restJar), List.of(), "myapp", "1.0");

    // Find quarkus-rest in the model and check its getDependencies()
    ResolvedDependency restDep =
        model.getDependencies().stream()
            .filter(d -> "quarkus-rest".equals(d.getArtifactId()))
            .findFirst()
            .orElseThrow();

    Collection<ArtifactCoords> subDeps = restDep.getDependencies();
    Set<String> subDepArtifacts =
        subDeps.stream().map(ArtifactCoords::getArtifactId).collect(Collectors.toSet());
    assertTrue(
        subDepArtifacts.contains("quarkus-core"), "quarkus-rest should depend on quarkus-core");
  }

  @Test
  void buildResolvesProjectGroupIdInPom() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    Path bufferJar =
        createJarWithPom(
            "buffer",
            "io.netty",
            "netty-buffer",
            "4.1.130.Final",
            "<dependency><groupId>${project.groupId}</groupId>"
                + "<artifactId>netty-common</artifactId>"
                + "<version>${project.version}</version></dependency>");
    Path commonJar = createJar("common", "io.netty", "netty-common", "4.1.130.Final");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(
            appJar, List.of(bufferJar, commonJar), List.of(), "myapp", "1.0");

    ResolvedDependency bufferDep =
        model.getDependencies().stream()
            .filter(d -> "netty-buffer".equals(d.getArtifactId()))
            .findFirst()
            .orElseThrow();

    Set<String> subDepArtifacts =
        bufferDep.getDependencies().stream()
            .map(ArtifactCoords::getArtifactId)
            .collect(Collectors.toSet());
    assertTrue(subDepArtifacts.contains("netty-common"), "should resolve ${project.groupId}");
  }

  @Test
  void buildExcludesTestAndProvidedScopeDeps() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    Path mainJar =
        createJarWithPom(
            "main",
            "io.quarkus",
            "quarkus-core",
            "3.33.1",
            "<dependency><groupId>org.junit</groupId><artifactId>junit</artifactId>"
                + "<version>5.0</version><scope>test</scope></dependency>"
                + "<dependency><groupId>javax</groupId><artifactId>servlet-api</artifactId>"
                + "<version>4.0</version><scope>provided</scope></dependency>"
                + "<dependency><groupId>io.smallrye</groupId><artifactId>config</artifactId>"
                + "<version>3.0</version></dependency>");
    Path configJar = createJar("config", "io.smallrye", "config", "3.0");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(
            appJar, List.of(mainJar, configJar), List.of(), "myapp", "1.0");

    ResolvedDependency mainDep =
        model.getDependencies().stream()
            .filter(d -> "quarkus-core".equals(d.getArtifactId()))
            .findFirst()
            .orElseThrow();

    Set<String> subDepArtifacts =
        mainDep.getDependencies().stream()
            .map(ArtifactCoords::getArtifactId)
            .collect(Collectors.toSet());
    assertTrue(subDepArtifacts.contains("config"), "compile-scope dep should be included");
    assertFalse(subDepArtifacts.contains("junit"), "test-scope dep should be excluded");
    assertFalse(subDepArtifacts.contains("servlet-api"), "provided-scope dep should be excluded");
  }

  @Test
  void buildSetsAppArtifactDependenciesForRootNode() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    Path arcJar = createExtensionJar("arc", "io.quarkus", "quarkus-arc", "3.33.1");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(appJar, List.of(arcJar), List.of(), "myapp", "1.0");

    Collection<ArtifactCoords> appDeps = model.getAppArtifact().getDependencies();
    assertFalse(appDeps.isEmpty(), "root node should have direct dependencies");
    assertTrue(
        appDeps.stream().anyMatch(c -> "quarkus-arc".equals(c.getArtifactId())),
        "root should link to quarkus-arc extension");
  }

  @Test
  void deploymentSpiJarsAreNotMarkedRuntime() throws IOException {
    Path appJar = createJar("app", "com.example", "myapp", "1.0");
    Path spiJar = createJar("spi", "io.quarkus", "quarkus-vertx-deployment-spi", "3.33.1");

    ApplicationModel model =
        QuarkusAppModelBuilder.build(appJar, List.of(), List.of(spiJar), "myapp", "1.0");

    ResolvedDependency spiDep =
        model.getDependencies().stream()
            .filter(d -> "quarkus-vertx-deployment-spi".equals(d.getArtifactId()))
            .findFirst()
            .orElseThrow();

    assertFalse(
        spiDep.isFlagSet(DependencyFlags.RUNTIME_CP),
        "deployment-spi jars should not be on runtime classpath");
    assertTrue(spiDep.isFlagSet(DependencyFlags.DEPLOYMENT_CP));
  }

  // ---- Helpers ----

  private Path createJar(String prefix, String groupId, String artifactId, String version)
      throws IOException {
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

  private Path createJarWithPom(
      String prefix, String groupId, String artifactId, String version, String depsXml)
      throws IOException {
    Path dir =
        tempDir.resolve("jars/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    String pomXml =
        "<?xml version=\"1.0\"?><project><groupId>"
            + groupId
            + "</groupId><artifactId>"
            + artifactId
            + "</artifactId><version>"
            + version
            + "</version><dependencies>"
            + depsXml
            + "</dependencies></project>";
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      String pomPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml";
      jos.putNextEntry(new JarEntry(pomPath));
      jos.write(pomXml.getBytes());
      jos.closeEntry();
      String propsPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
      jos.putNextEntry(new JarEntry(propsPath));
      jos.write(
          ("version=" + version + "\ngroupId=" + groupId + "\nartifactId=" + artifactId)
              .getBytes());
      jos.closeEntry();
    }
    return jar;
  }

  private Path createExtensionJar(String prefix, String groupId, String artifactId, String version)
      throws IOException {
    Path dir =
        tempDir.resolve("jars/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version);
    Files.createDirectories(dir);
    Path jar = dir.resolve(artifactId + "-" + version + ".jar");
    try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
      jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
      String props = "deployment-artifact=" + groupId + ":" + artifactId + "-deployment:" + version;
      jos.write(props.getBytes());
      jos.closeEntry();
    }
    return jar;
  }
}
