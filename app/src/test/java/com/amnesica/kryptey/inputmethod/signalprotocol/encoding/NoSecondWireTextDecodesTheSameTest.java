package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

import java.util.ArrayList;
import java.util.List;

/**
 * One envelope, one wire text — checked by trying to find a second.
 *
 * <p>`requireExhausted` says its job is that "refusing keeps a hostile envelope from smuggling data
 * past the parser", and the canonical-encoding check in {@code EnvelopeCodec} says the encoding must
 * be the only encoding of its bytes. Both were true at the layers they inspect and neither noticed a
 * byte with 255 accepted spellings: the presence flags were read as {@code != 0}, so every envelope
 * carrying an optional field had 254 alternative wire texts that decoded to an identical object and
 * passed every check in the chain.
 *
 * <p>So this looks for the thing the invariant forbids rather than for the case that broke it: take
 * a canonical envelope, change one byte, and see whether the result still decodes to the same
 * envelope. Sameness is decided by re-encoding — if the mutated text decodes to something that
 * encodes back to the original bytes, then two distinct wire texts mean one message, which is what
 * malleability is.
 *
 * <p>It does not claim to be exhaustive: it varies one byte at a time from a small set of values, so
 * a malleability needing two simultaneous changes is outside it. It is the shape of the property,
 * checked over the whole envelope rather than at the one field somebody remembered.
 */
public class NoSecondWireTextDecodesTheSameTest {

  private static MessageEnvelope bundleEnvelope() throws Exception {
    final IdentityKeyPair identity = IdentityKeyPair.generate();
    final ECKeyPair signed = ECKeyPair.generate();
    final ECKeyPair oneTime = ECKeyPair.generate();
    final byte[] signature = identity.getPrivateKey().calculateSignature(
        signed.getPublicKey().serialize());

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(3, 4242,
        new SignedPreKeyEntity(11, signed.getPublicKey(), signature),
        new PreKeyEntity(12, oneTime.getPublicKey()),
        null));
    return WireFixtures.carryingSignatureShapedBytes(new MessageEnvelope(
        new PreKeyResponse(identity.getPublicKey(), devices), "peer", 3));
  }

  /**
   * The sweep itself, so it can be pointed at more than one envelope shape.
   *
   * <p>It was written against one locally-built fixture and stayed that way, which is how the
   * {@code hasKyber} flag went undriven: this file's own {@code bundleEnvelope()} passes
   * {@code null} for the Kyber pre-key, so that byte is always {@code 0} and every mutation of it
   * truncates the envelope instead of exercising the flag. The identical guard seven lines above it
   * in the decoder, on {@code hasPreKey}, is killed by this very test. A shared Kyber-carrying
   * fixture sat in the same package the whole time.
   *
   * <p>The lesson is worth more than the fix: the marker for an untested twin here was not a
   * comment, it was <b>a field set to null in a fixture</b>.
   */
  private void assertExactlyOneWireTextDecodesTo(final MessageEnvelope envelope, final String what)
      throws Exception {
    final byte[] canonical = BinaryEnvelope.encode(envelope);
    assertNotNull(canonical);
    assertTrue(what + ": the fixture must produce a real envelope to mutate",
        canonical.length > 40);

    // Re-encoding the decoded original is what "the same envelope" means below.
    final byte[] reference = BinaryEnvelope.encode(BinaryEnvelope.decode(canonical));
    assertEquals(what + ": the encoder must be stable, or this test compares nothing",
        java.util.Arrays.toString(canonical), java.util.Arrays.toString(reference));

    final List<String> malleable = new ArrayList<>();
    int accepted = 0;
    for (int i = 0; i < canonical.length; i++) {
      for (final int replacement : new int[] {0x00, 0x01, 0x02, 0x7f, 0xff}) {
        if ((canonical[i] & 0xff) == replacement) continue;
        final byte[] mutated = canonical.clone();
        mutated[i] = (byte) replacement;
        final MessageEnvelope decoded;
        try {
          decoded = BinaryEnvelope.decode(mutated);
        } catch (final Exception refused) {
          continue;   // refusing a changed byte is the correct outcome
        }
        accepted++;
        if (java.util.Arrays.equals(BinaryEnvelope.encode(decoded), reference)) {
          malleable.add("byte " + i + " set to 0x" + Integer.toHexString(replacement));
        }
      }
    }

    assertTrue(what + ": this sweep must actually reach the decoder; if every mutation was "
        + "refused before parsing, it is measuring nothing", accepted > 0);
    assertEquals(what + ": a second wire text decodes to the same envelope. Two spellings of one "
        + "message is "
        + "what the canonical-encoding check and requireExhausted both say cannot happen, and a "
        + "byte with more than one accepted value is where it happens:\n"
        + String.join("\n", malleable), 0, malleable.size());
  }

  /** The original shape: a bundle with a one-time pre-key and NO Kyber key. */
  @Test
  public void nosingleByteChangeProducesAsecondSpellingOfTheSameEnvelope() throws Exception {
    assertExactlyOneWireTextDecodesTo(bundleEnvelope(), "bundle without a Kyber key");
  }

  /**
   * The shape this app actually emits, and the one the sweep could not see.
   *
   * <p>PQXDH is mandatory here, so every invite this app sends carries a Kyber pre-key. With
   * {@code requireFlag} gone from that byte, every real invite has 254 alternative wire texts that
   * decode to an identical envelope and satisfy both the canonical-encoding check and
   * {@code requireExhausted}.
   */
  @Test
  public void akyberCarryingBundleHasOneWireText() throws Exception {
    assertExactlyOneWireTextDecodesTo(
        WireFixtures.carryingSignatureShapedBytes(AwireBundleFixture.bundleEnvelope()),
        "bundle carrying a Kyber key");
  }

  /**
   * A bundle and a ciphertext in one envelope - the shape an invite reply takes.
   *
   * <p>Both presence flags are set here, so it is the only shape in which a mutation of one can be
   * masked by the structure the other imposes.
   */
  @Test
  public void acombinedBundleAndCiphertextHasOneWireText() throws Exception {
    final MessageEnvelope combined = AwireBundleFixture.bundleEnvelope();
    combined.setCiphertextMessage(new byte[] {9, 8, 7, 6, 5, 4, 3, 2});
    combined.setCiphertextType(3);
    assertExactlyOneWireTextDecodesTo(WireFixtures.carryingSignatureShapedBytes(combined),
        "bundle and ciphertext together");
  }

  /**
   * And the commonest shape of all: a message with no bundle beside it.
   *
   * <p>The ciphertext is 64 bytes rather than a token handful, so the envelope clears the
   * "is this a real envelope" floor the sweep asserts. A short one does not, and the first version
   * of this case failed on exactly that - which is the floor doing its job: an envelope too small
   * to be realistic is one whose sweep would cover almost nothing.
   */
  @Test
  public void aciphertextOnlyEnvelopeHasOneWireText() throws Exception {
    final byte[] ciphertext = new byte[64];
    for (int i = 0; i < ciphertext.length; i++) ciphertext[i] = (byte) (i + 1);
    assertExactlyOneWireTextDecodesTo(
        new MessageEnvelope(ciphertext, 3, "peer", 3), "ciphertext only");
  }
}
