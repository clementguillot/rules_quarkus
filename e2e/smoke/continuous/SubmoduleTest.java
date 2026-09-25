package submodule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SubmoduleTest {
  @Inject SubmoduleService service;

  @Test
  void observesSubmoduleOutputs() {
    assertEquals("module-v1", service.value());
  }
}
