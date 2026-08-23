package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * The case the rest of the trust model does not cover: <b>the pin itself is the attacker's.</b>
 *
 * <p>Everything else here assumes the pinned key is genuine and treats a newly offered key as
 * hostile. That is right whenever the pin was established honestly — but the pin is set by
 * trust-on-first-use, through the same messenger the threat model says can forge anything. A
 * messenger that swaps the very first invite, keeping the peer's real address name and device id
 * (both are plaintext envelope fields it can read off the genuine bundle), gets its own key pinned
 * with no warning of any kind.
 *
 * <p>In that mirror case every other control does the wrong thing. Dismissing throws away the
 * peer's real key. Deleting the contact keeps the impostor's. The badge ends up green over the
 * wrong identity, and the genuine peer is unreachable at their real address for the life of the
 * install — the only recovery being to clear app data, destroying the user's own identity and every
 * other contact.
 *
 * <p>So a user who does exactly the right thing — compares safety numbers, finds a mismatch — needs
 * an action, and {@code rejectContactKey} is it. These tests exist because the design reasoned
 * carefully about a substituted key arriving <em>after</em> a good pin and not at all about a bad
 * pin arriving first.
 */
public class WrongPinRecoveryTest {

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

  /**
   * The messenger swaps the first invite, keeping the peer's real address. This is the state every
   * test below starts from — and note it produces no warning at all, because there is no earlier
   * key for TOFU to notice a change from.
   */
  private void messengerPinsItsOwnKeyAtFirstContact() throws Exception {
    activate(attacker);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("a swapped first invite is accepted - nothing can detect it",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(forged), peerAddress));
    assertFalse("and it raises no identity-change warning",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals(attacker.getIdentityKeyPair().getPublicKey(), pinned());
  }

  @Test
  public void aSwappedFirstInviteIsUndetectableButRecoverable() throws Exception {
    final Contact contact = storedContact();
    messengerPinsItsOwnKeyAtFirstContact();

    // The contact must be verified FIRST, or "the badge did not survive" is trivially true and the
    // assertion proves nothing - a mutant that skips clearing it would pass.
    assertTrue(SignalProtocolMain.verifyContact(contact));
    assertTrue(contact.isVerified());
    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(contact));

    // The user compares numbers by voice. They do not match, so they reject.
    assertTrue(SignalProtocolMain.rejectContactKey(contact));

