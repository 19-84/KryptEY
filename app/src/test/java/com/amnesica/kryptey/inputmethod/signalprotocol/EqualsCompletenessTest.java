package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Every field of an envelope must participate in {@code equals}.
 *
 * <p>These classes have no production caller for {@code equals} — but the serialisation tests do:
 * {@code SignalProtocolTest} asserts {@code assertEquals(sent, received)} to prove an envelope
 * survives a JSON round trip. An {@code equals} that ignores a field makes that assertion vacuous
 * for the field it ignores, and the round trip could silently drop it.
 *
 * <p>A mutation sweep found the conjunction unguarded: nine of the operators in these two
 * {@code equals} methods could be weakened with the whole suite still green, because nothing ever
 * compared two objects that differ in exactly one field.
 */
public class EqualsCompletenessTest {

  /**
   * The timestamp is pinned, not left to the clock.
   *
   * <p>{@code MessageEnvelope} stamps {@code System.currentTimeMillis()} in its constructor, so two
   * "identical" envelopes built microseconds apart differ whenever the millisecond ticks between
   * them. An earlier version of this class left that to chance and passed on luck — a flake that
   * would have surfaced later as an unexplained CI failure.
   */
  private static final long FIXED_TIMESTAMP = 1_700_000_000_000L;

  private static MessageEnvelope base() {
    final MessageEnvelope envelope = new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 42);
    envelope.setTimestamp(FIXED_TIMESTAMP);
    return envelope;
  }

  @Test
  public void anEnvelopeEqualsAnIdenticalOne() {
    assertEquals(base(), base());
    assertEquals("equal objects must share a hash code", base().hashCode(), base().hashCode());
  }

  @Test
  public void aDifferentCiphertextTypeIsNotEqual() {
    final MessageEnvelope other = new MessageEnvelope(new byte[] {1, 2, 3}, 4, "peer-uuid", 42);
    other.setTimestamp(FIXED_TIMESTAMP);
    assertNotEquals(base(), other);
  }

  @Test
  public void aDifferentDeviceIdIsNotEqual() {
    final MessageEnvelope other = new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 43);
    other.setTimestamp(FIXED_TIMESTAMP);
    assertNotEquals(base(), other);
  }

  @Test
  public void aDifferentAddressNameIsNotEqual() {
    final MessageEnvelope other = new MessageEnvelope(new byte[] {1, 2, 3}, 3, "other-uuid", 42);
    other.setTimestamp(FIXED_TIMESTAMP);
    assertNotEquals(base(), other);
  }

  /** Array contents, not identity — this is the one an ordinary {@code equals} gets wrong. */
  @Test
  public void aDifferentCiphertextIsNotEqual() {
    for (final byte[] body : new byte[][] {{1, 2, 4}, {1, 2}}) {
      final MessageEnvelope other = new MessageEnvelope(body, 3, "peer-uuid", 42);
      other.setTimestamp(FIXED_TIMESTAMP);
      assertNotEquals(base(), other);
    }
    final MessageEnvelope sameContents =
        new MessageEnvelope(new byte[] {1, 2, 3}, 3, "peer-uuid", 42);
    sameContents.setTimestamp(FIXED_TIMESTAMP);
    assertEquals("equal contents in a different array must still be equal", base(), sameContents);
  }

  @Test
  public void aDifferentTimestampIsNotEqual() {
    final MessageEnvelope a = base();
    final MessageEnvelope b = base();
    b.setTimestamp(FIXED_TIMESTAMP + 1000);
    assertNotEquals("the timestamp is read on the receiving side, so it must be compared", a, b);
  }

  @Test
  public void anEnvelopeIsNotEqualToNullOrAnotherType() {
    assertNotEquals(base(), null);
    assertNotEquals(base(), "not an envelope");
  }

  /** A bundle-only envelope and a message-only one must never compare equal. */
  @Test
  public void aBundleEnvelopeIsNotEqualToAMessageEnvelope() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final MessageEnvelope bundle = SignalProtocolMain.getPreKeyResponseMessage();

    assertNotEquals(base(), bundle);
    assertNotEquals(bundle, base());
  }

  /**
   * Two envelopes differing in <em>only</em> the bundle must not compare equal.
   *
   * <p>Comparing a bundle envelope against a message one does not test this: they also differ in
   * the ciphertext, so dropping the bundle comparison entirely still leaves them unequal. That is
   * why this conjunct survived while the other four died — the test had to differ in exactly one
   * field to reach it, which is the whole point of the class.
   */
  @Test
  public void twoEnvelopesDifferingOnlyInTheirBundleAreNotEqual() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final MessageEnvelope first = SignalProtocolMain.getPreKeyResponseMessage();
    SignalProtocolMain.initialize(null);
    final MessageEnvelope second = SignalProtocolMain.getPreKeyResponseMessage();

    // Force every other field to agree, so the bundle is the only difference left.
    final MessageEnvelope a =
        new MessageEnvelope(first.getPreKeyResponse(), "same-uuid", 7);
    final MessageEnvelope b =
        new MessageEnvelope(second.getPreKeyResponse(), "same-uuid", 7);
    b.setTimestamp(a.getTimestamp());

    assertNotEquals("the bundle must participate in equals, or a round-trip test that swaps one "
        + "bundle for another still passes", a, b);
  }
}
