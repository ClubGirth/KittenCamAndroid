package com.example.kittencam;

import android.media.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.json.JSONObject;

/** Encodes received PCM to AAC; both tracks use the camera's microsecond timeline. */
final class AudioVideoRecording {
  final MediaMuxer muxer;
  final MediaCodec encoder;
  final int videoTrack, rate, channels;
  int audioTrack = -1, pendingBytes;
  boolean started;
  long start = -1, lastVideo, nextAudio, clockOffset = Long.MIN_VALUE, audioSamples;
  final ArrayDeque<Sample> pending = new ArrayDeque<>();

  record Sample(byte[] bytes, long pts, int flags) {}

  AudioVideoRecording(MediaMuxer muxer, MediaFormat video, JSONObject pcm, int rotation)
      throws Exception {
    this.muxer = muxer;
    rate = pcm.getInt("sampleRate");
    channels = pcm.getInt("channels");
    if (pcm.optBoolean("isFloat")
        || pcm.optBoolean("isBigEndian")
        || !pcm.optBoolean("interleaved", true)
        || !pcm.optBoolean("isSignedInteger", true)
        || pcm.getInt("bitsPerChannel") != 16
        || channels < 1
        || channels > 2
        || rate < 8000
        || rate > 96000) throw new IOException("Unsupported recording audio format");
    muxer.setOrientationHint(rotation);
    videoTrack = muxer.addTrack(video);
    encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
    try {
      MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_BIT_RATE, channels == 1 ? 96000 : 128000);
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoder.start();
    } catch (Exception e) {
      encoder.release();
      throw e;
    }
  }

  void video(byte[] packet, long pts, boolean key) throws Exception {
    if (start < 0) {
      if (!key) return;
      start = pts;
    }
    lastVideo = Math.max(lastVideo, pts - start);
    byte[] bytes = java.util.Arrays.copyOfRange(packet, 9, packet.length);
    pending.add(new Sample(bytes, lastVideo, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0));
    pendingBytes += bytes.length;
    if (pendingBytes > 16 * 1024 * 1024) throw new IOException("Recording audio did not start");
    drain(false);
    flushVideo();
  }

  void audio(byte[] packet) throws Exception {
    if (start < 0 || packet.length < 12) return;
    ByteBuffer header = ByteBuffer.wrap(packet);
    long timestamp = header.getLong();
    int frames = header.getInt();
    if (frames <= 0 || (long) frames * channels * 2 != packet.length - 12)
      throw new IOException("Invalid recording PCM packet");
    if (clockOffset == Long.MIN_VALUE)
      clockOffset = Math.abs(timestamp - start) > 5_000_000 ? timestamp - start - lastVideo : 0;
    long pts = timestamp - start - clockOffset;
    int offset = 12;
    if (pts < 0) {
      int skip = (int) Math.min(frames, (-pts * rate + 999999) / 1000000);
      offset += skip * channels * 2;
      pts += skip * 1000000L / rate;
    }
    pts = Math.max(nextAudio, Math.max(0, pts));
    while (offset < packet.length) {
      drain(false);
      int index = encoder.dequeueInputBuffer(10000);
      if (index < 0) throw new IOException("Audio encoder is not keeping up");
      ByteBuffer input = encoder.getInputBuffer(index);
      input.clear();
      int size = Math.min(input.remaining(), packet.length - offset);
      size -= size % (channels * 2);
      if (size == 0) throw new IOException("Audio encoder input buffer too small");
      input.put(packet, offset, size);
      encoder.queueInputBuffer(index, 0, size, pts, 0);
      pts += (size / (channels * 2)) * 1000000L / rate;
      nextAudio = pts;
      offset += size;
      drain(false);
    }
  }

  void flushVideo() {
    if (!started) return;
    while (!pending.isEmpty()) {
      Sample sample = pending.remove();
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      info.set(0, sample.bytes.length, sample.pts, sample.flags);
      muxer.writeSampleData(videoTrack, ByteBuffer.wrap(sample.bytes), info);
    }
    pendingBytes = 0;
  }

  void drain(boolean end) throws Exception {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    long deadline = System.nanoTime() + 1_500_000_000L;
    while (true) {
      int index = encoder.dequeueOutputBuffer(info, end ? 10000 : 0);
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (started) throw new IOException("Audio format changed during recording");
        audioTrack = muxer.addTrack(encoder.getOutputFormat());
        muxer.start();
        started = true;
        flushVideo();
      } else if (index >= 0) {
        try {
          if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            if (!started) throw new IOException("Audio encoder format unavailable");
            muxer.writeSampleData(audioTrack, encoder.getOutputBuffer(index), info);
            audioSamples++;
          }
        } finally {
          encoder.releaseOutputBuffer(index, false);
        }
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
      } else if (!end) return;
      if (end && System.nanoTime() > deadline)
        throw new IOException("Audio encoder did not finish");
    }
  }

  boolean finish() throws Exception {
    try {
      drain(false);
      int index = encoder.dequeueInputBuffer(100000);
      if (index < 0) throw new IOException("Unable to finish recording audio");
      encoder.queueInputBuffer(index, 0, 0, nextAudio, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      drain(true);
      flushVideo();
      return started && start >= 0 && audioSamples > 0;
    } finally {
      try {
        encoder.stop();
      } finally {
        encoder.release();
      }
    }
  }
}
