package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Measures the encoded size of a real pre-key bundle against the limit the UI enforces.
 *
 * <p>{@code E2EEStrip} rejects an invite / key-bundle message above {@code CHAR_THRESHOLD_RAW}
 * (500). PQXDH adds a Kyber-1024 public key — 1568 bytes before base64 — to every bundle, so this
 * is the one measurement that decides whether key exchange is possible at all. Nothing else in the
 * suite touches the UI layer, which is exactly why this could regress unnoticed.
 */
public class PreKeyBundleSizeTest {

  /**
   * Read from the production constant, not copied. A duplicated literal would let the two drift and
   * this test would then be asserting against a limit the app does not enforce.
   */
  private static final int UI_CHAR_THRESHOLD =
      com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStrip.CHAR_THRESHOLD_PRE_KEY_RESPONSE;

  private static MessageEnvelope realPreKeyResponseEnvelope() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull("could not build a pre-key response envelope", envelope);
    assertNotNull("envelope carries no pre-key response", envelope.getPreKeyResponse());
    return envelope;
  }

  @Test
  public void reportTheActualEncodedBundleSize() {
    final MessageEnvelope envelope = realPreKeyResponseEnvelope();

    // What E2EEStrip actually measures is the serialized envelope string, before the encoder runs
    // (and RawEncoder is the identity function, so this is literally what goes on the wire).
    final String onTheWire = JsonUtil.toJson(envelope);

    System.out.println("BUNDLE wire bytes    = "
        + onTheWire.getBytes(StandardCharsets.UTF_8).length);
    System.out.println("BUNDLE ui threshold  = " + UI_CHAR_THRESHOLD);

    assertTrue("sanity: a PQXDH bundle should be far from empty", onTheWire.length() > 100);
  }

  /**
   * The blocking assertion. If a bundle does not fit the limit the UI enforces, no user can ever
   * send an invite and the app cannot establish a single session — the keyboard's entire purpose.
   */
  @Test
  public void aPqxdhBundleMustFitTheLimitTheUiEnforces() {
    final MessageEnvelope envelope = realPreKeyResponseEnvelope();
    final String encoded = JsonUtil.toJson(envelope);

    assertTrue(
        "A PQXDH pre-key bundle encodes to " + encoded.length() + " characters but E2EEStrip "
            + "rejects invites above " + UI_CHAR_THRESHOLD + ". Key exchange is impossible until "
            + "either the threshold is raised or the bundle is made smaller.",
        encoded.length() <= UI_CHAR_THRESHOLD);
  }
}
