package com.example.kittencam;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Test;

public class WireTest {
  @Test
  public void readsFragmentedIosVideoFrame() throws Exception {
    byte[] payload =
        ByteBuffer.allocate(15)
            .put((byte) 1)
            .putLong(123456L)
            .put(new byte[] {0, 0, 0, 1, 0x65, 42})
            .array();
    byte[] packet =
        ByteBuffer.allocate(payload.length + 6)
            .put((byte) 1)
            .put((byte) 3)
            .putInt(payload.length)
            .put(payload)
            .array();
    InputStream fragmented =
        new FilterInputStream(new ByteArrayInputStream(packet)) {
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(1, len));
          }
        };
    Wire.Packet result = Wire.read(new DataInputStream(fragmented), false);
    assertEquals(3, result.type());
    assertArrayEquals(payload, result.payload());
  }

  @Test
  public void roundTripsTalkbackWithoutVersionByte() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Wire.write(out, 2, new byte[] {0, 0, 0, 1, 42, 43}, true);
    assertEquals(2, out.toByteArray()[0]);
    assertEquals(11, out.size());
    Wire.Packet result =
        Wire.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())), true);
    assertEquals(2, result.type());
    assertArrayEquals(new byte[] {0, 0, 0, 1, 42, 43}, result.payload());
  }

  @Test
  public void rejectsOversizedOrNegativeLength() throws Exception {
    for (int length : new int[] {-1, Wire.MAX_PAYLOAD + 1}) {
      byte[] p = ByteBuffer.allocate(6).put((byte) 1).put((byte) 3).putInt(length).array();
      assertThrows(
          IOException.class,
          () -> Wire.read(new DataInputStream(new ByteArrayInputStream(p)), false));
    }
  }

  @Test
  public void rejectsUnsupportedVersionAndTruncation() {
    assertThrows(
        IOException.class,
        () ->
            Wire.read(
                new DataInputStream(new ByteArrayInputStream(new byte[] {2, 3, 0, 0, 0, 0})),
                false));
    assertThrows(
        EOFException.class,
        () ->
            Wire.read(
                new DataInputStream(new ByteArrayInputStream(new byte[] {1, 3, 0, 0, 0, 2, 42})),
                false));
  }

  @Test
  public void separatesBackToBackPackets() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Wire.write(out, 4, new byte[0], false);
    Wire.write(out, 6, new byte[] {42}, false);
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(4, Wire.read(in, false).type());
    assertEquals(6, Wire.read(in, false).type());
    assertEquals(0, in.available());
  }

  @Test
  public void recognizesBothAnnexBStartCodes() {
    List<byte[]> nals = Wire.nals(new byte[] {0, 0, 0, 1, 0x67, 42, 0, 0, 1, 0x68, 43});
    assertEquals(2, nals.size());
    assertArrayEquals(new byte[] {0x67, 42}, nals.get(0));
    assertArrayEquals(new byte[] {0x68, 43}, nals.get(1));
    assertArrayEquals(new byte[] {0, 0, 0, 1, 0x68, 43}, Wire.annex(nals.get(1)));
  }
}
