package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * A first message the relay corrupted must not make the genuine copy undecryptable.
 *
 * <p>This was reported as a live defect and it is not one, which is why it is pinned here rather
 * than written down as an argument. The reasoning behind the report is sound and worth keeping: the
 * Kyber replay guard records the sender's base key inside {@code markKyberPreKeyUsed}, libsignal
 * calls that during session derivation, and derivation happens before the inner message is
 * authenticated. If those three held together, one flipped byte would burn the base key while the
 * message that carried it failed — and since every message a peer sends before hearing back carries
 * the same base key, every genuine copy afterwards would be refused as a replay. A permanent,
 * deniable, zero-crypto denial of session establishment, repeatable before every delivery.
 *
 * <p>Measured against libsignal 0.86.5, on two different corruptions — a flipped byte in the body
 * and a flipped byte in the trailing MAC — the store is untouched by the failure: no base key
 * recorded, no one-time pre-key marked used, and the genuine copy opens normally afterwards. The
 * callbacks are not reached until the message verifies.
 *
 * <p>That is a property of the library version, not of this code, which is exactly why it is a test.
 * An upgrade that moved those callbacks earlier would reintroduce the attack silently, and nothing
 * else here would notice.
 */
public class AcorruptedFirstMessageDoesNotPoisonTheGenuineOneTest {

  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress victimAddress;
  private Account victim;
  private Account peer;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(peer);
    assertTrue("precondition: the peer must have a session to send the first message on",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(victimBundle), victimAddress));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** The peer's opening message, as the relay receives it. */
  private String openingMessage() {
    SignalProtocolMain.getInstance().setAccount(peer);
    final MessageEnvelope envelope =
        SignalProtocolMain.encryptMessage("the first thing they said", victimAddress);
    assertNotNull("fixture: the peer must produce an opening message", envelope);
    try {
      return EnvelopeCodec.toWire(envelope);
    } catch (final Exception e) {
      throw new AssertionError("fixture: the opening message must encode", e);
    }
  }

  private void deliverCorrupted(final String wire, final int at) throws Exception {
    final MessageEnvelope tampered = EnvelopeCodec.fromWire(wire);
    final byte[] bytes = tampered.getCiphertextMessage();
    bytes[at] ^= 0x01;
    tampered.setCiphertextMessage(bytes);

    SignalProtocolMain.getInstance().setAccount(victim);
    try {
      SignalProtocolMain.decryptMessage(tampered, peerAddress);
      fail("fixture: a corrupted message must not decrypt, or this test is measuring nothing");
    } catch (final Exception expected) {
      // The point is what it leaves behind.
    }
  }

  @Test
  public void abodyCorruptionDoesNotBurnTheBaseKey() throws Exception {
    final String wire = openingMessage();
    final byte[] original = EnvelopeCodec.fromWire(wire).getCiphertextMessage();

    deliverCorrupted(wire, original.length / 2);

    SignalProtocolMain.getInstance().setAccount(victim);
    assertEquals("the corrupted copy failed before anything was recorded, so the genuine one must "
            + "still open. If it does not, one flipped byte permanently denies this peer a session: "
            + "every message they send before hearing back carries the same base key",
        "the first thing they said",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), peerAddress));
  }

  @Test
  public void amacCorruptionDoesNotBurnTheBaseKeyEither() throws Exception {
    final String wire = openingMessage();
    final byte[] original = EnvelopeCodec.fromWire(wire).getCiphertextMessage();

    // The trailing bytes are the MAC: this corruption parses cleanly and fails authentication,
    // which is the case the report was really about.
    deliverCorrupted(wire, original.length - 1);

    SignalProtocolMain.getInstance().setAccount(victim);
    assertEquals("a message that parses and fails its MAC must leave the store as it found it",
        "the first thing they said",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), peerAddress));
  }

  /**
   * And the guard those two rely on is still live.
   *
   * <p>Without this the pair above would pass just as happily against a build where the replay
   * refusal had been deleted altogether - which is the shape of vacuity this project keeps
   * finding. A genuine message replayed must still be refused.
   */
  @Test
  public void areplayOfTheGenuineMessageIsStillRefused() throws Exception {
    final String wire = openingMessage();

    SignalProtocolMain.getInstance().setAccount(victim);
    assertEquals("precondition: the genuine message must open the first time",
        "the first thing they said",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), peerAddress));

    try {
      SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), peerAddress);
      fail("a replayed opening message must be refused; if it is not, the two tests above are "
          + "measuring a guard that is not there");
    } catch (final Exception expected) {
      // Refused, which is what makes the other two tests mean something.
    }
  }
}
