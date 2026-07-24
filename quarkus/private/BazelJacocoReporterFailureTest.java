package io.quarkus.bazel.coverage;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone success and failure-path checks for {@link BazelJacocoReporter}. */
public final class BazelJacocoReporterFailureTest {
  private BazelJacocoReporterFailureTest() {}

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("bazel-jacoco-reporter-test");
    Path executionData = directory.resolve("execution.exec");
    Path classJars = directory.resolve("class-jars.txt");
    Path classJar = directory.resolve("classes.jar");
    Path output = directory.resolve("coverage.dat");

    Files.write(classJar, new byte[0]);
    Files.writeString(classJars, classJar.toString());

    expectFailure(
        "a missing execution-data file",
        () -> runReporter(directory.resolve("missing.exec"), output, classJars));

    Files.write(executionData, new byte[0]);
    Files.writeString(output, "stale coverage");
    runReporter(executionData, output, classJars);
    if (!Files.isRegularFile(output) || Files.size(output) != 0) {
      throw new AssertionError("Reporter did not publish an empty LCOV report");
    }

    Files.writeString(executionData, "not-jacoco-data");
    Files.writeString(classJars, directory.resolve("missing.jar").toString());
    expectFailure("a missing class jar", () -> runReporter(executionData, output, classJars));

    Files.writeString(classJars, ",");
    expectFailure("an empty class-jar entry", () -> runReporter(executionData, output, classJars));
  }

  private static void runReporter(Path executionData, Path output, Path classJars)
      throws Exception {
    BazelJacocoReporter.main(
        new String[] {
          "--execution-data",
          executionData.toString(),
          "--output",
          output.toString(),
          "--class-jars-file",
          classJars.toString()
        });
  }

  private static void expectFailure(String description, ThrowingRunnable action) throws Exception {
    try {
      action.run();
    } catch (Exception expected) {
      return;
    }
    throw new AssertionError("Reporter accepted " + description);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
