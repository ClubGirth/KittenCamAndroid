package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.hardware.camera2.*;
import android.net.nsd.NsdServiceInfo;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UltraWideLensTest {
  @Before
  public void permissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  @Test
  public void minimumOpensTheRealWideCameraAndBothOtherPresetsReturnToMain() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    AtomicReference<Feed> feed = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Main camera starts",
          () ->
              activity.get().host != null
                  && activity.get().host.config != null
                  && !activity.get().host.lensSwitching);
      CameraHost host = activity.get().host;
      boolean required =
          InstrumentationRegistry.getArguments()
              .getString("requireUltraWide", "false")
              .equals("true");
      if (required)
        assertTrue(
            "The known multi-lens device must expose its wider camera",
            host.zoomLimits.minimum < 1);
      org.junit.Assume.assumeTrue(
          "No separate ultra-wide lens on this device",
          host.zoomLimits.minimum < 1 && host.lensRouter.lenses.size() > 1);
      String main = host.activeCameraId;
      float minimum = host.zoomLimits.minimum;
      float mainFocal =
          host.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
      LocalNetwork.Device device = new LocalNetwork.Device("Own wide-camera test");
      for (int i = 0; i < 3; i++) {
        NsdServiceInfo e = new NsdServiceInfo();
        e.setHost(InetAddress.getLoopbackAddress());
        e.setPort(host.servers.get(i).getLocalPort());
        device.endpoints[i] = e;
      }
      scenario.onActivity(a -> {
        TextureView texture = new TextureView(a);
        Feed f = new Feed(a, device, texture);
        f.monitoring = false;
        f.error = error::set;
        feed.set(f);
        a.screen.addView(texture, new FrameLayout.LayoutParams(480, 320));
      });
      Feed f = feed.get();
      until("Main stream decodes", () -> f.decoded > 5);
      long before = f.decoded;
      scenario.onActivity(a -> a.cameraMinimumZoomButton.performClick());
      until(
          "Real wide camera opens",
          () ->
              !host.lensSwitching
                  && host.config != null
                  && !host.activeCameraId.equals(main)
                  && Math.abs(host.zoom - minimum) < .001);
      assertEquals(host.activeCameraId, host.camera.getId());
      float wideFocal =
          host.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
      assertTrue("A physically wider lens was selected", wideFocal < mainFocal);
      if (android.os.Build.VERSION.SDK_INT >= 30
          && host.characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
        assertEquals(
            host.activeLens.nativeLimits().minimum,
            host.request.get(CaptureRequest.CONTROL_ZOOM_RATIO),
            .0001f);
      until("Wide stream resumes decoding", () -> f.decoded > before + 5);
      assertEquals(minimum, (float) host.status(true, "test").optDouble("minimumZoom"), 0);
      String wide = host.activeCameraId;
      f.command(Wire.json("command", "smoothZoom", "zoom", 1));
      until(
          "1x returns to the main lens",
          () ->
              !host.lensSwitching
                  && host.config != null
                  && host.activeCameraId.equals(main)
                  && Math.abs(host.zoom - 1) < .001);
      f.command(Wire.json("command", "smoothZoom", "zoom", 3));
      until(
          "3x stays on the main lens",
          () ->
              !host.lensSwitching
                  && host.activeCameraId.equals(main)
                  && Math.abs(host.zoom - 3) < .001);
      if (android.os.Build.VERSION.SDK_INT >= 30
          && host.characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
        assertEquals(3f, host.request.get(CaptureRequest.CONTROL_ZOOM_RATIO), .001f);
      assertNull("No receiver errors while changing lenses", error.get());
      android.util.Log.i(
          "KittenCamLenses",
          "PASS: main camera="
              + main
              + " focal="
              + mainFocal
              + "; wide camera="
              + wide
              + " focal="
              + wideFocal
              + "; displayed minimum="
              + minimum
              + "; native wide zoom="
              + host.lensRouter.select(minimum).nativeZoom(minimum)
              + "; live decoder resumed; 1x/3x returned to main");
      scenario.onActivity(a -> {
        f.close();
        a.screen.removeView(f.view);
        a.leave();
      });
    } finally {
      if (feed.get() != null) feed.get().close();
    }
  }
}
