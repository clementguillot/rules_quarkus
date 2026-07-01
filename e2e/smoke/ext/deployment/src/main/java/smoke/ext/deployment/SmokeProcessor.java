package smoke.ext.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import smoke.ext.runtime.SmokeConfig;
import smoke.ext.runtime.SmokeRecorder;
import smoke.ext.runtime.SmokeService;

class SmokeProcessor {

  private static final String FEATURE = "smoke-extension";

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
  }

  @BuildStep
  AdditionalBeanBuildItem registerBeans() {
    return AdditionalBeanBuildItem.unremovableOf(SmokeService.class);
  }

  @BuildStep
  @Record(ExecutionTime.STATIC_INIT)
  void configure(SmokeRecorder recorder, SmokeConfig config) {
    recorder.setPrefix(config.prefix());
  }
}
