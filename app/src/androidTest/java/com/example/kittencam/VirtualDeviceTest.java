package com.example.kittencam;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device integration tests; use a real camera on hardware and a synthetic scene on emulators. */
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceTest {
  @Before
  public void grantMediaPermissions() {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    automation.grantRuntimePermission("com.example.kittencam", Manifest.permission.CAMERA);
    automation.grantRuntimePermission("com.example.kittencam", Manifest.permission.RECORD_AUDIO);
    if (android.os.Build.VERSION.SDK_INT <= 28) {
      automation.grantRuntimePermission(
          "com.example.kittencam", Manifest.permission.READ_EXTERNAL_STORAGE);
      automation.grantRuntimePermission(
          "com.example.kittencam", Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
  }

  static void until(String message, BooleanSupplier condition) throws Exception {
    long deadline = System.currentTimeMillis() + 20000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(100);
    assertTrue(message, condition.getAsBoolean());
  }

  @Test
  public void modeSelectionDiscoveryLayoutAndRotation() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        assertEquals("", a.mode);
        a.choose("control");
        assertEquals("control", a.mode);
        assertNotNull(a.network);
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      });
      Thread.sleep(1500);
      scenario.onActivity(a -> {
        assertEquals("control", a.mode);
        assertNotNull(a.feedArea);
        assertTrue(a.split.getWidth() > a.split.getHeight());
        a.leave();
        assertEquals("", a.mode);
        assertNull(a.network);
      });
    }
  }

  static boolean isHostedDevice(LocalNetwork.Device d, CameraHost host) {
    if (!d.name.equals(android.os.Build.MODEL)) return false;
    for (int i = 0; i < 3; i++)
      if (d.endpoints[i] == null || d.endpoints[i].getPort() != host.servers.get(i).getLocalPort())
        return false;
    return true;
  }

  @Test
  public void cameraToControllerVideoCommandsRecordingAndCleanup() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    AtomicReference<Feed> feed = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Camera must produce an H.264 configuration",
          () -> activity.get().host != null && activity.get().host.config != null);
      MainActivity a = activity.get();
      CameraHost host = a.host;
      assertEquals(3, host.servers.size());
      LocalNetwork.Device device;
      try (LocalNetwork browser = new LocalNetwork(a)) {
        browser.discover();
        until(
            "mDNS must discover all three camera services",
            () -> browser.devices.values().stream().anyMatch(d -> isHostedDevice(d, host)));
        device =
            browser.devices.values().stream()
                .filter(d -> isHostedDevice(d, host))
                .findFirst()
                .get();
      }
      scenario.onActivity(current -> {
        TextureView texture = new TextureView(current);
        Feed f = new Feed(current, device, texture);
        f.monitoring =
            false; // Avoid acoustic feedback when both ends run on the same physical phone.
        f.error =
            value -> {
              error.set(value);
              android.util.Log.e("KittenCamTest", String.valueOf(value));
            };
        feed.set(f);
        current.screen.addView(texture, new FrameLayout.LayoutParams(480, 320));
      });
      Feed f = feed.get();
      try {
        until("Control feed must decode actual camera frames", () -> f.decoded > 5);
      } finally {
        android.util.Log.i(
            "KittenCamTest",
            "Packets="
                + f.packets
                + " frames="
                + f.frames
                + " dropped="
                + f.dropped
                + " decoded="
                + f.decoded
                + " state="
                + f.state
                + " error="
                + error.get());
      }
      assertNull("Unexpected stream error", error.get());
      until("Camera zoom limits reach the controller", () -> f.remoteZoomLimits().hasMinimum());
      assertEquals(host.zoomLimits.minimum, f.remoteZoomLimits().minimum, 0);
      assertEquals(host.zoomLimits.maximum, f.remoteZoomLimits().maximum, 0);
      long rendered = f.decoded;
      scenario.onActivity(current -> f.view.setVisibility(android.view.View.GONE));
      Thread.sleep(500);
      scenario.onActivity(current -> f.view.setVisibility(android.view.View.VISIBLE));
      until("Video resumes after hiding/showing its surface", () -> f.decoded > rendered + 5);
      android.util.Log.i(
          "KittenCamTest",
          "commandEndpoint="
              + device.endpoints[0]
              + " actualPort="
              + host.commandPort
              + " status="
              + f.status);
      assertEquals("Discovered command port", host.commandPort, device.endpoints[0].getPort());
      f.command(Wire.json("command", "setZoom", "zoom", 2.0));
      until("Remote zoom command must apply", () -> host.zoom >= 1.9f);
      f.command(Wire.json("command", "setMicrophone", "enabled", true));
      until(
          "Microphone configuration and frames must reach the controller", () -> f.audioFrames > 2);
      // Capture is tested separately by TalkbackAudioTest. Some phones cannot open two
      // microphone captures concurrently for a same-device camera/controller loopback.
      assertNull("Camera audio must not report an error", error.get());
      until("Camera audio configuration received", () -> f.monitoringConfiguration != null);
      assertFalse("Recording audio must work with monitoring muted", f.monitoring);
      scenario.onActivity(current -> f.startRecording(true));
      until("Recording must begin at a keyframe", () -> f.recordStart >= 0);
      Uri uri = f.recordingUri;
      if (android.os.Build.VERSION.SDK_INT >= 29) assertNotNull("MediaStore recording URI", uri);
      long before = f.frames;
      until("Record a nonempty clip", () -> f.frames > before + 35);
      scenario.onActivity(current -> f.stopRecording());
      assertFalse(f.recording);
      until("Completed recording must be indexed in Gallery", () -> f.savedRecordingUri != null);
      uri = f.savedRecordingUri;
      assertEquals("content", uri.getScheme());
      try (android.database.Cursor row =
          a.getContentResolver()
              .query(
                  uri,
                  new String[] {
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.MIME_TYPE
                  },
                  null,
                  null,
                  null)) {
        assertNotNull(row);
        assertTrue(row.moveToFirst());
        assertTrue(row.getString(0).startsWith("KittenCam-"));
        assertEquals("video/mp4", row.getString(1));
      }
      MediaExtractor extractor = new MediaExtractor();
      try {
        extractor.setDataSource(a, uri, null);
        assertEquals(2, extractor.getTrackCount());
        int videoIndex = -1, audioIndex = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
          String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
          if ("video/avc".equals(mime)) videoIndex = i;
          if ("audio/mp4a-latm".equals(mime)) audioIndex = i;
        }
        assertTrue(videoIndex >= 0);
        assertTrue(audioIndex >= 0);
        long audioDuration = extractor.getTrackFormat(audioIndex).getLong(MediaFormat.KEY_DURATION);
        long videoDuration = extractor.getTrackFormat(videoIndex).getLong(MediaFormat.KEY_DURATION);
        assertTrue("Recorded audio must span the clip", audioDuration > 500000);
        assertTrue(
            "Audio/video durations stay aligned", Math.abs(audioDuration - videoDuration) < 500000);
        extractor.selectTrack(audioIndex);
        assertTrue("AAC samples must be saved", extractor.getSampleSize() > 0);
        assertAudioDecodes(extractor, extractor.getTrackFormat(audioIndex));
        extractor.unselectTrack(audioIndex);
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat video = extractor.getTrackFormat(videoIndex);
        assertEquals("video/avc", video.getString(MediaFormat.KEY_MIME));
        assertTrue(video.getInteger(MediaFormat.KEY_WIDTH) > 0);
        extractor.selectTrack(videoIndex);
        assertTrue(extractor.getSampleSize() > 0);
      } finally {
        extractor.release();
        if ("file".equals(uri.getScheme())) assertTrue(new java.io.File(uri.getPath()).delete());
        else a.getContentResolver().delete(uri, null, null);
      }
      scenario.onActivity(current -> {
        f.close();
        current.screen.removeView(f.view);
        current.leave();
        assertNull(current.host);
        assertNull(current.network);
      });
      assertTrue(f.closed);
      assertTrue(host.closed);
    } finally {
      if (feed.get() != null) feed.get().close();
    }
  }

  static void assertAudioDecodes(MediaExtractor extractor, MediaFormat format) throws Exception {
    android.media.MediaCodec decoder =
        android.media.MediaCodec.createDecoderByType("audio/mp4a-latm");
    try {
      decoder.configure(format, null, null, 0);
      decoder.start();
      boolean eos = false;
      long pcmBytes = 0;
      long deadline = System.currentTimeMillis() + 5000;
      android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
      while (pcmBytes == 0 && System.currentTimeMillis() < deadline) {
        if (!eos) {
          int input = decoder.dequeueInputBuffer(10000);
          if (input >= 0) {
            java.nio.ByteBuffer buffer = decoder.getInputBuffer(input);
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
              decoder.queueInputBuffer(
                  input, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              eos = true;
            } else {
              decoder.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int output = decoder.dequeueOutputBuffer(info, 10000);
        if (output >= 0) {
          pcmBytes += info.size;
          decoder.releaseOutputBuffer(output, false);
        }
      }
      assertTrue("Saved AAC must decode into PCM audio", pcmBytes > 0);
    } finally {
      try {
        decoder.stop();
      } finally {
        decoder.release();
      }
    }
  }
}
