package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The wire codec's limits, tested <em>at</em> their limits.
 *
 * <p>A mutation sweep of {@code BinaryEnvelope} found that almost every {@code >} in it could be
 * changed to {@code >=} — and every {@code <} to {@code <=} — with the whole suite still green. The
 * existing tests check values comfortably inside and comfortably outside each bound, never the two
 * adjacent values that define where the bound actually is.
 *
 * <p>That matters more here than in most code, because this parser is the first thing to touch
 * attacker-controlled bytes. An off-by-one in either direction is a real defect: one way it rejects
 * envelopes honest peers legitimately produce, the other it accepts one byte more than every
 * downstream consumer was written to expect.
 *
 * <p>Each test below asserts <b>both</b> sides of a boundary. Asserting only the rejection would
 * leave {@code >=} alive; asserting only the acceptance would leave {@code >} alive.
 */
public class WireBoundaryTest {

  /** Mirrors the private constant in {@link BinaryEnvelope}. */
  private static final int MAX_SENDER_NAME_CHARS = 64;

  private static MessageEnvelope messageNamed(final String name) {
    return new MessageEnvelope(new byte[] {9, 9}, 3, name, 42);
  }

  private static String repeat(final char c, final int n) {
    final StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append(c);
    return sb.toString();
  }

  // -------------------------------------------------------- sender name length

  @Test
  public void aSenderNameOfExactlyTheMaximumIsAccepted() throws Exception {
    final String atLimit = repeat('a', MAX_SENDER_NAME_CHARS);
    final byte[] encoded = BinaryEnvelope.encode(messageNamed(atLimit));

    assertEquals("a name of exactly the maximum must round-trip, or honest peers are refused",
        atLimit, BinaryEnvelope.decode(encoded).getSignalProtocolAddressName());
  }

