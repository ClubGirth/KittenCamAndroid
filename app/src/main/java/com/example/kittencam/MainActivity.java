package com.example.kittencam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;

/** Native Android counterpart of the SwiftUI mode picker and camera/control HUDs. */
public final class MainActivity extends androidx.activity.ComponentActivity {
  static final int BG = 0xff0b1012,
      CARD = 0xff20272a,
      WHITE = 0xfff4f7f8,
      MUTED = 0xffa1acb1,
      GREEN = 0xff63dfa0,
      CYAN = 0xff6bd9f1;
  final Handler main = new Handler(Looper.getMainLooper());
  LinearLayout root, panel, cameraList;
  FeedGrid feedArea;
  FrameLayout screen;
  TextView hostStatus, feedStatus, fullscreenHint;
  LocalNetwork network;
  CameraHost host;
  final List<Feed> feeds = new ArrayList<>();
  Feed selected;
  String mode = "";
  boolean grid = true, fullScreen;
  LinearLayout split, feedColumn;
  ScrollView controlScroll;
  LinearLayout fullscreenControls;
  ImageButton fullscreenTorch, fullscreenAudio, gridToggleButton;
  HoldToTalkButton fullscreenTalk;
  HorizontalScrollView feedSwitcher;
  LinearLayout feedTabs;
  final Map<Feed, ImageButton> expandButtons = new IdentityHashMap<>();
  boolean pendingDeviceRefresh;
  String pendingMode;
  View cameraControls;
  Button cameraMinimumZoomButton;
  AlertDialog activeDialog;
  final Runnable ticker =
      new Runnable() {
        public void run() {
          if (!mode.isEmpty()) {
            if (hostStatus != null && host != null)
              hostStatus.setText(
                  host.resolution + " · " + host.fps + " FPS\n● Ready · Port " + host.commandPort);
            if (feedStatus != null && selected != null)
              feedStatus.setText(
                  cameraName(selected.device.name)
                      + " · "
                      + selected.state
                      + "\n"
                      + selected.frames
                      + " frames · "
                      + selected.dropped
                      + " dropped"
                      + (selected.recording ? " · ● REC" : ""));
            if (feedStatus != null && selected == null)
              feedStatus.setText("No feed selected\nDiscovering local cameras");
            refreshFullscreenControls();
            main.postDelayed(this, 1000);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                if (fullScreen) exitFullscreen();
                else if (!mode.isEmpty()) leave();
                else finish();
              }
            });
    modes();
  }

  int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), 0x18ffffff);
    return d;
  }

  ImageButton iconButton(int icon, String description, Runnable action) {
    ImageButton button = new ImageButton(this);
    button.setImageResource(icon);
    button.setImageTintList(android.content.res.ColorStateList.valueOf(WHITE));
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
    button.setBackgroundResource(R.drawable.control_icon_background);
    button.setContentDescription(description);
    androidx.appcompat.widget.TooltipCompat.setTooltipText(button, description);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(4), 0, dp(4));
    return t;
  }

  TextView title(String s, int size) {
    TextView t = text(s, size, WHITE);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextColor(WHITE);
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setMinHeight(dp(44));
    b.setMinimumWidth(0);
    b.setBackground(shape(CARD, 20));
    b.setPadding(dp(14), dp(6), dp(14), dp(6));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  Button addButton(LinearLayout row, String label, Runnable action) {
    Button b = button(label, action);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
    p.setMargins(dp(3), dp(3), dp(3), dp(3));
    row.addView(b, p);
    return b;
  }

  void base() {
    screen = new FrameLayout(this);
    screen.setBackgroundColor(BG);
    root = column();
    screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(screen);
    screen.setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(
          insets.getSystemWindowInsetLeft(),
          insets.getSystemWindowInsetTop(),
          insets.getSystemWindowInsetRight(),
          insets.getSystemWindowInsetBottom());
      return insets;
    });
  }

  void modes() {
    mode = "";
    hostStatus = null;
    feedStatus = null;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    base();
    GradientDrawable bg =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff081013, 0xff1a2226, 0xff050506});
    screen.setBackground(bg);
    LinearLayout content = column();
    content.setPadding(dp(24), dp(24), dp(24), dp(24));
    content.addView(title("KittenCam", 34));
    TextView intro = text("Choose how this device should join the local camera setup.", 15, MUTED);
    content.addView(intro);
    space(content, 26);
    modeCard(
        content,
        "◉",
        "Viewing Device",
        "Use this phone as the camera and stream it on your network.",
        GREEN,
        () -> choose("camera"));
    space(content, 14);
    modeCard(
        content,
        "▦",
        "Control Device",
        "Discover KittenCam cameras, watch feeds, record, and control settings.",
        CYAN,
        () -> choose("control"));
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    p.setMargins(dp(4), 0, dp(4), 0);
    screen.addView(content, p);
  }

  void modeCard(
      LinearLayout parent, String icon, String heading, String body, int accent, Runnable action) {
    LinearLayout card = row();
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    card.setBackground(shape(0xff20272b, 10));
    TextView symbol = text(icon, 28, accent);
    symbol.setGravity(Gravity.CENTER);
    symbol.setBackground(shape(0x223dd9a0, 8));
    card.addView(symbol, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout copy = column();
    copy.setPadding(dp(14), 0, dp(8), 0);
    copy.addView(title(heading, 17));
    copy.addView(text(body, 12, MUTED));
    card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(text("›", 26, MUTED));
    card.setOnClickListener(v -> action.run());
    card.setContentDescription(heading + ". " + body);
    parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
  }

  void space(LinearLayout l, int height) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  void choose(String next) {
    if (Build.VERSION.SDK_INT >= 37
        && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
            != PackageManager.PERMISSION_GRANTED) {
      pendingMode = next;
      requestPermissions(new String[] {"android.permission.ACCESS_LOCAL_NETWORK"}, 13);
      return;
    }
    if (next.equals("camera")
        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      pendingMode = next;
      requestPermissions(
          new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 11);
      return;
    }
    start(next);
  }

  Runnable pendingRecordingAction;

  void withRecordingPermission(Runnable action) {
    if (Build.VERSION.SDK_INT <= 28
        && (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
      pendingRecordingAction = action;
      requestPermissions(
          new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
          },
          14);
    } else action.run();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] result) {
    super.onRequestPermissionsResult(code, permissions, result);
    if (code == 14) {
      Runnable action = pendingRecordingAction;
      pendingRecordingAction = null;
      if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == PackageManager.PERMISSION_GRANTED
          && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
              == PackageManager.PERMISSION_GRANTED) {
        if (action != null) action.run();
      } else {
        message("Storage permission is needed to save recordings to Gallery.");
        if (panel != null && selected != null) devices();
      }
    } else if (code == 13) {
      if (result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) choose(pendingMode);
      else message("Local network permission is needed to find and connect cameras.");
    } else if (code == 11) {
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        choose(pendingMode);
      else
        message(
            "Camera permission is needed for Viewing Device mode. You can enable it in Android"
                + " Settings.");
    } else if (code == 12
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
      message("Microphone permission is needed for talkback.");
  }

  void start(String next) {
    mode = next;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    network = new LocalNetwork(this);
    network.error = this::message;
    if (next.equals("camera")) cameraScreen();
    else controlScreen();
    main.removeCallbacks(ticker);
    main.post(ticker);
  }

  void header(String label) {
    LinearLayout bar = row();
    bar.setPadding(dp(12), dp(8), dp(12), dp(8));
    bar.addView(button("‹ Modes", this::leave));
    TextView heading = title(label, 18);
    heading.setGravity(Gravity.CENTER);
    bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    if (mode.equals("control")) {
      gridToggleButton =
          iconButton(
              R.drawable.ic_single,
              "Show single feed",
              () -> {
                grid = !grid;
                layoutFeeds();
              });
      bar.addView(gridToggleButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    root.addView(bar);
  }

  void cameraScreen() {
    base();
    TextureView preview = new TextureView(this);
    screen.addView(preview, 0, new FrameLayout.LayoutParams(-1, -1));
    root.setBackgroundColor(Color.TRANSPARENT);
    header("");
    hostStatus = text("Starting camera…", 12, WHITE);
    hostStatus.setGravity(Gravity.END);
    hostStatus.setBackground(shape(0x990b1012, 20));
    hostStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
    root.addView(hostStatus);
    View spacer = new View(this);
    root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    LinearLayout controls = column();
    cameraControls = controls;
    controls.setPadding(dp(12), dp(12), dp(12), dp(12));
    controls.setBackground(shape(0xdd1c2428, 24));
    LinearLayout settings = row();
    addButton(settings, "Video Settings", this::cameraSettings);
    controls.addView(settings);
    LinearLayout zoom = row();
    cameraMinimumZoomButton =
        addButton(
            zoom,
            "Min",
            () -> {
              if (host != null && host.zoomLimits.hasMinimum())
                cameraCommand(Wire.json("command", "smoothZoom", "zoom", host.zoomLimits.minimum));
            });
    cameraMinimumZoomButton.setEnabled(false);
    for (float z : new float[] {1, 3})
      addButton(zoom, z + "×", () -> cameraCommand(Wire.json("command", "smoothZoom", "zoom", z)));
    controls.addView(zoom);
    LinearLayout utility = row();
    addButton(
        utility,
        "Microphone",
        () -> cameraCommand(Wire.json("command", "setMicrophone", "enabled", !host.microphone)));
    addButton(
        utility,
        "Torch",
        () -> cameraCommand(Wire.json("command", "setTorch", "enabled", !host.torch)));
    controls.addView(utility);
    slider(
        controls,
        .01f,
        1,
        host == null ? 1 : host.torchLevel,
        v -> cameraCommand(Wire.json("command", "setTorch", "enabled", host.torch, "level", v)));
    TextView torchNote = text("Torch brightness depends on Android camera support.", 11, MUTED);
    controls.addView(torchNote);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.setMargins(dp(16), dp(8), dp(16), dp(22));
    root.addView(controls, cp);
    host = new CameraHost(this, preview, network);
    host.error = this::message;
    CameraHost activeHost = host;
    host.changed =
        () ->
            main.post(() -> {
              if (host == activeHost && cameraMinimumZoomButton != null) {
                String minimumLabel = activeHost.zoomLimits.minimumLabel();
                if (!minimumLabel.contentEquals(cameraMinimumZoomButton.getText()))
                  cameraMinimumZoomButton.setText(minimumLabel);
                cameraMinimumZoomButton.setEnabled(activeHost.zoomLimits.hasMinimum());
                cameraMinimumZoomButton.setContentDescription(
                    "Minimum camera zoom: " + activeHost.zoomLimits.minimumLabel());
              }
            });
    preview.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s, int w, int h) {
            host.start();
          }

          public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {
            if (host != null) host.transformPreview();
          }

          public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) {
            return true;
          }

          public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {}
        });
    ScaleGestureDetector pinch =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScale(ScaleGestureDetector d) {
                cameraCommand(
                    Wire.json("command", "setZoom", "zoom", host.zoom * d.getScaleFactor()));
                return true;
              }
            });
    preview.setOnTouchListener((v, event) -> {
      pinch.onTouchEvent(event);
      if (event.getAction() == MotionEvent.ACTION_UP && !pinch.isInProgress()) {
        controls.setVisibility(View.VISIBLE);
        v.performClick();
        autoHide();
      }
      return true;
    });
    autoHide();
  }

  final Runnable hide =
      () -> {
        if (cameraControls != null) cameraControls.setVisibility(View.INVISIBLE);
      };

  void autoHide() {
    main.removeCallbacks(hide);
    main.postDelayed(hide, 6000);
  }

  void cameraCommand(JSONObject command) {
    if (host == null) return;
    if (command.optString("command").equals("setMicrophone")
        && command.optBoolean("enabled")
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 12);
      return;
    }
    host.handler.post(() -> {
      try {
        host.apply(command);
      } catch (Exception e) {
        message(e.getMessage());
      }
    });
    autoHide();
  }

  void cameraSettings() {
    main.removeCallbacks(hide);
    LinearLayout body = column();
    body.setPadding(dp(20), dp(12), dp(20), dp(16));
    body.addView(title("Video Format", 22));
    body.addView(
        new VideoFormatPicker(
            this,
            host.videoFormats,
            host.resolution,
            host.fps,
            (resolution, fps) ->
                cameraCommand(
                    Wire.json("command", "setVideoFormat", "resolution", resolution, "fps", fps))));
    activeDialog =
        new AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton("Done", (d, w) -> autoHide())
            .show();
  }

  void controlScreen() {
    base();
    header("Control Device");
    boolean landscape =
        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    split = landscape ? row() : column();
    root.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
    feedColumn = column();
    feedStatus = text("No feed selected\nDiscovering local cameras", 14, MUTED);
    feedStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
    feedColumn.addView(feedStatus);
    feedSwitcher = new HorizontalScrollView(this);
    feedSwitcher.setHorizontalScrollBarEnabled(false);
    feedTabs = row();
    feedTabs.setPadding(dp(8), dp(2), dp(8), dp(6));
    feedSwitcher.addView(feedTabs);
    feedSwitcher.setContentDescription("Connected camera selection");
    feedColumn.addView(feedSwitcher, new LinearLayout.LayoutParams(-1, dp(58)));
    feedArea = new FeedGrid(this);
    feedColumn.addView(feedArea, new LinearLayout.LayoutParams(-1, 0, 1));
    split.addView(
        feedColumn,
        landscape
            ? new LinearLayout.LayoutParams(0, -1, 1)
            : new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    controlScroll = scroll;
    scroll.setFillViewport(false);
    panel = column();
    panel.setPadding(dp(14), dp(8), dp(14), dp(20));
    scroll.addView(panel);
    split.addView(
        scroll,
        landscape
            ? new LinearLayout.LayoutParams(dp(330), -1)
            : new LinearLayout.LayoutParams(-1, dp(340)));
    network.changed = () -> main.post(this::devices);
    network.discover();
    devices();
    layoutFeeds();
  }

  LinearLayout card(String label) {
    LinearLayout card = column();
    card.setPadding(dp(14), dp(12), dp(14), dp(12));
    card.setBackground(shape(CARD, 8));
    card.addView(title(label, 17));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, 0, 0, dp(12));
    panel.addView(card, p);
    return card;
  }

  String cameraName(String identity) {
    return new CameraNicknames(this).name(identity);
  }

  void saveCameraNickname(String identity, String nickname) {
    new CameraNicknames(this).save(identity, nickname);
    layoutFeeds();
    devices();
    if (selected != null && feedStatus != null)
      feedStatus.setText(cameraName(selected.device.name) + " · " + selected.state);
  }

  void renameCamera(String identity) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    input.setText(cameraName(identity));
    input.selectAll();
    input.setHint("Camera nickname");
    input.setContentDescription("Camera nickname");
    LinearLayout body = column();
    body.setPadding(dp(20), dp(8), dp(20), 0);
    body.addView(text("Device: " + identity, 12, MUTED));
    body.addView(input);
    activeDialog =
        new AlertDialog.Builder(this)
            .setTitle("Rename camera")
            .setView(body)
            .setPositiveButton(
                "Save", (dialog, which) -> saveCameraNickname(identity, input.getText().toString()))
            .setNeutralButton("Reset", (dialog, which) -> saveCameraNickname(identity, ""))
            .setNegativeButton("Cancel", null)
            .create();
    activeDialog.show();
    input.requestFocus();
    activeDialog
        .getWindow()
        .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
  }

  LinearLayout cameraNameRow(String identity, Runnable select) {
    LinearLayout line = row();
    TextView label = text(cameraName(identity), 13, WHITE);
    label.setTag("camera-name");
    label.setMaxLines(1);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.setMaxWidth(dp(200));
    label.setPadding(dp(8), 0, 0, 0);
    if (select != null) {
      label.setOnClickListener(v -> select.run());
      line.setOnClickListener(v -> select.run());
    }
    line.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
    label.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton edit =
        iconButton(
            R.drawable.ic_edit, "Rename " + cameraName(identity), () -> renameCamera(identity));
    edit.setBackground(new InsetDrawable(getDrawable(R.drawable.control_icon_background), dp(8)));
    edit.setPadding(dp(15), dp(15), dp(15), dp(15));
    line.addView(edit, new LinearLayout.LayoutParams(dp(48), dp(48)));
    return line;
  }

  void devices() {
    if (panel == null || !mode.equals("control")) return;
    if (feeds.stream().anyMatch(feed -> feed.talking)) {
      pendingDeviceRefresh = true;
      refreshFullscreenControls();
      return;
    }
    pendingDeviceRefresh = false;
    panel.removeAllViews();
    cameraList = card("Cameras");
    cameraList.addView(text("Connect both phones to the same Wi-Fi network.", 12, MUTED));
    if (network.devices.isEmpty())
      cameraList.addView(text("Searching for KittenCam devices…", 13, MUTED));
    for (LocalNetwork.Device d : network.devices.values()) {
      LinearLayout line = row();
      Feed existing =
          feeds.stream().filter(f -> f.device.name.equals(d.name)).findFirst().orElse(null);
      LinearLayout details = column();
      details.addView(cameraNameRow(d.name, existing == null ? null : () -> selectFeed(existing)));
      details.addView(text(d.ready() ? "Video ready" : "Waiting for services…", 12, MUTED));
      line.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
      Button connect =
          button(
              existing == null ? "Connect" : "Disconnect",
              () -> {
                if (existing != null) disconnect(existing);
                else connect(d);
              });
      connect.setEnabled(d.ready() || existing != null);
      line.addView(connect);
      cameraList.addView(line);
    }
    cameraList.addView(
        button(
            "Refresh",
            () -> {
              for (Feed f : feeds) f.refresh();
              if (network != null) network.checkDevices();
              devices();
            }));
    if (selected != null) feedControls();
  }

  void connect(LocalNetwork.Device d) {
    TextureView view = new TextureView(this);
    Feed f = new Feed(this, d, view);
    f.error = this::message;
    f.disconnected = () -> main.post(() -> lostFeed(f));
    f.changed =
        () ->
            main.post(() -> {
              VideoFormats reported = f.remoteFormats();
              String signature =
                  (reported == null ? "legacy" : reported.toJson().toString())
                      + "|"
                      + f.remoteZoomLimits().signature()
                      + "|"
                      + f.usesLegacyIosFormats();
              if (!f.closed
                  && selected == f
                  && f.status.has("resolution")
                  && (!f.uiStatusLoaded || !signature.equals(f.uiCapabilitySignature))) {
                f.uiStatusLoaded = true;
                f.uiCapabilitySignature = signature;
                devices();
              }
              if (pendingDeviceRefresh && !feeds.stream().anyMatch(feed -> feed.talking)) devices();
              refreshFullscreenControls();
            });
    feeds.add(f);
    selected = f;
    layoutFeeds();
    devices();
  }

  void lostFeed(Feed feed) {
    if (!feeds.contains(feed) || feed.closed) return;
    if (network != null) network.unavailable(feed.device);
    disconnect(feed);
  }

  void disconnect(Feed f) {
    f.close();
    feeds.remove(f);
    expandButtons.remove(f);
    if (selected == f) selected = feeds.isEmpty() ? null : feeds.get(0);
    if (fullScreen) {
      if (selected == null) exitFullscreen();
      else showFullscreenControls();
    }
    layoutFeeds();
    devices();
  }

  void selectFeed(Feed feed) {
    if (feed == null || !feeds.contains(feed)) return;
    boolean changed = selected != feed;
    if (changed && selected != null && selected.talking) selected.talk(false);
    selected = feed;
    if (fullScreen && changed) showFullscreenControls();
    layoutFeeds();
    if (changed) devices();
    if (feedStatus != null) feedStatus.setText(cameraName(feed.device.name) + " · " + feed.state);
    refreshFullscreenControls();
  }

  void updateFeedSwitcher() {
    if (feedSwitcher == null || feedTabs == null) return;
    boolean visible = !fullScreen && !grid && feeds.size() > 1;
    feedSwitcher.setVisibility(visible ? View.VISIBLE : View.GONE);
    feedTabs.removeAllViews();
    if (!visible) return;
    for (Feed feed : feeds) {
      LinearLayout tab = cameraNameRow(feed.device.name, () -> selectFeed(feed));
      tab.setSelected(feed == selected);
      tab.setContentDescription("Select camera " + cameraName(feed.device.name));
      GradientDrawable background = shape(feed == selected ? 0xff204e49 : CARD, 20);
      background.setStroke(
          dp(feed == selected ? 2 : 1), feed == selected ? 0xff54d8be : 0x33ffffff);
      tab.setBackground(background);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
      params.setMargins(dp(4), 0, dp(4), 0);
      feedTabs.addView(tab, params);
      if (feed == selected)
        tab.post(() ->
            tab.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, tab.getWidth(), tab.getHeight()), true));
    }
  }

  void layoutFeeds() {
    if (feedArea == null) return;
    updateFeedSwitcher();
    if (gridToggleButton != null) {
      gridToggleButton.setImageResource(grid ? R.drawable.ic_single : R.drawable.ic_grid);
      gridToggleButton.setContentDescription(grid ? "Show single feed" : "Show camera grid");
    }
    feedArea.grid = grid && !fullScreen;
    feedArea.requestLayout();
    // Keep TextureViews attached: detaching destroys their decoder surfaces.
    for (int i = feedArea.getChildCount() - 1; i >= 0; i--) {
      View v = feedArea.getChildAt(i);
      if (v.getTag() == null || feeds.stream().noneMatch(f -> f == v.getTag()))
        feedArea.removeViewAt(i);
    }
    if (feeds.isEmpty()) {
      feedArea.removeAllViews();
      LinearLayout empty = column();
      empty.setGravity(Gravity.CENTER);
      empty.addView(text("◉", 44, CYAN));
      empty.addView(title("No cameras connected", 20));
      empty.addView(
          text(
              "Start Viewing Device mode on another phone,\nthen connect to its feed below.",
              13,
              MUTED));
      feedArea.addView(empty, new LinearLayout.LayoutParams(-1, -1));
      return;
    }
    for (Feed f : feeds) {
      View existing = null;
      for (int i = 0; i < feedArea.getChildCount(); i++)
        if (feedArea.getChildAt(i).getTag() == f) existing = feedArea.getChildAt(i);
      if (existing == null) {
        FrameLayout container = new FrameLayout(this);
        container.setTag(f);
        container.setBackground(shape(Color.BLACK, 8));
        container.setClipToOutline(true);
        container.setPadding(dp(2), dp(2), dp(2), dp(2));
        container.setOnClickListener(v -> selectFeed(f));
        container.addView(f.view, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout name = cameraNameRow(f.device.name, () -> selectFeed(f));
        name.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams nameParams =
            new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.START);
        nameParams.setMarginEnd(dp(52));
        container.addView(name, nameParams);
        ImageButton expand =
            iconButton(
                R.drawable.ic_fullscreen,
                "Enter fullscreen for " + cameraName(f.device.name),
                () -> fullscreen(f));
        expandButtons.put(f, expand);
        FrameLayout.LayoutParams ep =
            new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        container.addView(expand, ep);
        feedArea.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));
        f.view.setOnClickListener(v -> selectFeed(f));
        gesture(f);
        existing = container;
      }
      TextView label = existing.findViewWithTag("camera-name");
      if (label != null) {
        label.setText(cameraName(f.device.name));
        ViewGroup nameRow = (ViewGroup) label.getParent();
        nameRow.getChildAt(1).setContentDescription("Rename " + cameraName(f.device.name));
      }
      boolean active = selected == f;
      existing.setSelected(active);
      GradientDrawable border = shape(Color.TRANSPARENT, 8);
      border.setStroke(dp(active ? 2 : 1), active ? 0xff54d8be : 0x44ffffff);
      ((FrameLayout) existing).setForeground(border);
      existing.setContentDescription(
          cameraName(f.device.name) + (active ? ", active feed" : ", select feed"));
      ImageButton expand = expandButtons.get(f);
      if (expand != null) {
        boolean expanded = fullScreen && active;
        expand.setImageResource(
            expanded ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        expand.setContentDescription(
            (expanded ? "Exit fullscreen for " : "Enter fullscreen for ")
                + cameraName(f.device.name));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            expand, expanded ? "Exit fullscreen" : "Enter fullscreen");
      }
      existing.setVisibility((fullScreen ? active : grid || active) ? View.VISIBLE : View.GONE);
    }
  }

  void gesture(Feed f) {
    ScaleGestureDetector scale =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              float focusX, focusY;

              public boolean onScaleBegin(ScaleGestureDetector d) {
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                return true;
              }

              public boolean onScale(ScaleGestureDetector d) {
                f.viewport.zoomAt(
                    d.getScaleFactor(), focusX, focusY, f.view.getWidth(), f.view.getHeight());
                f.viewport.x += d.getFocusX() - focusX;
                f.viewport.y += d.getFocusY() - focusY;
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                f.transform();
                return true;
              }
            });
    float[] last = {0, 0}, down = {0, 0};
    boolean[] multiple = {false};
    f.view.setOnTouchListener((v, e) -> {
      scale.onTouchEvent(e);
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          down[0] = e.getRawX();
          down[1] = e.getRawY();
          last[0] = e.getX();
          last[1] = e.getY();
          multiple[0] = false;
          break;
        case MotionEvent.ACTION_POINTER_DOWN:
          multiple[0] = true;
          break;
        case MotionEvent.ACTION_POINTER_UP:
          // Re-anchor panning to the surviving finger, never the lifted pointer.
          int remaining = e.getActionIndex() == 0 ? 1 : 0;
          last[0] = e.getX(remaining);
          last[1] = e.getY(remaining);
          break;
        case MotionEvent.ACTION_MOVE:
          if (e.getPointerCount() == 1 && !scale.isInProgress() && f.viewport.scale > 1) {
            f.viewport.x += e.getX() - last[0];
            f.viewport.y += e.getY() - last[1];
            f.transform();
          }
          last[0] = e.getX();
          last[1] = e.getY();
          break;
        case MotionEvent.ACTION_UP:
          float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
          if (fullScreen
              && !multiple[0]
              && f.viewport.scale <= 1.01f
              && Math.abs(dx) >= Math.max(dp(64), v.getWidth() * .15f)
              && Math.abs(dx) > Math.abs(dy) * 1.5f) {
            switchFullscreenFeed(dx < 0 ? 1 : -1);
            return true;
          }
          f.transform();
          if (!multiple[0] && Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12)) v.performClick();
          break;
        default:
          break;
      }
      return true;
    });
  }

  void switchFullscreenFeed(int direction) {
    if (!fullScreen || feeds.size() < 2) return;
    int index = feeds.indexOf(selected);
    selectFeed(feeds.get(Math.floorMod(index + direction, feeds.size())));
  }

  void fullscreen(Feed feed) {
    if (fullScreen && selected == feed) {
      exitFullscreen();
      return;
    }
    selectFeed(feed);
    fullScreen = true;
    root.getChildAt(0).setVisibility(View.GONE);
    feedStatus.setVisibility(View.GONE);
    controlScroll.setVisibility(View.GONE);
    showFullscreenControls();
    layoutFeeds();
    feed.refresh();
  }

  void showFullscreenControls() {
    if (fullscreenControls != null) screen.removeView(fullscreenControls);
    if (fullscreenHint != null) screen.removeView(fullscreenHint);
    fullscreenHint = null;
    fullscreenControls = null;
    fullscreenTorch = null;
    fullscreenAudio = null;
    fullscreenTalk = null;
    if (!fullScreen || selected == null) return;
    Feed feed = selected;
    fullscreenControls = row();
    fullscreenControls.setPadding(dp(6), dp(6), dp(6), dp(6));
    fullscreenControls.setBackground(shape(0xdd101719, 28));
    fullscreenTorch =
        iconButton(
            R.drawable.ic_torch_off, "Turn torch on", () -> feed.setTorch(!feed.torchEnabled()));
    fullscreenTalk = new HoldToTalkButton(this, feed, () -> allowTalk(feed));
    fullscreenAudio =
        iconButton(
            R.drawable.ic_volume_off,
            "Enable audio monitoring",
            () -> {
              feed.setMonitoring(!feed.monitoring);
              refreshFullscreenControls();
            });
    for (View control : new View[] {fullscreenTorch, fullscreenTalk, fullscreenAudio}) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
      params.setMargins(dp(3), 0, dp(3), 0);
      fullscreenControls.addView(control, params);
    }
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, dp(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    params.setMargins(dp(12), 0, dp(12), dp(16));
    screen.addView(fullscreenControls, params);
    if (feeds.size() > 1) {
      fullscreenHint = text("Swipe L/R to Switch Feeds", 13, WHITE);
      fullscreenHint.setGravity(Gravity.CENTER);
      fullscreenHint.setPadding(dp(12), dp(6), dp(12), dp(6));
      fullscreenHint.setBackground(shape(0xcc101719, 16));
      FrameLayout.LayoutParams hintParams =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      hintParams.setMargins(dp(12), 0, dp(12), dp(84));
      screen.addView(fullscreenHint, hintParams);
    }
    refreshFullscreenControls();
  }

  void refreshFullscreenControls() {
    if (!fullScreen || selected == null || fullscreenControls == null) return;
    boolean torch = selected.torchEnabled();
    fullscreenTorch.setImageResource(torch ? R.drawable.ic_torch : R.drawable.ic_torch_off);
    fullscreenTorch.setSelected(torch);
    fullscreenTorch.setContentDescription(torch ? "Turn torch off" : "Turn torch on");
    fullscreenTorch.setImageTintList(
        android.content.res.ColorStateList.valueOf(torch ? 0xffffd36b : WHITE));
    fullscreenAudio.setImageResource(
        selected.monitoring ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
    fullscreenAudio.setSelected(selected.monitoring);
    fullscreenAudio.setContentDescription(
        selected.monitoring ? "Mute audio monitoring" : "Enable audio monitoring");
    fullscreenAudio.setImageTintList(
        android.content.res.ColorStateList.valueOf(selected.monitoring ? 0xff54d8be : WHITE));
    androidx.appcompat.widget.TooltipCompat.setTooltipText(
        fullscreenTorch, fullscreenTorch.getContentDescription());
    androidx.appcompat.widget.TooltipCompat.setTooltipText(
        fullscreenAudio, fullscreenAudio.getContentDescription());
    fullscreenTalk.refresh();
  }

  void exitFullscreen() {
    if (!fullScreen) return;
    if (selected != null && selected.talking) selected.talk(false);
    fullScreen = false;
    if (fullscreenControls != null) screen.removeView(fullscreenControls);
    if (fullscreenHint != null) screen.removeView(fullscreenHint);
    fullscreenHint = null;
    fullscreenControls = null;
    fullscreenTorch = null;
    fullscreenAudio = null;
    fullscreenTalk = null;
    root.getChildAt(0).setVisibility(View.VISIBLE);
    feedStatus.setVisibility(View.VISIBLE);
    controlScroll.setVisibility(View.VISIBLE);
    layoutFeeds();
  }

  boolean allowTalk(Feed feed) {
    if (feed.closed || feed.device.endpoints[2] == null) {
      Toast.makeText(this, "Talkback is not available yet", Toast.LENGTH_SHORT).show();
      return false;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 12);
      return false;
    }
    if (feed.status.optInt("speakerVolume", -1) == 0)
      Toast.makeText(
              this,
              "Turn up media volume on the viewing device to hear talkback",
              Toast.LENGTH_LONG)
          .show();
    return true;
  }

  LinearLayout zoomPresetRow(ZoomLimits limits, java.util.function.Consumer<Float> selected) {
    LinearLayout presets = row();
    Button minimum =
        addButton(
            presets,
            limits.minimumLabel(),
            () -> {
              if (limits.hasMinimum()) selected.accept(limits.minimum);
            });
    minimum.setEnabled(limits.hasMinimum());
    minimum.setContentDescription("Minimum camera zoom: " + limits.minimumLabel());
    for (float z : new float[] {1, 3}) addButton(presets, z + "×", () -> selected.accept(z));
    return presets;
  }

  void feedControls() {
    Feed f = selected;
    LinearLayout zoom = card("Zoom");
    ZoomLimits zoomLimits = f.remoteZoomLimits();
    slider(
        zoom,
        zoomLimits.minimumOr(.5f),
        zoomLimits.maximumOr(15),
        (float) f.status.optDouble("zoom", 1),
        f::setLiveZoom);
    zoom.addView(
        zoomPresetRow(
            zoomLimits, value -> f.command(Wire.json("command", "smoothZoom", "zoom", value))));
    if (!zoomLimits.hasMinimum())
      zoom.addView(text("This camera does not report its minimum zoom.", 12, MUTED));
    LinearLayout torch = card("Torch");
    toggle(torch, "Enabled", f.torchEnabled(), f::setTorch);
    slider(torch, .01f, 1, (float) f.status.optDouble("torchLevel", 1), f::setTorchLevel);
    torch.addView(text("Brightness is device dependent.", 12, MUTED));
    LinearLayout audio = card("Microphone");
    toggle(audio, "Audio monitoring", f.monitoring, f::setMonitoring);
    toggle(
        audio,
        "Remote microphone",
        f.status.optBoolean("microphoneEnabled"),
        v -> f.command(Wire.json("command", "setMicrophone", "enabled", v)));
    HoldToTalkButton talk = new HoldToTalkButton(this, f, () -> allowTalk(f));
    audio.addView(talk, new LinearLayout.LayoutParams(-1, dp(48)));
    audio.addView(
        text(
            "Hold to speak through the viewing device speaker. Monitoring is muted while held.",
            12,
            MUTED));
    LinearLayout record = card("Recording");
    Button rec = button(f.recording ? "Stop recording" : "Record", () -> {});
    rec.setOnClickListener(v -> {
      if (f.recording) f.stopRecording();
      else
        withRecordingPermission(() -> {
          if (!f.closed) f.startRecording(true);
          rec.setText(f.recording ? "Stop recording" : "Record");
        });
      rec.setText(f.recording ? "Stop recording" : "Record");
    });
    record.addView(rec);
    toggle(
        record,
        "Motion-triggered",
        f.motion,
        v -> {
          if (v)
            withRecordingPermission(() -> {
              if (!f.closed) {
                f.motion = true;
                f.previous = null;
              }
            });
          else f.motion = false;
          f.previous = null;
          if (!v && f.recording && !f.manualRecording) f.stopRecording();
        });
    spinner(
        record,
        new String[] {"Low", "Medium", "High", "Very High"},
        f.sensitivity == .28
            ? "Low"
            : f.sensitivity == .11 ? "High" : f.sensitivity == .065 ? "Very High" : "Medium",
        s ->
            f.sensitivity =
                s.equals("Low")
                    ? .28
                    : s.equals("High") ? .11 : s.equals("Very High") ? .065 : .18);
    TextView duration = text("Minimum clip: " + f.minimumClip + "s", 12, MUTED);
    record.addView(duration);
    slider(
        record,
        1,
        600,
        f.minimumClip,
        v -> {
          f.minimumClip = Math.round(v);
          duration.setText("Minimum clip: " + f.minimumClip + "s");
        });
    record.addView(
        text(
            "Video + audio MP4 · Saved to Gallery / KittenCam Recordings\n" + f.lastRecording,
            12,
            MUTED));
    LinearLayout format = card("Video Format");
    format.addView(
        new VideoFormatPicker(
            this,
            f.remoteFormats(),
            f.status.optString("resolution", "Waiting…"),
            f.status.optInt("fps", 30),
            (resolution, fps) ->
                f.command(
                    Wire.json("command", "setVideoFormat", "resolution", resolution, "fps", fps))));
    if (f.usesLegacyIosFormats())
      format.addView(
          text("Available resolution/frame-rate combinations depend on the camera.", 12, MUTED));
    LinearLayout stats = card("Stream");
    stats.addView(
        text(
            "Select a feed to control it. Tap ⛶ for fullscreen; pinch and drag to inspect video.",
            12,
            MUTED));
    stats.addView(
        button(
            "Refresh camera status",
            () -> {
              f.refresh();
              main.postDelayed(this::devices, 1200);
            }));
  }

  void toggle(
      LinearLayout parent,
      String label,
      boolean initial,
      java.util.function.Consumer<Boolean> change) {
    Switch toggle = new Switch(this);
    toggle.setText(label);
    toggle.setTextColor(WHITE);
    toggle.setTextSize(14);
    toggle.setPadding(0, dp(8), 0, dp(8));
    toggle.setChecked(initial);
    toggle.setOnCheckedChangeListener((b, v) -> change.accept(v));
    parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
  }

  void slider(
      LinearLayout parent,
      float min,
      float max,
      float initial,
      java.util.function.Consumer<Float> change) {
    SeekBar s = new SeekBar(this);
    s.setMax(1000);
    s.setEnabled(max > min);
    s.setProgress(Math.round((initial - min) / (max - min) * 1000));
    s.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int p, boolean user) {
            if (user) change.accept(min + (max - min) * p / 1000);
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {
            change.accept(min + (max - min) * b.getProgress() / 1000);
          }
        });
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(44)));
  }

  void spinner(
      LinearLayout parent,
      String[] options,
      String initial,
      java.util.function.Consumer<String> change) {
    Spinner spinner = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
    spinner.setAdapter(adapter);
    int selected = Arrays.asList(options).indexOf(initial);
    spinner.setSelection(Math.max(0, selected));
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          boolean first = true;

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            if (first) {
              first = false;
              return;
            }
            change.accept(options[pos]);
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
  }

  long lastMessage;

  void message(String s) {
    main.post(() -> {
      if (isFinishing() || isDestroyed() || s == null) return;
      android.util.Log.e("KittenCam", s);
      long now = System.currentTimeMillis();
      if (now - lastMessage < 2000) return;
      lastMessage = now;
      if (activeDialog != null) activeDialog.dismiss();
      activeDialog =
          new AlertDialog.Builder(this)
              .setTitle("KittenCam")
              .setMessage(s)
              .setPositiveButton("OK", null)
              .show();
    });
  }

  void cleanup() {
    if (activeDialog != null) {
      activeDialog.dismiss();
      activeDialog = null;
    }
    fullScreen = false;
    fullscreenHint = null;
    fullscreenControls = null;
    fullscreenTorch = null;
    fullscreenAudio = null;
    fullscreenTalk = null;
    expandButtons.clear();
    gridToggleButton = null;
    feedSwitcher = null;
    feedTabs = null;
    pendingDeviceRefresh = false;
    main.removeCallbacks(ticker);
    main.removeCallbacks(hide);
    for (Feed f : new ArrayList<>(feeds)) f.close();
    feeds.clear();
    selected = null;
    if (host != null) {
      host.close();
      host = null;
    }
    if (network != null) {
      network.close();
      network = null;
    }
    panel = null;
    feedArea = null;
    cameraControls = null;
    cameraMinimumZoomButton = null;
  }

  void leave() {
    cleanup();
    modes();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (!mode.isEmpty()) leave();
  }

  @Override
  protected void onDestroy() {
    cleanup();
    super.onDestroy();
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    if (mode.equals("control") && split != null) {
      boolean landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
      split.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      feedColumn.setLayoutParams(
          landscape
              ? new LinearLayout.LayoutParams(0, -1, 1)
              : new LinearLayout.LayoutParams(-1, 0, 1));
      controlScroll.setLayoutParams(
          landscape
              ? new LinearLayout.LayoutParams(dp(330), -1)
              : new LinearLayout.LayoutParams(-1, dp(340)));
    }
    if (host != null) {
      host.updateDisplayRotation();
    } /* Active surfaces stay attached through rotation. */
  }
}
