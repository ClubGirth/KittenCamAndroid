package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaCodec;
import android.os.Build;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercise the camera currently attached to the test activity, never another network camera. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 28)
public class HardwareCapabilitiesTest {
  @org.junit.Before
  public void grantMediaPermissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  interface Operation {
    void run() throws Exception;
  }

  void camera(CameraHost host, Operation op) throws Exception {
    FutureTask<Void> task =
        new FutureTask<>(() -> {
          op.run();
          return null;
        });
    host.handler.post(task);
    task.get(10, TimeUnit.SECONDS);
  }

  int[] ratesToTest(CameraHost host, String resolution) {
    java.util.SortedSet<Integer> rates =
        new java.util.TreeSet<>(java.util.Arrays.asList(24, 30, 50, 60));
    rates.addAll(host.videoFormats.frameRates(resolution));
    return rates.stream().mapToInt(Integer::intValue).toArray();
  }

  @Test
  public void exposedZoomTorchAndVideoFormats() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    AtomicReference<String> failure = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Camera starts", () -> activity.get().host != null && activity.get().host.config != null);
      CameraHost host = activity.get().host;
      host.error = failure::set;
      var c = host.characteristics;
      var map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      Log.i(
          "KittenCamHardware",
          "model="
              + Build.MODEL
              + " zoom="
              + (Build.VERSION.SDK_INT >= 30
                  ? c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                  : "digital 1–" + c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
              + " fpsRanges="
              + Arrays.toString(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
              + " torchStrength="
              + host.supportsTorchStrength()
              + " sizes="
              + Arrays.toString(map.getOutputSizes(MediaCodec.class)));
      for (float z : new float[] {.5f, 1, 3}) {
        camera(host, () -> host.setZoom(z));
        until("Zoom lens is ready", () -> !host.lensSwitching && host.config != null);
        Log.i("KittenCamHardware", "zoomRequested=" + z + " applied=" + host.zoom);
      }
      camera(host, () -> host.setZoom(1));
      until("Main lens is ready", () -> !host.lensSwitching && host.config != null);
      if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
        try {
          camera(host, () -> host.apply(Wire.json("command", "setTorch", "enabled", true)));
          Thread.sleep(350);
          assertTrue(host.torch);
          Log.i("KittenCamHardware", "torch=on/off passed");
        } finally {
          camera(host, () -> host.apply(Wire.json("command", "setTorch", "enabled", false)));
        }
      }
      for (String resolution : new String[] {"720p", "1080p", "1440p", "4K"})
        for (int fps : ratesToTest(host, resolution)) {
          boolean accepted;
          try {
            camera(host, () -> host.changeFormat(resolution, fps));
            accepted = true;
          } catch (java.util.concurrent.ExecutionException e) {
            if (!(e.getCause() instanceof java.io.IOException)) throw e;
            accepted = false;
            Log.i(
                "KittenCamHardware",
                resolution + "@" + fps + " rejected: " + e.getCause().getMessage());
          }
          if (accepted) {
            until(
                "Video configuration for " + resolution + "@" + fps + " error=" + failure.get(),
                () -> host.config != null || failure.get() != null);
            assertNull("Camera format failed: " + resolution + "@" + fps, failure.get());
            assertNotNull(host.config);
            android.util.Range<Integer> requestedRange =
                host.request.get(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
            assertNotNull("Camera exposure FPS range", requestedRange);
            assertTrue(
                "Camera's actual request must include the advertised frame rate",
                requestedRange.contains(fps));
            Log.i(
                "KittenCamHardware",
                resolution + "@" + fps + " encoding started " + host.width + "x" + host.height);
          }
        }
      camera(host, () -> host.changeFormat("1080p", 30));
      until("Restore default format", () -> host.config != null);
      scenario.onActivity(MainActivity::leave);
    }
  }
}
