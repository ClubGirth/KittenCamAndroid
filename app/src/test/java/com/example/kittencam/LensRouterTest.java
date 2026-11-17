package com.example.kittencam;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class LensRouterTest {
  @Test
  public void combinesOpticalMinimumWithMainCameraDigitalMaximum() {
    var main = new LensRouter.Lens("main", 1, new ZoomLimits(1, 8));
    var wide = new LensRouter.Lens("wide", .6f, new ZoomLimits(1, 8));
    var router = new LensRouter(main, Arrays.asList(main, wide));
    assertEquals(.6f, router.limits.minimum, 0);
    assertEquals(8, router.limits.maximum, 0);
    assertEquals("wide", router.select(.6f).id());
    assertEquals(1, wide.nativeZoom(.6f), 0);
    assertEquals("main", router.select(1).id());
    assertEquals("main", router.select(3).id());
    assertEquals(3, main.nativeZoom(3), 0);
  }

  @Test
  public void zoomBetweenLensStopsUsesTheWiderLensesNativeCrop() {
    var wide = new LensRouter.Lens("wide", .6f, new ZoomLimits(1, 8));
    assertEquals(1.5f, wide.nativeZoom(.9f), .0001f);
    assertEquals(.6f, wide.displayedZoom(.1f), 0);
  }

  @Test
  public void derivesS21WideStopFromOpticsWithoutPhoneModelConstants() {
    assertEquals(.6f, LensRouter.relativeBase(6.7f, 9.6f, 2.2f, 5.6f), 0);
    assertEquals(.5f, LensRouter.relativeBase(4, 6, 2, 6), 0);
    assertTrue(Float.isNaN(LensRouter.relativeBase(0, 6, 2, 6)));
  }

  @Test
  public void singleAndLogicalCamerasKeepTheirNativeZoomRanges() {
    var single = new LensRouter.Lens("old", 1, new ZoomLimits(1, 8));
    assertEquals(1, new LensRouter(single, Collections.singletonList(single)).limits.minimum, 0);
    var logical = new LensRouter.Lens("logical", 1, new ZoomLimits(.5f, 10));
    var router = new LensRouter(logical, Collections.singletonList(logical));
    assertEquals("logical", router.select(.5f).id());
    assertEquals(.5f, logical.nativeZoom(.5f), 0);
  }
}
