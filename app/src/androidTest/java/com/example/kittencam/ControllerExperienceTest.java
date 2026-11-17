package com.example.kittencam;

import static org.junit.Assert.*;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ControllerExperienceTest {
  static Feed add(MainActivity a, String name) {
    Feed f = new Feed(a, new LocalNetwork.Device(name), new TextureView(a));
    f.status =
        Wire.json(
            "success",
            true,
            "resolution",
            "1080p",
            "fps",
            30,
            "zoom",
            1,
            "minimumZoom",
            1,
            "maximumZoom",
            8,
            "torchEnabled",
            false);
    a.feeds.add(f);
    return f;
  }

  static void swipe(View view, float x1, float y1, float x2, float y2) {
    long start = android.os.SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x1, y1, 0);
    MotionEvent move = MotionEvent.obtain(start, start + 150, MotionEvent.ACTION_MOVE, x2, y2, 0);
    MotionEvent up = MotionEvent.obtain(start, start + 200, MotionEvent.ACTION_UP, x2, y2, 0);
    view.dispatchTouchEvent(down);
    view.dispatchTouchEvent(move);
    view.dispatchTouchEvent(up);
    down.recycle();
    move.recycle();
    up.recycle();
  }

  static EditText nicknameInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText input = nicknameInput(group.getChildAt(i));
        if (input != null) return input;
      }
    return null;
  }

  static void screenshot(String filename) throws Exception {
    var instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
    instrumentation.waitForIdleSync();
    Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    java.io.File file =
        new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), filename);
    try (var output = new java.io.FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      bitmap.recycle();
    }
  }

  @Test
  public void fullscreenHintAndRenameButtonsFitOnScreen() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed first = add(a, "Nursery camera with a long name"), second = add(a, "Playroom camera");
        a.selected = first;
        a.layoutFeeds();
        a.devices();
        a.fullscreen(first);
      });
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> {
        int[] hint = new int[2], controls = new int[2];
        a.fullscreenHint.getLocationOnScreen(hint);
        a.fullscreenControls.getLocationOnScreen(controls);
        assertTrue(a.fullscreenHint.getWidth() > 0);
        assertTrue(
            "Hint sits above controls", hint[1] + a.fullscreenHint.getHeight() <= controls[1]);
        ViewGroup card = (ViewGroup) a.selected.view.getParent();
        TextView name = card.findViewWithTag("camera-name");
        ViewGroup row = (ViewGroup) name.getParent();
        assertTrue(name.getWidth() > 0);
        assertTrue("Pencil remains within the card", row.getRight() <= card.getWidth());
      });
      screenshot("controller-fullscreen.png");
      scenario.onActivity(a ->
          a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
      VirtualDeviceTest.until(
          "Landscape layout settles",
          () ->
              androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                      .getTargetContext()
                      .getResources()
                      .getConfiguration()
                      .orientation
                  == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      android.os.SystemClock.sleep(500); // Let the system's rotation animation finish.
      screenshot("controller-fullscreen-landscape.png");
      scenario.onActivity(a ->
          a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
      VirtualDeviceTest.until(
          "Portrait layout restored",
          () ->
              androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                      .getTargetContext()
                      .getResources()
                      .getConfiguration()
                      .orientation
                  == android.content.res.Configuration.ORIENTATION_PORTRAIT);
      scenario.onActivity(a -> a.exitFullscreen());
      screenshot("controller-single.png");
      scenario.onActivity(a -> a.leave());
    }
  }

  @Test
  public void fullscreenSwipesSwitchActiveFeedAndRetargetControls() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed first = add(a, "Swipe A"), second = add(a, "Swipe B"), third = add(a, "Swipe C");
        a.selected = first;
        a.layoutFeeds();
        a.devices();
        assertFalse(ZoomPresetTest.hasButton(a.panel, "Select feed"));
        a.fullscreen(first);
        assertEquals("Swipe L/R to Switch Feeds", a.fullscreenHint.getText());
        swipe(first.view, 700, 150, 50, 150);
        assertSame(second, a.selected);
        assertTrue(((View) second.view.getParent()).isSelected());
        assertEquals(View.GONE, ((View) first.view.getParent()).getVisibility());
        a.fullscreenAudio.performClick();
        assertTrue(second.monitoring);
        assertFalse(first.monitoring);
        swipe(second.view, 50, 150, 700, 150);
        assertSame(first, a.selected);
        swipe(first.view, 50, 150, 700, 150);
        assertSame("Right swipe wraps to the last feed", third, a.selected);
        swipe(third.view, 150, 50, 160, 700);
        assertSame("Vertical movement must not switch", third, a.selected);
        third.viewport.scale = 2;
        third.transform();
        swipe(third.view, 700, 150, 50, 150);
        assertSame("Zoomed dragging remains pan", third, a.selected);
        a.exitFullscreen();
        assertNull(a.fullscreenHint);
        a.leave();
      });
    }
  }

  @Test
  public void nicknamesPersistAcrossActivityRecreationAndResetToDiscoveryName() {
    String identity = "Nickname test camera";
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed f = add(a, identity);
        a.selected = f;
        a.layoutFeeds();
        a.devices();
        a.renameCamera(identity);
        EditText input = nicknameInput(a.activeDialog.getWindow().getDecorView());
        assertNotNull(input);
        input.setText("  Nursery  ");
        a.activeDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
      });
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> {
        Feed f = a.selected;
        assertEquals("Nursery", a.cameraName(identity));
        assertEquals(identity, f.device.name);
        TextView label = ((ViewGroup) f.view.getParent()).findViewWithTag("camera-name");
        assertEquals("Nursery", label.getText());
        ViewGroup nameRow = (ViewGroup) label.getParent();
        assertEquals("Rename Nursery", nameRow.getChildAt(1).getContentDescription());
        nameRow.getChildAt(1).performClick();
        assertTrue(a.activeDialog.isShowing());
        a.activeDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("Nursery", a.cameraName(identity));
      });
      scenario.recreate();
      scenario.onActivity(a -> {
        assertEquals("Nursery", a.cameraName(identity));
        a.renameCamera(identity);
        a.activeDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
      });
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> assertEquals(identity, a.cameraName(identity)));
    } finally {
      new CameraNicknames(
              androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                  .getTargetContext())
          .save(identity, "");
    }
  }

  @Test
  public void slidersSendBeforeReleaseAndLiveCommandsKeepOnlyNewestValue() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      try {
        scenario.onActivity(a -> {
          LinearLayout parent = new LinearLayout(a);
          java.util.List<Float> values = new java.util.ArrayList<>();
          a.slider(parent, .5f, 15, 1, values::add);
          SeekBar slider = (SeekBar) parent.getChildAt(0);
          slider.onKeyDown(
              KeyEvent.KEYCODE_DPAD_RIGHT,
              new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
          assertFalse("Keyboard/user progress must send without a touch release", values.isEmpty());
          Feed f = add(a, "Slider fixture");
          f.commandIo.execute(() -> {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
          f.setTorch(true);
          assertTrue("Old off status cannot reverse the user's toggle", f.torchEnabled());
          f.setTorchLevel(.4f);
          for (int i = 0; i < 500; i++) f.setLiveZoom(.5f + i / 100f);
          synchronized (f.pendingControls) {
            assertEquals(2, f.pendingControls.size());
            assertEquals(5.49, f.pendingControls.get("zoom").optDouble("zoom"), .001);
            assertEquals(.4, f.pendingControls.get("torch").optDouble("level"), .001);
          }
          a.cleanup();
        });
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  public void defaultMonitoringIsOffAndMutingReleasesPlayback() throws Exception {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        Feed f = add(a, "Silent connection");
        assertFalse(f.monitoring);
        try {
          f.handle(new Wire.Packet(6, Wire.bytes(AudioLink.configuration())));
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        assertNull("Disabled monitoring must not start an AudioTrack", f.playback.track);
        f.setMonitoring(false);
        assertNull(f.playback.track);
        a.cleanup();
      });
    }
  }

  @Test
  public void singleViewCanSwitchCardsAndSelectionHasTealStroke() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed first = add(a, "Camera A"), second = add(a, "Camera B");
        a.selected = first;
        a.grid = false;
        a.layoutFeeds();
        a.devices();
        assertEquals(View.VISIBLE, a.feedSwitcher.getVisibility());
        assertEquals(2, a.feedTabs.getChildCount());
        View cardA = (View) first.view.getParent(), cardB = (View) second.view.getParent();
        assertEquals(View.VISIBLE, cardA.getVisibility());
        assertEquals(View.GONE, cardB.getVisibility());
        assertTrue(cardA.isSelected());
        Bitmap border = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        cardA.getForeground().setBounds(0, 0, 100, 100);
        cardA.getForeground().draw(new Canvas(border));
        assertEquals(0xff54d8be, border.getPixel(50, 1));
        border.recycle();
        a.feedTabs.getChildAt(1).performClick();
        assertSame(second, a.selected);
        assertEquals(View.GONE, cardA.getVisibility());
        assertEquals(View.VISIBLE, cardB.getVisibility());
        assertTrue(cardB.isSelected());
        assertFalse(cardA.isSelected());
        a.grid = true;
        a.layoutFeeds();
        assertEquals(View.GONE, a.feedSwitcher.getVisibility());
        assertEquals(View.VISIBLE, cardA.getVisibility());
        assertEquals(View.VISIBLE, cardB.getVisibility());
        a.leave();
      });
    }
  }

  @Test
  public void sameFullscreenButtonExitsAndCompactControlsTargetSelectedFeed() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed first = add(a, "Camera A"), second = add(a, "Camera B");
        a.selected = first;
        a.layoutFeeds();
        a.devices();
        ImageButton expand = a.expandButtons.get(first);
        expand.performClick();
        assertTrue(a.fullScreen);
        assertEquals(3, a.fullscreenControls.getChildCount());
        assertTrue(expand.getContentDescription().toString().startsWith("Exit fullscreen"));
        assertEquals("Turn torch on", a.fullscreenTorch.getContentDescription());
        assertEquals("Enable audio monitoring", a.fullscreenAudio.getContentDescription());
        assertFalse(first.monitoring);
        assertFalse(second.monitoring);
        a.fullscreenAudio.performClick();
        assertTrue(first.monitoring);
        assertFalse(second.monitoring);
        assertEquals("Mute audio monitoring", a.fullscreenAudio.getContentDescription());
        View toolbar = a.fullscreenControls;
        expand.performClick();
        assertFalse(a.fullScreen);
        assertNull(toolbar.getParent());
        assertTrue(expand.getContentDescription().toString().startsWith("Enter fullscreen"));
        a.leave();
      });
    }
  }
}
