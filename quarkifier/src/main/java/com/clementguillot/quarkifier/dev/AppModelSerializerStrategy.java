package com.clementguillot.quarkifier.dev;

import io.quarkus.bootstrap.model.ApplicationModel;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy for serializing an {@link ApplicationModel} to a temp file.
 *
 * <p>Quarkus 3.31+ uses JSON-based serialization via {@code ApplicationModelSerializer}, while
 * earlier versions use Java Object Serialization via {@code BootstrapUtils}. This interface allows
 * the {@link DevModeLauncher} to work with both.
 */
public interface AppModelSerializerStrategy {

  /**
   * Serializes the application model to a temporary file.
   *
   * @param appModel the application model to serialize
   * @return path to the serialized file
   * @throws IOException if serialization fails
   */
  Path serialize(ApplicationModel appModel) throws IOException;
}
