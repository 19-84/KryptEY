package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The pending-identity state must have an exit, and that exit must be discard rather than adopt.
 *
 * <p>Entry into the state is controlled by an <em>attacker</em>: anyone who knows a contact's
 * address — and the messenger carrying every envelope does — can send one forged bundle, and the
 * contact's verified badge drops. Until this test existed nothing could ever clear that:
 * {@code acceptIdentityChange} had no UI, {@code removeIdentity} had no caller at all, and contact
 * removal deliberately left the pin in place. Messaging kept working on the genuine pinned key, so
 * nothing looked wrong — the badge was simply gone forever, for every contact the attacker chose,
 * which teaches the user it means nothing long before any real substitution.
 *
 * <p>The three prior rounds of review all asked whether a substituted key could get itself trusted.
 * None asked whether a legitimate user could get back out. These tests ask that.
 */
public class PendingChangeExitTest {

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

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), peerAddress));
  }

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private Contact storedContact() {
    final Contact contact = new Contact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);
    return contact;
  }

  /** One forged bundle, from anyone who knows the address. This is the whole attack. */
  private void attackerForcesAPendingChange() throws Exception {
    activate(attacker);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertFalse("the substitution must be refused",
        SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
  }

  // ---------------------------------------------------------------- the exit

  @Test
  public void deletingTheContactClearsTheChangeTheAttackerForced() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    assertTrue(contact.isVerified());

    attackerForcesAPendingChange();
    assertFalse("the badge should be gone", victim.getContactList().get(0).isVerified());

    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));

    assertFalse("deleting the contact must clear the pending change - it is the only way out",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertNull("and the stale pin must go with it",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  /**
   * The point of the exit: the user can actually recover. Without deletion clearing the pin, a
   * re-add is still refused and still unverifiable, forever.
   */
  @Test
  public void afterDeletingTheUserCanReEstablishAndVerifyAgain() throws Exception {
    storedContact();
    attackerForcesAPendingChange();
    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));

    // The peer sends a fresh invite. (In practice from a new address, but the same one is the
    // harder case and must also work.)
    activate(realPeer);
    final String freshInvite = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("re-establishing must succeed once the stale pin is gone",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(freshInvite), peerAddress));

    final Contact readded = storedContact();
    assertTrue("and the user must be able to verify again",
        SignalProtocolMain.verifyContact(readded));
    assertTrue(readded.isVerified());
    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(readded));
  }

  /** Re-adding lands in ordinary unverified first-contact state, not silently trusted. */
  @Test
  public void reAddingDoesNotInheritTrust() throws Exception {
    storedContact();
    SignalProtocolMain.verifyContact(victim.getContactList().get(0));
    attackerForcesAPendingChange();
    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));

    activate(realPeer);
    final String freshInvite = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(freshInvite), peerAddress);

    final Contact readded = storedContact();
    assertFalse("a re-added contact must start unverified",
        SignalProtocolMain.isContactKeyTrustworthy(readded));
  }

  /** Deleting one contact must not clear another's warning. */
  @Test
  public void deletingOneContactLeavesAnotherContactsWarningStanding() throws Exception {
    final SignalProtocolAddress otherAddress = ProtocolAddresses.of("other-peer-uuid", 7);
    activate(attacker);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(attackerBundle), otherAddress);
    victim.getSignalProtocolStore().getIdentityKeyStore()
        .recordIdentityChange(otherAddress, realPeer.getIdentityKeyPair().getPublicKey());

    storedContact();
    attackerForcesAPendingChange();
    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));

    assertTrue("deleting one contact cleared an unrelated contact's warning",
        SignalProtocolMain.hasUnacceptedIdentityChange(otherAddress));
  }

  // -------------------------------------------------------- discard, not adopt

  /**
   * The exit is discard. There must be no route that leaves the offered key installed — that would
   * turn the attacker's forced state into a delivery mechanism for their own key.
   */
  @Test
  public void deletingDiscardsTheOfferedKeyRatherThanAdoptingIt() throws Exception {
    storedContact();
    attackerForcesAPendingChange();
    final var attackerKey = attacker.getIdentityKeyPair().getPublicKey();

    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));

    assertNull("deletion must leave NO key pinned, least of all the attacker's",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse("the attacker's key must not have been adopted",
        attackerKey.equals(
            victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress)));
  }

  /**
   * And the one-tap adopt stays unwired. {@code acceptIdentityChange} still exists in the store,
   * but a legitimately reinstalled peer arrives under a fresh address (see
   * {@link AddressingPremiseTest}), so nothing ever needs to adopt a key at an old one. If a caller
   * for it appears in production code, that premise has to be re-argued first.
   */
  @Test
  public void verifyingStillRefusesWhileAChangeIsPending() throws Exception {
    final Contact contact = storedContact();
    attackerForcesAPendingChange();

    assertFalse("verify must refuse - and must SAY it refused, which is what the return value is "
        + "for; the screen used to advance and the badge just never appeared",
        SignalProtocolMain.verifyContact(contact));
    assertFalse(contact.isVerified());
  }

  /** The happy path still reports success, or the refusal signal is meaningless. */
  @Test
  public void verifyingReportsSuccessWhenNothingIsPending() throws Exception {
    final Contact contact = storedContact();
    assertTrue("a normal verification must report that it took",
        SignalProtocolMain.verifyContact(contact));
  }

  /** Messaging on the genuine key keeps working throughout - which is why the DoS was invisible. */
  @Test
  public void theGenuinePeerCanStillBeReachedWhileTheChangeIsPending() throws Exception {
    storedContact();
    attackerForcesAPendingChange();

    activate(victim);
    final MessageEnvelope toReal = SignalProtocolMain.encryptMessage("still works", peerAddress);
    assertNotNull("the pinned genuine key must still be usable", toReal);
  }
}
