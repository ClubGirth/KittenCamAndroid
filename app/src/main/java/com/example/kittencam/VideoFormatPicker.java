package com.example.kittencam;

import android.content.Context;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.function.BiConsumer;

/** A resolution change publishes exactly one valid resolution/FPS pair. */
final class VideoFormatPicker extends LinearLayout {
  final Spinner resolutionPicker, frameRatePicker;
  final VideoFormats capabilities;
  final BiConsumer<String, Integer> changed;
  String selectedResolution;
  int selectedFps;

  VideoFormatPicker(
      Context context,
      VideoFormats capabilities,
      String currentResolution,
      int currentFps,
      BiConsumer<String, Integer> changed) {
    super(context);
    setOrientation(VERTICAL);
    this.capabilities = capabilities;
    this.changed = changed;
    resolutionPicker = new Spinner(context);
    frameRatePicker = new Spinner(context);
    resolutionPicker.setContentDescription("Resolution");
    frameRatePicker.setContentDescription("Frame rate");
    if (capabilities == null || capabilities.isEmpty()) {
      TextView label = new TextView(context);
      label.setTextColor(0xffa1acb1);
      label.setTextSize(13);
      label.setText(
          currentResolution
              + " · "
              + currentFps
              + " FPS\n"
              + (capabilities == null
                  ? "This camera does not report its available formats."
                  : "No available video formats reported yet."));
      addView(label);
      return;
    }
    VideoFormats.Format initial = capabilities.find(currentResolution);
    selectedResolution =
        initial != null ? initial.resolution().name() : capabilities.resolutions()[0];
    selectedFps = capabilities.closestRate(selectedResolution, currentFps);
    addLabel(context, "Resolution");
    addView(resolutionPicker, new LayoutParams(-1, dp(48)));
    addLabel(context, "Frame rate");
    addView(frameRatePicker, new LayoutParams(-1, dp(48)));
    resolutionPicker.setAdapter(adapter(capabilities.resolutions()));
    resolutionPicker.setSelection(
        java.util.Arrays.asList(capabilities.resolutions()).indexOf(selectedResolution));
    resolutionPicker.setEnabled(capabilities.formats.size() > 1);
    updateRates();
    resolutionPicker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String resolution = String.valueOf(parent.getSelectedItem());
            if (resolution.equals(selectedResolution)) return;
            selectedResolution = resolution;
            selectedFps = capabilities.closestRate(resolution, selectedFps);
            updateRates();
            changed.accept(selectedResolution, selectedFps);
          }
        });
    frameRatePicker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            int fps = Integer.parseInt(String.valueOf(parent.getSelectedItem()));
            if (fps == selectedFps || !capabilities.supports(selectedResolution, fps)) return;
            selectedFps = fps;
            changed.accept(selectedResolution, selectedFps);
          }
        });
  }

  int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  ArrayAdapter<String> adapter(String[] values) {
    return new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, values);
  }

  void addLabel(Context context, String text) {
    TextView label = new TextView(context);
    label.setText(text);
    label.setTextSize(13);
    label.setTextColor(0xffa1acb1);
    addView(label);
  }

  void updateRates() {
    List<Integer> rates = capabilities.frameRates(selectedResolution);
    String[] labels = new String[rates.size()];
    for (int i = 0; i < rates.size(); i++) labels[i] = rates.get(i).toString();
    frameRatePicker.setAdapter(adapter(labels));
    frameRatePicker.setSelection(rates.indexOf(selectedFps));
    frameRatePicker.setEnabled(rates.size() > 1);
  }
}
