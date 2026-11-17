package com.example.kittencam;

import static org.junit.Assert.*;

import org.junit.Test;

public class FeedViewportTest {
  @Test
  public void focusStaysAnchoredWhileZooming() {
    FeedViewport v = new FeedViewport();
    v.zoomAt(2, 300, 200, 1000, 800);
    assertEquals(200, v.x, .001);
    assertEquals(200, v.y, .001);
    assertEquals(300, 500 + v.x + (300 - 500) * v.scale, .001);
    v.zoomAt(.5f, 300, 200, 1000, 800);
    v.constrain(1000, 800, 1000, 800);
    assertEquals(1, v.scale, 0);
    assertEquals(0, v.x, 0);
    assertEquals(0, v.y, 0);
  }

  @Test
  public void extremePanningNeverExposesBeyondImageEdges() {
    FeedViewport v = new FeedViewport();
    v.scale = 4;
    for (float direction : new float[] {-1, 1}) {
      v.x = direction * 100000;
      v.y = direction * 100000;
      v.constrain(1000, 800, 1000, 800);
      assertEquals(direction * 1500, v.x, 0);
      assertEquals(direction * 1200, v.y, 0);
    }
  }

  @Test
  public void letterboxedAxisRemainsCenteredAndResizeReclamps() {
    FeedViewport v = new FeedViewport();
    v.scale = 2;
    v.x = 1000;
    v.y = 1000;
    v.constrain(1000, 1600, 1000, 562.5f);
    assertEquals(500, v.x, 0);
    assertEquals(0, v.y, 0);
    v.constrain(1600, 1000, 562.5f, 1000);
    assertEquals(0, v.x, 0);
    v.zoomAt(100, 800, 500, 1600, 1000);
    assertEquals(4, v.scale, 0);
    v.zoomAt(.0001f, 0, 0, 1600, 1000);
    v.constrain(1600, 1000, 1600, 900);
    assertEquals(1, v.scale, 0);
    assertEquals(0, v.x, 0);
    assertEquals(0, v.y, 0);
  }
}
