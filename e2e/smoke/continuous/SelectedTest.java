package selected;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class SelectedTest {
  @Test
  void packageSelectorIsPreserved() {
    assertEquals("round trip", System.getProperty("fixture.property"));
  }
}
