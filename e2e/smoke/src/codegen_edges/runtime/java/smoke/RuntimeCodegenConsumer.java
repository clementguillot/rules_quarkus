package smoke;

import smoke.generated.GeneratedRuntime;

final class RuntimeCodegenConsumer {

  private RuntimeCodegenConsumer() {}

  static String message() {
    return GeneratedRuntime.message();
  }
}
