package com.example.kittencam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.json.JSONObject;

/** Camera capability bounds, never inferred from the current zoom position. */
final class ZoomLimits {
  static final ZoomLimits UNKNOWN = new ZoomLimits(Float.NaN, Float.NaN);
  final float minimum, maximum;

  ZoomLimits(float minimum, float maximum) {
    float low = valid(minimum) ? minimum : Float.NaN, high = valid(maximum) ? maximum : Float.NaN;
    if (valid(low) && valid(high) && high < low) {
      low = Float.NaN;
      high = Float.NaN;
    }
    this.minimum = low;
    this.maximum = high;
  }

  static boolean valid(float n) {
    return !Float.isNaN(n) && !Float.isInfinite(n) && n > 0;
  }

  boolean hasMinimum() {
    return valid(minimum);
  }

  boolean hasMaximum() {
    return valid(maximum);
  }

  float minimumOr(float fallback) {
    return hasMinimum() ? minimum : fallback;
  }

  float maximumOr(float fallback) {
    return hasMaximum() ? maximum : Math.max(minimumOr(fallback), fallback);
  }

  float clamp(float value) {
    float low = minimumOr(1), high = maximumOr(low);
    return Math.max(low, Math.min(high, value));
  }

  String minimumLabel() {
    return hasMinimum() ? label(minimum) : "Min";
  }

  static String label(float value) {
    return new BigDecimal(Float.toString(value))
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        + "×";
  }

  String signature() {
    return minimum + ":" + maximum;
  }

  JSONObject addTo(JSONObject json) {
    try {
      if (hasMinimum()) json.put("minimumZoom", (Object) minimum);
      if (hasMaximum()) json.put("maximumZoom", (Object) maximum);
    } catch (org.json.JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return json;
  }

  static ZoomLimits fromMetadata(JSONObject status, JSONObject information) {
    return new ZoomLimits(
        bound(status, information, "minimumZoom", "minZoom"),
        bound(status, information, "maximumZoom", "maxZoom"));
  }

  static float bound(JSONObject status, JSONObject information, String canonical, String alias) {
    for (JSONObject json : new JSONObject[] {status, information})
      if (json != null)
        for (String key : new String[] {canonical, alias}) {
          float value = (float) json.optDouble(key, Double.NaN);
          if (valid(value)) return value;
        }
    return Float.NaN;
  }
}
