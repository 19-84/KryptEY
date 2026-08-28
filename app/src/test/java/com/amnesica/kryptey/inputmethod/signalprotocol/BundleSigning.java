package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.BinaryEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;

import java.io.IOException;

/**
 * Signs a hand-built bundle the way its issuer would.
 *
 * <p>Every bundle now carries a signature over the canonical encoding of its whole content, made
 * with the identity key it names, so that a relay cannot mix fields from two genuine invites by the
 * same person. That closes a measured attack and it changes what a hand-built bundle in a test
 * means: an unsigned one is no longer something any party can produce.
 *
 * <p>So the tests that build hostile bundles have to say which adversary they are. A <b>relay</b>
 * editing a field in transit is now refused by the signature, and that is the property under test in
 * {@code AsplicedBundleIsRefusedTest}. A field-level check - no Kyber key, no one-time key, a second
 * device - is defence against the <b>issuer</b> itself, since only the issuer can sign what it
 * emits; those tests sign with this helper, and they keep testing exactly what they always claimed.
 *
 * <p>Deliberately not a production shortcut: nothing in {@code main} signs a bundle it did not build
 * from its own key material.
 */
public final class BundleSigning {

  private BundleSigning() {
  }

  /** The issuing signature over this bundle, as the account holding that identity would make it. */
  public static byte[] signatureFor(final Account issuer, final PreKeyResponse bundle) {
    try {
      return issuer.getIdentityKeyPair().getPrivateKey()
          .calculateSignature(BinaryEnvelope.canonicalBundleBytes(bundle));
    } catch (final IOException e) {
      throw new AssertionError("a bundle a test built must encode canonically", e);
    }
  }

  /** An envelope carrying that bundle, signed, at the address the issuer really has. */
  public static MessageEnvelope signedEnvelope(final Account issuer, final PreKeyResponse bundle,
                                        final String addressName, final int deviceId) {
    final MessageEnvelope envelope = new MessageEnvelope(bundle, addressName, deviceId);
    envelope.setBundleSignature(signatureFor(issuer, bundle));
    return envelope;
  }

  /**
   * The edited envelope carrying the ORIGINAL invite's signature - exactly what a relay produces.
   *
   * <p>A relay can copy an invite and change a field; what it cannot do is sign the result. So the
   * faithful model of an edit in transit is the edited content with the issuer's untouched
   * signature still attached, which is what this builds. Before bundles were signed these fixtures
   * had nothing to carry and the edit was indistinguishable from a fresh issue - which is precisely
   * the gap that let a relay mix fields from two genuine invites.
   */
  public static MessageEnvelope asEditedInTransit(final MessageEnvelope original,
                                                  final MessageEnvelope edited) {
    edited.setBundleSignature(original.getBundleSignature());
    return edited;
  }

  /** Re-signs an envelope whose bundle a test has just rebuilt. */
  public static MessageEnvelope resign(final Account issuer, final MessageEnvelope envelope) {
    envelope.setBundleSignature(signatureFor(issuer, envelope.getPreKeyResponse()));
    return envelope;
  }
}
