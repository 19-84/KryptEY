package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Measures the encoded size of a real pre-key bundle against the limit the UI enforces.
 *
 * <p>{@code E2EEStrip} rejects an invite above {@code CHAR_THRESHOLD_PRE_KEY_RESPONSE} (4096).
 * PQXDH adds a Kyber-1024 public key — 1568 bytes before base64 — to every bundle, so this is the
 * one measurement that decides whether key exchange is possible at all.
 *
 * <p>It used to measure {@code JsonUtil.toJson}, with a comment claiming that was "literally what
 * goes on the wire". It has not been since Phase 3: the wire format is
 * {@code EnvelopeCodec.toWire}, base64 over a binary envelope. So the one guard on invite size was
 * checking a representation the app does not produce. Its length is not even stable - the JSON
 * embeds registrationId and keyId as decimal integers, so it varies run to run (2515, 2517 and 2521
 * have all been recorded), which is why quoting it to the character was meaningless. The binary
 * envelope is fixed-width at 2484. The javadoc also named the limit as {@code CHAR_THRESHOLD_RAW} (500) when the
 * enforced one is 4096.
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
  public void reportTheActualEncodedBundleSize() throws Exception {
    final MessageEnvelope envelope = realPreKeyResponseEnvelope();

    // What E2EEStrip actually measures: the wire text, before the encoder runs.
    final String onTheWire = EnvelopeCodec.toWire(envelope);

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
  public void aPqxdhBundleMustFitTheLimitTheUiEnforces() throws Exception {
    final MessageEnvelope envelope = realPreKeyResponseEnvelope();
    final String encoded = EnvelopeCodec.toWire(envelope);

    assertTrue(
        "A PQXDH pre-key bundle encodes to " + encoded.length() + " characters but E2EEStrip "
            + "rejects invites above " + UI_CHAR_THRESHOLD + ". Key exchange is impossible until "
            + "either the threshold is raised or the bundle is made smaller.",
        encoded.length() <= UI_CHAR_THRESHOLD);
  }
}
