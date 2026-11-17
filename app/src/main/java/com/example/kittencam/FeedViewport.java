package com.example.kittencam;

/** Display-only zoom in fixed card coordinates; translations are measured in card pixels. */
final class FeedViewport {
  float scale = 1, x, y;

  void zoomAt(float factor, float focusX, float focusY, float width, float height) {
    if (!Float.isFinite(factor) || factor <= 0) return;
    float next = Math.max(1, Math.min(4, scale * factor));
    float ratio = next / scale;
    x = focusX - width / 2 - ratio * (focusX - width / 2 - x);
    y = focusY - height / 2 - ratio * (focusY - height / 2 - y);
    scale = next;
  }

  void constrain(float width, float height, float fittedWidth, float fittedHeight) {
    float maxX = Math.max(0, (fittedWidth * scale - width) / 2);
    float maxY = Math.max(0, (fittedHeight * scale - height) / 2);
    x = Math.max(-maxX, Math.min(maxX, x));
    y = Math.max(-maxY, Math.min(maxY, y));
    if (scale == 1) {
      x = 0;
      y = 0;
    }
  }
}
