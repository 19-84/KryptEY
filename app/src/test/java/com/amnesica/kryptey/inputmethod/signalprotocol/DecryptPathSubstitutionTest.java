package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;

import java.util.ArrayList;

/**
 * Substitution arriving on the <em>decrypt</em> path — the route an attacker would actually pick.
 *
 * <p>{@code IdentitySubstitutionTest} drives everything through {@code importOutOfBandKeyBundle},
 * which has no production callers. That is the same shape of gap those tests were written to close:
 * exercising a method the app never runs. A {@code PreKeySignalMessage} carries its own identity
 * key and needs no attached bundle, so an attacker who simply omits the bundle takes a completely
 * different code path — one where the substitution used to be refused but never recorded, leaving
 * the user with no warning and generic "delete and re-invite" advice.
 */
public class DecryptPathSubstitutionTest {

  private Account victim;
  private Account realPeer;
  private Account attacker;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(realPeer.getSignalProtocolAddress().getName(),
        realPeer.getDeviceId());

    // The victim pins the genuine peer.
    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), peerAddress));
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private SignalProtocolAddress victimAddress() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  /**
   * The attacker builds a session to the victim under their own identity, then sends a
   * PreKeySignalMessage that claims to come from the peer's address. No bundle is attached.
   */
  private MessageEnvelope forgedMessageFromAttacker() throws Exception {
    activate(victim);
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(attacker);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress()));
    final MessageEnvelope forged =
        SignalProtocolMain.encryptMessage("trust me", victimAddress());
    assertNotNull(forged);

    // Relabel it as the peer's, which is all a hostile messenger has to do.
    return new MessageEnvelope(forged.getCiphertextMessage(), forged.getCiphertextType(),
        peerAddress.getName(), peerAddress.getDeviceId());
  }

  @Test
  public void aForgedPreKeyMessageIsRefusedAndRecorded() throws Exception {
    final MessageEnvelope forged = forgedMessageFromAttacker();

    activate(victim);
    assertFalse("no change should be pending before the attack",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertThrows("a substituted identity must not decrypt", UntrustedIdentityException.class,
        () -> SignalProtocolMain.decryptMessage(forged, peerAddress));

    // The refusal alone is not enough: without a record the UI cannot tell this from an ordinary
    // decryption failure, and shows advice that used to walk the user onto the attacker's key.
    assertTrue("a substitution on the decrypt path must be recorded",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertNotNull("the offered key should be available for display",
        SignalProtocolMain.getPendingIdentity(peerAddress));
  }

  @Test
  public void theForgedAttemptDoesNotMoveThePin() throws Exception {
    final MessageEnvelope forged = forgedMessageFromAttacker();

    activate(victim);
    final var pinnedBefore =
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);

    assertThrows(UntrustedIdentityException.class,
        () -> SignalProtocolMain.decryptMessage(forged, peerAddress));

    org.junit.Assert.assertEquals("the pinned key must be untouched", pinnedBefore,
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  /** A previously compared safety number must not survive somebody presenting a different key. */
  @Test
  public void aForgedPreKeyMessageRevokesAnExistingVerification() throws Exception {
    final MessageEnvelope forged = forgedMessageFromAttacker();

    activate(victim);
    final Contact contact = new Contact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);
    SignalProtocolMain.verifyContact(contact);
    assertTrue(contact.isVerified());

    assertThrows(UntrustedIdentityException.class,
        () -> SignalProtocolMain.decryptMessage(forged, peerAddress));

    assertFalse("the verified badge must not outlive the key it was granted for",
        victim.getContactList().get(0).isVerified());
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
  }

  // ------------------------------------------------- bundle-only envelopes

  /**
   * A re-invite carries a bundle and no ciphertext. It used to fall through to
   * {@code UnknownMessageException} and the UI showed nothing at all, so a substituted re-invite
   * was a silent no-op.
   */
  @Test
  public void aBundleOnlyEnvelopeIsProcessedRatherThanDiscarded() throws Exception {
    activate(realPeer);
    final MessageEnvelope reInvite = SignalProtocolMain.getPreKeyResponseMessage();
    assertNotNull(reInvite.getPreKeyResponse());
    assertNull("a re-invite carries no ciphertext", reInvite.getCiphertextMessage());

    activate(victim);
    // Must not throw, and must return no plaintext - there is none to return.
    assertNull(SignalProtocolMain.decryptMessage(reInvite, peerAddress));
    assertTrue("the session should have been refreshed from the bundle",
        victim.getSignalProtocolStore().containsSession(peerAddress));
  }

  /** And a substituted bundle-only re-invite must be recorded, not silently dropped. */
  @Test
  public void aSubstitutedBundleOnlyReInviteIsRecorded() throws Exception {
    activate(attacker);
    final MessageEnvelope attackerBundle = SignalProtocolMain.getPreKeyResponseMessage();
    // Relabelled, not edited: the attacker's own bundle and its own signature, presented at the
    // peer's address. The issuing signature deliberately does not cover the address, which is what
    // keeps this a substitution the trust layer warns about rather than a parse failure.
    final MessageEnvelope relabelled = BundleSigning.asEditedInTransit(attackerBundle,
        new MessageEnvelope(attackerBundle.getPreKeyResponse(),
            peerAddress.getName(), peerAddress.getDeviceId()));

    activate(victim);
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    SignalProtocolMain.decryptMessage(relabelled, peerAddress);

    assertTrue("a substituted re-invite must raise the alarm rather than vanish",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
  }
}
