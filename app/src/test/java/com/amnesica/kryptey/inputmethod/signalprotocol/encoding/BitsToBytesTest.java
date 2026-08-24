package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Random;

/**
 * {@code convertByteArrayToBinary} and {@code convertBinaryToByteArray} must be exact inverses.
 *
 * <p>They were not. The decoder was {@code new BigInteger(binary, 2).toByteArray()} — arithmetic
 * where the job is transport — and BigInteger is signed, so it got the payload wrong in two
 * opposite ways. A first byte of 0x80 or above makes {@code toByteArray()} prepend a 0x00 sign
 * byte, handing {@code Inflater} one byte more than was compressed; leading 0x00 bytes are not
 * significant digits of an integer, so they are dropped and the payload comes back short.
 *
 * <p>The first byte of a DEFLATE stream is >= 0x80 for a large share of inputs, so roughly half of
 * all FAIRYTALE-encoded messages could not be decoded by whoever received them — they got
 * "could not decode message" for a message that encoded perfectly well.
 *
 * <p>It survived every test because the suite had exactly one round-trip fixture and the bug is a
 * property of the message, not of the encoder. One message, chosen years ago, happened to compress
 * to a first byte under 0x80. That is the whole reason this shipped.
 */
public class BitsToBytesTest {

  /** The direction that produced ZipException: a high bit in the leading byte. */
  @Test
  public void aLeadingByteWithTheHighBitSetSurvives() {
    final byte[] input = {(byte) 0x80, 0x01, 0x02};

    assertArrayEquals("a sign byte was invented",
        input, EncodeHelper.convertBinaryToByteArray(EncodeHelper.convertByteArrayToBinary(input)));
  }

  /** And the opposite direction, which loses data silently rather than throwing. */
  @Test
  public void leadingZeroBytesSurvive() {
    final byte[] input = {0x00, 0x00, 0x41};

    assertArrayEquals("leading zero bytes were dropped",
        input, EncodeHelper.convertBinaryToByteArray(EncodeHelper.convertByteArrayToBinary(input)));
  }

  /** Every single-byte value, which covers both boundaries at once. */
  @Test
  public void everyByteValueRoundTrips() {
    for (int value = 0; value <= 0xFF; value++) {
      final byte[] input = {(byte) value};
      final byte[] back =
          EncodeHelper.convertBinaryToByteArray(EncodeHelper.convertByteArrayToBinary(input));

      assertEquals("byte 0x" + Integer.toHexString(value) + " changed length",
          1, back.length);
      assertEquals("byte 0x" + Integer.toHexString(value) + " did not survive",
          (byte) value, back[0]);
    }
  }

  /** Random payloads, so the property is not carried by three hand-picked fixtures. */
  @Test
  public void randomPayloadsRoundTrip() {
    final Random random = new Random(20260824L);   // fixed seed: a failure must be reproducible
    for (int trial = 0; trial < 500; trial++) {
      final byte[] input = new byte[1 + random.nextInt(64)];
      random.nextBytes(input);

      assertArrayEquals("payload " + trial + " did not survive the bit round trip",
          input,
          EncodeHelper.convertBinaryToByteArray(EncodeHelper.convertByteArrayToBinary(input)));
    }
  }

  /**
   * Bits that are not a whole number of bytes are corrupt input, not a short payload. BigInteger
   * accepted them and returned something plausible; that is how corruption reaches Inflater.
   */
  @Test
  public void aPartialByteIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> EncodeHelper.convertBinaryToByteArray("0101"));
    assertThrows(IllegalArgumentException.class,
        () -> EncodeHelper.convertBinaryToByteArray("000000001"));
  }

  /** Non-bit characters likewise. */
  @Test
  public void nonBitCharactersAreRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> EncodeHelper.convertBinaryToByteArray("0000000X"));
  }

  /** The empty-input guard that stops a two-line paste killing the IME process stays. */
  @Test
  public void emptyBitsAreStillRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> EncodeHelper.convertBinaryToByteArray(""));
    assertThrows(IllegalArgumentException.class,
        () -> EncodeHelper.convertBinaryToByteArray(null));
  }
}
