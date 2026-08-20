package com.clementguillot.quarkifier.codegen;

import com.clementguillot.quarkifier.CodeGenerationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Deterministic packaging for generated sources and stable provider work products. */
final class CodeGenerationOutputs {

  private static final long DETERMINISTIC_ZIP_TIME = 0L;

  private CodeGenerationOutputs() {}

  static void packageOutputs(Path generatedSourcesDir, Path sourceJar, Path auxiliaryOutputDir)
      throws IOException, CodeGenerationException {
    List<Path> javaSources = new ArrayList<>();
    List<Path> auxiliaryFiles = new ArrayList<>();
    List<Path> unsupportedSources = new ArrayList<>();
    try (var paths = Files.walk(generatedSourcesDir)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".java")) {
                  javaSources.add(path);
                } else if (name.endsWith(".kt") || name.endsWith(".scala")) {
                  unsupportedSources.add(path);
                } else {
                  auxiliaryFiles.add(path);
                }
              });
    }
    if (!unsupportedSources.isEmpty()) {
      throw new CodeGenerationException(
          "Generated Kotlin/Scala sources are not supported yet: " + unsupportedSources);
    }
    Comparator<Path> relativeOrder =
        Comparator.comparing(path -> generatedSourcesDir.relativize(path).toString());
    javaSources.sort(relativeOrder);
    auxiliaryFiles.sort(relativeOrder);

    try (JarOutputStream jar =
        new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(sourceJar)))) {
      for (Path source : javaSources) {
        String entryName = generatedSourcesDir.relativize(source).toString().replace('\\', '/');
        JarEntry entry = new JarEntry(entryName);
        entry.setTime(DETERMINISTIC_ZIP_TIME);
        jar.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
          input.transferTo(jar);
        }
        jar.closeEntry();
      }
    }

    for (Path auxiliary : auxiliaryFiles) {
      Path relative = generatedSourcesDir.relativize(auxiliary);
      Path destination = auxiliaryOutputDir.resolve(relative);
      Files.createDirectories(destination.getParent());
      Files.copy(auxiliary, destination);
    }
  }

  static void copyStableWorkOutputs(Path workDir, Path workOutputDir) throws IOException {
    Files.createDirectories(workOutputDir);
    try (var paths = Files.walk(workDir)) {
      for (Path source :
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              // Providers commonly stage native tools and randomized wrapper
              // scripts in workDir. They are transient implementation details,
              // unlike descriptor sets and other stable non-executable products.
              .filter(path -> !Files.isExecutable(path))
              .sorted(Comparator.comparing(path -> workDir.relativize(path).toString()))
              .toList()) {
        Path destination = workOutputDir.resolve(workDir.relativize(source));
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
