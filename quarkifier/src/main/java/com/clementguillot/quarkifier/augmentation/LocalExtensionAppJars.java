package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.extension.ExtensionInfo;
import com.clementguillot.quarkifier.extension.ExtensionScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LocalExtensionAppJars {

  private LocalExtensionAppJars() {}

  /**
   * Moves locally-built Quarkus extension jars out of application roots and into runtime
   * dependencies.
   *
   * <p>A workspace jar carrying {@code META-INF/quarkus-extension.properties} is an extension
   * dependency, not application code. Leaving it as an application root would expose its classes
   * (e.g. {@code @ConfigRoot} mappings) to both the application and augment classloaders, breaking
   * build-time config-mapping lookup ({@code SRCFG00027}). The first local app jar (the application
   * artifact) is always kept as a root.
   */
  static AugmentationExecutor.ClasspathPartition reclassify(
      AugmentationExecutor.ClasspathPartition partition) throws IOException {
    List<Path> localAppJars = partition.localAppJars();
    if (localAppJars.size() <= 1) {
      return partition;
    }

    Set<Path> extensionJars = new HashSet<>();
    for (ExtensionInfo ext : ExtensionScanner.scan(localAppJars.subList(1, localAppJars.size()))) {
      extensionJars.add(ext.sourceJar());
    }
    if (extensionJars.isEmpty()) {
      return partition;
    }

    List<Path> appJars = localAppJars.stream().filter(jar -> !extensionJars.contains(jar)).toList();
    List<Path> runtimeJars = new ArrayList<>(partition.runtimeJars());
    // Append after original runtime jars to preserve their classpath order.
    runtimeJars.addAll(localAppJars.stream().filter(extensionJars::contains).toList());
    return new AugmentationExecutor.ClasspathPartition(appJars, runtimeJars);
  }
}
