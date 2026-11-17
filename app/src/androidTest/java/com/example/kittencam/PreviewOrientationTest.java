package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.content.pm.ActivityInfo;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PreviewOrientationTest {
  @Before
  public void permissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  @Test
  public void sensorCorrectionIsNotAppliedTwice() {
    // A sensor-corrected upright marker must stay upright in portrait. On rotating the
    // display, its right-pointing vector moves counterclockwise, not by another sensor turn.
    for (int sensor : new int[] {0, 90, 180, 270})
      for (int display : new int[] {0, 90, 180, 270}) {
        Matrix matrix =
            CameraHost.previewTransform(
                display % 180 == 0 ? 1080 : 2200,
                display % 180 == 0 ? 2200 : 1080,
                1920,
                1080,
                sensor,
                display);
        float[] right = {1, 0};
        matrix.mapVectors(right);
        double angle = Math.toDegrees(Math.atan2(right[1], right[0]));
        double difference = ((angle + display + 540) % 360) - 180;
        assertEquals("sensor=" + sensor + " display=" + display, 0, difference, .001);
        float[] center = {display % 180 == 0 ? 540 : 1100, display % 180 == 0 ? 1100 : 540};
        float[] mapped = center.clone();
        matrix.mapPoints(mapped);
        assertArrayEquals(center, mapped, .001f);
      }
  }

  @Test
  public void cameraPreviewUpdatesInPortraitAndBothLandscapeDirections() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Camera starts", () -> activity.get().host != null && activity.get().host.config != null);
      int[] orientations = {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      };
      int[] rotations = {0, 1, 3, 2, 0};
      for (int i = 0; i < orientations.length; i++) {
        int requested = orientations[i], expectedRotation = rotations[i];
        scenario.onActivity(a -> a.setRequestedOrientation(requested));
        until(
            "Display rotates",
            () ->
                activity.get().getWindowManager().getDefaultDisplay().getRotation()
                    == expectedRotation);
        Thread.sleep(600);
        scenario.onActivity(a -> {
          CameraHost host = a.host;
          assertNotNull(host);
          int sensor = host.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
          assertEquals(
              "Network metadata still describes the raw encoded buffer",
              (sensor - expectedRotation * 90 + 360) % 360,
              host.rotation);
          org.json.JSONObject packet = host.information();
          String[] names = {"landscapeLeft", "portrait", "landscapeRight", "portraitUpsideDown"};
          int degrees = (sensor - expectedRotation * 90 + 360) % 360;
          assertEquals(names[degrees / 90], packet.optString("orientation"));
          assertEquals(packet.optString("orientation"), packet.optString("deviceOrientation"));
          assertEquals(packet.optString("orientation"), packet.optString("videoOrientation"));
          assertEquals(degrees, packet.optInt("rotationDegreesClockwise"));
          assertEquals((360 - degrees) % 360, packet.optInt("rotationDegreesCounterClockwise"));
          assertEquals(degrees % 180 != 0, packet.optBoolean("isPortrait"));
          assertEquals(degrees % 180 != 0, packet.optBoolean("requiresDimensionSwap"));
          float[] expected = new float[9], actual = new float[9];
          CameraHost.previewTransform(
                  host.preview.getWidth(),
                  host.preview.getHeight(),
                  host.width,
                  host.height,
                  sensor,
                  expectedRotation * 90)
              .getValues(expected);
          host.preview.getTransform(new Matrix()).getValues(actual);
          assertArrayEquals(
              "The resized preview has the current display transform", expected, actual, .01f);
          Log.i(
              "KittenCamOrientation",
              "display="
                  + (expectedRotation * 90)
                  + " sensor="
                  + sensor
                  + " rawStreamRotation="
                  + host.rotation
                  + " previewRotation="
                  + (-expectedRotation * 90)
                  + " passed");
        });
      }
      scenario.onActivity(MainActivity::leave);
    }
  }
}
