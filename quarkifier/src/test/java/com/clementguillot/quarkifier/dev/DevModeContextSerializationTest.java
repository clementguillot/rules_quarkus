package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused serialization test for DevModeContext to determine whether the test CompilationUnit
 * survives the serialize/deserialize round-trip used by createDevJar → DevModeMain.main().
 *
 * <p>If this test PASSES, serialization works and the issue is elsewhere (e.g., the path doesn't
 * exist in the child JVM, or extraction happens too late).
 *
 * <p>If this test FAILS, we've found the serialization bug.
 */
class DevModeContextSerializationTest {

  @TempDir Path tempDir;

  @Test
  void testCompilationUnitSurvivesSerializationRoundTrip() throws Exception {
    // Arrange: create a DevModeContext with a ModuleInfo that has testClassesPath set
    String testClassesPath = "/tmp/test-classes";
    DevModeContext context = buildContextWithTestClasses(testClassesPath);

    // Sanity check: test is present before serialization
    assertTrue(
        context.getApplicationRoot().getTest().isPresent(),
        "Test CompilationUnit should be present before serialization");
    assertEquals(
        testClassesPath,
        context.getApplicationRoot().getTest().get().getClassesPath(),
        "Test classesPath should match before serialization");

    // Act: serialize using the EXACT same pattern as createDevJar
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes));
    obj.writeObject(context);
    obj.close();

    // Act: deserialize using the EXACT same pattern as DevModeMain.main()
    DevModeContext deserialized =
        (DevModeContext)
            new ObjectInputStream(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))
                .readObject();

    // Assert: test CompilationUnit survives the round-trip
    assertNotNull(
        deserialized.getApplicationRoot(),
        "ApplicationRoot should not be null after deserialization");
    assertTrue(
        deserialized.getApplicationRoot().getTest().isPresent(),
        "Test CompilationUnit should be present after deserialization");
    assertEquals(
        testClassesPath,
        deserialized.getApplicationRoot().getTest().get().getClassesPath(),
        "Test classesPath should match after deserialization");
  }

  @Test
  void testCompilationUnitSurvivesCrossClassloaderReSerialization() throws Exception {
    // Arrange: create a DevModeContext with a ModuleInfo that has testClassesPath set
    String testClassesPath = "/tmp/test-classes";
    DevModeContext context = buildContextWithTestClasses(testClassesPath);

    // Step 1: serialize using the EXACT same pattern as createDevJar
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes));
    obj.writeObject(context);
    obj.close();

    // Step 2: deserialize using the EXACT same pattern as DevModeMain.main()
    DevModeContext deserialized =
        (DevModeContext)
            new ObjectInputStream(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))
                .readObject();

    // Step 3: Simulate IsolatedDevModeMain's cross-classloader re-serialization
    ByteArrayOutputStream out2 = new ByteArrayOutputStream();
    ObjectOutputStream oo2 = new ObjectOutputStream(out2);
    oo2.writeObject(deserialized);
    DevModeContext reDeserialized =
        (DevModeContext)
            new ObjectInputStream(new ByteArrayInputStream(out2.toByteArray())).readObject();

    // Assert: test CompilationUnit survives the double round-trip
    assertNotNull(
        reDeserialized.getApplicationRoot(),
        "ApplicationRoot should not be null after re-deserialization");
    assertTrue(
        reDeserialized.getApplicationRoot().getTest().isPresent(),
        "Test CompilationUnit should be present after cross-classloader re-serialization");
    assertEquals(
        testClassesPath,
        reDeserialized.getApplicationRoot().getTest().get().getClassesPath(),
        "Test classesPath should match after cross-classloader re-serialization");
  }

  @Test
  void testCompilationUnitWithRealPathSurvivesRoundTrip() throws Exception {
    // Use a real temp directory path (more realistic scenario)
    Path testClassesDir = tempDir.resolve("test-classes");
    String testClassesPath = testClassesDir.toAbsolutePath().toString();
    DevModeContext context = buildContextWithTestClasses(testClassesPath);

    // Serialize (createDevJar pattern)
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream obj = new ObjectOutputStream(new DataOutputStream(bytes));
    obj.writeObject(context);
    obj.close();

    // Deserialize (DevModeMain.main() pattern)
    DevModeContext deserialized =
        (DevModeContext)
            new ObjectInputStream(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))
                .readObject();

    // Re-serialize (IsolatedDevModeMain pattern)
    ByteArrayOutputStream out2 = new ByteArrayOutputStream();
    ObjectOutputStream oo2 = new ObjectOutputStream(out2);
    oo2.writeObject(deserialized);
    DevModeContext reDeserialized =
        (DevModeContext)
            new ObjectInputStream(new ByteArrayInputStream(out2.toByteArray())).readObject();

    // Assert full chain
    assertTrue(
        reDeserialized.getApplicationRoot().getTest().isPresent(),
        "Test CompilationUnit should survive full serialization chain with real path");
    assertEquals(
        testClassesPath,
        reDeserialized.getApplicationRoot().getTest().get().getClassesPath(),
        "Test classesPath should match after full serialization chain with real path");
  }

  // ---- Helper ----

  private DevModeContext buildContextWithTestClasses(String testClassesPath) {
    DevModeContext context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName("test-app");
    context.setArgs(new String[0]);

    var moduleInfo =
        new DevModeContext.ModuleInfo.Builder()
            .setArtifactKey(ArtifactKey.ga("com.example", "test-app"))
            .setName("test-app")
            .setProjectDirectory("/tmp/project")
            .setSourcePaths(PathList.of(Path.of("/tmp/src/main/java")))
            .setClassesPath("/tmp/classes")
            .setResourcePaths(PathList.of())
            .setResourcesOutputPath(null)
            .setTargetDir("/tmp/target")
            .setTestClassesPath(testClassesPath)
            .setTestSourcePaths(PathList.of(Path.of("/tmp/src/test/java")))
            .setTestResourcePaths(PathList.of())
            .build();

    context.setApplicationRoot(moduleInfo);
    return context;
  }
}
