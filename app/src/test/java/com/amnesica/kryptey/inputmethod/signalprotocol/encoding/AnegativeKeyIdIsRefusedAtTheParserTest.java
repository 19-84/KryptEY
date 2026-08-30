package com.amnesica.kryptey.inputmethod.signalprotocol.encoding;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * The three key ids the decoder guards and nothing drove.
 *
 * <p>{@code BinaryEnvelope.decode} calls {@code requireUnsigned} on four ids. A mutation sweep
 * deleted three of them - {@code signedPreKeyId}, {@code preKeyId}, {@code kyberPreKeyId} - and the
 * whole suite stayed green. The fourth, {@code registrationId}, is killed, and the reason it is
 * killed explains why the others are not: the ENCODER has a matching {@code requireUnsigned} for
 * {@code registrationId} alone, so the one test able to kill a decoder-side check reaches it
 * through the encoder's twin during its re-encode step. The three ids with no encoder twin had no
 * killer at all.
 *
 * <p>That asymmetry is also what makes this input constructible. {@code writeU32} takes the other
 * three ids as given, so a hostile issuer - their own identity key, their own signature, which
 * {@code requireTheBundleWasIssuedAsOneUnit} accepts by design - can encode a negative id through
 * the ordinary path. The bundle is internally consistent and correctly signed. Nothing is edited.
 *
 * <p><b>What the guard is holding back.</b> Without it the value reaches libsignal, which raises an
 * unchecked {@code IllegalArgumentException} - "integer overflow during conversion of -1 to u32",
 * or for the one-time pre-key "Must supply both or neither of prekey and prekey_id".
 * {@code processPreKeyResponse} catches {@code IOException} only, and the path begins at the
 * Decrypt button inside a click listener. So the input method process dies, in whatever app the
 * user is typing in, repeatably, on a paste the messenger chose. This project names that as its
 * worst failure mode; the guards are what keep it a refusal instead.
 *
 * <p><b>Do not answer a failure here by adding {@code requireUnsigned} to the encoder</b> "for
 * symmetry". It would kill these mutants without adding a line of real coverage, and it would take
 * away the only way to construct the hostile input - leaving the decoder's guards undriven again,
 * with a green suite saying otherwise.
 */
public class AnegativeKeyIdIsRefusedAtTheParserTest {

  /** Encodes the way a hostile issuer would: ordinary path, signature-shaped bytes in place. */
  private static String wireTextWithIds(final int signedId, final int preKeyId, final int kyberId)
      throws IOException {
    return EnvelopeCodec.toWire(WireFixtures.carryingSignatureShapedBytes(
        AwireBundleFixture.bundleEnvelopeWithIds(signedId, preKeyId, kyberId)));
  }

  /**
   * The premise: with ordinary ids this bundle encodes and decodes.
   *
   * <p>Without this, every case below would pass on a fixture that cannot round-trip at all - an
   * exception thrown for a reason unrelated to the guard reads exactly like the guard working.
   */
  @Test
  public void thepremiseThatThisBundleRoundTrips() throws Exception {
    assertNotNull("an ordinary bundle must decode, or the refusals below prove nothing",
        EnvelopeCodec.fromWire(wireTextWithIds(11, 12, 13)));
  }

  @Test
  public void anegativeSignedPreKeyIdIsRefused() throws Exception {
    final String wire = wireTextWithIds(-1, 12, 13);
    final IOException refused = assertThrows("a negative signed pre-key id must be refused at the "
            + "parser as a checked IOException. Reaching libsignal it becomes an unchecked throw "
            + "out of a click listener, and the keyboard dies in the user's messenger",
        IOException.class, () -> EnvelopeCodec.fromWire(wire));
    assertTrue("and the refusal must name the field, so the next reader is not left guessing: "
        + refused.getMessage(), refused.getMessage().contains("signedPreKeyId"));
  }

  @Test
  public void anegativeOneTimePreKeyIdIsRefused() throws Exception {
    final String wire = wireTextWithIds(11, -1, 13);
    final IOException refused = assertThrows("a negative one-time pre-key id must be refused at "
            + "the parser", IOException.class, () -> EnvelopeCodec.fromWire(wire));
    assertTrue("naming the field: " + refused.getMessage(),
        refused.getMessage().contains("preKeyId"));
  }

  @Test
  public void anegativeKyberPreKeyIdIsRefused() throws Exception {
    final String wire = wireTextWithIds(11, 12, -1);
    final IOException refused = assertThrows("a negative Kyber pre-key id must be refused at the "
            + "parser", IOException.class, () -> EnvelopeCodec.fromWire(wire));
    assertTrue("naming the field: " + refused.getMessage(),
        refused.getMessage().contains("kyberPreKeyId"));
  }

  /**
   * Integer.MIN_VALUE as well as -1, because the two fail differently.
   *
   * <p>-1 is 0xFFFFFFFF, which libsignal reports as an overflow converting to u32.
   * {@code Integer.MIN_VALUE} is 0x80000000 - still negative, still refused here, but downstream it
   * is a perfectly ordinary u32 that no conversion complains about. A guard that happened to catch
   * only the all-ones case would leave that one through.
   */
  @Test
  public void themostNegativeIdIsRefusedTooNotJustMinusOne() throws Exception {
    final String wire = wireTextWithIds(Integer.MIN_VALUE, 12, 13);
    assertThrows("every negative id must be refused, not only 0xFFFFFFFF - 0x80000000 converts to "
            + "u32 without complaint downstream, so this one would pass silently into a session",
        IOException.class, () -> EnvelopeCodec.fromWire(wire));
  }
}
