package selected;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/** Packaged integration test inside a selected package: selectors must not pull it in. */
public class SelectedIT {
  @Test
  void mustNotRunContinuously() {
    fail("*IT tests were selected by continuous testing");
  }
}
