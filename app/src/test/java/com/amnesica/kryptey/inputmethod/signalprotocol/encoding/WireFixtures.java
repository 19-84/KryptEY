package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;

/**
 * Signature-shaped bytes for codec tests.
 *
 * <p>A bundle on the wire now carries a signature over its own canonical encoding, so the encoder
 * refuses to emit one without it. The codec does not <em>verify</em> that signature and should not:
 * verification needs the identity key the bundle names and belongs where the bundle is processed,
 * which is {@code SignalProtocolMain.requireTheBundleWasIssuedAsOneUnit}. A codec test therefore
 * needs bytes of the right shape and nothing more.
 *
 * <p>Named so that no reader mistakes it for a valid signature. Anything asserting that a spliced or
 * edited bundle is refused must go through the protocol layer, where the check actually lives -
 * {@code AsplicedBundleIsRefusedTest} is where that belongs.
 */
final class WireFixtures {

  /** Not a signature. Sixty-four bytes so the field looks like what production emits. */
  static final byte[] UNVERIFIED_SIGNATURE_SHAPED_BYTES = new byte[64];

  static {
    for (int i = 0; i < UNVERIFIED_SIGNATURE_SHAPED_BYTES.length; i++) {
      UNVERIFIED_SIGNATURE_SHAPED_BYTES[i] = (byte) (i + 1);
    }
  }

  private WireFixtures() {
  }

  /** The same envelope, carrying bytes where the issuing signature goes. */
  static MessageEnvelope carryingSignatureShapedBytes(final MessageEnvelope envelope) {
    envelope.setBundleSignature(UNVERIFIED_SIGNATURE_SHAPED_BYTES);
    return envelope;
  }
}
