package smoke;

import smoke.generated.GeneratedExported;

final class ExportedCodegenConsumer {

  private ExportedCodegenConsumer() {}

  static String message() {
    return GeneratedExported.message();
  }
}
