package com.example.kittencam;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.*;
import org.json.JSONObject;

final class AudioLink {
  interface Sink {
    void send(int type, byte[] bytes) throws Exception;
  }

  static JSONObject configuration() {
    return configuration(48000);
  }

  static JSONObject configuration(int sampleRate) {
    return Wire.json(
        "codec",
        "pcm_s16le",
        "format",
        "linearPCM",
        "sampleRate",
        sampleRate,
        "channels",
        1,
        "bitsPerChannel",
        16,
        "bytesPerFrame",
        2,
        "interleaved",
        true,
        "isFloat",
        false,
        "isBigEndian",
        false,
        "isSignedInteger",
        true);
  }

  static final class Capture implements AutoCloseable {
    volatile boolean running = true, active;
    volatile AudioRecord recorder;
    volatile int sampleRate;
    volatile long bytesCaptured;

    Capture(boolean talk, Sink sink, java.util.function.Consumer<String> error) {
      new Thread(
              () -> {
                AudioRecord selected = null;
                try {
                  int[] sources =
                      talk
                          ? new int[] {
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION
                          }
                          : new int[] {
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC
                          };
                  search:
                  for (int source : sources)
                    for (int rate : new int[] {48000, 44100, 16000, 8000}) {
                      if (!running) return;
                      int minimum =
                          AudioRecord.getMinBufferSize(
                              rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                      if (minimum <= 0) continue;
                      AudioRecord candidate = null;
                      try {
                        candidate =
                            new AudioRecord(
                                source,
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                Math.max(minimum * 2, rate / 50 * 2 * 4));
                        if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                          candidate.release();
                          continue;
                        }
                        recorder = candidate;
                        if (!running) {
                          candidate.release();
                          recorder = null;
                          return;
                        }
                        candidate.startRecording();
                        if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                          throw new IOException("Microphone did not start");
                        selected = candidate;
                        sampleRate = rate;
                        break search;
                      } catch (SecurityException denied) {
                        if (candidate != null) candidate.release();
                        throw denied;
                      } catch (Exception unavailable) {
                        if (candidate != null) {
                          try {
                            candidate.stop();
                          } catch (Exception ignored) {
                          }
                          candidate.release();
                        }
                        recorder = null;
                      }
                    }
                  if (selected == null)
                    throw new IOException("No supported microphone format is available");
                  if (!running) return;
                  sink.send(talk ? 1 : 6, Wire.bytes(configuration(sampleRate)));
                  active = true;
                  byte[] buffer = new byte[Math.max(320, sampleRate / 50 * 2)];
                  while (running) {
                    int size = selected.read(buffer, 0, buffer.length);
                    if (size < 0) {
                      if (!running) break;
                      throw new IOException("Microphone read failed: " + size);
                    }
                    if (size == 0) continue;
                    size -= size % 2;
                    bytesCaptured += size;
                    ByteBuffer payload = ByteBuffer.allocate(size + (talk ? 4 : 12));
                    if (!talk) payload.putLong(System.nanoTime() / 1000);
                    payload.putInt(size / 2).put(buffer, 0, size);
                    sink.send(talk ? 2 : 7, payload.array());
                  }
                } catch (SecurityException denied) {
                  if (running) error.accept("Microphone permission was denied.");
                } catch (Exception failure) {
                  if (running)
                    error.accept(
                        failure.getMessage() == null
                            ? "Microphone capture failed"
                            : failure.getMessage());
                } finally {
                  active = false;
                  recorder = null;
                  if (selected != null) {
                    try {
                      selected.stop();
                    } catch (Exception ignored) {
                    }
                    selected.release();
                  }
                }
              },
              "KittenCam microphone")
          .start();
    }

    public void close() {
      running = false;
      AudioRecord current = recorder;
      if (current != null)
        try {
          current.stop();
        } catch (Exception ignored) {
        }
    }
  }

  static final class Playback implements AutoCloseable {
    AudioTrack track;
    final AudioManager manager;
    final boolean speaker;
    int channels = 1;
    long writtenFrames;

    Playback() {
      this(null, false);
    }

    Playback(Context context, boolean speaker) {
      this.speaker = speaker;
      manager =
          context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    synchronized void configure(JSONObject config) throws Exception {
      close();
      channels = config.getInt("channels");
      int rate = config.getInt("sampleRate");
      if (config.getInt("bitsPerChannel") != 16
          || channels < 1
          || channels > 2
          || rate < 8000
          || rate > 192000) throw new IOException("Unsupported PCM format");
      int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
      int minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("Speaker does not support this PCM format");
      track =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(rate)
                      .setChannelMask(mask)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(2048, minimum))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      if (track.getState() != AudioTrack.STATE_INITIALIZED) {
        close();
        throw new IOException("Speaker could not be initialized");
      }
      if (speaker && manager != null)
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
          if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            track.setPreferredDevice(device);
            break;
          }
      writtenFrames = 0;
      track.play();
    }

    synchronized int play(byte[] payload, int offset) {
      if (track == null || offset >= payload.length) return 0;
      int total = 0;
      do {
        int result =
            track.write(
                payload,
                offset + total,
                payload.length - offset - total,
                speaker ? AudioTrack.WRITE_BLOCKING : AudioTrack.WRITE_NON_BLOCKING);
        if (result <= 0) break;
        total += result;
      } while (speaker && offset + total < payload.length);
      writtenFrames += total / (channels * 2);
      return total;
    }

    synchronized long playedFrames() {
      return track == null ? 0 : Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    }

    synchronized int outputDeviceType() {
      AudioDeviceInfo device = track == null ? null : track.getRoutedDevice();
      return device == null ? 0 : device.getType();
    }

    synchronized void finish() {
      long end = System.nanoTime() + 200_000_000L;
      while (track != null && playedFrames() < writtenFrames && System.nanoTime() < end)
        try {
          Thread.sleep(5);
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          break;
        }
    }

    public synchronized void close() {
      if (track != null) {
        try {
          track.pause();
          track.flush();
        } catch (Exception ignored) {
        }
        track.release();
        track = null;
      }
    }
  }
}
