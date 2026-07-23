package io.quarkus.bazel.coverage;

import com.google.testing.coverage.JacocoCoverageRunner;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Converts Bazel JaCoCo execution data and instrumented local jars to LCOV. */
public final class BazelJacocoReporter {
  private BazelJacocoReporter() {}

  public static void main(String[] args) throws Exception {
    Arguments parsed = Arguments.parse(args);
    validateInputFile(parsed.executionData, "execution data");
    validateInputFile(parsed.classJarsFile, "class-jars list");
    File[] classJars = readClassJars(parsed.classJarsFile);

    Path absoluteOutput = parsed.output.toAbsolutePath();
    Path outputDirectory = absoluteOutput.getParent();
    if (outputDirectory == null) {
      throw new IllegalArgumentException(
          "Coverage output has no parent directory: " + parsed.output);
    }
    Files.createDirectories(outputDirectory);
    Path temporaryOutput =
        Files.createTempFile(outputDirectory, absoluteOutput.getFileName().toString(), ".tmp");

    try {
      try (InputStream executionData = Files.newInputStream(parsed.executionData)) {
        new JacocoCoverageRunner(executionData, temporaryOutput.toString(), classJars).create();
      }
      if (Files.size(temporaryOutput) == 0) {
        throw new IOException("Bazel JaCoCo formatter produced an empty LCOV report");
      }
      replaceAtomically(temporaryOutput, absoluteOutput);
    } finally {
      Files.deleteIfExists(temporaryOutput);
    }
  }

  private static void validateInputFile(Path path, String description) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("Missing " + description + " file: " + path);
    }
    if (Files.size(path) == 0) {
      throw new IOException("Empty " + description + " file: " + path);
    }
  }

  private static File[] readClassJars(Path classJarsFile) throws IOException {
    String contents = Files.readString(classJarsFile).trim();
    if (contents.isEmpty()) {
      throw new IOException("Class-jars list contains no entries: " + classJarsFile);
    }
    String[] entries = contents.split(",", -1);
    File[] classJars = new File[entries.length];
    for (int index = 0; index < entries.length; index++) {
      String entry = entries[index];
      if (entry.isBlank()) {
        throw new IOException("Class-jars list contains an empty entry: " + classJarsFile);
      }
      Path jar = Path.of(entry);
      if (!Files.isRegularFile(jar)) {
        throw new IOException("Class-jars list references a missing file: " + jar);
      }
      classJars[index] = jar.toFile();
    }
    return classJars;
  }

  private static void replaceAtomically(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private record Arguments(Path executionData, Path output, Path classJarsFile) {
    private static Arguments parse(String[] args) {
      if (args.length != 6
          || !"--execution-data".equals(args[0])
          || !"--output".equals(args[2])
          || !"--class-jars-file".equals(args[4])) {
        throw new IllegalArgumentException(
            "Usage: BazelJacocoReporter --execution-data FILE --output FILE"
                + " --class-jars-file FILE");
      }
      return new Arguments(Path.of(args[1]), Path.of(args[3]), Path.of(args[5]));
    }
  }
}