  @Test
  public void aSenderNameOneOverTheMaximumIsRejected() {
    final String overLimit = repeat('a', MAX_SENDER_NAME_CHARS + 1);
    final IOException e = assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(messageNamed(overLimit)));
    assertTrue(e.getMessage(), e.getMessage().contains("too long"));
  }

  @Test
  public void anEmptySenderNameIsRejected() {
    final IOException e = assertThrows(IOException.class,
        () -> BinaryEnvelope.encode(messageNamed("")));
    assertTrue(e.getMessage(), e.getMessage().contains("empty"));
  }

  // --------------------------------------------------- sender name characters

  /**
   * The printable-ASCII window is [0x20, 0x7E] inclusive. Both endpoints must be <em>accepted</em>
   * and both neighbours rejected, or the window silently narrows by a character at either end.
   */
  @Test
  public void bothEndsOfThePrintableAsciiWindowAreAccepted() throws Exception {
    for (final char edge : new char[] {0x20, 0x7E}) {
      final String name = "a" + edge + "b";
      final byte[] encoded = BinaryEnvelope.encode(messageNamed(name));
      assertEquals("U+" + String.format("%04X", (int) edge) + " is inside the window and must "
              + "survive a round trip",
          name, BinaryEnvelope.decode(encoded).getSignalProtocolAddressName());
    }
  }

  @Test
  public void theCharactersJustOutsideTheWindowAreRejected() {
    for (final char bad : new char[] {0x1F, 0x7F}) {
      final IOException e = assertThrows("U+" + String.format("%04X", (int) bad) + " must be "
          + "refused", IOException.class, () -> BinaryEnvelope.encode(messageNamed("a" + bad + "b")));
      assertTrue(e.getMessage(), e.getMessage().contains("non-printable or non-ASCII"));
    }
  }

  // ----------------------------------------------------------- u8 field range

  /**
   * A device id of 255 is out of libsignal's range and refused for that reason, so the u8 write
   * guard cannot be reached through the device id. The ciphertext <em>type</em> is the reachable u8
   * field, and 255 is its last legal value.
   */
  @Test
  public void bothEndsOfTheU8RangeAreWritable() throws Exception {
    for (final int type : new int[] {0, 255}) {
      final byte[] encoded =
          BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, type, "peer", 42));
      assertEquals("ciphertext type " + type + " is a legal u8 and must round-trip",
          type, BinaryEnvelope.decode(encoded).getCiphertextType());
    }
  }

  @Test
  public void aU8FieldOneOverTheRangeIsRejected() {
    final IOException e = assertThrows(IOException.class, () ->
        BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, 256, "peer", 42)));
    assertTrue(e.getMessage(), e.getMessage().contains("out of range"));
  }

  @Test
  public void aNegativeU8FieldIsRejected() {
    final IOException e = assertThrows(IOException.class, () ->
        BinaryEnvelope.encode(new MessageEnvelope(new byte[] {1}, -1, "peer", 42)));
    assertTrue(e.getMessage(), e.getMessage().contains("out of range"));
  }

  // ------------------------------------------------------------ u16 field len

  /**
   * The ciphertext is a u16-length field. 65535 is the last encodable length and 65536 the first
   * that is not — the boundary the length prefix itself depends on.
   */
  @Test
  public void aCiphertextOfExactlyTheU16MaximumRoundTrips() throws Exception {
    final byte[] body = new byte[65535];
    for (int i = 0; i < body.length; i++) body[i] = (byte) i;

    final byte[] encoded = BinaryEnvelope.encode(new MessageEnvelope(body, 3, "peer", 42));
    final MessageEnvelope decoded = BinaryEnvelope.decode(encoded);

    assertNotNull(decoded.getCiphertextMessage());
    assertEquals("the largest encodable ciphertext must survive",
        body.length, decoded.getCiphertextMessage().length);
    org.junit.Assert.assertArrayEquals(body, decoded.getCiphertextMessage());
  }

  @Test
  public void aCiphertextOneOverTheU16MaximumIsRejected() {
    final IOException e = assertThrows(IOException.class, () ->
        BinaryEnvelope.encode(new MessageEnvelope(new byte[65536], 3, "peer", 42)));
    assertTrue(e.getMessage(), e.getMessage().contains("too long"));
  }

  // ------------------------------------------------------------ cursor bounds

  /**
   * {@code require} refuses to read past the buffer. The boundary is "exactly the remaining bytes",
   * which must succeed, versus one more, which must not — and the check must survive an integer
   * overflow in {@code pos + n} rather than wrapping negative and reading out of bounds.
   */
  @Test
  public void readingExactlyToTheEndSucceedsAndOneByteMoreDoesNot() throws Exception {
    final byte[] encoded = BinaryEnvelope.encode(messageNamed("peer"));

    // Exactly the whole buffer: fine.
    assertNotNull(BinaryEnvelope.decode(encoded));

    // One byte short: the last field claims more than remains.
    final byte[] truncated = new byte[encoded.length - 1];
    System.arraycopy(encoded, 0, truncated, 0, truncated.length);
    assertThrows("a field running one byte past the end must be refused",
        IOException.class, () -> BinaryEnvelope.decode(truncated));

    // One byte extra: trailing data is refused rather than ignored.
    final byte[] extended = new byte[encoded.length + 1];
    System.arraycopy(encoded, 0, extended, 0, encoded.length);
    final IOException e = assertThrows(IOException.class, () -> BinaryEnvelope.decode(extended));
    assertTrue(e.getMessage(), e.getMessage().contains("trailing bytes"));
  }

  /**
   * A declared length near {@code Integer.MAX_VALUE} must not overflow {@code pos + n} into a
   * negative number, which would pass a naive bounds check and read wild memory. The u16 length
   * prefix caps what can be declared, so this drives the guard directly through a hand-built frame.
   */
  @Test
  public void aHugeDeclaredLengthDoesNotOverflowTheBoundsCheck() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(1);          // version
    out.write(0x02);       // flags: ciphertext present
    out.write(4);          // name length
    out.write("peer".getBytes("US-ASCII"));
    out.write(42);         // device id
    out.write(3);          // ciphertext type
    out.write(0xFF);       // ciphertext length high byte
    out.write(0xFF);       // ciphertext length low byte  -> declares 65535, supplies none

    assertThrows("a length far beyond the buffer must be refused, not wrapped",
        IOException.class, () -> BinaryEnvelope.decode(out.toByteArray()));
  }
}
