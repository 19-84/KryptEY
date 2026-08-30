package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The signed pre-key rotates when you SEND, not when thirty days pass.
 *
 * <p>The in-app help says, without condition: <em>"Every 30 days your signed prekey will be
 * renewed."</em> {@code refreshSignedPreKeyIfNecessary} has exactly two callers and both are
 * outbound — inside {@code encrypt}, and inside {@code getPreKeyBundle}, which is reached only from
 * the Invite button and {@code exportOwnKeyBundle}. Nothing on the raise path, the decrypt path or
 * {@code processPreKeyResponse} calls it, and there is no timer anywhere in the app.
 *
 * <p>So a user who reads messages and never sends one never rotates, however long they wait. The
 * sentence is false for them, and it is false in the direction that matters: they believe a key
 * they are still using has been replaced.
 *
 * <p>There is a second cost, bounded but real. {@code deleteOlderSignedPreKeysIfNecessary} is called
 * only from inside {@code refreshSignedPreKeyIfNecessary}, and it is the only caller of
 * {@code removeOldSignedPreKeys} and {@code removeOldKyberPreKeys}. So the previous generation's
 * private halves stay on disk until the next send that falls more than the archive window after a
 * rotation — for a receive-only user, indefinitely.
 *
 * <p>Pinned rather than fixed, and the reason is in the code this measures. Calling the refresh
 * from the decrypt or raise path would hand the messenger the rotation trigger: every relayed
 * message becomes a possible key rotation plus a store write, and {@code SignalProtocolMain}
 * already records why that is dangerous — a rotation mints private material that must reach disk
 * before anything derived from it leaves the method, and on the contacts-unreadable arm writes are
 * refused, so the rotation would exist in memory only. The help sentence is what was wrong.
 */
@RunWith(RobolectricTestRunner.class)
public class ArotationOnlyHappensWhenYouSendTest {

  private Account peer;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress victimAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    victimAddress = ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(),
        victim.getDeviceId());
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** A message from the peer to the victim, built on the peer's side. */
  private MessageEnvelope aMessageForTheVictim() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("hello", victimAddress);
    assertNotNull(sent);
    activate(victim);
    return sent;
  }

  @Test
  public void receivingAmessageWithTherotationOverdueDoesNotRotate() throws Exception {
    final MessageEnvelope incoming = aMessageForTheVictim();

    // Long overdue: thirty days have passed and then some.
    victim.getMetadataStore().setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    final int signedBefore = victim.getMetadataStore().getActiveSignedPreKeyId();
    final int kyberBefore = victim.getMetadataStore().getActiveKyberPreKeyId();

    assertNotNull("fixture: the message must decrypt, or nothing on the receive path ran",
        SignalProtocolMain.decryptMessage(incoming, peerAddress));

    assertEquals("the help says the signed pre-key is renewed every 30 days. Receiving does not "
            + "renew it: refreshSignedPreKeyIfNecessary is called from encrypt and from bundle "
            + "export, and from nowhere else, so a user who only reads messages keeps the same key "
            + "for as long as they never send one",
        signedBefore, victim.getMetadataStore().getActiveSignedPreKeyId());
    assertEquals("and the Kyber half does not rotate either, for the same reason",
        kyberBefore, victim.getMetadataStore().getActiveKyberPreKeyId());
  }

  /**
   * And sending DOES rotate, which is what makes the assertion above a statement about the trigger
   * rather than about the rotation being broken.
   */
  @Test
  public void sendingAmessageWithTherotationOverdueDoesRotate() throws Exception {
    aMessageForTheVictim();
    activate(victim);

    victim.getMetadataStore().setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    final int signedBefore = victim.getMetadataStore().getActiveSignedPreKeyId();

    assertNotNull("fixture: the send must succeed",
        SignalProtocolMain.encryptMessage("and hello back", peerAddress));

    assertNotEquals("sending with the rotation overdue must renew the signed pre-key - this is the "
            + "control that makes the receive-only case above a finding about the trigger rather "
            + "than about rotation being broken outright",
        signedBefore, victim.getMetadataStore().getActiveSignedPreKeyId());
  }
}
