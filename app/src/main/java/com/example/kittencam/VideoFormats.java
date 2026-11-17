package com.example.kittencam;

import java.util.*;
import java.util.function.BiPredicate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable, per-resolution capabilities shared by validation and both Android settings screens.
 */
final class VideoFormats {
  record Resolution(String name, int width, int height) {}

  record RateRange(int lower, int upper) {}

  record Format(Resolution resolution, List<Integer> frameRates) {}

  static final VideoFormats EMPTY = new VideoFormats(Collections.emptyList());
  // The legacy Windows controller offers these command options without capability discovery.
  static final VideoFormats LEGACY_IOS =
      new VideoFormats(
          Arrays.asList(
              new Format(new Resolution("720p", 1280, 720), Arrays.asList(24, 30, 50, 60)),
              new Format(new Resolution("1080p", 1920, 1080), Arrays.asList(24, 30, 50, 60)),
              new Format(new Resolution("1440p", 2560, 1440), Arrays.asList(24, 30, 50, 60)),
              new Format(new Resolution("4K", 3840, 2160), Arrays.asList(24, 30, 50, 60))));
  final List<Format> formats;

  VideoFormats(List<Format> formats) {
    this.formats = Collections.unmodifiableList(new ArrayList<>(formats));
  }

  static VideoFormats discover(
      List<Resolution> resolutions,
      List<RateRange> exposureRanges,
      BiPredicate<Resolution, Integer> pipelineSupports) {
    // Fixed AE rates can be requested exactly. A variable range also exposes its maximum
    // (nominal) rate, but not arbitrary intermediate rates the camera cannot be told to use.
    SortedSet<Integer> candidates = new TreeSet<>();
    for (RateRange range : exposureRanges) {
      if (range.upper > 0 && range.upper <= 240) candidates.add(range.upper);
    }
    List<Format> supported = new ArrayList<>();
    for (Resolution resolution : resolutions) {
      List<Integer> rates = new ArrayList<>();
      for (int rate : candidates) if (pipelineSupports.test(resolution, rate)) rates.add(rate);
      if (!rates.isEmpty())
        supported.add(new Format(resolution, Collections.unmodifiableList(rates)));
    }
    return new VideoFormats(supported);
  }

  boolean isEmpty() {
    return formats.isEmpty();
  }

  Format find(String name) {
    String normalized = "2160p".equalsIgnoreCase(name) ? "4K" : name;
    for (Format format : formats)
      if (format.resolution.name.equalsIgnoreCase(normalized)) return format;
    return null;
  }

  boolean supports(String resolution, int fps) {
    Format format = find(resolution);
    return format != null && format.frameRates.contains(fps);
  }

  String[] resolutions() {
    String[] result = new String[formats.size()];
    for (int i = 0; i < formats.size(); i++) result[i] = formats.get(i).resolution.name;
    return result;
  }

  List<Integer> frameRates(String resolution) {
    Format format = find(resolution);
    return format == null ? Collections.emptyList() : format.frameRates;
  }

  int closestRate(String resolution, int preferred) {
    List<Integer> rates = frameRates(resolution);
    if (rates.isEmpty())
      throw new IllegalArgumentException("Unsupported resolution: " + resolution);
    int best = rates.get(0);
    for (int rate : rates) if (Math.abs(rate - preferred) < Math.abs(best - preferred)) best = rate;
    return best;
  }

  JSONArray toJson() {
    JSONArray result = new JSONArray();
    for (Format format : formats)
      result.put(
          Wire.json(
              "resolution",
              format.resolution.name,
              "width",
              format.resolution.width,
              "height",
              format.resolution.height,
              "fps",
              new JSONArray(format.frameRates)));
    return result;
  }

  /** A legacy peer without capability metadata is deliberately not guessed from its model name. */
  static VideoFormats fromStatus(JSONObject status) {
    JSONArray array = status.optJSONArray("supportedVideoFormats");
    if (array == null) return null;
    List<Format> formats = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject entry = array.optJSONObject(i);
      if (entry == null) continue;
      String name = entry.optString("resolution", "");
      int width = entry.optInt("width"), height = entry.optInt("height");
      JSONArray fps = entry.optJSONArray("fps");
      if (name.isEmpty() || width <= 0 || height <= 0 || fps == null) continue;
      SortedSet<Integer> rates = new TreeSet<>();
      for (int j = 0; j < fps.length(); j++) {
        int rate = fps.optInt(j);
        if (rate > 0 && rate <= 240) rates.add(rate);
      }
      if (!rates.isEmpty())
        formats.add(
            new Format(
                new Resolution(name, width, height),
                Collections.unmodifiableList(new ArrayList<>(rates))));
    }
    return new VideoFormats(formats);
  }
}
