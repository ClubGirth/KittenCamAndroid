package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.view.TextureView;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ZoomPresetTest {
  @Before
  public void permissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  @Test
  public void controllerMinimumPresetSendsPointSixAndKeepsOtherPresets() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        List<Float> commands = new ArrayList<>();
        LinearLayout buttons = a.zoomPresetRow(new ZoomLimits(.6f, 8), commands::add);
        assertEquals("0.6×", ((Button) buttons.getChildAt(0)).getText());
        assertEquals("1.0×", ((Button) buttons.getChildAt(1)).getText());
        assertEquals("3.0×", ((Button) buttons.getChildAt(2)).getText());
        for (int i = 0; i < 3; i++) buttons.getChildAt(i).performClick();
        assertEquals(Arrays.asList(.6f, 1f, 3f), commands);
        LinearLayout unknown =
            a.zoomPresetRow(
                ZoomLimits.UNKNOWN, value -> fail("Unknown minimum must not be guessed"));
        assertFalse(unknown.getChildAt(0).isEnabled());
      });
    }
  }

  @Test
  public void connectedFeedReadsCapabilitiesRatherThanCurrentZoomPosition() {
    ZoomLimits limits =
        ZoomLimits.fromMetadata(Wire.json("zoom", 3, "minimumZoom", .6, "maximumZoom", 8), null);
    assertEquals(.6f, limits.minimum, 0);
    assertEquals(8, limits.maximum, 0);
    assertFalse(ZoomLimits.fromMetadata(Wire.json("zoom", .5), null).hasMinimum());
    assertEquals(
        .5f,
        ZoomLimits.fromMetadata(Wire.json(), Wire.json("minZoom", .5, "maxZoom", 15)).minimum,
        0);
    assertEquals(
        .6f,
        ZoomLimits.fromMetadata(Wire.json("minimumZoom", .6), Wire.json("minimumZoom", .5)).minimum,
        0);
  }

  @Test
  public void legacyIphoneUsesHalfZoomUnlessActualMinimumIsReported() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        Feed iphone = new Feed(a, new LocalNetwork.Device("Living room iPhone"), new TextureView(a));
        a.feeds.add(iphone);
        iphone.status = Wire.json("zoom", 3);
        assertEquals(.5f, iphone.remoteZoomLimits().minimum, 0);
        List<Float> commands = new ArrayList<>();
        LinearLayout row = a.zoomPresetRow(iphone.remoteZoomLimits(), commands::add);
        assertEquals("0.5×", ((Button) row.getChildAt(0)).getText());
        row.getChildAt(0).performClick();
        assertEquals(Arrays.asList(.5f), commands);
        iphone.status = Wire.json("minimumZoom", .6, "maximumZoom", 8);
        assertEquals(.6f, iphone.remoteZoomLimits().minimum, 0);
        Feed android = new Feed(a, new LocalNetwork.Device("SM-G955U"), new TextureView(a));
        a.feeds.add(android);
        assertFalse(android.remoteZoomLimits().hasMinimum());
        a.cleanup();
      });
    }
  }

  @Test
  public void switchingCamerasUpdatesControllerMinimumPreset() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed first = new Feed(a, new LocalNetwork.Device("Zoom 0.6 fixture"), new TextureView(a));
        Feed second = new Feed(a, new LocalNetwork.Device("Zoom 0.5 fixture"), new TextureView(a));
        first.status =
            Wire.json(
                "resolution", "1080p", "fps", 30, "zoom", 3, "minimumZoom", .6, "maximumZoom", 8);
        second.status =
            Wire.json(
                "resolution", "1080p", "fps", 30, "zoom", 1, "minimumZoom", .5, "maximumZoom", 15);
        a.feeds.add(first);
        a.feeds.add(second);
        a.selected = first;
        a.devices();
        assertTrue(hasButton(a.panel, "0.6×"));
        a.selected = second;
        a.devices();
        assertTrue(hasButton(a.panel, "0.5×"));
        assertFalse(hasButton(a.panel, "0.6×"));
        a.leave();
      });
    }
  }

  static boolean hasButton(android.view.View view, String label) {
    if (view instanceof Button && ((Button) view).getText().toString().equals(label)) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasButton(group.getChildAt(i), label)) return true;
    }
    return false;
  }

  @Test
  public void viewingMinimumPresetMatchesCameraApiAndAdvertisedStatus() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Camera starts", () -> activity.get().host != null && activity.get().host.config != null);
      CameraHost host = activity.get().host;
      ZoomLimits expected = host.lensRouter.limits;
      assertEquals(expected.minimum, host.zoomLimits.minimum, 0);
      assertEquals(expected.minimum, (float) host.status(true, "test").optDouble("minimumZoom"), 0);
      assertEquals(expected.maximum, (float) host.information().optDouble("maximumZoom"), 0);
      scenario.onActivity(a -> {
        assertEquals(expected.minimumLabel(), a.cameraMinimumZoomButton.getText());
        assertTrue(a.cameraMinimumZoomButton.isEnabled());
        assertTrue(hasButton(a.root, "1.0×"));
        assertTrue(hasButton(a.root, "3.0×"));
        a.cameraMinimumZoomButton.performClick();
      });
      until(
          "Minimum zoom applies",
          () ->
              !host.lensSwitching
                  && host.config != null
                  && Math.abs(host.zoom - expected.minimum) < .001);
      android.util.Log.i(
          "KittenCamZoom",
          "Viewing minimum="
              + expected.minimum
              + " maximum="
              + expected.maximum
              + " preset="
              + expected.minimumLabel()
              + " passed");
      scenario.onActivity(MainActivity::leave);
    }
  }
}
