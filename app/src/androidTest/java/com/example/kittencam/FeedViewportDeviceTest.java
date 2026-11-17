package com.example.kittencam;

import static org.junit.Assert.*;

import android.graphics.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FeedViewportDeviceTest {
  static void touch(View view, long time, int action, int[] ids, float[] xs, float[] ys) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
    for (int i = 0; i < ids.length; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = ids[i];
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = xs[i];
      coords[i].y = ys[i];
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event =
        MotionEvent.obtain(
            1000,
            time,
            action,
            ids.length,
            properties,
            coords,
            0,
            0,
            1,
            1,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0);
    view.dispatchTouchEvent(event);
    event.recycle();
  }

  @Test
  public void pinchAndFingerHandoffNeverMoveTheTouchSurface() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        a.choose("control");
        Feed f = ControllerExperienceTest.add(a, "Viewport gesture fixture");
        a.selected = f;
        a.layoutFeeds();
        f.view.layout(0, 0, 1000, 800);
        f.transform();
        touch(
            f.view,
            1000,
            MotionEvent.ACTION_DOWN,
            new int[] {0},
            new float[] {400},
            new float[] {400});
        touch(
            f.view,
            1020,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[] {0, 1},
            new float[] {400, 600},
            new float[] {400, 400});
        touch(
            f.view,
            1040,
            MotionEvent.ACTION_MOVE,
            new int[] {0, 1},
            new float[] {300, 700},
            new float[] {400, 400});
        touch(
            f.view,
            1060,
            MotionEvent.ACTION_MOVE,
            new int[] {0, 1},
            new float[] {200, 800},
            new float[] {400, 400});
        touch(
            f.view,
            1080,
            MotionEvent.ACTION_MOVE,
            new int[] {0, 1},
            new float[] {200, 900},
            new float[] {400, 400});
        assertTrue("Pinch actually changes image magnification", f.viewport.scale > 1.1f);
        assertEquals("The image follows the moving pinch focus", 50, f.viewport.x, 1);
        touch(
            f.view,
            1100,
            MotionEvent.ACTION_POINTER_UP,
            new int[] {0, 1},
            new float[] {200, 900},
            new float[] {400, 400});
        float before = f.viewport.x;
        touch(
            f.view,
            1120,
            MotionEvent.ACTION_MOVE,
            new int[] {1},
            new float[] {905},
            new float[] {400});
        assertTrue(
            "Lifting the first finger must not cause a pan jump",
            Math.abs(f.viewport.x - before) <= 5.1f);
        touch(
            f.view,
            1140,
            MotionEvent.ACTION_MOVE,
            new int[] {1},
            new float[] {100000},
            new float[] {100000});
        assertBounds(f);
        assertEquals(1, f.view.getScaleX(), 0);
        assertEquals(1, f.view.getScaleY(), 0);
        assertEquals(0, f.view.getTranslationX(), 0);
        assertEquals(0, f.view.getTranslationY(), 0);
        touch(
            f.view,
            1160,
            MotionEvent.ACTION_UP,
            new int[] {1},
            new float[] {100000},
            new float[] {100000});
        a.leave();
      });
    }
  }

  static void assertBounds(Feed f) {
    RectF image = new RectF(0, 0, f.view.getWidth(), f.view.getHeight());
    f.view.getTransform(new Matrix()).mapRect(image);
    float w = f.view.getWidth(), h = f.view.getHeight();
    if (image.width() >= w - .1f) {
      assertTrue(image.left <= .1f);
      assertTrue(image.right >= w - .1f);
    } else assertEquals(w / 2, image.centerX(), .1f);
    if (image.height() >= h - .1f) {
      assertTrue(image.top <= .1f);
      assertTrue(image.bottom >= h - .1f);
    } else assertEquals(h / 2, image.centerY(), .1f);
  }

  @Test
  public void renderedVideoStaysWithinPanBoundsAcrossRotationsAndResizes() {
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(a -> {
        Feed f = new Feed(a, new LocalNetwork.Device("Bounds fixture"), new TextureView(a));
        try {
          for (int rotation : new int[] {0, 90, 180, 270})
            for (int[] size : new int[][] {{1000, 1600}, {1600, 1000}, {400, 400}})
              for (float scale : new float[] {1, 1.5f, 4})
                for (int direction : new int[] {-1, 1}) {
                  f.rotation = rotation;
                  f.view.layout(0, 0, size[0], size[1]);
                  f.viewport.scale = scale;
                  f.viewport.x = direction * 100000;
                  f.viewport.y = direction * 100000;
                  f.transform();
                  assertBounds(f);
                }
        } finally {
          f.close();
        }
      });
    }
  }
}
