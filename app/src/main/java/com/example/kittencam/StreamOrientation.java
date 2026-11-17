package com.example.kittencam;

import java.util.Locale;
import org.json.JSONObject;

/** Orientation names and raw-buffer rotations understood by the Windows controller. */
enum StreamOrientation {
  LANDSCAPE_LEFT(0, "landscapeLeft"),
  PORTRAIT(90, "portrait"),
  LANDSCAPE_RIGHT(180, "landscapeRight"),
  PORTRAIT_UPSIDE_DOWN(270, "portraitUpsideDown");

  final int clockwise;
  final int counterclockwise;
  final String wireName;
  final boolean portrait;

  StreamOrientation(int clockwise, String wireName) {
    this.clockwise = clockwise;
    this.counterclockwise = (360 - clockwise) % 360;
    this.wireName = wireName;
    this.portrait = clockwise % 180 != 0;
  }

  static StreamOrientation fromWireName(String name) {
    if (name == null) return null;
    String normalized = name.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    // Match upside-down portrait before the more general portrait name, as Windows does.
    if (normalized.contains("portraitupsidedown")) return PORTRAIT_UPSIDE_DOWN;
    if (normalized.contains("portrait")) return PORTRAIT;
    if (normalized.contains("landscaperight")) return LANDSCAPE_RIGHT;
    if (normalized.contains("landscapeleft")) return LANDSCAPE_LEFT;
    return null;
  }

  static int resolveDisplayRotation(String name, int numericClockwise) {
    StreamOrientation named = fromWireName(name);
    return named != null ? named.clockwise : ((numericClockwise % 360) + 360) % 360;
  }

  /** Legacy iOS landscape angles disagree with its names; match the working Windows viewer. */
  static int displayRotation(JSONObject information) {
    for (String key :
        new String[] {
          "orientation", "deviceOrientation", "videoOrientation", "cameraOrientation"
        }) {
      StreamOrientation named = fromWireName(information.optString(key, null));
      if (named != null) return named.clockwise;
    }
    // encodedOrientation describes the encoder's capture basis, not the current display.
    int degrees =
        information.optInt(
            "rotationDegreesClockwise",
            information.optInt("displayRotationDegrees", information.optInt("rotationDegrees", 0)));
    return resolveDisplayRotation(null, degrees);
  }

  static StreamOrientation fromClockwise(int degrees) {
    int normalized = ((degrees % 360) + 360) % 360;
    for (StreamOrientation orientation : values()) {
      if (orientation.clockwise == normalized) return orientation;
    }
    throw new IllegalArgumentException("Expected a quarter-turn rotation: " + degrees);
  }
}
