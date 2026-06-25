package com.example.greeting.runtime;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class GreetingRecorder {

  public void setPrefix(String prefix) {
    GreetingService.setPrefix(prefix);
  }
}
