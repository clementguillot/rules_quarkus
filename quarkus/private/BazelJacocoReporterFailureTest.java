package io.quarkus.bazel.coverage;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone failure-path checks for {@link BazelJacocoReporter}. */
public final class BazelJacocoReporterFailureTest {
  private BazelJacocoReporterFailureTest() {}

  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("bazel-jacoco-reporter-test");
    Path executionData = directory.resolve("execution.exec");
    Path classJars = directory.resolve("class-jars.txt");
    Path output = directory.resolve("coverage.dat");

    Files.writeString(executionData, "not-jacoco-data");
    Files.writeString(classJars, directory.resolve("missing.jar").toString());

    expectFailure(
        "a missing execution-data file",
        () -> runReporter(directory.resolve("missing.exec"), output, classJars));

    Files.write(executionData, new byte[0]);
    expectFailure("empty execution data", () -> runReporter(executionData, output, classJars));

    Files.writeString(executionData, "not-jacoco-data");
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