    assertNull("the impostor's key must be forgotten", pinned());
    assertFalse("and the badge must not survive it", contact.isVerified());
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(contact));
    assertFalse("the stored row must be cleared too, not just the passed object",
        victim.getContactList().get(0).isVerified());
  }

  /** And afterwards the genuine peer can finally be pinned. This is the whole point. */
  @Test
  public void afterRejectingTheGenuineKeyCanBePinned() throws Exception {
    final Contact contact = storedContact();
    messengerPinsItsOwnKeyAtFirstContact();
    SignalProtocolMain.rejectContactKey(contact);

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("the real peer must be reachable once the wrong pin is gone",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuine), peerAddress));

    assertEquals(realPeer.getIdentityKeyPair().getPublicKey(), pinned());
    assertTrue(SignalProtocolMain.verifyContact(victim.getContactList().get(0)));
    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
  }

  /**
   * Without rejection the genuine peer is permanently locked out, which is what makes this a
   * missing control rather than a convenience. Documents the behaviour the reject path exists to
   * escape.
   */
  @Test
  public void withoutRejectingTheGenuinePeerStaysLockedOut() throws Exception {
    storedContact();
    messengerPinsItsOwnKeyAtFirstContact();

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertFalse("the real key is refused against the impostor's pin",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(genuine), peerAddress));

    // Deleting does not help: the pin deliberately outlives the contact.
    SignalProtocolMain.removeContactFromContactListAndProtocol(
        new Contact("Real", "Peer", peerAddress.getName(), peerAddress.getDeviceId(), false));
    assertEquals("deletion keeps the impostor's key, which is why it is not the exit",
        attacker.getIdentityKeyPair().getPublicKey(), pinned());
  }

  /**
   * Confirming instead of rejecting is the destructive mistake, and it must stay confined to the
   * user's explicit choice: it discards the genuine key and greens the badge over the impostor.
   * Recorded so the asymmetry is visible — confirm and reject are not interchangeable.
   */
  @Test
  public void confirmingAgainstAWrongPinKeepsTheImpostor() throws Exception {
    final Contact contact = storedContact();
    messengerPinsItsOwnKeyAtFirstContact();

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(genuine), peerAddress);
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    SignalProtocolMain.verifyContact(contact);

    assertEquals("confirming keeps whatever is pinned - here, the attacker's",
        attacker.getIdentityKeyPair().getPublicKey(), pinned());
    // Which is exactly why the verify screen must also offer "does not match".
    assertTrue(SignalProtocolMain.rejectContactKey(contact));
    assertNull(pinned());
  }

  @Test
  public void rejectingIsScopedAndSafeOnEmptyInput() throws Exception {
    final SignalProtocolAddress other = ProtocolAddresses.of("other-uuid", 9);
    victim.getSignalProtocolStore().getIdentityKeyStore()
        .saveIdentity(other, realPeer.getIdentityKeyPair().getPublicKey());

    final Contact contact = storedContact();
    messengerPinsItsOwnKeyAtFirstContact();
    SignalProtocolMain.rejectContactKey(contact);

    assertNotNull("rejecting one contact must not forget another's key",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(other));
    assertFalse(SignalProtocolMain.rejectContactKey(null));
    assertFalse("nothing pinned means nothing to forget",
        SignalProtocolMain.rejectContactKey(
            new Contact("No", "Body", "unknown-uuid", 5, false)));
  }

  /** Rejecting drops the session too, or encryption keeps running on the impostor's ratchet. */
  @Test
  public void rejectingDropsTheSessionWithTheKey() throws Exception {
    final Contact contact = storedContact();
    messengerPinsItsOwnKeyAtFirstContact();
    assertTrue(victim.getSignalProtocolStore().containsSession(peerAddress));

    SignalProtocolMain.rejectContactKey(contact);

    assertFalse("a session under a rejected key must not survive",
        victim.getSignalProtocolStore().containsSession(peerAddress));
  }

  // ------------------------------------------------------- S7: fallback branch

  /**
   * The fingerprint coupling guard, on the branch the other guards miss.
   *
   * <p>{@code createFingerprint} prefers the session key and falls back to the pin. Both existing
   * guard tests establish a session in setup, so they only ever exercise the session branch — a
   * refactor that made the <em>fallback</em> consult {@code getPendingIdentity} would survive both
   * and turn confirm into a one-tap adopt. That state is reachable in production: reject or delete
   * drops the session while a pin remains.
   */
  @Test
  public void theFingerprintFallbackAlsoShowsThePinnedKeyNotThePendingOne() throws Exception {
    final Contact contact = storedContact();

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(genuine), peerAddress);

    // Drop the session, keep the pin: this is the fallback branch.
    victim.getSignalProtocolStore().getSessionStore().deleteSession(peerAddress);
    assertFalse(victim.getSignalProtocolStore().containsSession(peerAddress));
    final String beforeAttack =
        SignalProtocolMain.getFingerprint(contact).getDisplayableFingerprint().getDisplayText();

    activate(attacker);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    SignalProtocolMain.importOutOfBandKeyBundle(forged, peerAddress);
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    victim.getSignalProtocolStore().getSessionStore().deleteSession(peerAddress);

    final String duringAttack =
        SignalProtocolMain.getFingerprint(contact).getDisplayableFingerprint().getDisplayText();

    assertEquals("the pin-only fallback must show the pinned key, or confirming adopts the "
        + "offered one", beforeAttack, duringAttack);
  }
}
