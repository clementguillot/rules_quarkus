package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.dev.AppModelSerializerImpl;
import com.clementguillot.quarkifier.dev.AppModelSerializerStrategy;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeSet;

/** Writes the model and additional application roots consumed by the one-shot test launcher. */
final class ApplicationTestModelWriter {

  private ApplicationTestModelWriter() {}

  /** Writes the serialized test ApplicationModel to {@code <output-dir>/test-app-model.dat}. */
  static void write(Path outputDir, ApplicationModel appModel) throws Exception {
    Path modelFile = outputDir.resolve("test-app-model.dat");
    AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
    Path serializedModel = serializer.serialize(appModel);
    if (!serializedModel.equals(modelFile)) {
      Files.copy(serializedModel, modelFile, StandardCopyOption.REPLACE_EXISTING);
      Files.deleteIfExists(serializedModel);
    }
    // QuarkusTest adds the app artifact itself. Additional local modules must
    // be scanned for endpoints/beans using the same paths as the model, rather
    // than sandbox runfiles aliases that can index the app artifact twice.
    var additionalRoots = new TreeSet<String>();
    for (var dependency : appModel.getDependencies()) {
      if (dependency.getWorkspaceModule() != null
          && dependency.isRuntimeCp()
          && !dependency.isRuntimeExtensionArtifact()) {
        for (Path path : dependency.getResolvedPaths()) {
          additionalRoots.add(path.toString());
        }
      }
    }
    Files.writeString(
        outputDir.resolve("test-additional-app-roots.txt"), String.join(",", additionalRoots));
  }
}
