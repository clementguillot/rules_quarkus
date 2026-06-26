package smoke.ext.runtime;

import jakarta.enterprise.context.ApplicationScoped;

/** Runtime CDI bean provided by the smoke extension; its prefix is set by the recorder. */
@ApplicationScoped
public class SmokeService {

  private static volatile String prefix = "Smoke";

  static void setPrefix(String prefix) {
    SmokeService.prefix = prefix;
  }

  public String greet(String name) {
    return prefix + ", " + name + "!";
  }
}
