package com.example.kittencam;

import static org.junit.Assert.*;

import org.junit.Test;

public class ZoomLimitsTest {
  @Test
  public void usesExactMinimumForCommandsAndReadablePresetLabel() {
    ZoomLimits limits = new ZoomLimits(.6f, 8);
    assertEquals("0.6×", limits.minimumLabel());
    assertEquals(.6f, limits.clamp(.5f), 0);
    assertEquals(1, limits.clamp(1), 0);
    assertEquals(3, limits.clamp(3), 0);
    assertEquals(8, limits.clamp(15), 0);
  }

  @Test
  public void handlesDifferentCameraMinimumsWithoutModelNames() {
    assertEquals("0.5×", new ZoomLimits(.5f, 15).minimumLabel());
    assertEquals("0.625×", new ZoomLimits(.625f, 10).minimumLabel());
    assertEquals("1×", new ZoomLimits(1, 8).minimumLabel());
    assertEquals("0.6×", new ZoomLimits(.59999996f, 8).minimumLabel());
  }

  @Test
  public void unknownOrInvalidBoundsDoNotBecomeFalseCapabilities() {
    assertFalse(ZoomLimits.UNKNOWN.hasMinimum());
    assertEquals("Min", ZoomLimits.UNKNOWN.minimumLabel());
    assertFalse(new ZoomLimits(0, 8).hasMinimum());
    assertFalse(new ZoomLimits(Float.POSITIVE_INFINITY, 8).hasMinimum());
    assertFalse(new ZoomLimits(3, 1).hasMinimum());
    assertFalse(new ZoomLimits(3, 1).hasMaximum());
  }
}
