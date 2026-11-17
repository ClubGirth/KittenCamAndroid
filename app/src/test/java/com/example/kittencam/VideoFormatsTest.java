package com.example.kittencam;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class VideoFormatsTest {
  static final VideoFormats.Resolution HD = new VideoFormats.Resolution("720p", 1280, 720);
  static final VideoFormats.Resolution UHD = new VideoFormats.Resolution("4K", 3840, 2160);

  @Test
  public void legacyIosCommandsMatchWindowsMenuOptions() {
    assertArrayEquals(
        new String[] {"720p", "1080p", "1440p", "4K"}, VideoFormats.LEGACY_IOS.resolutions());
    for (String resolution : VideoFormats.LEGACY_IOS.resolutions())
      assertEquals(Arrays.asList(24, 30, 50, 60), VideoFormats.LEGACY_IOS.frameRates(resolution));
  }

  @Test
  public void intersectsResolutionSpecificCameraAndEncoderLimits() {
    VideoFormats formats =
        VideoFormats.discover(
            Arrays.asList(HD, UHD),
            Arrays.asList(
                new VideoFormats.RateRange(24, 24),
                new VideoFormats.RateRange(30, 30),
                new VideoFormats.RateRange(60, 60)),
            (resolution, fps) -> resolution.width() < 3840 || fps <= 30);
    assertEquals(Arrays.asList(24, 30, 60), formats.frameRates("720p"));
    assertEquals(Arrays.asList(24, 30), formats.frameRates("4K"));
    assertFalse(formats.supports("4K", 60));
    assertFalse(formats.supports("1440p", 30));
  }

  @Test
  public void omitsResolutionsThatHaveNoUsableFrameRates() {
    VideoFormats formats =
        VideoFormats.discover(
            Arrays.asList(HD, UHD),
            Collections.singletonList(new VideoFormats.RateRange(30, 30)),
            (resolution, fps) -> resolution.width() < 3840);
    assertArrayEquals(new String[] {"720p"}, formats.resolutions());
    assertNull(formats.find("4K"));
  }

  @Test
  public void variableRangeDoesNotInventIntermediateFrameRates() {
    VideoFormats formats =
        VideoFormats.discover(
            Collections.singletonList(HD),
            Arrays.asList(new VideoFormats.RateRange(7, 30), new VideoFormats.RateRange(15, 15)),
            (resolution, fps) -> true);
    assertEquals(Arrays.asList(15, 30), formats.frameRates("720p"));
    assertFalse(formats.supports("720p", 24));
  }

  @Test
  public void changingResolutionChoosesAValidNearbyRate() {
    VideoFormats formats =
        VideoFormats.discover(
            Arrays.asList(HD, UHD),
            Arrays.asList(
                new VideoFormats.RateRange(24, 24),
                new VideoFormats.RateRange(30, 30),
                new VideoFormats.RateRange(60, 60)),
            (resolution, fps) -> resolution.width() < 3840 || fps <= 30);
    assertEquals(30, formats.closestRate("4K", 60));
    assertEquals(24, formats.closestRate("4K", 24));
    assertTrue(formats.supports("2160p", 30));
  }

  @Test
  public void handlesLowResolutionDevicesAndEmptyCapabilities() {
    VideoFormats.Resolution vga = new VideoFormats.Resolution("640×480", 640, 480);
    VideoFormats formats =
        VideoFormats.discover(
            Collections.singletonList(vga),
            Collections.singletonList(new VideoFormats.RateRange(15, 15)),
            (resolution, fps) -> true);
    assertTrue(formats.supports("640×480", 15));
    assertEquals(15, formats.closestRate("640×480", 30));
    assertTrue(
        VideoFormats.discover(
                Collections.singletonList(vga), Collections.emptyList(), (r, f) -> true)
            .isEmpty());
  }
}
