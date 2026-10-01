package com.clementguillot.quarkifier.dev;

import io.quarkus.bootstrap.model.ApplicationModel;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy for serializing an {@link ApplicationModel} to a temp file.
 *
 * <p>The serialized format must match what the targeted Quarkus minor's {@code
 * BootstrapAppModelFactory} reads back; every supported minor reads JSON from {@code
 * ApplicationModelSerializer}. This interface keeps that choice out of {@link DevModeLauncher}.
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
