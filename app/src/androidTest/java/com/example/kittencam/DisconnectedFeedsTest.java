package com.example.kittencam;

import static com.example.kittencam.VirtualDeviceTest.until;
import static org.junit.Assert.*;

import android.net.nsd.NsdServiceInfo;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DisconnectedFeedsTest {
  static final class Camera implements AutoCloseable {
    final ServerSocket status = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    final ServerSocket video = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    final ExecutorService workers = Executors.newCachedThreadPool();
    final ConcurrentLinkedQueue<Socket> clients = new ConcurrentLinkedQueue<>();
    final String name;
    volatile boolean closed, silent;

    Camera(String name) throws Exception {
      this.name = name;
      workers.execute(() -> {
        while (!closed)
          try {
            Socket client = status.accept();
            workers.execute(() -> {
              try (client) {
                var input = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) {}
                byte[] body =
                    Wire.bytes(
                        Wire.json(
                            "success",
                            true,
                            "resolution",
                            "1080p",
                            "fps",
                            30,
                            "minimumZoom",
                            1,
                            "maximumZoom",
                            8,
                            "microphoneEnabled",
                            false));
                client
                    .getOutputStream()
                    .write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length
                                + "\r\nConnection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
              } catch (Exception ignored) {
              }
            });
          } catch (Exception ignored) {
            return;
          }
      });
      workers.execute(() -> {
        while (!closed)
          try {
            Socket client = video.accept();
            clients.add(client);
            workers.execute(() -> {
              try (client) {
                Wire.write(
                    client.getOutputStream(),
                    1,
                    Wire.bytes(
                        Wire.json(
                            "encodedWidth",
                            640,
                            "encodedHeight",
                            360,
                            "orientation",
                            "landscapeLeft",
                            "fps",
                            30)),
                    false);
                while (!closed) {
                  if (!silent) Wire.write(client.getOutputStream(), 4, new byte[8], false);
                  Thread.sleep(100);
                }
              } catch (Exception ignored) {
              } finally {
                clients.remove(client);
              }
            });
          } catch (Exception ignored) {
            return;
          }
      });
    }

    NsdServiceInfo endpoint(int kind) {
      NsdServiceInfo info = new NsdServiceInfo();
      info.setServiceName(name);
      info.setServiceType(LocalNetwork.TYPES[kind]);
      info.setHost(InetAddress.getLoopbackAddress());
      info.setPort(kind == 0 ? status.getLocalPort() : video.getLocalPort());
      return info;
    }

    void discover(LocalNetwork network) {
      network.resolved(endpoint(0), 0);
      network.resolved(endpoint(1), 1);
    }

    void blackhole() throws Exception {
      silent = true;
      status
          .close(); // Simulates power loss: no HTTP response and no TCP close from the video peer.
    }

    public void close() {
      closed = true;
      try {
        status.close();
      } catch (Exception ignored) {
      }
      try {
        video.close();
      } catch (Exception ignored) {
      }
      for (Socket client : clients)
        try {
          client.close();
        } catch (Exception ignored) {
        }
      workers.shutdownNow();
    }
  }

  @Test
  public void closedCameraDisappearsAndFullscreenSelectsTheRemainingFeed() throws Exception {
    try (Camera first = new Camera("Lost camera fixture");
        Camera second = new Camera("Remaining camera fixture");
        var scenario = ActivityScenario.launch(MainActivity.class)) {
      AtomicReference<MainActivity> activity = new AtomicReference<>();
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("control");
        first.discover(a.network);
        second.discover(a.network);
      });
      MainActivity a = activity.get();
      until(
          "Both reachable cameras are listed",
          () ->
              a.network.devices.containsKey(first.name)
                  && a.network.devices.containsKey(second.name));
      AtomicReference<Feed> lost = new AtomicReference<>(), remaining = new AtomicReference<>();
      scenario.onActivity(current -> {
        current.connect(current.network.devices.get(first.name));
        lost.set(current.selected);
        current.connect(current.network.devices.get(second.name));
        remaining.set(current.selected);
      });
      until(
          "Both video connections open",
          () -> lost.get().state.equals("Live") && remaining.get().state.equals("Live"));
      AtomicReference<View> card = new AtomicReference<>();
      scenario.onActivity(current -> {
        current.fullscreen(lost.get());
        card.set((View) lost.get().view.getParent());
      });
      first.close();
      until("Lost feed closes", () -> lost.get().closed);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(current -> {
        assertFalse(current.feeds.contains(lost.get()));
        assertFalse(current.network.devices.containsKey(first.name));
        assertTrue(current.network.devices.containsKey(second.name));
        assertNull(card.get().getParent());
        assertSame(remaining.get(), current.selected);
        assertTrue(current.fullScreen);
        assertSame(remaining.get(), current.fullscreenTalk.feed);
        assertNull(current.fullscreenHint);
        current.disconnect(remaining.get());
        assertFalse(current.fullScreen);
        assertTrue(
            "Manual disconnect keeps a reachable camera available",
            current.network.devices.containsKey(second.name));
        current.leave();
      });
    }
  }

  @Test
  public void silentPowerLossTimesOutAndRemovesTheLastFeed() throws Exception {
    try (Camera camera = new Camera("Silent camera fixture");
        var scenario = ActivityScenario.launch(MainActivity.class)) {
      AtomicReference<MainActivity> activity = new AtomicReference<>();
      AtomicReference<Feed> feed = new AtomicReference<>();
      scenario.onActivity(a -> {
        activity.set(a);
        a.choose("control");
        camera.discover(a.network);
      });
      MainActivity a = activity.get();
      until("Camera listed", () -> a.network.devices.containsKey(camera.name));
      scenario.onActivity(current -> {
        current.connect(current.network.devices.get(camera.name));
        feed.set(current.selected);
      });
      until("Video connected", () -> feed.get().state.equals("Live") && feed.get().packets > 0);
      scenario.onActivity(current -> current.fullscreen(feed.get()));
      camera.blackhole();
      until("Silent video stream reaches read timeout", () -> feed.get().closed);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(current -> {
        assertTrue(current.feeds.isEmpty());
        assertNull(current.selected);
        assertFalse(current.fullScreen);
        assertNull(current.fullscreenControls);
        assertFalse(current.network.devices.containsKey(camera.name));
        current.leave();
      });
    }
  }

  @Test
  public void discoveryPrunesDeadAdvertisementsAndRestoresReachableCameras() throws Exception {
    try (Camera camera = new Camera("Probe camera fixture");
        LocalNetwork network =
            new LocalNetwork(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
      camera.discover(network);
      until("Reachability probe publishes camera", () -> network.devices.containsKey(camera.name));
      LocalNetwork.Device device = network.devices.get(camera.name);
      until("Initial probe finishes", () -> !device.probing.get());
      java.util.concurrent.atomic.AtomicInteger changes =
          new java.util.concurrent.atomic.AtomicInteger();
      network.changed = changes::incrementAndGet;
      network.resolved(camera.endpoint(2), 2);
      assertEquals("Late talk service refreshes controller controls", 1, changes.get());
      until("Talk-service probe finishes", () -> !device.probing.get());
      network.removeEndpoint(camera.name, 2);
      assertEquals("Talk loss updates controls without removing the camera", 2, changes.get());
      assertTrue(network.devices.containsKey(camera.name));
      network.removeEndpoint(camera.name, 1);
      assertFalse(
          "Losing the video advertisement removes availability immediately",
          network.devices.containsKey(camera.name));
      camera.discover(network);
      until(
          "A returning service is checked and listed",
          () -> network.devices.containsKey(camera.name));
      until("Returning service probe finishes", () -> !device.probing.get());
      network.unavailable(device);
      assertFalse(network.devices.containsKey(camera.name));
      network.checkDevices();
      until(
          "Reachable camera returns without relying on another mDNS announcement",
          () -> network.devices.containsKey(camera.name));
      until("Probe finishes", () -> !device.probing.get());
      camera.close();
      network.checkDevices();
      until("First failed probe completes", () -> !device.probing.get());
      assertTrue(
          "A single missed status request is tolerated", network.devices.containsKey(camera.name));
      network.checkDevices();
      until(
          "Two failures remove the stale advertisement",
          () -> !network.devices.containsKey(camera.name));
      network.removeEndpoint(camera.name, 1);
      assertFalse(device.ready());
    }
  }
}
