package com.example.kittencam;

import static org.junit.Assert.*;

import org.junit.Test;

public class StreamOrientationTest {
  @Test
  public void landscapeNamesMatchTheWindowsControllersNamedRotationLookup() {
    assertEquals("landscapeLeft", StreamOrientation.fromClockwise(0).wireName);
    assertEquals("landscapeRight", StreamOrientation.fromClockwise(180).wireName);
    assertNotEquals(StreamOrientation.fromClockwise(0), StreamOrientation.fromClockwise(180));
  }

  @Test
  public void allFourDirectionsHaveConsistentNamesAndInverseAngles() {
    String[] names = {"landscapeLeft", "portrait", "landscapeRight", "portraitUpsideDown"};
    for (int i = 0; i < 4; i++) {
      StreamOrientation orientation = StreamOrientation.fromClockwise(i * 90);
      assertEquals(names[i], orientation.wireName);
      assertEquals(i * 90, orientation.clockwise);
      assertEquals(0, (orientation.clockwise + orientation.counterclockwise) % 360);
      assertEquals(i % 2 == 1, orientation.portrait);
    }
  }

  @Test
  public void normalizesSignedAnglesAndRejectsNonQuarterTurns() {
    assertSame(StreamOrientation.PORTRAIT_UPSIDE_DOWN, StreamOrientation.fromClockwise(-90));
    assertSame(StreamOrientation.LANDSCAPE_LEFT, StreamOrientation.fromClockwise(360));
    assertThrows(IllegalArgumentException.class, () -> StreamOrientation.fromClockwise(45));
  }

  @Test
  public void legacyIosLandscapeNamesOverrideTheirConflictingNumericAngles() {
    assertEquals(0, StreamOrientation.resolveDisplayRotation("landscapeLeft", 180));
    assertEquals(180, StreamOrientation.resolveDisplayRotation("landscapeRight", 0));
    assertEquals(90, StreamOrientation.resolveDisplayRotation("portrait", 90));
    assertEquals(270, StreamOrientation.resolveDisplayRotation("portraitUpsideDown", 270));
  }

  @Test
  public void androidNamesAndNumericOnlyPeersKeepTheirExistingRotations() {
    for (StreamOrientation orientation : StreamOrientation.values())
      assertEquals(
          orientation.clockwise,
          StreamOrientation.resolveDisplayRotation(orientation.wireName, orientation.clockwise));
    assertEquals(180, StreamOrientation.resolveDisplayRotation(null, 180));
    assertEquals(270, StreamOrientation.resolveDisplayRotation("unknown", -90));
  }
}
