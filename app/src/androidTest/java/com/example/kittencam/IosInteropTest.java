package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.os.Build;
import android.util.Log;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in, read-only check against an existing iOS camera. Never run as part of a default suite. */
@RunWith(AndroidJUnit4.class)
public class IosInteropTest {
  @Test
  public void receiveExistingIosCameraWithoutChangingItsSettings() throws Exception {
    String target =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("iosCameraName");
    org.junit.Assume.assumeTrue(
        "Pass iosCameraName explicitly to opt into a live camera test",
        target != null && !target.isEmpty());
    assertNotEquals(Build.MODEL, target);
    new VirtualDeviceTest().grantMediaPermissions();
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    AtomicReference<Feed> feed = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("control");
      });
      until(
          "Discover the explicitly selected iOS camera",
          () ->
              activity.get().network.devices.containsKey(target)
                  && activity.get().network.devices.get(target).ready());
      MainActivity a = activity.get();
      LocalNetwork.Device d = a.network.devices.get(target);
      scenario.onActivity(current -> {
        TextureView texture = new TextureView(current);
        Feed f = new Feed(current, d, texture);
        f.monitoring = false;
        f.error = error::set;
        feed.set(f);
        current.screen.addView(texture, new FrameLayout.LayoutParams(720, 960));
      });
      Feed f = feed.get();
      until("Decode iOS H.264 video", () -> f.decoded >= 30);
      until("Read camera status", () -> f.status.optBoolean("success"));
      if (f.status.optBoolean("microphoneEnabled"))
        until("Receive iOS microphone packets", () -> f.audioFrames >= 10);
      assertNull("Unexpected iOS stream error", error.get());
      assertNotNull("iPhone format controls must be available", f.remoteFormats());
      scenario.onActivity(current -> {
        VideoFormatPicker picker =
            new VideoFormatPicker(
                current,
                f.remoteFormats(),
                f.status.optString("resolution", "1080p"),
                f.status.optInt("fps", 30),
                (resolution, fps) ->
                    fail("Read-only interoperability must not issue format changes"));
        assertNotNull(picker.resolutionPicker.getAdapter());
        assertNotNull(picker.frameRatePicker.getAdapter());
      });
      Log.i(
          "KittenCamInterop",
          "iPhone format controls: legacy="
              + f.usesLegacyIosFormats()
              + " statusFields="
              + f.status.names()
              + " streamFields="
              + f.streamInformation.names());
      Log.i(
          "KittenCamInterop",
          "Read-only iOS interoperability passed: video="
              + f.frames
              + " decoded="
              + f.decoded
              + " audio="
              + f.audioFrames
              + " dropped="
              + f.dropped
              + " format="
              + f.width
              + "x"
              + f.height
              + " rotation="
              + f.rotation
              + " orientation="
              + f.streamInformation.optString("orientation")
              + " advertisedRotation="
              + f.streamInformation.optInt("rotationDegreesClockwise", -1)
              + "; no commands or talkback sent");
      assertEquals(
          "The Android viewer uses the Windows-compatible orientation interpretation",
          StreamOrientation.displayRotation(f.streamInformation),
          f.rotation);
      if ("true"
          .equals(
              androidx.test.platform.app.InstrumentationRegistry.getArguments()
                  .getString("recordWithAudio"))) {
        assertTrue(
            "iPhone microphone must already be enabled", f.status.optBoolean("microphoneEnabled"));
        until("iPhone PCM configuration received", () -> f.monitoringConfiguration != null);
        scenario.onActivity(current -> f.startRecording(true));
        until("Recording begins at an iPhone keyframe", () -> f.recordStart >= 0);
        long before = f.frames;
        until("Record an iPhone clip", () -> f.frames > before + 60);
        scenario.onActivity(current -> f.stopRecording());
        until("iPhone clip published to Gallery", () -> f.savedRecordingUri != null);
        android.net.Uri clip = f.savedRecordingUri;
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
          extractor.setDataSource(a, clip, null);
          assertEquals(2, extractor.getTrackCount());
          int audio = -1;
          for (int i = 0; i < extractor.getTrackCount(); i++)
            if ("audio/mp4a-latm"
                .equals(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)))
              audio = i;
          assertTrue(audio >= 0);
          extractor.selectTrack(audio);
          VirtualDeviceTest.assertAudioDecodes(extractor, extractor.getTrackFormat(audio));
          assertFalse("Monitoring remains muted while recording audio", f.monitoring);
          android.util.Log.i(
              "KittenCamInterop",
              "iPhone recording: H.264 + decodable AAC, monitoring muted; "
                  + f.monitoringConfiguration);
        } finally {
          extractor.release();
          a.getContentResolver().delete(clip, null, null);
        }
        assertNull("iPhone recording error", error.get());
      }
      scenario.onActivity(current -> {
        f.close();
        current.screen.removeView(f.view);
        current.leave();
      });
    } finally {
      if (feed.get() != null) feed.get().close();
    }
  }
}
