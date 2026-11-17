package com.example.kittencam;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

final class Feed implements AutoCloseable {
  final Activity activity;
  final LocalNetwork.Device device;
  final TextureView view;
  final FeedViewport viewport = new FeedViewport();
  final ExecutorService io = Executors.newCachedThreadPool();
  final ExecutorService commandIo = Executors.newSingleThreadExecutor();
  final AudioLink.Playback playback = new AudioLink.Playback();
  volatile boolean closed, monitoring, talking, talkReady, recording, motion;
  volatile JSONObject monitoringConfiguration;
  volatile long talkPacketsSent, talkBytesSent;
  volatile int talkGeneration;
  volatile long packets, frames, dropped, audioFrames, decoded;
  volatile JSONObject status = new JSONObject();
  volatile JSONObject streamInformation = new JSONObject();
  volatile String state = "Connecting…";
  Runnable changed = () -> {};
  Runnable disconnected = () -> {};
  java.util.function.Consumer<String> error = s -> {};
  Socket socket, talkSocket;
  AudioLink.Capture talkCapture;
  MediaCodec decoder;
  Surface surface;
  android.graphics.SurfaceTexture retainedTexture;
  boolean connecting, uiStatusLoaded;
  String uiCapabilitySignature;
  volatile VideoFormats streamFormats;
  MediaFormat format;
  int width = 1920, height = 1080, rotation;
  byte[] configuration;
  boolean decoderWaitingKey = true;
  MediaMuxer muxer;
  AudioVideoRecording avRecording;
  long recordStart = -1;
  Uri recordingUri;
  File legacyRecordingFile;
  volatile Uri savedRecordingUri;
  String lastRecording = "";
  boolean waitingKey, manualRecording;
  double motionLevel;
  double sensitivity = .18;
  int minimumClip = 8;
  long lastMotion;
  int[] previous;

  Feed(Activity a, LocalNetwork.Device d, TextureView v) {
    activity = a;
    device = d;
    view = v;
    view.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t, int w, int h) {
            if (closed) return;
            if (retainedTexture != null && retainedTexture != t)
              view.setSurfaceTexture(retainedTexture);
            else retainedTexture = t;
            if (!connecting) connect();
            transform();
          }

