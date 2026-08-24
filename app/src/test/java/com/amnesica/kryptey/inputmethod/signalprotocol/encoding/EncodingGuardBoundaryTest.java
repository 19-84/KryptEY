package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * Guards and bounds in the encoding helpers, tested at the values that define them.
 *
 * <p>A mutation sweep found these unguarded. They matter more than most boundary cases because this
 * is the code that runs on the IME main thread against whatever happens to be on the clipboard, and
 * because one of these guards exists to stop a crash that already shipped: copying any two-line
 * message routed text with no encoded bits into {@code new BigInteger("", 2)}, which threw and
 * killed the keyboard process.
 *
 * <p>Each guard is written {@code x == null || x.isEmpty()}. Weakening the {@code ||} to
 * {@code &&} leaves a null slipping through to an NPE and an empty string to the original crash —
 * and survived, because the existing tests exercise only one of the two arms.
 */
public class EncodingGuardBoundaryTest {

  // ------------------------------------------------ convertBinaryToByteArray guards

  @Test
  public void anEmptyBitStringIsRejectedRatherThanCrashing() {
    final IllegalArgumentException e = assertThrows(
        "an empty bit string must be refused, not handed to BigInteger",
        IllegalArgumentException.class, () -> EncodeHelper.convertBinaryToByteArray(""));
    assertTrue(e.getMessage(), e.getMessage().contains("no encoded bits"));
  }

  /** The other arm of the same guard, which no test covered. */
  @Test
  public void aNullBitStringIsRejectedRatherThanCrashing() {
    final IllegalArgumentException e = assertThrows(
        "a null bit string must hit the guard, not an NPE",
        IllegalArgumentException.class, () -> EncodeHelper.convertBinaryToByteArray(null));
    assertTrue(e.getMessage(), e.getMessage().contains("no encoded bits"));
  }

  /** And a real bit string still decodes, or the guard could just reject everything. */
  @Test
  public void aRealBitStringStillDecodes() {
    final byte[] bytes = EncodeHelper.convertBinaryToByteArray("00000001");
    assertNotNull(bytes);
    assertTrue("expected at least one byte", bytes.length >= 1);
  }

  // ------------------------------------- encodedTextContainsInvisibleCharacters guards

  @Test
  public void anEmptyTextIsRejectedByTheInvisibleCharacterCheck() {
    assertThrows(IOException.class,
        () -> EncodeHelper.encodedTextContainsInvisibleCharacters(""));
  }

  @Test
  public void aNullTextIsRejectedByTheInvisibleCharacterCheck() {
    assertThrows("a null text must hit the guard rather than NPE inside the matcher",
        IOException.class, () -> EncodeHelper.encodedTextContainsInvisibleCharacters(null));
  }

  @Test
  public void ordinaryTextReportsNoInvisibleCharacters() throws Exception {
    org.junit.Assert.assertFalse(
        EncodeHelper.encodedTextContainsInvisibleCharacters("just some words"));
    assertTrue("a zero-width character must be detected",
        EncodeHelper.encodedTextContainsInvisibleCharacters("word‌word"));
  }

  // ------------------------------------------ convertBinaryToInvisibleString bounds

  /**
   * The bit string is consumed in groups of four, and a trailing partial group is dropped.
   *
   * <p>Both bounds that decide this survived mutation: the loop's {@code i < length} and the
   * {@code endExclusive >= length + 1} skip. Asserting only that a well-formed multiple of four
   * round-trips leaves both alive, because they differ only on the ragged tail.
   */
  @Test
  public void exactMultiplesOfFourAreFullyConsumed() {
    assertEquals("one group must produce one character",
        1, EncodeHelper.convertBinaryToInvisibleString("0000").length());
    assertEquals("two groups must produce two characters",
        2, EncodeHelper.convertBinaryToInvisibleString("00000001").length());
    assertEquals(3, EncodeHelper.convertBinaryToInvisibleString("000000010010").length());
  }

  /**
   * Note the loop's own bound is an <b>equivalent mutant</b> and is not chased here. Widening
   * {@code i < length} to {@code i <= length} adds one iteration, reachable only when the length is
   * an exact multiple of four — and that iteration computes {@code endExclusive = length + 4}, which
   * the partial-group skip immediately rejects. The skip is the real bound; the loop condition is
   * belt and braces. Recording the reasoning rather than manufacturing an assertion for it.
   */
  /** A ragged tail is dropped rather than half-encoded or overrunning the string. */
  @Test
  public void aTrailingPartialGroupIsDropped() {
    for (int extra = 1; extra <= 3; extra++) {
      final StringBuilder bits = new StringBuilder("00000001"); // two whole groups
      for (int i = 0; i < extra; i++) bits.append('1');
      assertEquals("a partial group of " + extra + " bits must be dropped, not encoded",
          2, EncodeHelper.convertBinaryToInvisibleString(bits.toString()).length());
    }
  }

  @Test
  public void anEmptyBitStringEncodesToNothing() {
    assertEquals(0, EncodeHelper.convertBinaryToInvisibleString("").length());
  }

  /** Shorter than one whole group: nothing at all, and no exception. */
  @Test
  public void aBitStringShorterThanOneGroupEncodesToNothing() {
    for (final String tooShort : new String[] {"0", "01", "010"}) {
      assertEquals("\"" + tooShort + "\" must produce no output",
          0, EncodeHelper.convertBinaryToInvisibleString(tooShort).length());
    }
  }

  // -------------------------------------------------------- the wire size cap

  /**
   * The wire cap is the guard against the FairyTale inflate amplification — a ~390 KB clipboard
   * payload inflating to 64 MB on the IME main thread — so where exactly it sits is load-bearing.
   */
  @Test
  public void textOfExactlyTheWireCapIsAccepted() {
    final StringBuilder atCap = new StringBuilder();
    for (int i = 0; i < EnvelopeCodec.MAX_WIRE_CHARS; i++) atCap.append('A');

    // Not a valid envelope, so it must fail as a decode error - never as "too large".
    final IOException e =
        assertThrows(IOException.class, () -> EnvelopeCodec.fromWire(atCap.toString()));
    org.junit.Assert.assertFalse("text of exactly the cap must not be rejected for size",
        e.getMessage().contains("too large"));
  }

  @Test
  public void textOneCharacterOverTheWireCapIsRejectedForSize() {
    final StringBuilder overCap = new StringBuilder();
    for (int i = 0; i < EnvelopeCodec.MAX_WIRE_CHARS + 1; i++) overCap.append('A');

    final IOException e =
        assertThrows(IOException.class, () -> EnvelopeCodec.fromWire(overCap.toString()));
    assertTrue(e.getMessage(), e.getMessage().contains("too large"));
  }
}
