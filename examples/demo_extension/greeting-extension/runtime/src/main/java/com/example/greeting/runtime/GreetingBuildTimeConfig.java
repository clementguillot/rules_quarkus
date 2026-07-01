package com.example.greeting.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Build time configuration for the greeting extension. */
@ConfigMapping(prefix = "quarkus.greeting")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface GreetingBuildTimeConfig {

  /** The greeting prefix. */
  @WithDefault("Hello")
  String prefix();
}
