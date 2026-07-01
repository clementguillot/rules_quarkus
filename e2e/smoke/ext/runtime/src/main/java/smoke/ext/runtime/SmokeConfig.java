package smoke.ext.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for the smoke extension. Exercises the {@code @ConfigRoot} path that
 * previously triggered SRCFG00027 when an extension jar leaked onto two classloaders.
 */
@ConfigMapping(prefix = "smoke.ext")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface SmokeConfig {

  /** Greeting prefix. */
  @WithDefault("Smoke")
  String prefix();
}
