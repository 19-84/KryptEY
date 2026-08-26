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
    activate(alice);
    final MessageEnvelope carolBundle = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull(carolBundle);
    final int offeredToCarol = carolBundle.getPreKeyResponse().getDevices().get(0)
        .getPreKey().getKeyId();
    final int declaredByBob = new org.signal.libsignal.protocol.message.PreKeySignalMessage(
        EnvelopeCodec.fromWire(second).getCiphertextMessage()).getPreKeyId().orElse(-1);

    // Asserted, not assumed. The scenario only reproduces the bug because Carol's invite is
    // allocated the very id Bob's second message declares - true today because the regenerated id
    // sorts below the untouched one and the allocator takes the lowest unused. Change either and
    // the collision stops happening, and this test would pass while testing nothing.
    assertEquals("this scenario needs Carol's invite to offer the id Bob's message declares, or "
        + "there is no collision to detect", declaredByBob, offeredToCarol);

    activate(carol);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(carolBundle)), addressOf(alice)));
    final String carolOpening = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("hello from carol", addressOf(alice)));

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

  /**
   * An id we never held is not created, which is the half that carries the security weight.
   *
   * <p>A reviewer pointed out that the three tests above all leave the "did we hold this id before"
   * half of the gate unkilled: in each of them the declared id is one the store really does hold,
   * so the "absent afterwards" half alone gives the right answer. Dropping the first half survives
   * the whole suite — and it is the half that closes the attack.
   *
   * <p>On the short-circuit path libsignal reads none of the message's outer fields except the
   * identity and base keys, so the declared pre-key id is not covered by the inner MAC and the
   * envelope has no integrity protection of its own. A relay can therefore rewrite it. Without the
   * first half of the gate that turns an aimed delete into an aimed <em>write</em>: the victim mints
   * a fresh unused record at an id of the attacker's choosing, which is worse, because the allocator
   * hands out the lowest unused id — so the attacker picks which key the victim's next invite
   * offers, and the pruning that bounds the used-key set stops being reached at all.
   *
   * <p>The rewrite is done on the serialized message rather than through a constructor because
   * libsignal exposes none: {@code PreKeySignalMessage} can only be built from bytes. Field 1 of
   * that protobuf is {@code pre_key_id}, a varint, so a small id is one byte after the {@code 0x08}
   * tag and can be replaced in place without disturbing anything else.
   */
  @Test
  public void anidWeNeverHeldIsNotCreatedByAmessageThatDeclaresIt() throws Exception {
    activate(alice);
    final String bundle = EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), addressOf(alice)));
    final MessageEnvelope first =
        SignalProtocolMain.encryptMessage("one", addressOf(alice));
    final MessageEnvelope second =
        SignalProtocolMain.encryptMessage("two", addressOf(alice));

    activate(alice);
    SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(EnvelopeCodec.toWire(first)),
        addressOf(bob));

    final int declared = new org.signal.libsignal.protocol.message.PreKeySignalMessage(
        second.getCiphertextMessage()).getPreKeyId().orElse(-1);
    assertTrue("the second message must declare a pre-key id, or there is nothing to rewrite",
        declared >= 0);

    final int strangerId = 99;
    assertTrue("precondition: the store must NOT hold the id we are about to declare",
        !alice.getSignalProtocolStore().containsPreKey(strangerId));

    final byte[] rewritten = withDeclaredPreKeyId(second.getCiphertextMessage(), declared,
        strangerId);
    final MessageEnvelope forged = new MessageEnvelope(rewritten, second.getCiphertextType(),
        second.getSignalProtocolAddressName(), second.getDeviceId());

    final java.util.Map<Integer, String> before = snapshotPreKeys(alice);
    activate(alice);
    try {
      SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(EnvelopeCodec.toWire(forged)),
          addressOf(bob));
    } catch (final Exception ignored) {
      // Whether the forged message decrypts is not the point - the store must be untouched either
      // way, and on the short-circuit path it does decrypt.
    }

    assertTrue("a pre-key was created at an id this device never held, chosen by whoever relayed "
            + "the message. That hands an attacker the id the next invite will offer.",
        !alice.getSignalProtocolStore().containsPreKey(strangerId));
    assertEquals("and nothing else in the store may have moved", before, snapshotPreKeys(alice));
  }

  /** Replaces the {@code pre_key_id} varint (protobuf field 1) in a serialized PreKeySignalMessage. */
  private static byte[] withDeclaredPreKeyId(final byte[] serialized, final int from, final int to) {
    assertTrue("this rewrite only handles single-byte varints", from < 128 && to < 128);
    final byte[] copy = serialized.clone();
    for (int i = 0; i + 1 < copy.length; i++) {
      if (copy[i] == 0x08 && copy[i + 1] == (byte) from) {
        copy[i + 1] = (byte) to;
        return copy;
      }
    }
    throw new AssertionError("could not find the pre_key_id field to rewrite");
  }

  /** Every stored pre-key id mapped to its serialized record, as a comparable snapshot. */
  private static java.util.Map<Integer, String> snapshotPreKeys(final Account account)
      throws Exception {
    // Two things worth knowing about this helper.
    //
    // It scans a fixed range, so it asserts that the range actually covers the store - otherwise
    // two truncated views could compare equal and a test would pass by not looking. And loadPreKey
    // marks a record USED, so taking a snapshot flips the whole store: nothing here reads that flag
    // (a serialized PreKeyRecord does not carry it), but a future assertion about allocation after
    // a snapshot would be reading a store this helper had already changed.
    final int scanned = 200;
    assertTrue("the snapshot range no longer covers the store, so it would compare truncated views",
        account.getSignalProtocolStore().getPreKeyStore().getSize() <= scanned);
    final java.util.Map<Integer, String> snapshot = new java.util.TreeMap<>();
    for (int id = 0; id < scanned; id++) {
      if (!account.getSignalProtocolStore().containsPreKey(id)) continue;
      snapshot.put(id, com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64.encodeBytes(
          account.getSignalProtocolStore().loadPreKey(id).serialize()));
    }
    return snapshot;
  }
}
