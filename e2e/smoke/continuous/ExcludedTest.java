package excluded;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

public class ExcludedTest {
  @Test
  void mustNotRun() {
    fail("class/package selectors were lost");
  }
}
