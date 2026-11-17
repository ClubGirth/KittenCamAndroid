package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaCodec;
import android.util.Size;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VideoFormatsDeviceTest {
  @Before
  public void permissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  static VideoFormats fakeFormats() {
    return VideoFormats.discover(
        Arrays.asList(
            new VideoFormats.Resolution("1080p", 1920, 1080),
            new VideoFormats.Resolution("4K", 3840, 2160)),
        Arrays.asList(
            new VideoFormats.RateRange(24, 24),
            new VideoFormats.RateRange(30, 30),
            new VideoFormats.RateRange(60, 60)),
        (resolution, fps) -> resolution.width() < 3840 || fps <= 30);
  }

  @Test
  public void iphoneGetsWindowsCompatibleControlsAndReportedCapabilitiesTakePriority() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        Feed f =
            new Feed(a, new LocalNetwork.Device("Nursery iPhone"), new android.view.TextureView(a));
        try {
          f.status = Wire.json("resolution", "1080p", "fps", 30);
          assertTrue(f.usesLegacyIosFormats());
          assertTrue(f.remoteFormats().supports("4K", 60));
          VideoFormatPicker picker =
              new VideoFormatPicker(a, f.remoteFormats(), "1080p", 30, (r, fps) -> {});
          assertEquals(4, picker.resolutionPicker.getAdapter().getCount());
          assertEquals(4, picker.frameRatePicker.getAdapter().getCount());
          f.status = Wire.json("supportedVideoFormats", fakeFormats().toJson());
          assertFalse(f.usesLegacyIosFormats());
          assertFalse(f.remoteFormats().supports("4K", 60));
          assertTrue(f.remoteFormats().supports("4K", 30));
          f.status = Wire.json("supportedVideoFormats", new org.json.JSONArray());
          assertTrue(
              "Explicit empty capability list must not become guessed options",
              f.remoteFormats().isEmpty());
          f.status = Wire.json();
          f.streamFormats = fakeFormats();
          assertFalse(f.usesLegacyIosFormats());
          assertFalse(f.remoteFormats().supports("4K", 60));
        } finally {
          f.close();
        }
      });
    }
  }

  @Test
  public void pickerNeverSendsAnInvalidPairWhenResolutionChanges() throws Exception {
    List<String> commands = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<VideoFormatPicker> picker = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        VideoFormatPicker p =
            new VideoFormatPicker(
                a, fakeFormats(), "1080p", 60, (r, f) -> commands.add(r + "@" + f));
        picker.set(p);
        a.screen.addView(p, new FrameLayout.LayoutParams(-1, -2));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      assertTrue("Opening settings must not send commands", commands.isEmpty());
      scenario.onActivity(a -> picker.get().resolutionPicker.setSelection(1));
      until("One valid format command", () -> commands.size() == 1);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      assertEquals(Collections.singletonList("4K@30"), commands);
      scenario.onActivity(a -> {
        VideoFormatPicker p = picker.get();
        assertEquals(2, p.frameRatePicker.getAdapter().getCount());
        assertEquals("24", p.frameRatePicker.getAdapter().getItem(0));
        assertEquals("30", p.frameRatePicker.getAdapter().getItem(1));
        p.frameRatePicker.setSelection(0);
      });
      until("Frame rate change", () -> commands.size() == 2);
      assertEquals("4K@24", commands.get(1));
    }
  }

  @Test
  public void legacyCameraShowsCurrentFormatWithoutGuessingAlternatives() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        VideoFormatPicker p =
            new VideoFormatPicker(
                a,
                null,
                "1080p",
                30,
                (r, f) -> fail("Legacy camera must not receive invented settings"));
        a.screen.addView(p);
        assertNull(p.resolutionPicker.getAdapter());
        assertNull(p.frameRatePicker.getAdapter());
        assertNull(VideoFormats.fromStatus(Wire.json("resolution", "1080p", "fps", 30)));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
  }

  @Test
  public void actualCameraPublishesOnlyCaptureAndEncoderSupportedPairs() throws Exception {
    AtomicReference<MainActivity> activity = new AtomicReference<>();
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("camera");
      });
      until(
          "Camera starts", () -> activity.get().host != null && activity.get().host.config != null);
      CameraHost host = activity.get().host;
      VideoFormats formats = host.videoFormats;
      assertFalse(formats.isEmpty());
      assertTrue(formats.supports(host.resolution, host.fps));
      assertEquals(
          formats.toJson().toString(),
          VideoFormats.fromStatus(host.status(true, "test")).toJson().toString());
      assertEquals(
          formats.toJson().toString(),
          VideoFormats.fromStatus(host.information()).toJson().toString());
      var map = host.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      var encoder =
          host.encoder.getCodecInfo().getCapabilitiesForType("video/avc").getVideoCapabilities();
      var exposures =
          host.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
      for (var format : formats.formats) {
        var resolution = format.resolution();
        assertTrue(
            Arrays.asList(map.getOutputSizes(MediaCodec.class))
                .contains(new Size(resolution.width(), resolution.height())));
        for (int rate : format.frameRates()) {
          assertTrue(Arrays.stream(exposures).anyMatch(range -> range.getUpper() == rate));
          assertTrue(
              encoder.areSizeAndRateSupported(resolution.width(), resolution.height(), rate));
          long duration =
              map.getOutputMinFrameDuration(
                  MediaCodec.class, new Size(resolution.width(), resolution.height()));
          assertTrue(duration <= 0 || rate <= Math.round(1e9 / duration));
        }
      }
      android.util.Log.i("KittenCamFormats", "Advertised formats=" + formats.toJson());
      scenario.onActivity(MainActivity::leave);
    }
  }
}
