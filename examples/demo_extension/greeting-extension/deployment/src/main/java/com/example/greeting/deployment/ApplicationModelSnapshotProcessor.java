package com.example.greeting.deployment;

import io.quarkus.bootstrap.app.ApplicationModelSerializer;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionDevModeConfig;
import io.quarkus.bootstrap.model.JvmOptions;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

/** Test-only post-curation observer used to capture Maven and Gradle reference models. */
class ApplicationModelSnapshotProcessor {

  @BuildStep
  @Produce(ArtifactResultBuildItem.class)
  void snapshotApplicationModel(CurateOutcomeBuildItem curateOutcome) throws IOException {
    snapshot(curateOutcome);
  }

  static void snapshot(CurateOutcomeBuildItem curateOutcome) throws IOException {
    String output = System.getProperty("rules.quarkus.application-model.snapshot");
    if (output == null || output.isBlank()) {
      output = System.getenv("RULES_QUARKUS_APPLICATION_MODEL_SNAPSHOT");
    }
    if (output == null || output.isBlank()) {
      return;
    }
    ApplicationModel model = curateOutcome.getApplicationModel();
    makeGradleRoundTrippedDevConfigSerializable(model);
    ApplicationModelSerializer.serialize(model, Path.of(output));
  }

  /**
   * Gradle round-trips the model before augmentation. Quarkus 3.33.2 deserializes an empty
   * extension dev-mode JVM option set as {@code null}, while its JSON serializer assumes the value
   * is non-null. Restoring the semantically equivalent empty value keeps this oracle observational.
   */
  private static void makeGradleRoundTrippedDevConfigSerializable(ApplicationModel model) {
    for (ExtensionDevModeConfig config : model.getExtensionDevModeConfig()) {
      if (config.getJvmOptions() != null) {
        continue;
      }
      try {
        Field field = ExtensionDevModeConfig.class.getDeclaredField("jvmOptions");
        field.setAccessible(true);
        field.set(config, JvmOptions.builder().build());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(
            "Cannot normalize Quarkus 3.33.2's null Gradle extension dev-mode options", e);
      }
    }
  }
}
