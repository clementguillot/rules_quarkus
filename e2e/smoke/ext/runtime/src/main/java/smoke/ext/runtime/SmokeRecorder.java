package smoke.ext.runtime;

import io.quarkus.runtime.annotations.Recorder;

/** Records the build-time config prefix into the runtime service. */
@Recorder
public class SmokeRecorder {

  public void setPrefix(String prefix) {
    SmokeService.setPrefix(prefix);
  }
}
