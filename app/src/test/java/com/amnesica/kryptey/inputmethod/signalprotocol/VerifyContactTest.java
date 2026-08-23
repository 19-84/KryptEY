package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
    SignalProtocolMain.verifyContact(null); // must not throw
    SignalProtocolMain.getInstance().setAccount(null);
    SignalProtocolMain.verifyContact(
        new Contact("A", "B", "some-uuid", 42, false)); // must not throw
  }

  /**
   * Verification must NOT adopt a pending identity change, and must refuse outright while one is
   * pending.
   *
   * <p>An earlier version of this test asserted the opposite. The argument then was that a
   * legitimately reinstalled contact otherwise has no route forward — but that case cannot arise:
   * {@code initializeProtocol} mints a fresh {@code UUID.randomUUID()} per install, so a
   * reinstalled peer appears as a NEW address and never collides with an existing pin. Every
   * pending change is therefore a substitution or a store rollback, which makes a one-tap accept on
   * the verify screen an attack surface rather than a recovery path.
   */
  @Test
  public void verifyingIsRefusedWhileASubstitutedIdentityIsPending() throws Exception {
    final Contact contact = storedContact();

    SignalProtocolMain.initialize(null);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(me);
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    final IdentityKey pinnedBefore =
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);

    SignalProtocolMain.verifyContact(contact);

    assertTrue("verifying must not silently adopt a substituted key",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals("the pinned key must be untouched", pinnedBefore,
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse("and the contact must not be marked verified", contact.isVerified());
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
}
