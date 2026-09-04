package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelReader;
import io.quarkus.bootstrap.model.ApplicationModel;

/** Loads the optional TEST-mode application model used by Quarkus continuous testing. */
final class ContinuousTestApplicationModelLoader {

  private ContinuousTestApplicationModelLoader() {}

  static ApplicationModel load(QuarkifierConfig config, BazelApplicationModel applicationModel)
      throws Exception {
    if (config.testApplicationModel() == null) {
      return null;
    }
    var explicitModel = BazelApplicationModelReader.read(config.testApplicationModel());
    AugmentationExecutor.validateModelCompatibility(AugmentationMode.TEST, explicitModel);
    validateRelationship(applicationModel, explicitModel);
    return ExplicitApplicationModelBuilder.build(explicitModel);
  }

  static void validateRelationship(
      BazelApplicationModel applicationModel, BazelApplicationModel testModel)
      throws AugmentationException {
    BazelApplicationModel.Node devApplication = applicationNode(applicationModel, "Dev");
    BazelApplicationModel.Node testApplication = applicationNode(testModel, "Continuous-test");
    if (!devApplication.id().equals(testApplication.id())
        || !devApplication.bazelLabel().equals(testApplication.bazelLabel())) {
      throw new AugmentationException(
          "Continuous-test model application '"
              + testApplication.bazelLabel()
              + "' does not match dev application '"
              + devApplication.bazelLabel()
              + "'");
    }
  }

  private static BazelApplicationModel.Node applicationNode(
      BazelApplicationModel model, String description) throws AugmentationException {
    return model.nodes().stream()
        .filter(node -> node.id().equals(model.applicationId()))
        .findFirst()
        .orElseThrow(
            () ->
                new AugmentationException(
                    description + " model application node is missing: " + model.applicationId()));
  }
}
