package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
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
 * A relay can put a genuine peer's key at an address of its own choosing, and every check says fine.
 *
 * <p>The bundle signature covers {@code canonicalBundleBytes}, which is {@code writeBundle}: the
 * identity key, the inner device id, the registration id and the three pre-keys. The envelope's
 * sender NAME and its device id are written outside that, so re-delivering a genuine, correctly
 * signed invite under a different address produces bytes that verify perfectly — nothing was
 * forged, and the issuer's own signature still covers everything it ever covered.
 *
 * <p>What lands is a second contact holding the peer's real identity key. And the safety number is
 * a pure function of the two identity keys — the address was deliberately removed from it — so the
 * second row shows the <em>same digits</em> as the first. A user who does exactly what every
 * user-facing document tells them to do, and reads the number aloud, is told by their real peer
 * that it matches. Because it does.
 *
 * <p>This is not the same as the impostor case the duplicate-name warning was written for. There an
 * attacker supplies their own key and the numbers differ. Here the key is the peer's, the numbers
 * agree, and the voice comparison — the one step the README, the help and the store listing all
 * name as the thing that tells a chat partner apart from the messenger — endorses the extra row.
 *
 * <p>Pinned as measured rather than fixed. Refusing an invite whose identity key is already pinned
 * at another address hands the messenger an eviction: one relayed copy at a bogus address would
 * burn a real contact's key and make every genuine invite from that peer refusable, with the app's
 * own advice looping forever. This branch has already paid for one refusal that locked the keyboard.
 */
@RunWith(RobolectricTestRunner.class)
public class ArelayCanMintExtraRowsForAgenuinePeerTest {

  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String genuineInvite;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    genuineInvite = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String fingerprintFor(final SignalProtocolAddress address, final String first) {
    final Contact contact = new Contact(first, "Peer", address.getName(), address.getDeviceId(),
        false);
    // The digits the user actually reads aloud, not the object.
    final org.signal.libsignal.protocol.fingerprint.Fingerprint fingerprint =
        SignalProtocolMain.getFingerprint(contact);
    return fingerprint == null ? null : fingerprint.getDisplayableFingerprint().getDisplayText();
  }

  @Test
  public void thesameSignedInviteRelabelledPinsAsecondRowWithTheSameSafetyNumber() throws Exception {
    // The honest arrival.
    assertTrue("fixture: the genuine invite must be accepted",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuineInvite), peerAddress));
    final String genuineNumber = fingerprintFor(peerAddress, "Real");
    assertNotNull("fixture: the real contact must have a safety number", genuineNumber);

    // The relay re-delivers the SAME bundle under an address it chose. Nothing is forged: the
    // signature is the issuer's own, over a bundle nobody edited.
    final MessageEnvelope original = EnvelopeCodec.fromWire(genuineInvite);
    final SignalProtocolAddress relabelled =
        ProtocolAddresses.of("an-address-the-relay-picked", 1);
    final MessageEnvelope relayed = BundleSigning.asEditedInTransit(original,
        new MessageEnvelope(original.getPreKeyResponse(), relabelled.getName(),
            relabelled.getDeviceId()));

    assertTrue("the relabelled invite is accepted: the signature covers the bundle, and the sender "
            + "name and device id are written outside it, so nothing the relay touched is signed",
        SignalProtocolMain.processPreKeyResponseMessage(relayed, relabelled));

    assertNotEquals("fixture: the two rows must be at different addresses, or there is no second "
        + "row to be wrong about", peerAddress.getName(), relabelled.getName());

    final String relayedNumber = fingerprintFor(relabelled, "Also");
    assertNotNull("the relayed row must have a safety number to compare", relayedNumber);

    assertEquals("the relayed row shows the SAME safety number as the genuine one, because the "
            + "number is a function of the two identity keys and the address was deliberately "
            + "removed from it. So the user reads the digits aloud, their real peer confirms them, "
            + "and the comparison the whole design rests on endorses a row the messenger created",
        genuineNumber, relayedNumber);

    // And the one check that CAN see it. The number cannot distinguish the rows, and neither can
    // the address check, which asks only whether this address is taken. What is unspoofable is the
    // key: an honest peer cannot have one identity key at two addresses, because a reinstall mints
    // a new key along with the new address.
    assertEquals("nothing in the app noticed that the peer's identity key is now pinned at two "
            + "addresses. That is the one fact the relay cannot forge, because the key is inside "
            + "the signature and the address is not",
        java.util.Collections.singletonList(peerAddress),
        SignalProtocolMain.addressesAlreadyPinningTheSameKey(relabelled));
  }

  /**
   * And an honest reinstall must not look like the attack, or the signal is worthless.
   *
   * <p>This is the control that decides whether the check above can ever be shown to a user.
   * {@code initializeProtocol} mints a fresh UUID, a fresh device id and a fresh identity key pair
   * together, so a peer who reinstalls arrives with a different key at a different address — two
   * rows, two numbers, and no shared key. If a reinstall did produce one key at two addresses, the
   * check would fire on the commonest honest event there is.
   */
  @Test
  public void anhonestReinstallDoesNotPinOneKeyAtTwoAddresses() throws Exception {
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuineInvite), peerAddress));

    // The peer reinstalls: a new account, and therefore a new identity and a new address.
    final Account victimAccount = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account reinstalled = SignalProtocolMain.getInstance().getAccount();
    reinstalled.setMessageLogLoader(ArrayList::new);
    final SignalProtocolAddress newAddress = ProtocolAddresses.of(
        reinstalled.getSignalProtocolAddress().getName(), reinstalled.getDeviceId());
    final String afterReinstall = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(victimAccount);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(afterReinstall), newAddress));

    assertTrue("a reinstall mints a new identity key with its new address, so it must not look "
            + "like one key at two addresses - if it did, this check would fire on the commonest "
            + "honest event there is and would have to be discarded",
        SignalProtocolMain.addressesAlreadyPinningTheSameKey(newAddress).isEmpty());
  }
}
