package smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class OtherTest {
  @Test
  void unaffectedTestStillRuns() {
    assertTrue(true);
  }
}
