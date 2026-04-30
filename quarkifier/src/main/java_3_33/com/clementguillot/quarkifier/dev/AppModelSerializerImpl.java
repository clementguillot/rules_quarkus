package com.clementguillot.quarkifier.dev;

import io.quarkus.bootstrap.app.ApplicationModelSerializer;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Quarkus 3.33 implementation using {@code ApplicationModelSerializer.serialize()} (JSON format by
 * default). This is required because Quarkus 3.31+ changed {@code
 * BootstrapAppModelFactory.loadFromSystemProperty()} to use {@code
 * ApplicationModelSerializer.deserialize()} which expects JSON, not Java Object Serialization.
 */
public final class AppModelSerializerImpl implements AppModelSerializerStrategy {

  @Override
  public Path serialize(ApplicationModel appModel) throws IOException {
    return ApplicationModelSerializer.serialize(appModel, false);
  }
}
