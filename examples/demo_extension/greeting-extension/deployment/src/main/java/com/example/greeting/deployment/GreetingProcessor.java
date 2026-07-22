package com.example.greeting.deployment;

import com.example.greeting.runtime.GreetingBuildTimeConfig;
import com.example.greeting.runtime.GreetingRecorder;
import com.example.greeting.runtime.GreetingService;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import java.util.List;
import java.util.Map;

class GreetingProcessor {

  private static final String FEATURE = "greeting";
  private static final String SNAPSHOT_ENV = "RULES_QUARKUS_APPLICATION_MODEL_SNAPSHOT";
  private static final String SNAPSHOT_PROPERTY = "rules.quarkus.application-model.snapshot";

  @BuildStep
  FeatureBuildItem feature(CurateOutcomeBuildItem curateOutcome) {
    captureReferenceModelIfRequested(curateOutcome);
    return new FeatureBuildItem(FEATURE);
  }

  /**
   * Maven test augmentation prunes packaging-only build steps, so the conformance observer is
   * called from this always-active feature step. Bazel intentionally excludes the observer source;
   * reflection keeps that build free of Maven-oracle-only dependencies.
   */
  private static void captureReferenceModelIfRequested(CurateOutcomeBuildItem curateOutcome) {
    String output = System.getProperty(SNAPSHOT_PROPERTY);
    if (output == null || output.isBlank()) {
      output = System.getenv(SNAPSHOT_ENV);
    }
    if (output == null || output.isBlank()) {
      return;
    }
    try {
      Class<?> observer =
          Class.forName(
              "com.example.greeting.deployment.ApplicationModelSnapshotProcessor",
              false,
              GreetingProcessor.class.getClassLoader());
      var method = observer.getDeclaredMethod("snapshot", CurateOutcomeBuildItem.class);
      method.setAccessible(true);
      method.invoke(null, curateOutcome);
    } catch (ClassNotFoundException ignored) {
      // Expected under Bazel: its native snapshot hook lives in quarkifier.
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot capture the reference ApplicationModel", e);
    }
  }

  @BuildStep
  AdditionalBeanBuildItem registerBeans() {
    return AdditionalBeanBuildItem.unremovableOf(GreetingService.class);
  }

  @BuildStep
  @Record(ExecutionTime.STATIC_INIT)
  void configureGreeting(GreetingRecorder recorder, GreetingBuildTimeConfig config) {
    recorder.setPrefix(config.prefix());
  }

  @BuildStep(onlyIf = IsLocalDevelopment.class)
  void devUI(BuildProducer<CardPageBuildItem> cardsProducer, GreetingBuildTimeConfig config) {
    CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();
    cardPageBuildItem.addPage(
        Page.webComponentPageBuilder()
            .icon("font-awesome-solid:hand")
            .title("Greetings")
            .componentLink("qwc-greeting-greetings.js"));
    cardPageBuildItem.addBuildTimeData(
        "greetings",
        List.of(
            Map.of("name", "World", "message", config.prefix() + " World!"),
            Map.of("name", "Quarkus", "message", config.prefix() + " Quarkus!"),
            Map.of("name", "Bazel", "message", config.prefix() + " Bazel!")));
    cardsProducer.produce(cardPageBuildItem);
  }
}
