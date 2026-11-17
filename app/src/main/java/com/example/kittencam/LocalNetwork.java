package com.example.kittencam;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

final class LocalNetwork implements AutoCloseable {
  static final String[] TYPES = {
    "_kittencam._tcp.", "_kittencam-video._tcp.", "_kittencam-talk._tcp."
  };

  static final class Device {
    final String name;
    final NsdServiceInfo[] endpoints = new NsdServiceInfo[3];
    final java.util.concurrent.atomic.AtomicBoolean probing =
        new java.util.concurrent.atomic.AtomicBoolean();
    volatile int revision, failures;

    Device(String n) {
      name = n;
    }

    boolean ready() {
      return endpoints[0] != null && endpoints[1] != null;
    }
  }

  final NsdManager manager;
  final WifiManager.MulticastLock lock;
  final List<NsdManager.RegistrationListener> registrations = new ArrayList<>();
  final List<NsdManager.DiscoveryListener> browsers = new ArrayList<>();
  final Map<String, Device> devices = new ConcurrentHashMap<>();
  final Map<String, Device> knownDevices = new ConcurrentHashMap<>();
  final Set<String> announcements = ConcurrentHashMap.newKeySet();
  final ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor();
  final ExecutorService probes = Executors.newCachedThreadPool();
  boolean discovering;
  final Map<String, NsdManager.ServiceInfoCallback> updates = new ConcurrentHashMap<>();
  final Queue<Runnable> resolves = new ArrayDeque<>();
  boolean resolving;
  volatile boolean closed;
  Runnable changed = () -> {};
  java.util.function.Consumer<String> error = s -> {};

  LocalNetwork(Context context) {
    manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    lock =
        ((WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE))
            .createMulticastLock("KittenCam discovery");
    lock.setReferenceCounted(false);
    lock.acquire();
  }

  synchronized void advertise(int kind, int port) {
    if (closed) return;
    NsdServiceInfo info = new NsdServiceInfo();
    info.setServiceName(Build.MODEL);
    info.setServiceType(TYPES[kind]);
    info.setPort(port);
    NsdManager.RegistrationListener listener =
        new NsdManager.RegistrationListener() {
          public void onServiceRegistered(NsdServiceInfo s) {}

          public void onServiceUnregistered(NsdServiceInfo s) {}

          public void onRegistrationFailed(NsdServiceInfo s, int e) {
            error.accept("Discovery registration failed: " + e);
          }

          public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
        };
    registrations.add(listener);
    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
  }

