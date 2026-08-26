package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * Decrypting a peer's second opening message must not destroy the pre-key an invite is offering.
 *
 * <p>The decrypt path regenerates a one-time pre-key at the id the incoming message declares. That
 * is right when libsignal actually consumed one, and it runs on <em>every</em> pre-key message —
 * including the ones where nothing was consumed. libsignal short-circuits when the session record
 * already holds a state for the message's base key, and every message a peer sends before it hears
 * back carries the same base key and the same declared pre-key id. For a copy-paste keyboard,
 * several messages before a reply is the ordinary case.
 *
 * <p>The regenerated record is fresh material marked <em>unused</em>, and the allocator hands out
 * the lowest unused id — so the id a peer's next message will overwrite is exactly the id the next
 * invite offers. That is not a race; it is the default ordering.
 *
 * <p>This is the failure {@code KeyUtil.getUnusedOneTimePreKeyId}'s own javadoc says was fixed:
 * "their opening message referenced pre-key 1 but pre-key 1 was now different key material, so it
 * could never be decrypted and there was no way to recover". The allocator closed the front door.
 * The decrypt path had reopened it.
 *
 * <p>There is an attacker version too, worth recording even though this test does not stage it: on
 * the short-circuit path libsignal reads none of the outer message's fields except the identity and
 * base keys, so the declared pre-key id is not covered by the inner MAC. A hostile relay can
 * therefore choose which of the victim's pre-keys gets overwritten, aim it at the id their
 * outstanding invite offers, and silently prevent every new contact from ever establishing a
 * session — while the existing conversation continues to look perfectly healthy.
 */
@RunWith(RobolectricTestRunner.class)
public class SecondPreKeyMessageDoesNotClobberAnInviteTest {

  private Account alice;
  private Account bob;
  private Account carol;

  @Before
  public void createThreeParties() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    carol = SignalProtocolMain.getInstance().getAccount();
    assertNotNull(alice);
    assertNotNull(bob);
    assertNotNull(carol);
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  /** Alice publishes an invite; {@code peer} accepts it and returns its opening ciphertext. */
  private String inviteAndOpen(final Account peer, final String text) throws Exception {
    activate(alice);
    final String bundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());

    activate(peer);
    assertTrue("the peer must be able to accept the invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bundle), addressOf(alice)));
    final MessageEnvelope opening = SignalProtocolMain.encryptMessage(text, addressOf(alice));
    assertNotNull(opening);
    return EnvelopeCodec.toWire(opening);
  }

  /**
   * Bob's second message must not cost Carol her session.
   *
   * <p>The ordering is the ordinary one: Bob writes twice before Alice replies, and Alice invites
   * someone else in between.
   */
  @Test
  public void asecondOpeningMessageFromOnePeerDoesNotStopAnotherPeerConnecting() throws Exception {
    // Bob accepts an invite and writes twice before hearing anything back. Both are opening
    // messages carrying the same base key and the same declared pre-key id.
    activate(alice);
    final String bobBundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), addressOf(alice)));
    final String first = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("are you there", addressOf(alice)));
    final String second = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("still there?", addressOf(alice)));

    activate(alice);
    assertEquals("are you there",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(first), addressOf(bob)));

    // Alice now invites Carol. The invite offers whatever id the allocator considers unused.
    final String carolOpening = inviteAndOpen(carol, "hello from carol");

    // And only then reads Bob's second message.
    activate(alice);
    assertEquals("still there?",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(second), addressOf(bob)));

    // Carol's opening message must still work. If Bob's second message re-minted the id Carol's
    // invite offered, the agreement no longer matches and Carol can never open a session - with no
    // recovery path, because the material she holds is simply gone.
    activate(alice);
    assertEquals("reading a second opening message from one peer destroyed the pre-key another "
            + "peer's invite was offering, so that peer can never establish a session and there is "
            + "no way to recover it", "hello from carol",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(carolOpening), addressOf(carol)));
  }

  /**
   * And the narrower property underneath it: a message that consumed nothing changes nothing.
   *
   * <p>Stated separately from the scenario above because it is the actual rule. The scenario is one
   * way to notice it being broken; this is the thing to keep true.
   */
  @Test
  public void amessageThatConsumesNoPreKeyLeavesTheStoreAlone() throws Exception {
    activate(alice);
    final String bundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), addressOf(alice)));
    final String first = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("one", addressOf(alice)));
    final String second = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("two", addressOf(alice)));

    activate(alice);
    SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(first), addressOf(bob));

    // Snapshot every pre-key the store holds, after the first message has done its work.
    final java.util.Map<Integer, String> before = snapshotPreKeys(alice);
    assertTrue("the store must hold pre-keys, or this test is about nothing", !before.isEmpty());

    activate(alice);
    SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(second), addressOf(bob));

    assertEquals("a second opening message consumed no pre-key, so it must not have changed any "
        + "key material in the store", before, snapshotPreKeys(alice));
  }

  /**
   * And the other half: a message that DID consume a pre-key still replaces it.
   *
   * <p>Without this, "never regenerate at all" satisfies both tests above while quietly draining
   * the pre-key supply - every accepted invite would permanently spend an id and none would come
   * back, so a user eventually runs out and can issue no more invites. The rule is "replace exactly
   * what was consumed", and that has two sides.
   */
  @Test
  public void amessageThatDoesConsumeApreKeyStillReplacesIt() throws Exception {
    activate(alice);
    final String bundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    final java.util.Map<Integer, String> beforeAnyone = snapshotPreKeys(alice);

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), addressOf(alice)));
    final String opening = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("hello", addressOf(alice)));

    activate(alice);
    SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(opening), addressOf(bob));
    final java.util.Map<Integer, String> afterwards = snapshotPreKeys(alice);

    assertEquals("the store must still hold the same number of pre-keys - the consumed one is "
            + "replaced, not simply spent, or the supply drains and the user eventually cannot "
            + "issue invites at all", beforeAnyone.size(), afterwards.size());
    assertEquals("and the same ids", beforeAnyone.keySet(), afterwards.keySet());
    assertTrue("but the consumed id must carry DIFFERENT material now, or the same key would be "
        + "offered to the next contact", !beforeAnyone.equals(afterwards));
  }

  /** Every stored pre-key id mapped to its serialized record, as a comparable snapshot. */
  private static java.util.Map<Integer, String> snapshotPreKeys(final Account account)
      throws Exception {
    final java.util.Map<Integer, String> snapshot = new java.util.TreeMap<>();
    for (int id = 0; id < 200; id++) {
      if (!account.getSignalProtocolStore().containsPreKey(id)) continue;
      snapshot.put(id, com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64.encodeBytes(
          account.getSignalProtocolStore().loadPreKey(id).serialize()));
    }
    return snapshot;
  }
}
