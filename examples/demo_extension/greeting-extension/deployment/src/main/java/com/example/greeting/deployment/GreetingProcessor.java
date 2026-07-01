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
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import java.util.List;
import java.util.Map;

class GreetingProcessor {

  private static final String FEATURE = "greeting";

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
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
