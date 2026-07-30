package smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import smoke.generated.GeneratedTest;

class CodegenTest {

  @Test
  void generatedTestSourceIsCompiled() {
    assertEquals("Configured Test: test code generation", GeneratedTest.message());
  }
}
