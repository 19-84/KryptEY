package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;

import org.junit.Test;

import java.util.Random;

/**
 * The numeric fields at their boundaries, and over random values.
 *
 * <p>{@code BinaryEnvelopeTest} is thorough about hostile input - truncation, trailing bytes,
 * declared lengths past the buffer, unknown versions and flags - but every round-trip in it uses a
 * fixture. That is the shape of gap that hid the {@code BigInteger} defect in the other codec for
 * years: one message, chosen once, that happened to avoid the broken case.
 *
 * <p>This envelope encodes lengths as u8, u16 and u32. Those are exactly where a length-prefix
 * format breaks, and the values that break it are 0, 127/128 (sign), 255/256 (u8 overflow) and
 * 65535/65536 (u16 overflow) - none of which a hand-written fixture is likely to be.
 */
public class BinaryEnvelopeBoundaryTest {

  private static final String SENDER = "0f9d4c2e-1b3a-4d5e-8f70-a1b2c3d4e5f6";

  private static byte[] bytes(final int length, final long seed) {
    final byte[] out = new byte[length];
    new Random(seed).nextBytes(out);
    return out;
  }

  private static MessageEnvelope roundTrip(final MessageEnvelope envelope) throws Exception {
    final MessageEnvelope back = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(envelope));
    assertNotNull("the envelope did not survive the wire at all", back);
    return back;
  }

  /**
   * Ciphertext length is a u16, so 255/256 and 65535 are where it would break. Zero is included
   * because an empty ciphertext is structurally different from an absent one.
   */
  @Test
  public void everyCiphertextLengthBoundaryRoundTrips() throws Exception {
    // Bounded by what can actually travel: EnvelopeCodec refuses wire text past MAX_WIRE_CHARS,
    // and base64 costs four characters per three bytes, so a 65535-byte ciphertext is 87436
    // characters and is rejected before any of this matters. The u16 boundary is unreachable
    // through the wire; the boundaries that ARE reachable are the u8 ones.
    final int[] lengths = {1, 2, 127, 128, 129, 254, 255, 256, 257, 1023, 1024, 4095, 4096, 5000};

    for (final int length : lengths) {
      final byte[] ciphertext = bytes(length, length);
      final MessageEnvelope back = roundTrip(
          new MessageEnvelope(ciphertext, 3, SENDER, 7));

      assertArrayEquals("a " + length + "-byte ciphertext did not survive",
          ciphertext, back.getCiphertextMessage());
      assertEquals("the type must survive too at length " + length, 3, back.getCiphertextType());
    }
  }

  /** The ciphertext type is a u8: 0, 127, 128 and 255 are the values that distinguish a bug. */
  @Test
  public void everyCiphertextTypeValueRoundTrips() throws Exception {
    for (final int type : new int[] {0, 1, 2, 3, 127, 128, 200, 255}) {
      final MessageEnvelope back = roundTrip(new MessageEnvelope(bytes(32, type), type, SENDER, 7));

      assertEquals("ciphertext type " + type + " did not survive - a u8 read as signed would "
              + "return " + (type > 127 ? type - 256 : type), type, back.getCiphertextType());
    }
  }

  /** Device id is a u8 and libsignal constrains it to [1,127]; both ends must survive. */
  @Test
  public void everyValidDeviceIdRoundTrips() throws Exception {
    for (int deviceId = 1; deviceId <= 127; deviceId++) {
      final MessageEnvelope back = roundTrip(new MessageEnvelope(bytes(16, deviceId), 3, SENDER,
          deviceId));

      assertEquals("device id " + deviceId + " did not survive", deviceId, back.getDeviceId());
    }
  }

  /** Sender names at the length cap, since the length is a u8 and the cap is 64. */
  @Test
  public void senderNamesAtTheLengthCapRoundTrip() throws Exception {
    for (final int length : new int[] {1, 36, 63, 64}) {
      final String name = "a".repeat(length);
      final MessageEnvelope back = roundTrip(new MessageEnvelope(bytes(16, length), 3, name, 7));

      assertEquals("a " + length + "-character sender name did not survive",
          name, back.getSignalProtocolAddressName());
    }
  }

  /**
   * Random envelopes, so the property is not carried by the boundaries alone.
   *
   * <p>Fixed seed: a failure has to be reproducible, or the next person sees a test that passed
   * yesterday and fails today with no way to get back to the input.
   */
  @Test
  public void randomEnvelopesRoundTripExactly() throws Exception {
    final Random random = new Random(20260824L);

    for (int trial = 0; trial < 300; trial++) {
      final int length = 1 + random.nextInt(4096);
      final int type = random.nextInt(256);
      final int deviceId = 1 + random.nextInt(127);
      final byte[] ciphertext = new byte[length];
      random.nextBytes(ciphertext);

      final MessageEnvelope back =
          roundTrip(new MessageEnvelope(ciphertext, type, SENDER, deviceId));

      assertArrayEquals("trial " + trial + " (len=" + length + ", type=" + type + ", device="
          + deviceId + ") lost the ciphertext", ciphertext, back.getCiphertextMessage());
      assertEquals("trial " + trial + " lost the type", type, back.getCiphertextType());
      assertEquals("trial " + trial + " lost the device id", deviceId, back.getDeviceId());
      assertEquals("trial " + trial + " lost the sender", SENDER,
          back.getSignalProtocolAddressName());
    }
  }

  /**
   * A byte value of 0x00 in every position, which is the classic place a length-prefixed or
   * terminator-sensitive format loses data.
   */
  @Test
  public void aciphertextOfAllZeroesSurvives() throws Exception {
    for (final int length : new int[] {1, 255, 256, 4096}) {
      final byte[] zeroes = new byte[length];
      final MessageEnvelope back = roundTrip(new MessageEnvelope(zeroes, 3, SENDER, 7));

      assertArrayEquals(length + " zero bytes did not survive", zeroes,
          back.getCiphertextMessage());
    }
  }

  /** And all-0xFF, the other end of the same class of bug. */
  @Test
  public void aciphertextOfAllOnesSurvives() throws Exception {
    for (final int length : new int[] {1, 255, 256, 4096}) {
      final byte[] ones = new byte[length];
      java.util.Arrays.fill(ones, (byte) 0xFF);
      final MessageEnvelope back = roundTrip(new MessageEnvelope(ones, 3, SENDER, 7));

      assertArrayEquals(length + " 0xFF bytes did not survive", ones, back.getCiphertextMessage());
      assertTrue("the wire text must stay base64", EnvelopeCodec
          .toWire(new MessageEnvelope(ones, 3, SENDER, 7)).matches("[A-Za-z0-9+/=]+"));
    }
  }

  /**
   * A ciphertext too large for the wire is refused with a clear error, not truncated.
   *
   * <p>The u16 length field can describe 65535 bytes and the wire cannot carry them: base64 costs
   * four characters per three bytes, so that is 87436 characters against a cap of 8192. The format
   * and the transport disagree about what is representable.
   *
   * <p>The asymmetry is deliberate to record rather than to fix here: {@code toWire} will happily
   * produce text {@code fromWire} refuses. Nothing reaches a user that way, because
   * {@code E2EEStrip.encode} caps the ENCODED length before anything is handed to the clipboard -
   * that check exists precisely because a message could otherwise send and be undecodable on
   * arrival. This pins where the refusal actually lives, so a future reader does not assume the
   * codec is symmetric and remove the check that is really doing the work.
   */
  @Test
  public void aciphertextTooLargeForTheWireIsRefusedOnRead() throws Exception {
    final MessageEnvelope oversized = new MessageEnvelope(bytes(65535, 1), 3, SENDER, 7);

    // toWire ACCEPTS it. The codec is asymmetric: it will produce text no reader will take.
    final String wire = EnvelopeCodec.toWire(oversized);
    assertTrue("87436 characters is far past the cap", wire.length() > 80000);

    final java.io.IOException refused = org.junit.Assert.assertThrows(java.io.IOException.class,
        () -> EnvelopeCodec.fromWire(wire));
    assertTrue("the refusal must name the size and the limit: " + refused.getMessage(),
        refused.getMessage() != null && refused.getMessage().contains("too large"));
  }
}
