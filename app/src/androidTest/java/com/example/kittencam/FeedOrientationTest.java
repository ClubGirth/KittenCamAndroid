package com.example.kittencam;

import static org.junit.Assert.*;

import android.graphics.Matrix;
import android.view.TextureView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FeedOrientationTest {
  @Test
  public void iosLandscapePacketsApplyTheSameRotationAsWindows() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        TextureView texture = new TextureView(a);
        texture.layout(0, 0, 640, 360);
        Feed feed = new Feed(a, new LocalNetwork.Device("Orientation fixture"), texture);
        try {
          String[] names = {"landscapeLeft", "landscapeRight", "portrait", "portraitUpsideDown"};
          int[] iosAngles = {180, 0, 90, 270};
          int[] expected = {0, 180, 90, 270};
          for (int i = 0; i < names.length; i++) {
            feed.handle(
                new Wire.Packet(
                    1,
                    Wire.bytes(
                        Wire.json(
                            "orientation",
                            names[i],
                            "rotationDegreesClockwise",
                            iosAngles[i],
                            "encodedWidth",
                            1920,
                            "encodedHeight",
                            1080))));
            assertEquals(names[i], expected[i], feed.rotation);
            float[] vector = {1, 0};
            texture.getTransform(new Matrix()).mapVectors(vector);
            double angle = Math.toDegrees(Math.atan2(vector[1], vector[0]));
            double error = ((angle - expected[i] + 540) % 360) - 180;
            assertEquals("Rendered feed transform for " + names[i], 0, error, .001);
          }
        } catch (Exception e) {
          throw new AssertionError(e);
        } finally {
          feed.close();
        }
      });
    }
  }

  @Test
  public void numericFallbackDoesNotMistakeEncoderBasisForDisplayOrientation() {
    assertEquals(
        0,
        StreamOrientation.displayRotation(
            Wire.json("encodedOrientation", "landscapeRight", "rotationDegreesClockwise", 0)));
    assertEquals(270, StreamOrientation.displayRotation(Wire.json("displayRotationDegrees", -90)));
    assertEquals(
        180,
        StreamOrientation.displayRotation(
            Wire.json("deviceOrientation", "landscapeRight", "rotationDegrees", 0)));
  }
}
