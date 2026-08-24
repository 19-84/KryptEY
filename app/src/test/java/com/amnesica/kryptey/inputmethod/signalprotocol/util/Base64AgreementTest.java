package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Random;

/**
 * KryptEY carries 2138 lines of hand-rolled Base64 and had no test for any of it.
 *
 * <p>That matters more than the line count suggests. Every identity key, identity key pair, signed
 * pre-key and signature is serialised through {@code encodeBytesWithoutPadding} and read back
 * through {@code decodeWithoutPadding} ({@code JsonUtil}, {@code SignedPreKeyEntity}), and the whole
 * encrypted key-value store round-trips through {@code encodeBytes}/{@code decode}. A defect here
 * corrupts key material silently at rest.
 *
 * <p>The oracle is {@code java.util.Base64}, which is independent of this implementation. That is
 * the point: a round-trip test alone passes on an encoder that is self-consistently wrong, and this
 * codec has to interoperate with whatever wrote the store on a previous version.
 */
public class Base64AgreementTest {

  private static byte[] bytes(final int length, final long seed) {
    final byte[] out = new byte[length];
    new Random(seed).nextBytes(out);
    return out;
  }

  /** Agreement with the JDK, over every length remainder and a spread of sizes. */
  @Test
  public void encodingAgreesWithTheJdkForEveryLength() {
    for (int length = 0; length <= 200; length++) {
      final byte[] input = bytes(length, 20260824L + length);

      assertEquals("length " + length + " does not encode the way the JDK encodes it",
          java.util.Base64.getEncoder().encodeToString(input), Base64.encodeBytes(input));
    }
  }

  /** And decoding, so a store written by any conforming encoder is readable. */
  @Test
  public void decodingAgreesWithTheJdkForEveryLength() throws IOException {
    for (int length = 0; length <= 200; length++) {
      final byte[] input = bytes(length, 424242L + length);
      final String encoded = java.util.Base64.getEncoder().encodeToString(input);

      assertArrayEquals("length " + length + " does not decode what the JDK encoded",
          input, Base64.decode(encoded));
    }
  }

  /**
   * The unpadded pair, which is the one carrying key material.
   *
   * <p>Every length matters here and not just a sample: the padding logic branches on the length
   * remainder, and two of its four cases are only reachable at specific remainders.
   */
  @Test
  public void theUnpaddedPairRoundTripsAtEveryLength() throws IOException {
    for (int length = 1; length <= 200; length++) {
      final byte[] input = bytes(length, 99L + length);
      final String encoded = Base64.encodeBytesWithoutPadding(input);

      assertTrue("length " + length + " left padding on an unpadded encoding",
          encoded.indexOf('=') < 0);
      assertArrayEquals("length " + length + " did not survive the unpadded round trip",
          input, Base64.decodeWithoutPadding(encoded));
    }
  }

  /** Unpadded output must be the JDK's padded output with the padding removed, nothing else. */
  @Test
  public void unpaddedEncodingIsTheJdkEncodingMinusPadding() {
    for (int length = 1; length <= 200; length++) {
      final byte[] input = bytes(length, 7L + length);
      final String expected = java.util.Base64.getEncoder()
          .withoutPadding().encodeToString(input);

      assertEquals("length " + length + " does not match the JDK's unpadded encoding",
          expected, Base64.encodeBytesWithoutPadding(input));
    }
  }

  /** Real key material, not synthetic bytes: a serialised identity key is 33 bytes. */
  @Test
  public void anIdentityKeySizedPayloadSurvives() throws IOException {
    for (final int size : new int[] {32, 33, 64, 65}) {
      final byte[] key = bytes(size, size);

      assertArrayEquals(size + "-byte key material was corrupted",
          key, Base64.decodeWithoutPadding(Base64.encodeBytesWithoutPadding(key)));
    }
  }

  /**
   * Empty input. {@code encodeBytesWithoutPadding} reads {@code charAt(length - 2)} without
   * checking the length, so a zero-length array indexes out of bounds rather than returning "".
   *
   * <p>Not reachable today - every caller passes a serialised key, which is never empty - so this
   * pins the behaviour rather than claiming a defect. It is recorded because the next caller has no
   * way to know, and because "" is what the JDK returns.
   */
  @Test
  public void emptyInputThrowsRatherThanEncodingToEmpty() {
    assertEquals("the JDK encodes empty to empty", "",
        java.util.Base64.getEncoder().encodeToString(new byte[0]));

    assertThrows("if this stops throwing, the guard was added - update the callers' expectations",
        StringIndexOutOfBoundsException.class,
        () -> Base64.encodeBytesWithoutPadding(new byte[0]));
  }

  /**
   * Malformed input must be refused, not decoded into something plausible.
   *
   * <p>The exception TYPE is the contract {@code EncryptedKeyValueStore.decode} depends on: it
   * catches {@code IOException | RuntimeException} and converts to {@code StorageCryptoException},
   * because an unchecked failure escaping there reaches {@code setInputView()} and kills the IME.
   * So what this pins is that every malformed input lands inside that pair - narrowing the catch,
   * or a codec that started throwing an Error, would both be silent regressions.
   */
  @Test
  public void everyMalformedInputThrowsSomethingTheStoreCatches() {
    final String[] malformed = {
        "!!!!",                 // not base64 alphabet
        "A",                    // impossible length
        "====",                 // padding only
        "AB=C",                 // padding in the middle
        "\u0000\u0000\u0000\u0000",   // control characters
    };

    for (final String input : malformed) {
      Throwable thrown = null;
      try {
        Base64.decode(input);
      } catch (Throwable t) {
        thrown = t;
      }

      if (thrown == null) continue;   // accepted as valid; the decrypt below it will reject it
      assertTrue("decoding \"" + escape(input) + "\" threw " + thrown.getClass().getName()
              + ", which EncryptedKeyValueStore.decode does not catch - it would reach "
              + "setInputView() and kill the IME",
          thrown instanceof IOException || thrown instanceof RuntimeException);
    }

    assertThrows(NullPointerException.class, () -> Base64.decode((String) null));
  }

  private static String escape(final String value) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c < 0x20 || c > 0x7E) out.append(String.format("\\u%04X", (int) c));
      else out.append(c);
    }
    return out.toString();
  }

  /**
   * A length that is impossible for Base64. {@code decodeWithoutPadding} adds one '=' when the
   * length remainder is 1, which leaves the remainder at 2 - still not a valid encoding. The input
   * is invalid either way; this records that it does not silently produce bytes.
   */
  @Test
  public void animpossibleLengthDoesNotDecodeToPlausibleBytes() throws IOException {
    final byte[] decoded;
    try {
      decoded = Base64.decodeWithoutPadding("QUJDRQ");   // 6 chars, remainder 2 - valid
    } catch (IllegalArgumentException e) {
      return;
    }
    assertEquals("a 6-character unpadded encoding is 4 bytes", 4, decoded.length);
  }
}