          public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t, int w, int h) {
            transform();
          }

          public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t) {
            if (closed) return true;
            retainedTexture = t;
            return false;
          }

          public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t) {
            decoded++;
            sampleMotion();
          }
        });
    if (view.isAvailable()) connect();
  }

  void connect() {
    if (connecting || closed) return;
    connecting = true;
    io.execute(() -> {
      try {
        var endpoint = device.endpoints[1];
        if (endpoint == null) throw new IOException("Video service is unavailable");
        socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 5000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);
        state = "Live";
        changed.run();
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        while (!closed) {
          Wire.Packet p = Wire.read(in, false);
          packets++;
          handle(p);
        }
      } catch (Exception e) {
        if (!closed) {
          state = "Disconnected";
          if (!(e instanceof EOFException)
              && !(e instanceof SocketException)
              && !(e instanceof SocketTimeoutException))
            error.accept(e.getMessage() == null ? "Video connection lost" : e.getMessage());
          changed.run();
          disconnected.run();
        }
      } finally {
        stopRecording();
        releaseDecoder();
        if (socket != null)
          try {
            socket.close();
          } catch (Exception ignored) {
          }
      }
    });
    refresh();
  }

  void handle(Wire.Packet p) throws Exception {
    byte[] b = p.payload();
    switch (p.type()) {
      case 1:
        JSONObject info = new JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
        int newW = info.optInt("encodedWidth", width), newH = info.optInt("encodedHeight", height);
        streamInformation = info;
        rotation = StreamOrientation.displayRotation(info);
        width = newW;
        height = newH;
        if (info.has("supportedVideoFormats")) {
          streamFormats = VideoFormats.fromStatus(info);
        }
        changed.run();
        activity.runOnUiThread(this::transform);
        break;
      case 2:
        configure(b);
        break;
      case 3:
        if (b.length < 9) throw new IOException("Truncated video packet");
        frames++;
        boolean key = b[0] == 1;
        long pts = ByteBuffer.wrap(b, 1, 8).getLong();
        if (key) decoderWaitingKey = false;
        if (!decoderWaitingKey) decode(b, pts);
        record(b, pts, key);
        break;
      case 5:
        throw new IOException(new String(b, java.nio.charset.StandardCharsets.UTF_8));
      case 6:
        configureRecordingAudio(
            new JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8)));
        prepareMonitoring();
        break;
      case 7:
        audioFrames++;
        recordAudio(b);
        if (b.length >= 12 && monitoring && !talking) playback.play(b, 12);
        break;
    }
  }

  synchronized void configure(byte[] b) throws Exception {
    if (Arrays.equals(configuration, b) && decoder != null) return;
    if (recording) stopRecording();
    releaseDecoder();
    configuration = b.clone();
    decoderWaitingKey = true;
    ByteBuffer p = ByteBuffer.wrap(b);
    if (p.remaining() < 8) throw new IOException("Invalid H.264 configuration");
    int n = p.getInt();
    if (n <= 0 || n > p.remaining() - 4) throw new IOException("Invalid SPS");
    byte[] sps = new byte[n];
    p.get(sps);
    n = p.getInt();
    if (n <= 0 || n > p.remaining()) throw new IOException("Invalid PPS");
    byte[] pps = new byte[n];
    p.get(pps);
    format = MediaFormat.createVideoFormat("video/avc", width, height);
    format.setByteBuffer("csd-0", ByteBuffer.wrap(Wire.annex(sps)));
    format.setByteBuffer("csd-1", ByteBuffer.wrap(Wire.annex(pps)));
    surface = new Surface(retainedTexture != null ? retainedTexture : view.getSurfaceTexture());
    decoder = MediaCodec.createDecoderByType("video/avc");
    decoder.configure(format, surface, null, 0);
    decoder.start();
  }

  synchronized void decode(byte[] p, long pts) {
    if (decoder == null) return;
    try {
      drain();
      int index = decoder.dequeueInputBuffer(1000);
      if (index < 0) {
        dropped++;
        return;
      }
      ByteBuffer input = decoder.getInputBuffer(index);
      if (input.capacity() < p.length - 9) {
        dropped++;
        decoder.queueInputBuffer(index, 0, 0, pts, 0);
        return;
      }
      input.put(p, 9, p.length - 9);
      decoder.queueInputBuffer(index, 0, p.length - 9, pts, 0);
      drain();
    } catch (Exception e) {
      if (dropped == 0) android.util.Log.e("KittenCam", "Video decoding failed", e);
      dropped++;
    }
  }

  void drain() {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int out;
    while (true) {
      out = decoder.dequeueOutputBuffer(info, 0);
      if (out >= 0) decoder.releaseOutputBuffer(out, view.isShown());
      else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
    }
  }

  void transform() {
    if (view.getWidth() == 0 || view.getHeight() == 0) return;
    float w = view.getWidth(), h = view.getHeight();
    boolean swap = rotation % 180 != 0;
    float dw = swap ? height : width, dh = swap ? width : height;
    float scale = Math.min(w / dw, h / dh);
    android.graphics.Matrix m = new android.graphics.Matrix();
    m.setScale(width * scale / w, height * scale / h, w / 2, h / 2);
    m.postRotate(rotation, w / 2, h / 2);
    viewport.constrain(w, h, dw * scale, dh * scale);
    m.postScale(viewport.scale, viewport.scale, w / 2, h / 2);
    m.postTranslate(viewport.x, viewport.y);
    view.setTransform(m);
  }

  ZoomLimits remoteZoomLimits() {
    ZoomLimits reported = ZoomLimits.fromMetadata(status, streamInformation);
    if (!reported.hasMinimum() && isIosCamera()) return new ZoomLimits(.5f, reported.maximum);
    return reported;
  }

  boolean isIosCamera() {
    String identity = device.name;
    for (JSONObject metadata : new JSONObject[] {status, streamInformation}) {
      if (metadata == null) continue;
      String platform = metadata.optString("platform", metadata.optString("operatingSystem", ""));
      if (platform.equalsIgnoreCase("ios") || platform.equalsIgnoreCase("ipados")) return true;
      identity += " " + metadata.optString("deviceName") + " " + metadata.optString("deviceModel");
    }
    String lower = identity.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod");
  }

  VideoFormats reportedFormats() {
    VideoFormats fromStatus = VideoFormats.fromStatus(status);
    return fromStatus != null ? fromStatus : streamFormats;
  }

  boolean usesLegacyIosFormats() {
    return reportedFormats() == null && isIosCamera();
  }

  VideoFormats remoteFormats() {
    VideoFormats reported = reportedFormats();
    return reported != null ? reported : isIosCamera() ? VideoFormats.LEGACY_IOS : null;
  }

  void refresh() {
    command(null);
  }

  final Map<String, JSONObject> pendingControls = new HashMap<>();
  volatile Boolean requestedTorch;

  boolean torchEnabled() {
    Boolean requested = requestedTorch;
    return requested != null ? requested : status.optBoolean("torchEnabled");
  }

  void setTorch(boolean enabled) {
    requestedTorch = enabled;
    command(Wire.json("command", "setTorch", "enabled", enabled));
  }

  void setTorchLevel(float level) {
    latestControl("torch", Wire.json("command", "setTorch", "level", level));
  }

  void setLiveZoom(float zoom) {
    latestControl("zoom", Wire.json("command", "setZoom", "zoom", zoom));
  }

  void latestControl(String key, JSONObject value) {
    synchronized (pendingControls) {
      if (closed) return;
      boolean scheduled = pendingControls.containsKey(key);
      pendingControls.put(key, value);
      if (scheduled) return;
    }
    commandIo.execute(() -> {
      while (!closed) {
        JSONObject next;
        synchronized (pendingControls) {
          next = pendingControls.get(key);
          if (next == null) {
            pendingControls.remove(key);
            return;
          }
          pendingControls.put(key, null);
        }
        if (key.equals("torch")) {
          try {
            next.put("enabled", torchEnabled());
          } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(e);
          }
        }
        executeCommand(next);
      }
    });
  }

  void command(JSONObject command) {
    if (closed) return;
    commandIo.execute(() -> executeCommand(command));
  }

  void executeCommand(JSONObject command) {
    if (closed) return;
    HttpURLConnection c = null;
    try {
      var e = device.endpoints[0];
      if (e == null) throw new IOException("Command service unavailable");
      String host = e.getHost().getHostAddress();
      if (host.contains(":")) host = "[" + host + "]";
      c =
          (HttpURLConnection)
              new URL(
                      "http://"
                          + host
                          + ":"
                          + e.getPort()
                          + (command == null ? "/status" : "/command"))
                  .openConnection();
      c.setConnectTimeout(4000);
      c.setReadTimeout(10000);
      if (command != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(Wire.bytes(command));
      }
      InputStream stream = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      byte[] buffer = new byte[2048];
      int count;
      while ((count = stream.read(buffer)) != -1) {
        received.write(buffer, 0, count);
        if (received.size() > 65536) throw new IOException("Response too large");
      }
      String body = new String(received.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
      JSONObject s = new JSONObject(body);
      if (!s.optBoolean("success")) throw new IOException(s.optString("message", "Command failed"));
      status = s;
      Boolean requested = requestedTorch;
      if (requested != null && s.has("torchEnabled") && s.optBoolean("torchEnabled") == requested)
        requestedTorch = null;
      changed.run();
    } catch (Exception ex) {
      if (!closed) error.accept(ex.getMessage());
    } finally {
      if (c != null) c.disconnect();
    }
  }

  void setMonitoring(boolean enabled) {
    if (closed) return;
    monitoring = enabled;
    if (!enabled) playback.close();
    else io.execute(this::prepareMonitoring);
    changed.run();
  }

  void prepareMonitoring() {
    if (closed || !monitoring || talking || monitoringConfiguration == null) return;
    try {
      playback.configure(monitoringConfiguration);
      if (closed || !monitoring || talking) playback.close();
    } catch (Exception failure) {
      monitoring = false;
      playback.close();
      error.accept("Audio monitoring unavailable: " + failure.getMessage());
      changed.run();
    }
  }

  synchronized void talk(boolean enabled) {
    if (closed && enabled) return;
    if (enabled == talking && (enabled || (talkSocket == null && talkCapture == null))) return;
    int generation = ++talkGeneration;
    talking = enabled;
    talkReady = false;
    if (talkCapture != null) {
      talkCapture.close();
      talkCapture = null;
    }
    if (talkSocket != null) {
      try {
        talkSocket.close();
      } catch (Exception ignored) {
      }
      talkSocket = null;
    }
    if (!enabled) {
      if (!closed && monitoring) io.execute(this::prepareMonitoring);
      changed.run();
      return;
    }
    playback.close();
    changed.run();
    io.execute(() -> {
      Socket connection = new Socket();
      try {
        var endpoint = device.endpoints[2];
        if (endpoint == null) throw new IOException("Talkback service is not available yet");
        synchronized (this) {
          if (generation != talkGeneration || !talking || closed) {
            connection.close();
            return;
          }
          talkSocket = connection;
        }
        connection.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 4000);
        connection.setTcpNoDelay(true);
        synchronized (this) {
          if (generation != talkGeneration || !talking || closed) {
            connection.close();
            return;
          }
          talkCapture =
              new AudioLink.Capture(
                  true,
                  (type, payload) -> {
                    if (generation != talkGeneration || !talking || closed)
                      throw new IOException("Talkback stopped");
                    Wire.write(connection.getOutputStream(), type, payload, true);
                    synchronized (Feed.this) {
                      if (generation != talkGeneration || !talking || closed) return;
                      talkPacketsSent++;
                      if (type == 2) {
                        talkBytesSent += payload.length - 4;
                        if (!talkReady) {
                          talkReady = true;
                          changed.run();
                        }
                      }
                    }
                  },
                  message -> talkFailed(generation, message));
        }
      } catch (Exception failure) {
        try {
          connection.close();
        } catch (Exception ignored) {
        }
        talkFailed(generation, failure.getMessage());
      }
    });
  }

  synchronized void talkFailed(int generation, String message) {
    if (generation != talkGeneration || !talking || closed) return;
    talk(false);
    error.accept(message == null ? "Talkback failed" : message);
  }

  synchronized void startRecording(boolean manual) {
    if (recording) return;
    try {
      if (format == null) throw new IOException("Wait for video before recording");
      if (monitoringConfiguration == null || !status.optBoolean("microphoneEnabled", true))
        throw new IOException("Enable the remote microphone before recording with audio.");
      savedRecordingUri = null;
      String name = "KittenCam-" + System.currentTimeMillis() + ".mp4";
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KittenCam Recordings");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        recordingUri =
            activity
                .getContentResolver()
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (recordingUri == null) throw new IOException("Unable to create recording");
        try (ParcelFileDescriptor fd =
            activity.getContentResolver().openFileDescriptor(recordingUri, "w")) {
          muxer =
              new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
      } else {
        File folder =
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "KittenCam Recordings");
        if (!folder.exists() && !folder.mkdirs())
          throw new IOException("Recording folder unavailable");
        legacyRecordingFile = new File(folder, name);
        muxer =
            new MediaMuxer(
                legacyRecordingFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      }
      avRecording = new AudioVideoRecording(muxer, format, monitoringConfiguration, rotation);
      recordStart = -1;
      waitingKey = true;
      manualRecording = manual;
      recording = true;
      lastRecording = name + " (video + audio)";
      changed.run();
    } catch (Exception e) {
      error.accept(e.getMessage());
      stopRecording();
    }
  }

  synchronized void record(byte[] b, long pts, boolean key) {
    if (!recording || muxer == null) return;
    try {
      avRecording.video(b, pts, key);
      recordStart = avRecording.start;
      waitingKey = recordStart < 0;
    } catch (Exception e) {
      error.accept("Recording failed: " + e.getMessage());
      stopRecording();
    }
  }

  synchronized void configureRecordingAudio(JSONObject config) {
    if (avRecording != null
        && (avRecording.rate != config.optInt("sampleRate")
            || avRecording.channels != config.optInt("channels"))) stopRecording();
    monitoringConfiguration = config;
  }

  synchronized void recordAudio(byte[] packet) {
    if (!recording || avRecording == null) return;
    try {
      avRecording.audio(packet);
    } catch (Exception e) {
      error.accept("Recording audio failed: " + e.getMessage());
      stopRecording();
    }
  }

  synchronized void stopRecording() {
    boolean valid = false;
    if (muxer != null) {
      try {
        valid = avRecording != null && avRecording.finish();
        if (avRecording != null && avRecording.started) muxer.stop();
      } catch (Exception failure) {
        valid = false;
        error.accept("Unable to finalize recording: " + failure.getMessage());
      }
      avRecording = null;
      muxer.release();
      muxer = null;
    }
    if (recordingUri != null) {
      if (valid) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        activity.getContentResolver().update(recordingUri, v, null, null);
        savedRecordingUri = recordingUri;
      } else activity.getContentResolver().delete(recordingUri, null, null);
      recordingUri = null;
    }
    if (legacyRecordingFile != null) {
      File completed = legacyRecordingFile;
      legacyRecordingFile = null;
      if (valid) {
        MediaScannerConnection.scanFile(
            activity,
            new String[] {completed.getAbsolutePath()},
            new String[] {"video/mp4"},
            (path, uri) -> {
              savedRecordingUri = uri;
              if (uri == null) error.accept("Recording saved, but Gallery indexing failed.");
              changed.run();
            });
      } else if (completed.exists() && !completed.delete()) {
        error.accept("Unable to remove an incomplete recording.");
      }
    }
    recording = false;
    changed.run();
  }

  long lastSample;

  void sampleMotion() {
    long now = System.currentTimeMillis();
    if (!motion || now - lastSample < 400) return;
    lastSample = now;
    Bitmap b = view.getBitmap(32, 18);
    if (b == null) return;
    int[] pixels = new int[576];
    b.getPixels(pixels, 0, 32, 0, 0, 32, 18);
    b.recycle();
    double difference = 0;
    if (previous != null) {
      for (int i = 0; i < pixels.length; i++) {
        int p = pixels[i], q = previous[i];
        difference += Math.abs(luma(p) - luma(q)) / 255.0;
      }
      motionLevel = difference / pixels.length;
      if (motionLevel >= sensitivity) {
        lastMotion = now;
        if (!recording) startRecording(false);
      }
      if (recording && !manualRecording && now - lastMotion > minimumClip * 1000L) stopRecording();
    }
    previous = pixels;
  }

  static double luma(int p) {
    return ((p >> 16) & 255) * .299 + ((p >> 8) & 255) * .587 + (p & 255) * .114;
  }

  synchronized void releaseDecoder() {
    if (decoder != null) {
      try {
        decoder.stop();
      } catch (Exception ignored) {
      }
      decoder.release();
      decoder = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    talk(false);
    if (socket != null)
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    stopRecording();
    playback.close();
    io.shutdownNow();
    commandIo.shutdownNow();
    // Decoder release is synchronized with the stream thread before its retained surface is freed.
    releaseDecoder();
    if (retainedTexture != null && !view.isAvailable()) {
      retainedTexture.release();
      retainedTexture = null;
    }
  }
}
