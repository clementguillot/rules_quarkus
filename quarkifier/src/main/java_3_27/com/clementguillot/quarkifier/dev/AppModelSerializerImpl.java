package com.clementguillot.quarkifier.dev;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.util.BootstrapUtils;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Quarkus 3.27 implementation using {@code BootstrapUtils.serializeAppModel()} (Java Object
 * Serialization). This format is compatible with the {@code BootstrapAppModelFactory} in Quarkus
 * 3.27 which reads the model using {@code ObjectInputStream}.
 */
public final class AppModelSerializerImpl implements AppModelSerializerStrategy {

  @Override
  public Path serialize(ApplicationModel appModel) throws IOException {
    try {
      return BootstrapUtils.serializeAppModel(appModel, false);
    } catch (Exception e) {
      throw new IOException("Failed to serialize ApplicationModel", e);
    }
  }
}
