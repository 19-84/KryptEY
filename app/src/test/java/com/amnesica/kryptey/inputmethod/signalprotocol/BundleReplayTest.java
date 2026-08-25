package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * What replaying a captured invite actually does, measured rather than described.
 *
 * <p>REVIVAL.md carries this as a known-deferred defect: "No freshness check, so replaying a
 * captured envelope forces a session rebuild. Halved (the bundle was being processed twice per
 * message) but not eliminated." That is prose. A deferred defect is a decision to live with
 * something, and living with something you have only described is how the size of it drifts.
 *
 * <p>So this pins the behaviour as it stands. It is not a fix and does not assert that a replay is
 * refused — it asserts what a replay costs, so that if the cost ever changes, in either direction,
 * a test says so. Two of these would fail on a fix, which is the point: the fix has to come with a
 * decision about the re-add flow, and these are the assertions that decision has to confront.
 */
public class BundleReplayTest {

  private Account victim;
  private Account peer;
  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress victimAddress;
  private String peerInvite;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());

    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerInvite = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(victim);
  }

  private byte[] sessionBytes() {
    return victim.getSignalProtocolStore().getSessionStore()
        .loadSession(peerAddress).serialize();
  }

  /** The messenger keeps a copy of every invite it relays; replaying one costs it nothing. */
  private boolean replayTheInvite() throws Exception {
    return SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerInvite), peerAddress);
  }

  /**
   * A replayed invite is accepted, and it replaces the session.
   *
   * <p>This is the defect, stated as a fact rather than a worry. Nothing about the second delivery
   * is distinguishable from the first, so the store rebuilds - and a rebuilt session is a reset
   * ratchet: the chain keys the two sides had agreed are gone.
   */
  @Test
  public void areplayedInviteIsAcceptedAndReplacesTheSession() throws Exception {
    assertTrue("precondition: the first invite must build a session", replayTheInvite());
    final byte[] afterFirst = sessionBytes();
    assertNotNull(afterFirst);

    assertTrue("a replayed invite is accepted - there is no freshness check", replayTheInvite());
    final byte[] afterReplay = sessionBytes();

    assertFalse("the replay replaced the session state rather than being ignored",
        java.util.Arrays.equals(afterFirst, afterReplay));
  }

  /**
   * The pinned identity does NOT move, which is the reason this is deferred rather than urgent.
   *
   * <p>A replay resets the ratchet; it does not hand the attacker a key. Everything the trust model
   * rests on survives, so the cost is availability and forward secrecy of the current chain, not
   * confidentiality against the messenger.
   */
  @Test
  public void areplayDoesNotMoveThePinnedIdentity() throws Exception {
    assertTrue(replayTheInvite());
    final var pinnedBefore =
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);
    assertNotNull("precondition: the first invite must pin a key", pinnedBefore);

    assertTrue(replayTheInvite());

    assertEquals("a replay must not move the pin - if this ever fails, the defect stops being a "
            + "ratchet reset and becomes a substitution", pinnedBefore,
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  /**
   * And messages already in the log survive it, so a replay is not a way to erase history.
   */
  @Test
  public void areplayDoesNotDisturbTheChatLog() throws Exception {
    assertTrue(replayTheInvite());

    final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact contact =
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
            "Peer", "Account", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final java.util.ArrayList<com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact>
        contacts = new java.util.ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    assertNotNull("precondition: the fixture must be able to send",
        SignalProtocolMain.encryptMessage("something worth keeping", peerAddress));
    final int before = victim.getUnencryptedMessages().size();
    assertTrue("precondition: the send must have been logged", before > 0);

    assertTrue(replayTheInvite());

    assertEquals("a replayed invite must not take the conversation with it", before,
        victim.getUnencryptedMessages().size());
  }

  /**
   * How many replays it takes to lose a held-back message.
   *
   * <p>The first attempt at this asserted the message was lost after one replay. It was wrong, for
   * two reasons worth writing down. A {@code PreKeySignalMessage} carries what is needed to derive
   * its own session, so replaying the SENDER's invite does not touch it - the direction being
   * rebuilt is the other one. And once both sides are in the ratchet, libsignal ARCHIVES the session
   * it displaces rather than discarding it, so an ordinary message still decrypts from an archived
   * state.
   *
   * <p>The second attempt looped until decryption succeeded and returned - which passes on the first
   * iteration and measures nothing. Decryption consumes the message, so a trial cannot be retried;
   * each K needs its own fixture.
   *
   * <p>So the cost is archive slots, which are finite. This runs one fixture per K, and pins the
   * number of replays a messenger needs before a message it is holding becomes undecryptable. If
   * that number ever drops, the defect got cheaper to exploit.
   */
  @Test
  public void enoughReplaysLoseAheldBackMessageAndTheThresholdIsPinned() throws Exception {
    Integer threshold = null;
    for (int replays = 1; replays <= 64 && threshold == null; replays++) {
      if (!heldMessageSurvives(replays)) threshold = replays;
    }

    assertNotNull("a held-back message survived 64 replays. Either libsignal's archive is larger "
        + "than that, or the replay stopped displacing the session - both change what this "
        + "deferred defect costs, so the number belongs in REVIVAL.md either way", threshold);
    assertTrue("one replay must not be enough to lose a message, or the defect is far cheaper "
        + "than recorded (measured: " + threshold + ")", threshold > 1);
    assertEquals("measured at 41 replays, which is libsignal's archived-session limit plus the "
        + "live one. Pinned so a change in either direction is noticed: fewer means the defect "
        + "got cheaper, more means the archive grew and the recorded cost is stale",
        41, threshold.intValue());
  }

  /**
   * Builds a fresh pair, gets both sides into the ratchet, holds one message back, replays the
   * invite {@code replays} times, and reports whether the held message still decrypts.
   */
  private boolean heldMessageSurvives(final int replays) throws Exception {
    setUp();

    assertTrue(replayTheInvite());
    final MessageEnvelope toPeer = SignalProtocolMain.encryptMessage("hello", peerAddress);
    assertNotNull(toPeer);

    SignalProtocolMain.getInstance().setAccount(peer);
    assertEquals("hello", SignalProtocolMain.decryptMessage(toPeer, victimAddress));
    final MessageEnvelope heldBack =
        SignalProtocolMain.encryptMessage("held back by the messenger", victimAddress);
    assertNotNull(heldBack);
    assertEquals("precondition: the held message must be an ordinary one - a pre-key message "
        + "carries its own session and would survive any number of replays",
        2, heldBack.getCiphertextType());

    SignalProtocolMain.getInstance().setAccount(victim);
    for (int i = 0; i < replays; i++) {
      assertTrue(replayTheInvite());
    }

    try {
      return "held back by the messenger".equals(
          SignalProtocolMain.decryptMessage(heldBack, peerAddress));
    } catch (Exception lost) {
      return false;
    }
  }
}
