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

  /**
   * The {@code ec()} call site, which reads two fields and had no test at all.
   *
   * <p>{@code exactlyItsOwnEncoding} is called three times: on the identity key (the case above),
   * on the Kyber public key, and inside {@code ec()} - which reads BOTH the signed pre-key and the
   * one-time pre-key. A sweep found the last two driven by nothing, and the cause was the fixture:
   * {@code apaddedKeyFieldIsRefused} only ever calls {@code padTheIdentityKeyField}, and the
   * byte-flip sweep next door cannot reach these because padding needs two simultaneous changes -
   * the length prefix and the inserted bytes - which its own javadoc excludes.
   *
   * <p>Asserting the message text, not merely that something was thrown: a padded field refused for
   * an unrelated reason would satisfy a bare {@code assertThrows} while saying nothing about this
   * guard. That distinction is not hypothetical - it is exactly what the Kyber field turned out to
   * do, and it is why that field is tested separately below rather than in this table.
   */
  @Test
  public void bothFieldsEcReadsRefusePadding() throws Exception {
    final byte[] genuine = BinaryEnvelope.encode(WireFixtures.carryingSignatureShapedBytes(
        AwireBundleFixture.bundleEnvelope()));

    final String[] names = {"signed pre-key", "one-time pre-key"};
    final byte[][] padded = {
        AwireBundleFixture.padTheSignedPreKeyField(genuine),
        AwireBundleFixture.padTheOneTimePreKeyField(genuine)};

    for (int i = 0; i < names.length; i++) {
      final byte[] bytes = padded[i];
      assertTrue("fixture: padding the " + names[i] + " must actually change the bytes",
          bytes.length > genuine.length);

      final IOException refused = assertThrows("a padded " + names[i] + " field must be refused. "
              + "Accepted, the same envelope has more than one wire spelling while the parser still "
              + "calls itself exhausted, and the issuing signature re-encodes the padding away so a "
              + "padded genuine invite still verifies",
          IOException.class, () -> BinaryEnvelope.decode(bytes));
      assertTrue("the " + names[i] + " refusal must be the trailing-bytes one rather than some "
              + "other complaint that happens to fire: " + refused.getMessage(),
          refused.getMessage().contains("trailing bytes"));
    }
  }

  /**
   * The Kyber field is refused, but not by this app - and that is worth pinning, not glossing.
   *
   * <p>The first version of this test asserted the trailing-bytes message here too and failed with
   * {@code malformed kyber pre key}. The reason is a real difference between the two libsignal key
   * types: {@code ECPublicKey} accepts trailing bytes and re-serialises without them, which is the
   * whole reason {@code exactlyItsOwnEncoding} exists, while {@code KEMPublicKey} rejects an
   * over-long array outright. So the guard on the Kyber field never fires: libsignal refuses first.
   *
   * <p>That makes it defence in depth rather than a live control, and it explains why a sweep could
   * mutate it and construct no harm. It is worth keeping and worth pinning for the same reason the
   * EC behaviour is pinned above: if a libsignal upgrade ever relaxed {@code KEMPublicKey}, this
   * app's own refusal is what would stand, and nothing else in the suite would notice the change.
   */
  @Test
  public void apaddedKyberFieldIsRefusedByLibsignalBeforeTheAppsOwnGuard() throws Exception {
    final byte[] genuine = BinaryEnvelope.encode(WireFixtures.carryingSignatureShapedBytes(
        AwireBundleFixture.bundleEnvelope()));
    final byte[] padded = AwireBundleFixture.padTheKyberPreKeyField(genuine);
    assertTrue("fixture: padding must actually change the bytes", padded.length > genuine.length);

    final IOException refused = assertThrows("a padded Kyber field must not decode",
        IOException.class, () -> BinaryEnvelope.decode(padded));
    assertTrue("today libsignal's own KEMPublicKey refuses the over-long array first, so the "
            + "message is the malformed-key one rather than this app's trailing-bytes refusal. If "
            + "this assertion ever fails with 'trailing bytes', that is not a regression - it means "
            + "KEMPublicKey has become permissive and the app's guard is now the thing standing. "
            + "Was: " + refused.getMessage(),
        refused.getMessage().contains("malformed kyber pre key"));
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
