package fixture;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class FlatTest {
  @Test
  void observesBazelOutputs() throws Exception {
    System.out.println("CONTINUOUS_OUTPUT");
    assertEquals("round trip", System.getProperty("fixture.property"));
    assertEquals("quote '$dollar `backtick` $(subshell)", System.getProperty("fixture.jvm"));
    assertEquals("value-v1/main-v1", Main.message());
    assertEquals("test-v1", smoke.generated.GeneratedTest.message());
    assertEquals("helper-v1", Helper.message());
    assertEquals("resource-v1", resource("input.txt"));
    assertNull(resource("undeclared.txt"));
    String added = resource("added.txt");
    if (added != null) {
      assertEquals("added-v1", added);
    }
  }

  private String resource(String name) throws Exception {
    try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
      return stream == null
          ? null
          : new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
  }
}
