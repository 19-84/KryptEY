package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * The invisible alphabet is a wire format between installs, not an internal detail.
 *
 * <p>Two KryptEY users are usually on different versions - that is the whole point of a
 * copy-and-paste messenger - so the mapping from nibble to zero-width character is as much a
 * compatibility contract as the envelope layout. Permuting it consistently in both directions
 * survives every round-trip test there is, because a round trip only ever asks whether the encoder
 * agrees with itself. It does not ask whether it agrees with the version the other person is
 * running.
 *
 * <p>So this pins the actual characters, the way {@code WireFormatGoldenTest} pins the binary
 * envelope. A change here is a decision to break every existing install, and should have to be
 * made deliberately rather than by editing a switch.
 */
public class FairyTaleGoldenTest {

  @Before
  public void loadDecoySentences() {
    FairyTaleEncoder.initForTest(
        "Once upon a time there was a keyboard. It kept its own counsel. Nobody read its mail.",
        "The miller had a daughter. She spun straw. The straw was never gold, only straw.");
  }

  /**
   * All sixteen nibbles, in order, as one golden string.
   *
   * <p>Written as escapes rather than literals on purpose: these characters are invisible, so a
   * literal would be unreviewable and a stray edit undetectable in a diff.
   */
  private static final String NIBBLES_0_TO_15 =
      "\u200C\u200D\u2060\u2062"      // 0 1 2 3
          + "\u200B\u200E\u200F\u2064"  // 4 5 6 7
          + "\u206A\u206B\u206C\u206D"  // 8 9 A B
          + "\u206E\u206F\uFEFF\u061C"; // C D E F

  @Test
  public void theInvisibleAlphabetIsUnchanged() {
    // 0x01 0x23 0x45 0x67 0x89 0xAB 0xCD 0xEF - every nibble value exactly once, in order.
    final byte[] everyNibble = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB,
        (byte) 0xCD, (byte) 0xEF};
    final String binary = EncodeHelper.convertByteArrayToBinary(everyNibble);

    assertEquals("the nibble-to-character mapping changed - this is a wire format shared with "
            + "every other install, and changing it makes messages from older versions undecodable",
        NIBBLES_0_TO_15, EncodeHelper.convertBinaryToInvisibleString(binary));
  }

  /** And the reverse direction against the same golden string, so neither half can drift alone. */
  @Test
  public void theAlphabetDecodesBackToEveryNibble() {
    assertEquals("0000000100100011010001010110011110001001101010111100110111101111",
        EncodeHelper.convertInvisibleStringToBinary(NIBBLES_0_TO_15));
  }

  /**
   * The decoy must vary.
   *
   * <p>A fixed decoy defeats the entire purpose of this encoder. Its job is to make an encrypted
   * message look like an innocuous sentence to whoever is reading the channel; if every message
   * carries the same sentence, the sentence itself becomes the signature, and a messenger can flag
   * every KryptEY user by string match without decoding anything.
   *
   * <p>Replacing {@code Random.nextInt(size)} with a constant 0 survived the whole suite.
   */
  @Test
  public void thedecoySentenceVaries() throws IOException {
    final Set<String> decoys = new HashSet<>();
    for (int i = 0; i < 40; i++) {
      final String encoded = FairyTaleEncoder.encode("{\"a\":" + i + "}", null);
      // The decoy is the visible prefix; the payload is invisible.
      final StringBuilder visible = new StringBuilder();
      for (int c = 0; c < encoded.length(); c++) {
        final char ch = encoded.charAt(c);
        if (NIBBLES_0_TO_15.indexOf(ch) < 0) visible.append(ch);
      }
      decoys.add(visible.toString());
    }

    assertTrue("40 encodes produced " + decoys.size() + " distinct decoy sentences - a fixed decoy "
            + "makes the sentence itself the signature that identifies every KryptEY user",
        decoys.size() > 3);
  }
}
