package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.FairyTaleEncoder;

import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.util.Random;

/**
 * The send-side length caps and the receive-side decode cap must agree, and nothing currently
 * relates them.
 *
 * <p>They count different things in different units. {@code checkMessageLengthForEncodingMethod}
 * counts UTF-8 bytes of the <em>plaintext wire text</em> before encoding; {@code MAX_DECODABLE_CHARS}
 * counts characters of the <em>encoded text</em> after it. The FairyTale encoder expands - it
 * compresses, then spends one invisible character per 4 bits, then prepends a decoy sentence - so
 * the two are separated by a ratio that no test pins and no comment states.
 *
 * <p>Measured today the ratio is about 1.57 for incompressible input, so the 4096-byte invite cap
 * produces 6402 characters against a limit of 8192. There is headroom, but it is accidental
 * headroom: raising the invite threshold past roughly 5240 would make key bundles that send
 * successfully and then cannot be decoded by the person who receives them - and the failure would
 * appear on the recipient's device, in a different constant, in a different class.
 *
 * <p>These are not asserting a ratio, which would be brittle. They assert the property that
 * matters: whatever the send side permits, the receive side must accept.
 */
public class SendAndReceiveLimitsTest {

  @Before
  public void loadDecoySentences() {
    // The decoy corpus does not affect any property here - only its length does, and the real
    // sentences are shorter than these. Using initForTest keeps this out of Robolectric, where a
    // stale static storage helper from an earlier class makes SignalProtocolMain.initialize hand
    // back a null account.
    FairyTaleEncoder.initForTest(
        "Once upon a time there was a keyboard. It kept its own counsel. Nobody read its mail.",
        "The miller had a daughter. She spun straw. The straw was never gold, only straw.");
    SignalProtocolMain.testIsRunning = true;
  }

  /**
   * Incompressible text of exactly the given size - the worst case, since the encoder compresses
   * first. Ordinary prose would understate the expansion and let the property pass on inputs the
   * app will not see.
   */
  private String incompressible(final int bytes) {
    final String alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    final Random random = new Random(20260824L);
    final StringBuilder builder = new StringBuilder(bytes);
    while (builder.length() < bytes) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return builder.toString();
  }

  private void assertSendableIsDecodable(final int sendLimitBytes, final String what)
      throws IOException {
    final String plain = incompressible(sendLimitBytes);
    final String encoded = FairyTaleEncoder.encode(plain, null);

    assertNotNull(what + " did not encode", encoded);
    assertTrue(what + " is permitted at " + sendLimitBytes + " bytes but encodes to "
            + encoded.length() + " characters, past the " + E2EEStrip.MAX_DECODABLE_CHARS
            + "-character decode cap - it would send successfully and fail on the recipient's "
            + "device, in a different constant in a different class",
        encoded.length() <= E2EEStrip.MAX_DECODABLE_CHARS);
  }

  @Test
  public void theLargestPermittedInviteIsStillDecodable() throws IOException {
    assertSendableIsDecodable(E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE, "a key bundle");
  }

  @Test
  public void theLargestPermittedMessageIsStillDecodable() throws IOException {
    // The two message thresholds are separate constants that happen to be equal; take the larger
    // so this keeps meaning something if one of them moves.
    assertSendableIsDecodable(
        Math.max(E2EEStrip.CHAR_THRESHOLD_RAW, E2EEStrip.CHAR_THRESHOLD_FAIRYTALE),
        "a chat message");
  }

  /**
   * And the real article, not a synthetic worst case: an actual PQXDH bundle, which is what the
   * invite threshold was raised to 4096 to accommodate in the first place.
   */
  @Test
  public void arealPqxdhBundleEncodesWellInsideTheDecodeCap() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final String wire = SignalProtocolMain.exportOwnKeyBundle();

    assertTrue("precondition: the bundle must be within the send-side cap",
        wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            <= E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE);

    final String encoded = FairyTaleEncoder.encode(wire, null);
    assertTrue("a real PQXDH bundle encoded to " + encoded.length()
            + " characters, past the decode cap of " + E2EEStrip.MAX_DECODABLE_CHARS,
        encoded.length() <= E2EEStrip.MAX_DECODABLE_CHARS);
  }

  /**
   * The RAW encoder is a pass-through, so its own cap must simply be below the decode cap. Trivial
   * today and worth stating: it is the assumption that makes the default path safe without any of
   * the expansion arithmetic above.
   */
  @Test
  public void theRawPathNeedsNoExpansionHeadroom() {
    assertTrue("the raw send cap exceeds the decode cap",
        E2EEStrip.CHAR_THRESHOLD_RAW <= E2EEStrip.MAX_DECODABLE_CHARS);
  }
}
