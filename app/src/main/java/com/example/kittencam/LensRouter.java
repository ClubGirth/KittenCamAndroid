package com.example.kittencam;

import java.util.*;

/** Keeps UI zoom relative to the default rear lens while selecting a real wider camera. */
final class LensRouter {
  record Lens(String id, float baseZoom, ZoomLimits nativeLimits) {
    float minimum() {
      return baseZoom * nativeLimits.minimumOr(1);
    }

    float nativeZoom(float displayed) {
      return nativeLimits.clamp(displayed / baseZoom);
    }

    float displayedZoom(float displayed) {
      return baseZoom * nativeZoom(displayed);
    }
  }

  final Lens primary;
  final List<Lens> lenses;
  final ZoomLimits limits;

  LensRouter(Lens primary, List<Lens> lenses) {
    this.primary = primary;
    this.lenses = Collections.unmodifiableList(new ArrayList<>(lenses));
    float minimum = primary.minimum();
    for (Lens lens : lenses) minimum = Math.min(minimum, lens.minimum());
    limits = new ZoomLimits(minimum, primary.baseZoom * primary.nativeLimits.maximumOr(1));
  }

  Lens select(float requested) {
    float zoom = limits.clamp(requested);
    if (zoom >= primary.minimum()) return primary;
    Lens best = null;
    for (Lens lens : lenses)
      if (lens.minimum() <= zoom + .0001f && (best == null || lens.baseZoom > best.baseZoom))
        best = lens;
    if (best != null) return best;
    return Collections.min(lenses, Comparator.comparingDouble(Lens::minimum));
  }

  static float relativeBase(
      float primaryFocal, float primarySensorWidth, float focal, float sensorWidth) {
    if (!ZoomLimits.valid(primaryFocal)
        || !ZoomLimits.valid(primarySensorWidth)
        || !ZoomLimits.valid(focal)
        || !ZoomLimits.valid(sensorWidth)) return Float.NaN;
    float ratio = (focal / sensorWidth) / (primaryFocal / primarySensorWidth);
    // Separate camera IDs each call their own optical view 1×. Normalize their UI lens
    // stops to the usual tenths (e.g. the S21 Ultra's wider optical view is shown as 0.6×).
    return Math.max(.1f, Math.round(ratio * 10) / 10f);
  }
}
