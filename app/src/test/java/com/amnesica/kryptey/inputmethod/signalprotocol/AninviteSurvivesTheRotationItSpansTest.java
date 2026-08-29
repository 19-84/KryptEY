package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * What the signed pre-key archive window is FOR, asserted on an invite rather than on the store.
 *
 * <p>{@code PreKeyRotationTest} pins which records survive a rotation and which are retired once
 * the window passes. That is the mechanism. The reason it exists is a user-visible one: somebody was
 * handed an invite, and by the time they get round to answering it the sender's keys have rotated.
 * The archive window is what keeps that answer decryptable, and nothing crossed from "the record is
 * still in the store" to "the message still opens".
 *
 * <p>A review round named this as the test it could not construct. It is worth having in both
 * directions: an invite spanning a rotation must still work, and an invite older than the archive
 * window must not — because "keep everything" would pass the first assertion and quietly give up
 * forward secrecy, which is what the window bounds.
 */
public class AninviteSurvivesTheRotationItSpansTest {

  private Account sender;
  private Account peer;
  private SignalProtocolAddress senderAddress;

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    sender = SignalProtocolMain.getInstance().getAccount();
    sender.setMessageLogLoader(ArrayList::new);
    senderAddress = ProtocolAddresses.of(sender.getSignalProtocolAddress().getName(),
        sender.getDeviceId());

    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Rotates the sender's signed and Kyber pre-keys, the way the monthly refresh does. */
  private void theSendersKeysRotate() {
    activate(sender);
    sender.getMetadataStore().setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    assertTrue("fixture: the refresh must actually rotate",
        KeyUtil.refreshSignedPreKeyIfNecessary(sender.getSignalProtocolStore(),
            sender.getMetadataStore()));
  }

  /** Winds the archive window into the past and runs the maintenance pass that retires. */
  private void theArchiveWindowExpires() {
    activate(sender);
    sender.getMetadataStore().setOldSignedPreKeyDeletionTime(System.currentTimeMillis() - 1);
    sender.getMetadataStore().setNextSignedPreKeyRefreshTime(System.currentTimeMillis() + 60_000);
    KeyUtil.refreshSignedPreKeyIfNecessary(sender.getSignalProtocolStore(),
        sender.getMetadataStore());
  }

  /**
   * An invite handed out before a rotation still opens the answer that comes after it.
   *
   * <p>This is the whole point of keeping the superseded key for two days: the answer was composed
   * against the bundle the peer was given, and the rotation happened in between.
   */
  @Test
  public void ananswerComposedAgainstThePreviousBundleStillOpensAfterArotation() throws Exception {
    activate(sender);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    assertNotNull("fixture: the invite must be produced", invite);

    // The peer answers it - composing against the keys they were handed.
    activate(peer);
    assertTrue("fixture: the peer must be able to use the invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(invite), senderAddress));
    final MessageEnvelope answer =
        SignalProtocolMain.encryptMessage("I got your invite", senderAddress);
    assertNotNull("fixture: the peer must be able to compose an answer", answer);

    // Meanwhile the sender's keys rotate, before that answer is opened.
    theSendersKeysRotate();

    final String opened = SignalProtocolMain.decryptMessage(answer,
        ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId()));

    assertEquals("an answer composed against the bundle the peer was handed must still open after "
            + "the sender rotates - that is what the two-day archive window is for, and without it "
            + "everyone invited shortly before a rotation is simply unreachable",
        "I got your invite", opened);
  }

  /**
   * And once the window has passed, it does not — which is the half that keeps the window a window.
   *
   * <p>Without this the test above would pass just as well against a build that never retired
   * anything, and never retiring is giving up the forward secrecy the rotation exists to provide.
   */
  @Test
  public void andAnanswerOlderThanTheArchiveWindowDoesNot() throws Exception {
    activate(sender);
    final String invite = SignalProtocolMain.exportOwnKeyBundle();
    assertNotNull(invite);

    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(invite), senderAddress));
    final MessageEnvelope answer =
        SignalProtocolMain.encryptMessage("answered far too late", senderAddress);
    assertNotNull(answer);

    theSendersKeysRotate();
    theArchiveWindowExpires();

    // Measured rather than assumed: the answer is refused by libsignal, which cannot find the
    // retired record, and it surfaces as InvalidKeyIdException - one of the CHECKED types
    // decryptMessage declares and every caller on the clipboard path already handles. That the
    // refusal is checked matters as much as that it refuses: an unchecked throw out of this path
    // is the crash class this project names as its worst.
    try {
      final String opened = SignalProtocolMain.decryptMessage(answer,
          ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId()));
      assertNull("past the archive window the superseded key is gone, so this answer must no "
              + "longer open. Keeping it would pass the test above and quietly retire the forward "
              + "secrecy the rotation exists to provide", opened);
    } catch (final org.signal.libsignal.protocol.InvalidKeyIdException refused) {
      assertTrue("the refusal must name the missing record: " + refused.getMessage(),
          refused.getMessage() != null && refused.getMessage().contains("signed prekey"));
    }
  }
}
