package com.clementguillot.quarkifier.augmentation;

import io.quarkus.bootstrap.model.ApplicationModel;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Selects the physical runtime jars that Quarkus packaging must consume. */
final class RuntimeJarSelector {

  private RuntimeJarSelector() {}

  /**
   * Includes model-selected conditional candidates, which intentionally are action inputs but are
   * not exposed on Bazel's public Java classpath.
   */
  static List<Path> select(
      AugmentationExecutor.ClasspathPartition partition, ApplicationModel appModel) {
    var result = new LinkedHashSet<>(partition.runtimeJars());
    for (var dependency : appModel.getDependencies()) {
      if (!dependency.isRuntimeCp() || dependency.isWorkspaceModule()) {
        continue;
      }
      for (Path path : dependency.getResolvedPaths()) {
        if (path.toString().endsWith(".jar")) {
          result.add(path);
        }
      }
    }
    return List.copyOf(result);
  }
}
