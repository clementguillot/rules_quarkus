package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelReader;
import io.quarkus.bootstrap.model.ApplicationModel;

/** Loads the optional TEST-mode application model used by Quarkus continuous testing. */
final class ContinuousTestApplicationModelLoader {

  private ContinuousTestApplicationModelLoader() {}

  static ApplicationModel load(QuarkifierConfig config) throws Exception {
    if (config.testApplicationModel() == null) {
      return null;
    }
    var explicitModel = BazelApplicationModelReader.read(config.testApplicationModel());
    AugmentationExecutor.validateModelCompatibility(AugmentationMode.TEST, explicitModel);
    return ExplicitApplicationModelBuilder.build(explicitModel);
  }
}
