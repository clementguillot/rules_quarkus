package com.clementguillot.quarkifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link AugmentationMode}. */
class AugmentationModeTest {

  @Test
  void parse_normal() {
    assertEquals(AugmentationMode.NORMAL, AugmentationMode.parse("normal"));
  }

  @Test
  void parse_dev() {
    assertEquals(AugmentationMode.DEV, AugmentationMode.parse("dev"));
  }

  @Test
  void parse_test() {
    assertEquals(AugmentationMode.TEST, AugmentationMode.parse("test"));
  }

  @Test
  void parse_caseInsensitive() {
    assertEquals(AugmentationMode.NORMAL, AugmentationMode.parse("NORMAL"));
    assertEquals(AugmentationMode.DEV, AugmentationMode.parse("Dev"));
    assertEquals(AugmentationMode.TEST, AugmentationMode.parse("TEST"));
  }

  @Test
  void parse_invalidThrows() {
    var ex = assertThrows(IllegalArgumentException.class, () -> AugmentationMode.parse("invalid"));
    assertTrue(ex.getMessage().contains("invalid"));
  }
}
