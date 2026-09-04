package smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class StateTest {
  @Test
  void observesSource() {
    assertEquals("baseline", State.value());
  }
}
