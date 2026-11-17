package com.example.kittencam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.display.DisplayManager;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Camera2 surface encoding keeps raw frames on the Galaxy hardware encoder. */
final class CameraHost implements AutoCloseable {
  final Activity activity;
  final TextureView preview;
  final LocalNetwork network;
  final HandlerThread worker = new HandlerThread("KittenCam camera");
  final Handler handler;
  final DisplayManager displays;
  final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}

        public void onDisplayRemoved(int id) {}

        public void onDisplayChanged(int id) {
          if (id == activity.getWindowManager().getDefaultDisplay().getDisplayId())
            updateDisplayRotation();
        }
      };
  final ExecutorService io = Executors.newCachedThreadPool();
  final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
  final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  final List<Client> clients = new CopyOnWriteArrayList<>();
  volatile boolean closed;
  volatile byte[] config;
  CameraDevice camera;
  CameraManager cameraManager;
  final Map<String, CameraCharacteristics> lensCharacteristics = new LinkedHashMap<>();
  volatile LensRouter lensRouter;
  LensRouter.Lens desiredLens, openingLens, activeLens;
  CameraDevice closingCamera;
  volatile String activeCameraId = "";
  volatile boolean lensSwitching = true;
  boolean initialCameraOpen = true;
  CameraCaptureSession session;
  MediaCodec encoder;
  Surface input, display;
  CameraCharacteristics characteristics;
  volatile VideoFormats videoFormats = VideoFormats.EMPTY;
  String encoderName;
  CaptureRequest.Builder request;
  volatile float zoom = 1, torchLevel = 1;
  volatile ZoomLimits zoomLimits = ZoomLimits.UNKNOWN;
  volatile boolean torch, microphone;
  volatile String resolution = "1080p";
  volatile int fps = 30;
  int width = 1920, height = 1080, commandPort;
  volatile int rotation = 90;
  volatile long orientationRevision;
  AudioLink.Capture capture;
  volatile byte[] audioConfiguration = Wire.bytes(AudioLink.configuration());
  volatile int audioSampleRate = 48000;
  volatile long talkBackPacketsReceived, talkBackBytesReceived, talkBackFramesPlayed;
  volatile int talkBackOutputDeviceType;
  Runnable changed = () -> {};
  java.util.function.Consumer<String> error = s -> {};

  CameraHost(Activity a, TextureView t, LocalNetwork n) {
    activity = a;
    preview = t;
    network = n;
    worker.start();
    handler = new Handler(worker.getLooper());
    displays = (DisplayManager) a.getSystemService(Activity.DISPLAY_SERVICE);
    displays.registerDisplayListener(displayListener, new Handler(a.getMainLooper()));
  }

  void start() {
    io.execute(() -> {
      try {
        if (closed) return;
        listen(0);
        if (closed) return;
        listen(1);
        if (closed) return;
        listen(2);
        if (!closed) handler.post(this::openCamera);
      } catch (Exception e) {
        fail(e);
      }
    });
  }

  synchronized void listen(int kind) throws Exception {
    if (closed) return;
    ServerSocket server = new ServerSocket(0);
    servers.add(server);
    if (kind == 0) commandPort = server.getLocalPort();
    network.advertise(kind, server.getLocalPort());
    changed.run();
    io.execute(() -> {
      while (!closed)
        try {
          Socket socket = server.accept();
          socket.setTcpNoDelay(true);
          sockets.add(socket);
          io.execute(() -> {
            try {
              if (kind == 0) http(socket);
              else if (kind == 1) stream(socket);
              else talk(socket);
            } catch (Exception e) {
              if (!closed
                  && kind == 2
                  && !(e instanceof EOFException)
                  && !(e instanceof SocketException)) fail(e);
            } finally {
              sockets.remove(socket);
              try {
                socket.close();
              } catch (Exception ignored) {
              }
            }
          });
        } catch (Exception e) {
          if (!closed) fail(e);
        }
    });
  }

  void stream(Socket s) throws Exception {
    Client c = new Client(s);
    clients.add(c);
    try {
      c.send(1, Wire.bytes(information()));
      if (config != null) c.send(2, config);
      if (microphone) c.send(6, audioConfiguration);
      while (!closed && !s.isClosed()) {
        byte[] p = c.queue.poll(2, TimeUnit.SECONDS);
        if (p != null) s.getOutputStream().write(p);
        else Wire.write(s.getOutputStream(), 4, new byte[0], false);
      }
    } finally {
      clients.remove(c);
    }
  }

  static final class Client {
    final Socket socket;
    final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(30);

    Client(Socket s) {
      socket = s;
    }

    void send(int type, byte[] p) {
      byte[] packet =
          ByteBuffer.allocate(p.length + 6)
              .put((byte) 1)
              .put((byte) type)
              .putInt(p.length)
              .put(p)
              .array();
      if (!queue.offer(packet))
        try {
          socket.close();
        } catch (Exception ignored) {
        }
    }
  }

  void broadcast(int type, byte[] p) {
    if (type == 6) {
      audioConfiguration = p;
      try {
        audioSampleRate =
            new JSONObject(new String(p, java.nio.charset.StandardCharsets.UTF_8))
                .getInt("sampleRate");
      } catch (Exception ignored) {
      }
    }
    for (Client c : clients) c.send(type, p);
  }

  JSONObject information() {
    StreamOrientation orientation = StreamOrientation.fromClockwise(rotation);
    return zoomLimits.addTo(
        Wire.json(
            "protocolVersion",
            1,
            "deviceName",
            Build.MODEL,
            "streamType",
            "h264",
            "transport",
            "tcp",
            "encodedWidth",
            width,
            "encodedHeight",
            height,
            "fps",
            fps,
            "supportedVideoFormats",
            videoFormats.toJson(),
            "rotationDegreesClockwise",
            orientation.clockwise,
            "rotationDegrees",
            orientation.clockwise,
            "displayRotationDegrees",
            orientation.clockwise,
            "rotationDegreesCounterClockwise",
            orientation.counterclockwise,
            "encodedOrientation",
            "landscapeRight",
            "orientation",
            orientation.wireName,
            "deviceOrientation",
            orientation.wireName,
            "videoOrientation",
            orientation.wireName,
            "isPortrait",
            orientation.portrait,
            "requiresDimensionSwap",
            orientation.portrait,
            "mirrorHorizontally",
            false,
            "cameraPosition",
            "back",
            "orientationRevision",
            orientationRevision,
            "orientationTimestampMilliseconds",
            System.currentTimeMillis(),
            "displayWidth",
            orientation.portrait ? height : width,
            "displayHeight",
            orientation.portrait ? width : height,
            "audioEnabled",
            microphone,
            "audioCodec",
            "pcm_s16le",
            "audioSampleRate",
            audioSampleRate,
            "audioChannels",
            1,
            "audioBitsPerChannel",
            16));
  }

  JSONObject status(boolean success, String message) {
    return zoomLimits.addTo(
        Wire.json(
            "success",
            success,
            "message",
            message,
            "zoom",
            zoom,
            "activeCameraId",
            activeCameraId,
            "lensSwitching",
            lensSwitching,
            "talkBackPacketsReceived",
            talkBackPacketsReceived,
            "talkBackBytesReceived",
            talkBackBytesReceived,
            "talkBackFramesPlayed",
            talkBackFramesPlayed,
            "talkBackOutputDeviceType",
            talkBackOutputDeviceType,
            "speakerVolume",
            ((AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE))
                .getStreamVolume(AudioManager.STREAM_MUSIC),
            "torchEnabled",
            torch,
            "torchLevel",
            torchLevel,
            "resolution",
            resolution,
            "fps",
            fps,
            "supportedVideoFormats",
            videoFormats.toJson(),
            "microphoneEnabled",
            microphone,
            "microphoneAuthorized",
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
            "microphoneMode",
            "Wide capture"));
  }

  void http(Socket s) throws Exception {
    s.setSoTimeout(5000);
    BufferedInputStream in = new BufferedInputStream(s.getInputStream());
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    int state = 0, b;
    while (state < 4 && (b = in.read()) != -1) {
      header.write(b);
      state =
          (state == 0 && b == 13)
              ? 1
              : (state == 1 && b == 10)
                  ? 2
                  : (state == 2 && b == 13) ? 3 : (state == 3 && b == 10) ? 4 : 0;
      if (header.size() > 16384) throw new IOException("Headers too large");
    }
    String h = header.toString("UTF-8");
    String[] lines = h.split("\r\n");
    int length = 0;
    for (String line : lines)
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
        length = Integer.parseInt(line.substring(15).trim());
    if (length < 0 || length > 65536) throw new IOException("Invalid body length");
    byte[] body = new byte[length];
    new DataInputStream(in).readFully(body);
    int code = 200;
    JSONObject result;
    try {
      if (lines[0].startsWith("GET /status ")) result = status(true, "KittenCam is ready.");
      else if (lines[0].startsWith("POST /command ")) {
        JSONObject c = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        FutureTask<JSONObject> task =
            new FutureTask<>(() -> {
              apply(c);
              return status(true, "Command applied.");
            });
        handler.post(task);
        result = task.get(8, TimeUnit.SECONDS);
      } else {
        code = 404;
        result = status(false, "Endpoint not found.");
      }
    } catch (Exception e) {
      code = 400;
      result = status(false, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
    }
    byte[] bytes = Wire.bytes(result);
    OutputStream out = s.getOutputStream();
    out.write(
        ("HTTP/1.1 "
                + code
                + (code == 200 ? " OK" : " Error")
                + "\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length
                + "\r\nConnection: close\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    out.write(bytes);
    out.flush();
  }

  void apply(JSONObject c) throws Exception {
    switch (c.getString("command")) {
      case "setZoom":
        zoomGeneration++;
        setZoom((float) c.getDouble("zoom"));
        break;
      case "smoothZoom":
        smoothZoom((float) c.getDouble("zoom"), (float) c.optDouble("rate", 2));
        break;
      case "setTorch":
        boolean requestedTorch = c.getBoolean("enabled");
        if (requestedTorch
            && (characteristics == null
                || !Boolean.TRUE.equals(
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))))
          throw new IOException("This camera does not have a torch.");
        float requestedLevel = (float) c.optDouble("level", torchLevel);
        if (c.has("level")
            && Math.abs(requestedLevel - torchLevel) > .01f
            && !supportsTorchStrength())
          throw new IOException(
              "This phone exposes on/off torch control only while the camera is active.");
        torch = requestedTorch;
        torchLevel = Math.max(.01f, Math.min(1, requestedLevel));
        update();
        break;
      case "setMicrophone":
        setMicrophone(c.getBoolean("enabled"));
        break;
      case "setVideoFormat":
        changeFormat(c.getString("resolution"), c.getInt("fps"));
        break;
      default:
        throw new IOException("Unknown command");
    }
    changed.run();
  }

  int zoomGeneration;

  void smoothZoom(float target, float rate) {
    int generation = ++zoomGeneration;
    float start = zoom;
    long began = SystemClock.elapsedRealtime();
    long duration =
        Math.max(
            120, Math.min(2500, (long) (Math.abs(target - start) / Math.max(.1f, rate) * 1000)));
    Runnable ramp =
        new Runnable() {
          public void run() {
            if (closed || generation != zoomGeneration) return;
            float progress =
                Math.min(1, (SystemClock.elapsedRealtime() - began) / (float) duration);
            float eased = progress * progress * (3 - 2 * progress);
            try {
              setZoom(start + (target - start) * eased);
            } catch (Exception e) {
              fail(e);
              return;
            }
            if (progress < 1) handler.postDelayed(this, 33);
          }
        };
    handler.post(ramp);
  }

  void setZoom(float z) throws Exception {
    zoom = zoomLimits.clamp(z);
    if (lensRouter != null) {
      LensRouter.Lens selected = lensRouter.select(zoom);
      zoom = selected.displayedZoom(zoom);
      if (activeLens == null || !selected.id().equals(activeLens.id()) || camera == null) {
        requestLens(selected);
        changed.run();
        return;
      }
    }
    update();
    changed.run();
  }

  void setMicrophone(boolean enabled) throws Exception {
    if (enabled
        && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
      throw new IOException("Microphone permission is required");
    microphone = enabled;
    if (capture != null) {
      capture.close();
      capture = null;
    }
    if (enabled) capture = new AudioLink.Capture(false, this::broadcast, s -> error.accept(s));
    broadcast(1, Wire.bytes(information()));
    changed.run();
  }

  void changeFormat(String r, int f) throws Exception {
    VideoFormats.Format format = videoFormats.find(r);
    if (format == null) throw new IOException("This camera and encoder do not support " + r);
    if (!videoFormats.supports(r, f))
      throw new IOException("Frame rate unavailable at " + r + ": " + f + " FPS");
    width = format.resolution().width();
    height = format.resolution().height();
    resolution = format.resolution().name();
    fps = f;
    lensSwitching = true;
    closePipeline();
    configure();
  }

  static ZoomLimits readZoomLimits(CameraCharacteristics characteristics) {
    if (Build.VERSION.SDK_INT >= 30) {
      Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
      if (range != null) return new ZoomLimits(range.getLower(), range.getUpper());
    }
    Float maximum = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    return new ZoomLimits(1, maximum == null ? 1 : Math.max(1, maximum));
  }

  void discoverVideoFormats() throws Exception {
    var map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (map == null) throw new IOException("Camera did not report video capabilities");
    Size[] reported = map.getOutputSizes(MediaCodec.class);
    Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
    if (reported == null || previewSizes == null)
      throw new IOException("No video surfaces supported");
    List<Size> outputs = Arrays.asList(reported), previews = Arrays.asList(previewSizes);
    List<VideoFormats.Resolution> candidates = new ArrayList<>();
    VideoFormats.Resolution[] presets = {
      new VideoFormats.Resolution("720p", 1280, 720),
      new VideoFormats.Resolution("1080p", 1920, 1080),
      new VideoFormats.Resolution("1440p", 2560, 1440),
      new VideoFormats.Resolution("4K", 3840, 2160)
    };
    for (var preset : presets) {
      Size size = new Size(preset.width(), preset.height());
      if (outputs.contains(size) && previews.contains(size)) candidates.add(preset);
    }
    List<VideoFormats.RateRange> rates = new ArrayList<>();
    Range<Integer>[] exposure =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    if (exposure != null)
      for (var range : exposure)
        rates.add(new VideoFormats.RateRange(range.getLower(), range.getUpper()));
    MediaCodec probe = MediaCodec.createEncoderByType("video/avc");
    MediaCodecInfo.VideoCapabilities encoderCaps;
    try {
      encoderName = probe.getName();
      encoderCaps = probe.getCodecInfo().getCapabilitiesForType("video/avc").getVideoCapabilities();
    } finally {
      probe.release();
    }
    java.util.function.BiPredicate<VideoFormats.Resolution, Integer> supported =
        (candidate, rate) -> {
          Size size = new Size(candidate.width(), candidate.height());
          long duration = map.getOutputMinFrameDuration(MediaCodec.class, size);
          return (duration <= 0 || rate <= Math.round(1e9 / duration))
              && encoderCaps.areSizeAndRateSupported(candidate.width(), candidate.height(), rate);
        };
    videoFormats = VideoFormats.discover(candidates, rates, supported);
    if (videoFormats.isEmpty()) {
      List<VideoFormats.Resolution> fallback = new ArrayList<>();
      outputs.stream()
          .filter(size ->
              size.getWidth() <= 1920 && size.getHeight() <= 1080 && previews.contains(size))
          .sorted(
              Comparator.comparingLong((Size size) -> (long) size.getWidth() * size.getHeight())
                  .reversed())
          .forEach(size ->
              fallback.add(
                  new VideoFormats.Resolution(
                      size.getWidth() + "×" + size.getHeight(),
                      size.getWidth(),
                      size.getHeight())));
      VideoFormats options = VideoFormats.discover(fallback, rates, supported);
      if (!options.isEmpty())
        videoFormats = new VideoFormats(Collections.singletonList(options.formats.get(0)));
    }
    if (videoFormats.isEmpty())
      throw new IOException("No compatible camera/encoder video formats were reported");
    VideoFormats.Format initial = videoFormats.find("1080p");
    if (initial == null) initial = videoFormats.formats.get(0);
    resolution = initial.resolution().name();
    width = initial.resolution().width();
    height = initial.resolution().height();
    fps = videoFormats.closestRate(resolution, 30);
  }

  void openCamera() {
    if (closed) return;
    try {
      cameraManager = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
      for (String id : cameraManager.getCameraIdList()) {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
        if (Objects.equals(
            c.get(CameraCharacteristics.LENS_FACING), CameraCharacteristics.LENS_FACING_BACK))
          lensCharacteristics.put(id, c);
      }
      if (lensCharacteristics.isEmpty()) throw new IOException("No rear camera available");
      String primaryId = lensCharacteristics.keySet().iterator().next();
      CameraCharacteristics primaryCharacteristics = lensCharacteristics.get(primaryId);
      LensRouter.Lens primary =
          new LensRouter.Lens(primaryId, 1, readZoomLimits(primaryCharacteristics));
      List<LensRouter.Lens> lenses = new ArrayList<>();
      lenses.add(primary);
      float[] primaryFocals =
          primaryCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
      SizeF primarySize =
          primaryCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
      if (primaryFocals != null && primaryFocals.length > 0 && primarySize != null) {
        for (var entry : lensCharacteristics.entrySet()) {
          if (entry.getKey().equals(primaryId)) continue;
          CameraCharacteristics c = entry.getValue();
          float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
          SizeF size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
          var map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
          if (focals == null
              || focals.length == 0
              || size == null
              || map == null
              || map.getOutputSizes(MediaCodec.class) == null
              || map.getOutputSizes(SurfaceTexture.class) == null) continue;
          float base =
              LensRouter.relativeBase(
                  primaryFocals[0], primarySize.getWidth(), focals[0], size.getWidth());
          LensRouter.Lens candidate = new LensRouter.Lens(entry.getKey(), base, readZoomLimits(c));
          if (ZoomLimits.valid(base) && candidate.minimum() < primary.minimum() - .001f)
            lenses.add(candidate);
        }
      }
      lensRouter = new LensRouter(primary, lenses);
      zoomLimits = lensRouter.limits;
      zoom = zoomLimits.clamp(zoom);
      requestLens(lensRouter.select(zoom));
    } catch (Exception e) {
      fail(e);
    }
  }

  void requestLens(LensRouter.Lens lens) {
    if (closed) return;
    desiredLens = lens;
    if (closingCamera != null || openingLens != null) return;
    if (camera != null) {
      if (activeLens != null && activeLens.id().equals(lens.id())) return;
      lensSwitching = true;
      config = null;
      closePipeline();
      closingCamera = camera;
      camera = null;
      closingCamera.close();
      return;
    }
    openRequestedLens();
  }

  void openRequestedLens() {
    if (closed
        || desiredLens == null
        || openingLens != null
        || closingCamera != null
        || camera != null) return;
    final LensRouter.Lens lens = desiredLens;
    openingLens = lens;
    lensSwitching = true;
    try {
      if (activity.checkSelfPermission(Manifest.permission.CAMERA)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Camera permission is required");
      cameraManager.openCamera(
          lens.id(),
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice device) {
              openingLens = null;
              if (closed) {
                device.close();
                return;
              }
              if (!desiredLens.id().equals(lens.id())) {
                closingCamera = device;
                device.close();
                return;
              }
              camera = device;
              activeLens = lens;
              activeCameraId = lens.id();
              characteristics = lensCharacteristics.get(lens.id());
              try {
                String previousResolution = resolution;
                int previousFps = fps;
                discoverVideoFormats();
                VideoFormats.Format previous = videoFormats.find(previousResolution);
                if (previous != null) {
                  resolution = previous.resolution().name();
                  width = previous.resolution().width();
                  height = previous.resolution().height();
                  fps = videoFormats.closestRate(resolution, previousFps);
                }
                if (!Boolean.TRUE.equals(
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) torch = false;
                configure();
                if (initialCameraOpen) {
                  initialCameraOpen = false;
                  if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                      == PackageManager.PERMISSION_GRANTED) setMicrophone(true);
                }
                changed.run();
              } catch (Exception e) {
                lensFailed(lens, device, e);
              }
            }

            public void onClosed(CameraDevice device) {
              if (closingCamera == device) {
                closingCamera = null;
                if (!closed) openRequestedLens();
              }
            }

            public void onDisconnected(CameraDevice device) {
              if (device == closingCamera) {
                device.close();
                return;
              }
              if (closed) {
                device.close();
                return;
              }
              lensFailed(lens, device, new IOException("Camera disconnected"));
            }

            public void onError(CameraDevice device, int code) {
              if (device == closingCamera) {
                device.close();
                return;
              }
              if (closed) {
                device.close();
                return;
              }
              openingLens = null;
              lensFailed(lens, device, new IOException("Camera error: " + code));
            }
          },
          handler);
    } catch (Exception e) {
      openingLens = null;
      lensFailed(lens, null, e);
    }
  }

  void lensFailed(LensRouter.Lens lens, CameraDevice device, Exception error) {
    if (closed) {
      if (device != null) device.close();
      return;
    }
    fail(error);
    if (lensRouter != null && !lens.id().equals(lensRouter.primary.id())) {
      List<LensRouter.Lens> remaining = new ArrayList<>();
      for (var candidate : lensRouter.lenses)
        if (!candidate.id().equals(lens.id())) remaining.add(candidate);
      lensRouter = new LensRouter(lensRouter.primary, remaining);
      zoomLimits = lensRouter.limits;
      zoom = zoomLimits.clamp(zoom);
      desiredLens = lensRouter.primary;
    } else {
      desiredLens = null;
      lensSwitching = false;
    }
    closePipeline();
    camera = null;
    if (device != null) {
      closingCamera = device;
      device.close();
    } else if (desiredLens != null) openRequestedLens();
    changed.run();
  }

  void configure() {
    if (closed || camera == null) return;
    try {
      config = null;
      int sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      int device = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
      setStreamRotation((sensor - device + 360) % 360);
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2000000, width * height * fps / 8));
      format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      encoder = MediaCodec.createByCodecName(encoderName);
      final MediaCodec active = encoder;
      encoder.setCallback(
          new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int index) {}

            public void onError(MediaCodec c, MediaCodec.CodecException e) {
              if (c == encoder) fail(e);
            }

            public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
              if (closed || c != encoder) return;
              try {
                byte[] sps = raw(f.getByteBuffer("csd-0")), pps = raw(f.getByteBuffer("csd-1"));
                sps = strip(sps);
                pps = strip(pps);
                config =
                    ByteBuffer.allocate(sps.length + pps.length + 8)
                        .putInt(sps.length)
                        .put(sps)
                        .putInt(pps.length)
                        .put(pps)
                        .array();
                broadcast(1, Wire.bytes(information()));
                broadcast(2, config);
              } catch (Exception e) {
                fail(e);
              }
            }

            public void onOutputBufferAvailable(
                MediaCodec c, int index, MediaCodec.BufferInfo info) {
              if (closed || c != encoder) return;
              try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                  ByteBuffer data = c.getOutputBuffer(index);
                  data.position(info.offset);
                  data.limit(info.offset + info.size);
                  byte[] p = new byte[info.size];
                  data.get(p);
                  broadcast(
                      3,
                      ByteBuffer.allocate(p.length + 9)
                          .put(
                              (byte) ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? 1 : 0))
                          .putLong(info.presentationTimeUs)
                          .put(p)
                          .array());
                }
              } finally {
                c.releaseOutputBuffer(index, false);
              }
            }
          },
          handler);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      input = encoder.createInputSurface();
      encoder.start();
      activity.runOnUiThread(this::transformPreview);
      SurfaceTexture texture = preview.getSurfaceTexture();
      if (texture == null) throw new IOException("Preview not ready");
      texture.setDefaultBufferSize(width, height);
      display = new Surface(texture);
      request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      request.addTarget(input);
      request.addTarget(display);
      camera.createCaptureSession(
          Arrays.asList(input, display),
          new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession s) {
              if (closed || encoder != active) {
                s.close();
                return;
              }
              session = s;
              try {
                update();
                lensSwitching = false;
                changed.run();
              } catch (Exception e) {
                fail(e);
              }
            }

            public void onConfigureFailed(CameraCaptureSession s) {
              if (closed || encoder != active) return;
              lensFailed(
                  activeLens,
                  camera,
                  new IOException("Camera format unavailable. Try 1080p at 30 FPS."));
            }
          },
          handler);
    } catch (Exception e) {
      fail(e);
    }
  }

  static byte[] raw(ByteBuffer b) {
    ByteBuffer copy = b.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

  static byte[] strip(byte[] b) {
    List<byte[]> n = Wire.nals(b);
    return n.isEmpty() ? b : n.get(0);
  }

  void update() throws Exception {
    if (request == null || session == null) return;
    float nativeZoom = activeLens == null ? zoom : activeLens.nativeZoom(zoom);
    if (Build.VERSION.SDK_INT >= 30
        && characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null)
      request.set(CaptureRequest.CONTROL_ZOOM_RATIO, nativeZoom);
    else {
      Rect rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      int w = (int) (rect.width() / nativeZoom), h = (int) (rect.height() / nativeZoom);
      request.set(
          CaptureRequest.SCALER_CROP_REGION,
          new Rect(
              rect.centerX() - w / 2,
              rect.centerY() - h / 2,
              rect.centerX() + w / 2,
              rect.centerY() + h / 2));
    }
    request.set(
        CaptureRequest.FLASH_MODE,
        torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    Range<Integer>[] ranges =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    Range<Integer> selected = null;
    for (var range : ranges)
      if (range.contains(fps) && (selected == null || range.getLower() > selected.getLower()))
        selected = range;
    if (selected != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected);
    if (Build.VERSION.SDK_INT >= 35 && supportsTorchStrength())
      request.set(
          CaptureRequest.FLASH_STRENGTH_LEVEL,
          Math.max(
              1,
              Math.round(
                  torchLevel
                      * characteristics.get(
                          CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL))));
    session.setRepeatingRequest(request.build(), null, handler);
  }

  boolean supportsTorchStrength() {
    if (Build.VERSION.SDK_INT < 35 || characteristics == null) return false;
    Integer max = characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
    return max != null && max > 1;
  }

  boolean setStreamRotation(int updated) {
    if (rotation == updated) return false;
    rotation = updated;
    orientationRevision++;
    return true;
  }

  void updateDisplayRotation() {
    final int displayDegrees = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
    handler.post(() -> {
      if (closed || characteristics == null) return;
      int sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      int updated = (sensor - displayDegrees + 360) % 360;
      if (setStreamRotation(updated)) {
        broadcast(1, Wire.bytes(information()));
      }
      activity.runOnUiThread(this::transformPreview);
    });
  }

  /** Camera2 already applies the sensor transform to a TextureView's buffer. */
  static Matrix previewTransform(
      int viewWidth,
      int viewHeight,
      int bufferWidth,
      int bufferHeight,
      int sensorDegrees,
      int displayDegrees) {
    Matrix matrix = new Matrix();
    if (viewWidth <= 0 || viewHeight <= 0) return matrix;
    // The default TextureView content is in the sensor-corrected, natural-display basis.
    boolean sensorSwaps = sensorDegrees % 180 != 0;
    float naturalWidth = sensorSwaps ? bufferHeight : bufferWidth;
    float naturalHeight = sensorSwaps ? bufferWidth : bufferHeight;
    boolean displaySwaps = displayDegrees % 180 != 0;
    float displayedWidth = displaySwaps ? naturalHeight : naturalWidth;
    float displayedHeight = displaySwaps ? naturalWidth : naturalHeight;
    float scale = Math.max(viewWidth / displayedWidth, viewHeight / displayedHeight);
    matrix.setScale(
        naturalWidth * scale / viewWidth,
        naturalHeight * scale / viewHeight,
        viewWidth / 2f,
        viewHeight / 2f);
    matrix.postRotate(-displayDegrees, viewWidth / 2f, viewHeight / 2f);
    return matrix;
  }

  void transformPreview() {
    if (closed || characteristics == null) return;
    int sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    int device = activity.getWindowManager().getDefaultDisplay().getRotation() * 90;
    preview.setTransform(
        previewTransform(preview.getWidth(), preview.getHeight(), width, height, sensor, device));
  }

  void talk(Socket socket) throws Exception {
    AudioLink.Playback playback = new AudioLink.Playback(activity.getApplicationContext(), true);
    long reportedFrames = 0;
    try {
      DataInputStream input = new DataInputStream(socket.getInputStream());
      while (!closed) {
        Wire.Packet packet = Wire.read(input, true);
        talkBackPacketsReceived++;
        if (packet.type() == 1)
          playback.configure(
              new JSONObject(
                  new String(packet.payload(), java.nio.charset.StandardCharsets.UTF_8)));
        else if (packet.type() == 2 && packet.payload().length >= 4) {
          int frames = ByteBuffer.wrap(packet.payload()).getInt();
          if (frames < 0 || (long) frames * playback.channels * 2 != packet.payload().length - 4)
            throw new IOException("Invalid talkback PCM frame length");
          talkBackBytesReceived += packet.payload().length - 4;
          playback.play(packet.payload(), 4);
          long played = playback.playedFrames();
          talkBackFramesPlayed += Math.max(0, played - reportedFrames);
          reportedFrames = played;
          talkBackOutputDeviceType = playback.outputDeviceType();
        }
      }
    } finally {
      playback.finish();
      long played = playback.playedFrames();
      talkBackFramesPlayed += Math.max(0, played - reportedFrames);
      talkBackOutputDeviceType = playback.outputDeviceType();
      playback.close();
    }
  }

  void fail(Exception e) {
    if (!closed) error.accept(e.getMessage() == null ? e.toString() : e.getMessage());
  }

  void closePipeline() {
    if (session != null) {
      session.close();
      session = null;
    }
    if (encoder != null) {
      try {
        encoder.stop();
      } catch (Exception ignored) {
      }
      encoder.release();
      encoder = null;
    }
    if (input != null) {
      input.release();
      input = null;
    }
    if (display != null) {
      display.release();
      display = null;
    }
    request = null;
  }

  public synchronized void close() {
    closed = true;
    displays.unregisterDisplayListener(displayListener);
    if (capture != null) capture.close();
    for (ServerSocket s : servers)
      try {
        s.close();
      } catch (Exception ignored) {
      }
    for (Socket s : sockets)
      try {
        s.close();
      } catch (Exception ignored) {
      }
    handler.post(() -> {
      closePipeline();
      if (camera != null) camera.close();
      worker.quitSafely();
    });
    io.shutdownNow();
  }
}
