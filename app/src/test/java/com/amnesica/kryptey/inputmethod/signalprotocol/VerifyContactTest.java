package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * {@code verifyContact} — the only trust API the UI actually calls, and until now completely
 * untested.
 *
 * <p>Mutation testing deleted its entire body and the suite stayed green. That matters more than
 * the usual coverage gap: it is wired to the button a user presses after comparing safety numbers,
 * so if it silently stops recording the result, the app quietly forgets that a contact was ever
 * verified.
 */
public class VerifyContactTest {

  private Account me;
  private Account peer;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    me = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress);
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  private Contact storedContact() {
    final Contact contact = new Contact("Peer", "Account", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    me.setContactList(contacts);
    return contact;
  }

  @Test
  public void verifyingRecordsTheContactAsVerified() throws Exception {
    final Contact contact = storedContact();
    assertFalse(contact.isVerified());

    SignalProtocolMain.verifyContact(contact);

    assertTrue("verification was not recorded on the contact", contact.isVerified());
    assertTrue("the stored contact list was not updated",
        me.getContactList().get(0).isVerified());
  }

  /** Verification is what makes an in-band contact trustworthy; that link must hold. */
  @Test
  public void verifyingMakesAnInBandContactTrustworthy() throws Exception {
    final Contact contact = storedContact();
    assertFalse("an in-band contact starts untrustworthy",
        SignalProtocolMain.isContactKeyTrustworthy(contact));

    SignalProtocolMain.verifyContact(contact);

    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(contact));
  }

  @Test
  public void verifyingAnUnknownContactIsRejected() {
    me.setContactList(new ArrayList<>());
    assertThrows(UnknownContactException.class, () -> SignalProtocolMain.verifyContact(
        new Contact("Ghost", "Contact", "nobody-uuid", 42, false)));
  }

  @Test
  public void verifyingIsSafeWithNoContactAndNoAccount() throws Exception {
    // The return value is the contract, not just the absence of a throw. verifyContact's javadoc
    // says false means "this could not be recorded", and the strip renders that as a toast rather
    // than as a verified badge - so a mutant returning true here would put a badge over a contact
    // that does not exist, which is the one outcome the whole trust model has no recovery from.
    assertFalse("no contact means nothing could have been compared, so nothing may be recorded",
        SignalProtocolMain.verifyContact(null));
    SignalProtocolMain.getInstance().setAccount(null);
    assertFalse("and no account means the same",
        SignalProtocolMain.verifyContact(new Contact("A", "B", "some-uuid", 42, false)));
  }

  /**
   * Verification DISMISSES a pending identity change: it throws the offered key away and keeps the
   * pinned one. It must never adopt the offered key.
   *
   * <p>This assertion has been written three ways and the history is the argument.
   *
   * <p>First it asserted that verifying <em>adopts</em> the pending key, so a legitimately
   * reinstalled contact had a route forward. Wrong: {@code initializeProtocol} mints a fresh
   * {@code UUID.randomUUID()} per install, so a reinstalled peer arrives at a NEW address and never
   * collides with an existing pin (see {@link AddressingPremiseTest}). Every pending change is a
   * substitution or a store rollback, so a one-tap accept was an attack surface.
   *
   * <p>Then it asserted a flat <em>refusal</em>. That made the state terminal: an attacker forces it
   * with one forged bundle and nobody can leave, so the badge is destroyed permanently.
   *
   * <p>Now: dismiss. Safe for one specific reason — the number on the verify screen is computed from
   * the PINNED key, so a user pressing verify has compared the key already in use and found it
   * correct. Discarding what somebody else offered is the right response to that. See
   * {@code PendingChangeExitTest#verifyingWouldBeUnsafeIfTheOfferedKeyWereEverDisplayed}, which
   * guards the coupling.
   */
  @Test
  public void verifyingDismissesAPendingChangeWithoutAdoptingTheOfferedKey() throws Exception {
    final Contact contact = storedContact();

    SignalProtocolMain.initialize(null);
    final Account attacker = SignalProtocolMain.getInstance().getAccount();
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    final IdentityKey pinnedBefore =
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);

    assertTrue(SignalProtocolMain.verifyContact(contact));

    assertFalse("the pending change must be gone - it was the only thing blocking the badge",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals("verifying must not move the pin", pinnedBefore,
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertNotEquals("and must never install the offered key",
        attacker.getIdentityKeyPair().getPublicKey(),
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertTrue(contact.isVerified());
  }

  /**
   * A substituted identity must invalidate an existing verification.
   *
   * <p>{@code verified} was a sticky boolean nothing ever reset, and {@code isContactKeyTrustworthy}
   * short-circuits on it — so a contact verified against one key kept its green badge after
   * somebody presented another.
   */
  @Test
  public void aSubstitutedIdentityClearsAnExistingVerification() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    assertTrue(contact.isVerified());
    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(contact));

    SignalProtocolMain.initialize(null);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);

    assertFalse("a substituted key must revoke the verified badge",
        me.getContactList().get(0).isVerified());
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(me.getContactList().get(0)));
  }

  /**
   * With a change pending, the number shown must remain the PINNED key's — the key actually in use.
   * Showing the offered key's number would invite the user to compare, and thereby legitimise, the
   * substituted key.
   */
  @Test
  public void theDisplayedFingerprintTracksThePinnedKeyNotThePendingOne() throws Exception {
    storedContact();
    final String before = SignalProtocolMain.getFingerprint(storedContact())
        .getDisplayableFingerprint().getDisplayText();

    SignalProtocolMain.initialize(null);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);

    final String after = SignalProtocolMain.getFingerprint(storedContact())
        .getDisplayableFingerprint().getDisplayText();

    assertNotNull(after);
    assertEquals("a refused key must not change the number shown to the user", before, after);
  }

  /**
   * A contact with no pinned key cannot have been verified, because there is nothing to compare.
   *
   * <p>Nothing checked. Verifying set the badge and {@code isContactKeyTrustworthy} returned true -
   * over a key nobody had compared, because there was no key. When the peer's bundle later arrived
   * and trust-on-first-use pinned it, the contact was ALREADY green, so a hostile messenger
   * substituting that first bundle inherits a verified badge it never earned.
   *
   * <p>The only thing preventing it was one runtime {@code setEnabled(false)} in the verify screen,
   * in a different file, with no test: deleting those lines left the whole suite green, and the
   * layout declares no {@code android:enabled}, so the default is enabled. An invariant this class
   * depends on has to be enforced by this class.
   */
  @Test
  public void acontactWithNoPinnedKeyCannotBeVerified() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();

    final SignalProtocolAddress strangerAddress =
        ProtocolAddresses.of("never-seen-uuid", 11);
    final Contact stranger = new Contact("Never", "Seen", strangerAddress.getName(),
        strangerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(stranger);
    fresh.setContactList(contacts);

    assertNull("precondition: nothing may be pinned for this address",
        fresh.getSignalProtocolStore().getIdentityKeyStore().getIdentity(strangerAddress));

    assertFalse("verifying with no pinned key must be refused",
        SignalProtocolMain.verifyContact(stranger));
    assertFalse("and must not leave the badge set", stranger.isVerified());
    assertFalse("nor the stored copy", fresh.getContactList().get(0).isVerified());
    assertFalse("and the contact must not read as trustworthy",
        SignalProtocolMain.isContactKeyTrustworthy(stranger));
  }

  /**
   * A verified flag on a Contact the account does not hold must not outlive a pending change.
   *
   * <p>{@code isContactKeyTrustworthy} ends with {@code && !hasUnacceptedIdentityChange(...)}, and
   * deleting that clause left the whole suite green. The reason is the pattern that keeps recurring
   * here: every fixture reaches the check only after {@code clearVerificationFor} has already set
   * {@code verified = false}, so the second guard never decides anything.
   *
   * <p>The live path where it does decide is a {@code Contact} instance the account's list does not
   * contain - notably the copy {@code ListAdapterContacts.getItem} builds, which becomes the chosen
   * contact and which {@code clearVerificationFor}'s in-place mutation cannot reach. That object
   * still says verified, and the store is what has to overrule it.
   */
  @Test
  public void astaleVerifiedFlagMustNotOutliveApendingChange() throws Exception {
    final Contact contact = storedContact();
    SignalProtocolMain.verifyContact(contact);
    assertTrue(SignalProtocolMain.isContactKeyTrustworthy(contact));

    // A detached copy, as the list adapter builds - same address, still carrying the badge.
    final Contact detached = new Contact(contact.getFirstName(), contact.getLastName(),
        contact.getSignalProtocolAddressName(), contact.getDeviceId(), true);

    SignalProtocolMain.initialize(null);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertTrue("the detached copy still claims to be verified, which is the point",
        detached.isVerified());
    assertFalse("a stale verified flag must not survive a pending identity change - the store has "
            + "to overrule the object", SignalProtocolMain.isContactKeyTrustworthy(detached));
  }

  /**
   * The read side must check for a pin too, not only the write side.
   *
   * <p>{@code verifyContact} refuses to set the badge with nothing pinned.
   * {@code isContactKeyTrustworthy} did not ask, so a Contact object carrying
   * {@code verified = true} over an address holding no key read as trustworthy - which is the
   * defect the write-side guard was added for, surviving on the other side of it.
   *
   * <p>Not reachable from today's UI, because every badge renders from the account's live list. That
   * is a property of the current call sites, not of this method, and the whole point of the guard
   * is to stop depending on that.
   */
  @Test
  public void averifiedFlagOverNoPinnedKeyIsNotTrustworthy() {
    final Contact detached = new Contact("Ghost", "Contact", "never-pinned-uuid", 21, true);

    assertTrue("precondition: the object claims to be verified", detached.isVerified());
    assertNull("precondition: nothing may be pinned for this address",
        me.getSignalProtocolStore().getIdentityKeyStore()
            .getIdentity(ProtocolAddresses.of("never-pinned-uuid", 21)));

    assertFalse("a verified flag over an address with no key must not read as trustworthy",
        SignalProtocolMain.isContactKeyTrustworthy(detached));
  }
}
