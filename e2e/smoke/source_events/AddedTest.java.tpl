package smoke.newpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AddedTest {
  @Test
  void newTestIsDiscovered() {
    assertEquals("expected", "deliberate failure");
  }
}
