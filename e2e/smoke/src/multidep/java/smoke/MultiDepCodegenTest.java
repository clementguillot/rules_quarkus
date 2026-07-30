package smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import smoke.contracts.generated.Contract;
import smoke.grpc.generated.HelloRequest;

/**
 * Regression guard for test-application selection. Its quarkus_test names both //:lib and the
 * libraries //:lib already depends on, which is what strict deps force on any test importing types
 * from a shared contract library. Only //:lib may be promoted to the application under test.
 */
@QuarkusTest
class MultiDepCodegenTest {

  @Test
  void generatedTypesFromEveryDeclaredLibraryAreOnTheClasspath() {
    assertEquals("c-1", Contract.newBuilder().setId("c-1").build().getId());
    assertEquals("Bazel", HelloRequest.newBuilder().setName("Bazel").build().getName());
  }
}
