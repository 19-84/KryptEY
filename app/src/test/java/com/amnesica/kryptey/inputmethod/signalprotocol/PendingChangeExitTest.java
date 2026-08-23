package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The pending-identity state must have an exit, and that exit must not open a substitution window.
 *
 * <p>Entry into the state is controlled by an <em>attacker</em>: one forged bundle to an address
 * the messenger sees in every envelope, and the contact's verified badge drops. For a while nothing
 * could clear that — {@code acceptIdentityChange} had no UI, {@code removeIdentity} had no caller,
 * and deletion deliberately did not help. Messaging kept working on the genuine pinned key, so
 * nothing looked broken; the badge was simply gone. A permanent, remotely-triggerable DoS on the one
 * indicator the trust model rests on teaches the user to disregard it before any real substitution.
 *
 * <p>Deletion was then tried as the exit and was worse. The app's "delete and ask for a new invite"
 * advice is its standard response to <em>any</em> decryption failure, and the messenger can induce
 * one at will by replaying a message or flipping a bit — neither of which records a pending change,
 * so no identity-change warning fires. Clearing the pin on delete therefore handed the attacker a
 * trust-on-first-use window on a branch that had no guard on it at all.
 *
 * <p>The exit used now is <b>dismiss</b>: throw away the offered key, keep the pin. It opens no
 * window, because nothing about the stored key changes.
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

  private IdentityKey pinned() {
    return victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);
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
  public void dismissingClearsTheChangeAndKeepsThePin() throws Exception {
    storedContact();
    final IdentityKey genuine = pinned();
    attackerForcesAPendingChange();

    assertTrue(SignalProtocolMain.dismissIdentityChange(peerAddress));

    assertFalse("the state an attacker forced must be escapable",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals("dismissing must not disturb the pinned key - that is the whole point",
        genuine, pinned());
  }

  /**
   * Dismissing must discard the offered key, never install it. If it installed it, the attacker's
   * forced state would become their delivery mechanism.
   */
  @Test
  public void dismissingDiscardsTheOfferedKeyRatherThanAdoptingIt() throws Exception {
    storedContact();
    attackerForcesAPendingChange();

    SignalProtocolMain.dismissIdentityChange(peerAddress);

    org.junit.Assert.assertNotEquals("dismissing must never install the offered key",
        attacker.getIdentityKeyPair().getPublicKey(), pinned());
    assertEquals(realPeer.getIdentityKeyPair().getPublicKey(), pinned());
  }

  /** And the attacker cannot simply try again after a dismissal. */
  @Test
  public void aSecondSubstitutionAfterDismissalIsStillRefused() throws Exception {
    storedContact();
    final IdentityKey genuine = pinned();
    attackerForcesAPendingChange();
    SignalProtocolMain.dismissIdentityChange(peerAddress);

    activate(attacker);
    final String again = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertFalse("dismissing must not weaken the pin",
        SignalProtocolMain.importOutOfBandKeyBundle(again, peerAddress));
    assertEquals(genuine, pinned());
  }

  @Test
  public void dismissingIsScopedToOneAddress() throws Exception {
    final SignalProtocolAddress other = ProtocolAddresses.of("other-peer-uuid", 7);
    victim.getSignalProtocolStore().getIdentityKeyStore()
        .saveIdentity(other, realPeer.getIdentityKeyPair().getPublicKey());
    victim.getSignalProtocolStore().getIdentityKeyStore()
        .recordIdentityChange(other, attacker.getIdentityKeyPair().getPublicKey());

    storedContact();
    attackerForcesAPendingChange();
    SignalProtocolMain.dismissIdentityChange(peerAddress);

    assertTrue("dismissing one contact cleared an unrelated contact's warning",
        SignalProtocolMain.hasUnacceptedIdentityChange(other));
  }

  @Test
  public void dismissingNothingReportsNothing() {
    assertFalse("nothing was pending, so nothing was dismissed",
        SignalProtocolMain.dismissIdentityChange(peerAddress));
    assertFalse(SignalProtocolMain.dismissIdentityChange(null));
  }

  // ------------------------------------------------- the exit through the UI

  /**
   * The route a user actually takes: they open the verify screen, compare the number shown, find it
   * matches, and press verify. The number shown is the PINNED key's, so that press means "the key I
   * already have is the right one" — which dismisses the offered key.
   */
  @Test
  public void verifyingAfterAnAttackRestoresTheBadgeWithoutMovingThePin() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    final IdentityKey genuine = pinned();

    attackerForcesAPendingChange();
    assertFalse("the badge should drop when a key is offered",
        victim.getContactList().get(0).isVerified());

    assertTrue(SignalProtocolMain.verifyContact(victim.getContactList().get(0)));

    assertTrue("the user must be able to get the badge back",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals("and the pin must not have moved", genuine, pinned());
  }

  /**
   * The safety of the step above rests entirely on the displayed number being the pinned key's. If
   * that ever changes, pressing verify becomes a one-tap adopt of the attacker's key.
   */
  @Test
  public void verifyingWouldBeUnsafeIfTheOfferedKeyWereEverDisplayed() throws Exception {
    final Contact contact = storedContact();
    final String beforeAttack = SignalProtocolMain.getFingerprint(contact)
        .getDisplayableFingerprint().getDisplayText();

    attackerForcesAPendingChange();

    final String duringAttack = SignalProtocolMain.getFingerprint(contact)
        .getDisplayableFingerprint().getDisplayText();
    assertEquals("verifyContactInContactList dismisses the pending change on the strength of this "
            + "number being the PINNED key's. If a refactor ever makes createFingerprint show the "
            + "offered key while a change is pending, verify becomes a one-tap adopt of an "
            + "attacker's key and must be changed back to a refusal first.",
        beforeAttack, duringAttack);
  }

  /** Messaging on the genuine key keeps working throughout - which is why the DoS was invisible. */
  @Test
  public void theGenuinePeerCanStillBeReachedWhileTheChangeIsPending() throws Exception {
    storedContact();
    attackerForcesAPendingChange();

    activate(victim);
    assertNotNull("the pinned genuine key must still be usable",
        SignalProtocolMain.encryptMessage("still works", peerAddress));
  }

  /** Verify reports success on the ordinary path, or its return value means nothing. */
  @Test
  public void verifyingReportsSuccessWhenNothingIsPending() throws Exception {
    assertTrue(SignalProtocolMain.verifyContact(storedContact()));
  }

  /** false must mean only "nothing loaded" - the UI renders it as exactly that. */
  @Test
  public void verifyingReportsFalseOnlyWhenNothingIsLoaded() throws Exception {
    assertFalse(SignalProtocolMain.verifyContact(null));
    SignalProtocolMain.getInstance().setAccount(null);
    assertFalse(SignalProtocolMain.verifyContact(
        new Contact("A", "B", "some-uuid", 42, false)));
  }
}
