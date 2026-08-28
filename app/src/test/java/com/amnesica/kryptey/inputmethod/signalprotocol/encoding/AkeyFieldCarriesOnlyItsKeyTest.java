package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

import java.io.IOException;
import java.util.Arrays;

/**
 * A length-prefixed key field must carry the key and nothing else.
 *
 * <p>libsignal's deserialisers read a key from the front of the array and ignore what follows.
 * Measured against 0.86.5: a 33-byte EC public key with eight bytes appended is accepted, and
 * re-serialises to the same 33 bytes. The wire format prefixes each key with its own length, so
 * those extra bytes were parsed, discarded, and the cursor then declared the envelope exhausted -
 * contradicting {@code requireExhausted}'s own sentence about nothing riding past the parser, in
 * exactly the way the device-count refusal was written to stop.
 *
 * <p>It matters more since bundles became signed. The signature is verified over the canonical
 * re-encoding of what was parsed, so padding does not break it - it re-encodes away. A relay could
 * pad a genuine invite and have it verify, which makes the wire text malleable while every
 * downstream decision stays identical. Nothing renders the padding, so this is a malleability
 * primitive rather than a live attack; the reason to refuse it is that the alternative is a written
 * invariant that is false.
 */
public class AkeyFieldCarriesOnlyItsKeyTest {

  /** The library behaviour this refusal exists because of. Pinned, since an upgrade could change it. */
  @Test
  public void libsignalItselfAcceptsTrailingBytesAfterAkey() throws Exception {
    final ECPublicKey key = IdentityKeyPair.generate().getPublicKey().getPublicKey();
    final byte[] canonical = key.serialize();

    final ECPublicKey padded = new ECPublicKey(Arrays.copyOf(canonical, canonical.length + 8), 0);

    assertArrayEquals("if this ever throws instead, the decoder's refusal below is belt and braces "
            + "rather than the only thing standing - which is worth knowing either way",
        canonical, padded.serialize());
  }

  /** So the decoder refuses the padded field rather than parsing it back to the same key. */
  @Test
  public void apaddedKeyFieldIsRefused() throws Exception {
    final byte[] genuine = BinaryEnvelope.encode(WireFixtures.carryingSignatureShapedBytes(
        AwireBundleFixture.bundleEnvelope()));

    final byte[] padded = AwireBundleFixture.padTheIdentityKeyField(genuine);
    assertTrue("fixture: padding must actually change the bytes", padded.length > genuine.length);

    final IOException refused = assertThrows("a key field carrying more than its key must be "
            + "refused; accepted, the same envelope has more than one wire spelling and the parser "
            + "still calls itself exhausted",
        IOException.class, () -> BinaryEnvelope.decode(padded));
    assertTrue("the refusal must name what was wrong: " + refused.getMessage(),
        refused.getMessage().contains("trailing bytes"));
  }

  /** And the unpadded envelope still decodes, so the refusal is not simply refusing everything. */
  @Test
  public void thegenuineEnvelopeStillDecodes() throws Exception {
    final MessageEnvelope decoded = BinaryEnvelope.decode(BinaryEnvelope.encode(
        WireFixtures.carryingSignatureShapedBytes(AwireBundleFixture.bundleEnvelope())));

    assertEquals("peer", decoded.getSignalProtocolAddressName());
    assertTrue("the bundle must survive", decoded.getPreKeyResponse() != null);
  }
}
