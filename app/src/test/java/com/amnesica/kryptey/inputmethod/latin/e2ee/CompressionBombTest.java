package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EncodeHelper;

import org.junit.Test;

import java.io.IOException;

/**
 * A compression bomb must be refused, not inflated.
 *
 * <p>The receive path caps its INPUT at {@code MAX_DECODABLE_CHARS} and used to cap nothing else.
 * Measured end to end, an 8192-character paste produced 37,855,647 characters in about half a
 * second - roughly 72MB as a UTF-16 String, an amplification of 4621x. Two stages compose: the
 * inflate, then the fifteen expanding {@code replaceAll} passes in {@code deSimplifyJsonKeys},
 * whose best amplifier is {@code "a"} to {@code "signalProtocolAddressName"}.
 *
 * <p>What made it a crash rather than a slow decode is that {@code decodeMessage} catches
 * {@code RuntimeException}, and {@code OutOfMemoryError} is an {@code Error}. It escaped into
 * LatinIME's clipboard callback - which runs on EVERY clipboard change, with routing decided by the
 * presence of a {@code \p{C}} character rather than by which encoder the user chose. The adversary
 * here is the messenger, so this needs no cooperation from the user.
 *
 * <p>These bound the output, which is the fix. Catching the Error would not be one: by the time an
 * OutOfMemoryError is thrown the process has already been damaged, and other allocations elsewhere
 * fail first.
 */
public class CompressionBombTest {

  /** Highly compressible input, which is what makes a bomb a bomb. */
  private static byte[] bomb(final int plaintextBytes) throws IOException {
    return EncodeHelper.compressString("a".repeat(plaintextBytes));
  }

  @Test
  public void ahighlyCompressiblePayloadIsRefusedRatherThanExpanded() throws IOException {
    // 4MB of 'a' compresses to a few kilobytes - comfortably inside every input cap on the way in.
    final byte[] compressed = bomb(4 * 1024 * 1024);

    // The real limit is 4096 BYTES, not 8192: the encoder spends two invisible characters per
    // byte, and the paste cap counts characters. Asserting 8192 here passed by 30 characters, by
    // luck rather than by the assertion meaning anything.
    assertTrue("precondition: the bomb must be small enough to paste - " + compressed.length
            + " bytes becomes " + (compressed.length * 2) + " characters against a cap of "
            + com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStrip.MAX_DECODABLE_CHARS,
        compressed.length * 2 <= com.amnesica.kryptey.inputmethod.latin.e2ee
            .E2EEStrip.MAX_DECODABLE_CHARS);

    final IOException refused = assertThrows(
        "an 8KB payload that expands to megabytes must be refused, not inflated",
        IOException.class, () -> EncodeHelper.decompressString(compressed));

    assertTrue("the refusal should say what happened, not surface as a generic failure: "
            + refused.getMessage(),
        refused.getMessage() != null && refused.getMessage().contains("exceeds"));
  }

  /**
   * Refusing must be fast, on a bomb that can actually arrive.
   *
   * <p>This used to compress 64MB, giving 65,233 bytes - 130,466 invisible characters, sixteen
   * times past the paste cap. No real path can deliver it, so the test was measuring something the
   * code will never see. Sized to the cap instead, which is the largest bomb that can reach
   * {@code decompressString} at all.
   */
  @Test
  public void thebudgetIsBelowWhatWouldExhaustMemory() throws IOException {
    final int maxCompressedBytes =
        com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStrip.MAX_DECODABLE_CHARS / 2;

    byte[] sized = bomb(64 * 1024 * 1024);
    int plaintext = 64 * 1024 * 1024;
    while (sized.length > maxCompressedBytes) {
      plaintext /= 2;
      sized = bomb(plaintext);
    }
    final byte[] compressed = sized;

    assertTrue("precondition: the bomb must fit through the paste cap",
        compressed.length * 2 <= com.amnesica.kryptey.inputmethod.latin.e2ee
            .E2EEStrip.MAX_DECODABLE_CHARS);

    final long before = System.nanoTime();
    assertThrows("a bomb that fits through the paste cap must still be refused",
        IOException.class, () -> EncodeHelper.decompressString(compressed));
    final long millis = (System.nanoTime() - before) / 1_000_000;

    assertTrue("refusing a bomb must be fast - it took " + millis + "ms, which means it was "
        + "expanded first", millis < 2000);
  }


  /** And a legitimate payload of the largest size the codec accepts still decompresses. */
  @Test
  public void thelargestLegitimatePayloadStillDecompresses() throws IOException {
    // Incompressible, so it is the worst case for the budget rather than the best.
    final StringBuilder builder = new StringBuilder();
    final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    final java.util.Random random = new java.util.Random(20260824L);
    while (builder.length() < 8192) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    final String payload = builder.toString();

    org.junit.Assert.assertEquals("a payload the size of the wire cap must still round-trip",
        payload, EncodeHelper.decompressString(EncodeHelper.compressString(payload)));
  }

  /**
   * A truncated stream is truncated, not short.
   *
   * <p>{@code InflaterOutputStream.close()} does not check that the stream finished, so a payload
   * cut in half came back as a silent prefix of itself. Nothing downstream could tell that from a
   * genuinely shorter message; it was contained only because BinaryEnvelope's length framing
   * rejected it afterwards. Defence in depth, made explicit.
   */
  @Test
  public void atruncatedStreamIsRejectedRatherThanReturnedAsAPrefix() throws IOException {
    final String original = "{\"a\":\"the quick brown fox jumps over the lazy dog\"}";
    final byte[] compressed = EncodeHelper.compressString(original);

    for (final int keep : new int[] {compressed.length / 2, 4, 1}) {
      final byte[] cut = java.util.Arrays.copyOf(compressed, keep);

      String returned = null;
      try {
        returned = EncodeHelper.decompressString(cut);
      } catch (IOException expected) {
        continue;
      }
      org.junit.Assert.assertEquals("a stream cut to " + keep + " of " + compressed.length
              + " bytes came back as a silent prefix: \"" + returned + "\"",
          original, returned);
    }
  }
}
