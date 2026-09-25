package submodule;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SubmoduleService {
  public String value() {
    return "module-v1";
  }
}
