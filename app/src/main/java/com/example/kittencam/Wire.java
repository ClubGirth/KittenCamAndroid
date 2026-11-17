package com.example.kittencam;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.JSONObject;

/** iOS KittenCam protocol v1. All framing integers are network byte order. */
final class Wire {
  static final int MAX_PAYLOAD = 16 * 1024 * 1024;

  record Packet(int type, byte[] payload) {}

  static Packet read(DataInputStream in, boolean talk) throws IOException {
    if (!talk && in.readUnsignedByte() != 1) throw new IOException("Unsupported protocol version");
    int type = in.readUnsignedByte(), length = in.readInt();
    if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Invalid packet length");
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return new Packet(type, bytes);
  }

  static synchronized void write(OutputStream stream, int type, byte[] payload, boolean talk)
      throws IOException {
    DataOutputStream out = new DataOutputStream(stream);
    if (!talk) out.writeByte(1);
    out.writeByte(type);
    out.writeInt(payload.length);
    out.write(payload);
    out.flush();
  }

  static JSONObject json(Object... values) {
    JSONObject result = new JSONObject();
    try {
      for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    return result;
  }

  static byte[] bytes(JSONObject o) {
    return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static List<byte[]> nals(byte[] bytes) {
    List<byte[]> result = new ArrayList<>();
    int start = -1;
    for (int i = 0; i < bytes.length - 2; i++) {
      int n =
          bytes[i] == 0 && bytes[i + 1] == 0
              ? (bytes[i + 2] == 1
                  ? 3
                  : (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1 ? 4 : 0))
              : 0;
      if (n > 0) {
        if (start >= 0 && i > start) result.add(Arrays.copyOfRange(bytes, start, i));
        start = i + n;
        i += n - 1;
      }
    }
    if (start >= 0 && start < bytes.length)
      result.add(Arrays.copyOfRange(bytes, start, bytes.length));
    return result;
  }

  static byte[] annex(byte[] nal) {
    return ByteBuffer.allocate(nal.length + 4).putInt(1).put(nal).array();
  }
}
