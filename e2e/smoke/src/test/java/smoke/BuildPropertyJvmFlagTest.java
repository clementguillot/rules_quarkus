package smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Certifies quoted test-launcher properties with spaces and colons in their names. */
class BuildPropertyJvmFlagTest {

  @Test
  void preservesCompletePropertyNameAsOneJvmArgument() {
    assertEquals("round trip", System.getProperty("declared: smoke key"));
    assertEquals(
        "quote '$dollar `backtick` $(subshell)", System.getProperty("declared.shell.value"));
  }
}
