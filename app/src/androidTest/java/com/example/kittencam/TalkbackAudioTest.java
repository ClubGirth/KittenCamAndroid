package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TalkbackAudioTest {
  @Before
  public void permissions() {
    new VirtualDeviceTest().grantMediaPermissions();
  }

  static final class Receiver implements AutoCloseable {
    final ServerSocket server;
    final ExecutorService workers = Executors.newCachedThreadPool();
    final AtomicLong packets = new AtomicLong(), bytes = new AtomicLong();
    volatile boolean closed;

    Receiver() throws Exception {
      server = new ServerSocket(0);
      workers.execute(() -> {
        while (!closed)
          try {
            Socket socket = server.accept();
            workers.execute(() -> {
              try (socket) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (!closed) {
                  Wire.Packet p = Wire.read(in, true);
                  packets.incrementAndGet();
                  if (p.type() == 2) bytes.addAndGet(p.payload().length - 4);
                }
              } catch (Exception ignored) {
              }
            });
          } catch (Exception ignored) {
          }
      });
    }

    LocalNetwork.Device device() throws Exception {
      var device = new LocalNetwork.Device("Talkback test only");
      var endpoint = new NsdServiceInfo();
      endpoint.setHost(InetAddress.getLoopbackAddress());
      endpoint.setPort(server.getLocalPort());
      device.endpoints[2] = endpoint;
      return device;
    }

    public void close() {
      closed = true;
      try {
        server.close();
      } catch (Exception ignored) {
      }
      workers.shutdownNow();
    }
  }

  @Test
  public void microphonePacketsSurviveRapidStopStartWithoutOldSessionCancellingNewOne()
      throws Exception {
    AtomicReference<Feed> value = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    try (Receiver receiver = new Receiver();
        var scenario = ActivityScenario.launch(MainActivity.class)) {
      var device = receiver.device();
      scenario.onActivity(a -> {
        Feed f = new Feed(a, device, new TextureView(a));
        f.error = error::set;
        value.set(f);
      });
      Feed feed = value.get();
      for (int i = 0; i < 4; i++) {
        feed.talk(true);
        Thread.sleep(35);
        feed.talk(false);
      }
      feed.talk(true);
      until(
          "Real microphone PCM reaches the socket",
          () -> feed.talkReady && receiver.bytes.get() > 2000);
      assertTrue(feed.talking);
      assertTrue(feed.talkCapture.active);
      assertTrue(feed.talkCapture.bytesCaptured > 0);
      assertTrue(receiver.packets.get() > 2);
      Thread.sleep(250);
      assertTrue("A cancelled earlier session must not stop the latest press", feed.talking);
      assertNull(error.get());
      feed.talk(false);
      assertFalse(feed.talking);
      assertFalse(feed.talkReady);
      feed.close();
    } finally {
      if (value.get() != null) value.get().close();
    }
  }

  @Test
  public void holdingThroughAScrollGestureKeepsTalkingAndReleaseStops() throws Exception {
    AtomicReference<Feed> value = new AtomicReference<>();
    AtomicReference<HoldToTalkButton> button = new AtomicReference<>();
    try (Receiver receiver = new Receiver();
        var scenario = ActivityScenario.launch(MainActivity.class)) {
      var device = receiver.device();
      scenario.onActivity(a -> {
        Feed f = new Feed(a, device, new TextureView(a));
        value.set(f);
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = a.column();
        scroll.addView(content);
        HoldToTalkButton hold = new HoldToTalkButton(a, f, () -> true);
        button.set(hold);
        content.addView(hold, new LinearLayout.LayoutParams(a.dp(180), a.dp(48)));
        content.addView(new View(a), new LinearLayout.LayoutParams(1, a.dp(900)));
        a.screen.addView(scroll, new FrameLayout.LayoutParams(a.dp(200), a.dp(300)));
      });
      scenario.onActivity(a -> {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 30, 20, 0);
        button.get().dispatchTouchEvent(e);
        e.recycle();
      });
      until(
          "Hold starts microphone packets",
          () -> receiver.bytes.get() > 2000 && value.get().talking);
      scenario.onActivity(a -> {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now - 200, now, MotionEvent.ACTION_MOVE, 30, 180, 0);
        button.get().dispatchTouchEvent(e);
        e.recycle();
        assertTrue(value.get().talking);
      });
      scenario.onActivity(a -> {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now - 300, now, MotionEvent.ACTION_UP, 30, 180, 0);
        button.get().dispatchTouchEvent(e);
        e.recycle();
        assertFalse(value.get().talking);
      });
    } finally {
      if (value.get() != null) value.get().close();
    }
  }

  @Test
  public void speakerPlaybackConsumesPcmOnBuiltInSpeaker() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AudioLink.Playback playback = new AudioLink.Playback(context, true);
    try {
      playback.configure(AudioLink.configuration());
      // Fill the device's native buffer so streaming playback starts on both Samsungs.
      byte[] silence = new byte[Math.max(9600, playback.track.getBufferSizeInFrames() * 4)];
      int count = playback.play(silence, 0);
      assertEquals(silence.length, count);
      playback.finish();
      assertTrue(playback.playedFrames() > 0);
      assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, playback.outputDeviceType());
    } finally {
      playback.close();
    }
  }
}