  synchronized void discover() {
    if (closed || discovering) return;
    discovering = true;
    checks.scheduleWithFixedDelay(this::checkDevices, 3, 3, TimeUnit.SECONDS);
    for (int i = 0; i < 3; i++) {
      final int kind = i;
      NsdManager.DiscoveryListener listener =
          new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}

            public void onDiscoveryStopped(String t) {}

            public void onStartDiscoveryFailed(String t, int e) {
              error.accept("Discovery failed: " + e);
            }

            public void onStopDiscoveryFailed(String t, int e) {}

            public void onServiceFound(NsdServiceInfo info) {
              if (closed) return;
              announcements.add(kind + ":" + info.getServiceName());
              if (Build.VERSION.SDK_INT >= 34) {
                observe(info, kind);
                return;
              }
              enqueue(() ->
                  manager.resolveService(
                      info,
                      new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo s, int e) {
                          next();
                        }

                        public void onServiceResolved(NsdServiceInfo s) {
                          if (!closed && announcements.contains(kind + ":" + info.getServiceName()))
                            resolved(s, kind);
                          next();
                        }
                      }));
            }

            public void onServiceLost(NsdServiceInfo s) {
              if (closed) return;
              announcements.remove(kind + ":" + s.getServiceName());
              removeEndpoint(s.getServiceName(), kind);
              if (Build.VERSION.SDK_INT >= 34) {
                var callback = updates.remove(kind + ":" + s.getServiceName());
                if (callback != null) manager.unregisterServiceInfoCallback(callback);
              }
            }
          };
      browsers.add(listener);
      manager.discoverServices(TYPES[i], NsdManager.PROTOCOL_DNS_SD, listener);
    }
  }

  synchronized void resolved(NsdServiceInfo service, int kind) {
    if (closed) return;
    Device d = knownDevices.computeIfAbsent(service.getServiceName(), Device::new);
    NsdServiceInfo previous = d.endpoints[kind];
    boolean endpointChanged =
        previous == null
            || previous.getPort() != service.getPort()
            || !Objects.equals(previous.getHost(), service.getHost());
    d.endpoints[kind] = service;
    if (endpointChanged) {
      d.revision++;
      if (devices.get(d.name) == d) changed.run();
    }
    probe(d);
  }

  synchronized void removeEndpoint(String name, int kind) {
    Device d = knownDevices.get(name);
    if (d == null || d.endpoints[kind] == null) return;
    d.endpoints[kind] = null;
    d.revision++;
    boolean removed = !d.ready() && devices.remove(name, d);
    if (removed || devices.get(name) == d) changed.run();
    if (Arrays.stream(d.endpoints).allMatch(Objects::isNull)) knownDevices.remove(name, d);
  }

  synchronized void unavailable(Device device) {
    device.revision++;
    device.failures = 2;
    if (devices.remove(device.name, device)) changed.run();
  }

  void checkDevices() {
    if (!closed) for (Device d : knownDevices.values()) probe(d);
  }

  void probe(Device d) {
    if (closed || !d.ready() || !d.probing.compareAndSet(false, true)) return;
    int revision = d.revision;
    NsdServiceInfo endpoint = d.endpoints[0];
    try {
      probes.execute(() -> {
        try {
          boolean reachable = statusReachable(endpoint);
          synchronized (LocalNetwork.this) {
            if (closed || knownDevices.get(d.name) != d || d.revision != revision || !d.ready())
              return;
            if (reachable) {
              d.failures = 0;
              if (devices.put(d.name, d) != d) changed.run();
            } else if (++d.failures >= 2) unavailable(d);
          }
        } finally {
          d.probing.set(false);
        }
      });
    } catch (RejectedExecutionException ignored) {
      d.probing.set(false);
    }
  }

  static boolean statusReachable(NsdServiceInfo endpoint) {
    HttpURLConnection connection = null;
    try {
      if (endpoint == null || endpoint.getHost() == null) return false;
      String address = endpoint.getHost().getHostAddress();
      if (address.contains(":")) address = "[" + address + "]";
      connection =
          (HttpURLConnection)
              new URL("http://" + address + ":" + endpoint.getPort() + "/status").openConnection();
      connection.setConnectTimeout(1200);
      connection.setReadTimeout(1200);
      if (connection.getResponseCode() != 200) return false;
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
          bytes.write(buffer, 0, count);
          if (bytes.size() > 65536) return false;
        }
        return new org.json.JSONObject(
                new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
            .optBoolean("success");
      }
    } catch (Exception ignored) {
      return false;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  @android.annotation.TargetApi(34)
  void observe(NsdServiceInfo info, int kind) {
    String key = kind + ":" + info.getServiceName();
    NsdManager.ServiceInfoCallback callback =
        new NsdManager.ServiceInfoCallback() {
          public void onServiceInfoCallbackRegistrationFailed(int code) {
            updates.remove(key, this);
            if (!closed) error.accept("Service updates failed: " + code);
          }

          public void onServiceInfoCallbackUnregistered() {}

          public void onServiceLost() {
            if (!closed && updates.get(key) == this) removeEndpoint(info.getServiceName(), kind);
          }

          public void onServiceUpdated(NsdServiceInfo service) {
            if (!closed && updates.get(key) == this) resolved(service, kind);
          }
        };
    if (updates.putIfAbsent(key, callback) == null)
      manager.registerServiceInfoCallback(info, Runnable::run, callback);
  }

  synchronized void enqueue(Runnable r) {
    resolves.add(r);
    if (!resolving) next();
  }

  synchronized void next() {
    resolving = false;
    if (!closed && !resolves.isEmpty()) {
      resolving = true;
      resolves.remove().run();
    }
  }

  public synchronized void close() {
    closed = true;
    checks.shutdownNow();
    probes.shutdownNow();
    announcements.clear();
    knownDevices.clear();
    devices.clear();
    for (var l : browsers)
      try {
        manager.stopServiceDiscovery(l);
      } catch (Exception ignored) {
      }
    for (var l : registrations)
      try {
        manager.unregisterService(l);
      } catch (Exception ignored) {
      }
    if (Build.VERSION.SDK_INT >= 34)
      for (var callback : updates.values())
        try {
          manager.unregisterServiceInfoCallback(callback);
        } catch (Exception ignored) {
        }
    updates.clear();
    if (lock.isHeld()) lock.release();
  }
}
