package smoke;

import smoke.dependency.DependencyMessage;

final class DependencyGeneratedConsumer {

  private DependencyGeneratedConsumer() {}

  static String value() {
    return DependencyMessage.newBuilder().setValue("dependency").build().getValue();
  }
}
