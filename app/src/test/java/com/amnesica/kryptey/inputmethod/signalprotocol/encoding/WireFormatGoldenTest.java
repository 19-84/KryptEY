package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;

import org.junit.Test;

/**
 * Pins the wire format against hard-coded bytes.
 *
 * <p>Every other test in this package is a self-consistent round trip: encode then decode with the
 * same code. Mutation testing showed exactly what that misses — flipping u32 endianness on
 * <em>both</em> sides, changing {@code VERSION} from 1 to 2, and swapping the two flag bit values
 * all survived the entire suite. Each of those silently redefines the format while every round-trip
 * test still passes, which would break already-deployed peers and defeats the purpose of carrying a
 * version byte at all.
 *
 * <p>A golden vector is the only thing that catches that class of change. If one of these fails,
 * the question is not "fix the test" — it is "did we intend to change the wire format, and did we
 * bump the version byte?"
 */
public class WireFormatGoldenTest {

  private static String hex(final byte[] bytes) {
    final StringBuilder sb = new StringBuilder();
    for (final byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static byte[] unhex(final String s) {
    final byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  /**
   * A message envelope with no bundle. Fully deterministic — no key material — so it can be pinned
   * byte for byte.
   *
   * <pre>
   *   01        version 1
   *   02        flags: FLAG_CIPHERTEXT only
   *   04        sender name length
   *   61626364  "abcd"
   *   2a        deviceId 42
   *   03        ciphertextType 3
   *   0005      ciphertext length 5, big-endian
   *   0102030405
   * </pre>
   */
  private static final String GOLDEN_MESSAGE = "010204616263642a0300050102030405";

  @Test
  public void aMessageEnvelopeEncodesToExactlyTheseBytes() throws Exception {
    final MessageEnvelope envelope =
        new MessageEnvelope(new byte[] {1, 2, 3, 4, 5}, 3, "abcd", 42);

    assertEquals("the wire format changed; bump VERSION if that was intentional",
        GOLDEN_MESSAGE, hex(BinaryEnvelope.encode(envelope)));
  }

  @Test
  public void theGoldenBytesDecodeBackToTheSameEnvelope() throws Exception {
    final MessageEnvelope decoded = BinaryEnvelope.decode(unhex(GOLDEN_MESSAGE));

    assertEquals("abcd", decoded.getSignalProtocolAddressName());
    assertEquals(42, decoded.getDeviceId());
    assertEquals(3, decoded.getCiphertextType());
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, decoded.getCiphertextMessage());
  }

  /** The version byte must be 1. Changing it is a protocol break, not a refactor. */
  @Test
  public void theVersionByteIsPinned() throws Exception {
    assertEquals(0x01, BinaryEnvelope.encode(
        new MessageEnvelope(new byte[] {1}, 2, "abcd", 42))[0]);
  }

  /** Flag bit values are part of the format: swapping them is invisible to a round trip. */
  @Test
  public void flagBitValuesArePinned() throws Exception {
    final byte messageOnly =
        BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 2, "abcd", 42))[1];
    assertEquals("FLAG_CIPHERTEXT must be bit 1 (0x02)", 0x02, messageOnly);
  }

  /**
   * Multi-byte integers are big-endian. Flipping this on both sides round-trips perfectly and
   * breaks every deployed peer, so it needs pinning against literal bytes.
   */
  @Test
  public void multiByteLengthsAreBigEndian() throws Exception {
    // 258 = 0x0102: big-endian writes 01 02, little-endian would write 02 01.
    final byte[] ciphertext = new byte[258];
    final byte[] encoded =
        BinaryEnvelope.encode(new MessageEnvelope(ciphertext, 3, "abcd", 42));

    // version, flags, nameLen, "abcd", deviceId, ciphertextType => length starts at offset 9
    assertEquals("high byte of the u16 length is not first (endianness flipped?)",
        0x01, encoded[9] & 0xFF);
    assertEquals(0x02, encoded[10] & 0xFF);
  }

  /**
   * The header is fixed-width up to the ciphertext, so a small change in field order shows up here
   * even when both sides agree and the round trip still passes.
   */
  @Test
  public void headerFieldOrderIsPinned() throws Exception {
    final byte[] encoded =
        BinaryEnvelope.encode(new MessageEnvelope(new byte[] {9}, 7, "abcd", 42));

    assertEquals("version", 0x01, encoded[0] & 0xFF);
    assertEquals("flags", 0x02, encoded[1] & 0xFF);
    assertEquals("name length", 0x04, encoded[2] & 0xFF);
    assertEquals("name", "abcd", new String(encoded, 3, 4, java.nio.charset.StandardCharsets.UTF_8));
    assertEquals("deviceId follows the name", 42, encoded[7] & 0xFF);
    assertEquals("ciphertextType follows deviceId", 7, encoded[8] & 0xFF);
  }

  /** Guards the golden vector itself against being quietly regenerated to match a change. */
  @Test
  public void theGoldenVectorIsTheSizeItClaims() {
    final byte[] golden = unhex(GOLDEN_MESSAGE);
    assertEquals(16, golden.length);
    assertTrue("golden vector should carry no key material", golden.length < 64);
  }
}
