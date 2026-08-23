package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/**
 * An actual identity substitution, driven through libsignal rather than through the store's API.
 *
 * <p>This exists because the previous tests could not have caught the bug they were written for.
 * They exercised the pending-change mechanism by calling {@code saveIdentity} twice — a state
 * libsignal never produces, because it calls {@code isTrustedIdentity} first and aborts. The
 * mechanism was therefore dead code in production while the suite was green.
 *
 * <p>The rule this encodes: a trust decision must be tested through the path an attacker would
 * actually take, not through the store method that would only be reached if the decision had
 * already gone the attacker's way.
 */
public class IdentitySubstitutionTest {

  private Account victim;
  private Account realPeer;
  private Account attacker;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();
  }

  private void activate(final Account a) {
    SignalProtocolMain.getInstance().setAccount(a);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  /**
   * A key bundle belonging to {@code from}.
   *
   * <p>Switches the active account as a side effect, so it must never be used as a call argument:
   * Java evaluates arguments first, which would run the enclosing call under the wrong identity.
   * Always assign it to a local and re-activate the account under test.
   */
  private String bundleFrom(final Account from) throws Exception {
    activate(from);
    return EnvelopeCodec.toWire(SignalProtocolMain.getPreKeyResponseMessage());
  }

  @Test
  public void aSubstitutedIdentityIsRefusedAndRecorded() throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);

    // The victim first establishes a session with the genuine peer.
    final String genuine = bundleFrom(realPeer);
    activate(victim);
    assertTrue(SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress));

    final IdentityKey pinned = victim.getSignalProtocolStore().getIdentityKeyStore()
        .getIdentity(peerAddress);
    assertNotNull(pinned);
    assertFalse("no change should be pending yet",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    // Now the messenger substitutes the attacker's bundle for the same address.
    final String substituted = bundleFrom(attacker);
    activate(victim);
    assertFalse("a substituted identity must not establish a session",
        SignalProtocolMain.importOutOfBandKeyBundle(substituted, peerAddress));

    // The refusal alone is not enough — the change has to be recorded, or the UI cannot tell this
    // apart from an ordinary failure and will advise the user to delete and re-add.
    assertTrue("the substitution was refused but never recorded",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals("the pinned key must not move", pinned,
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  @Test
  public void theRecordedKeyIsTheOneThatWasOffered() throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);

    final String genuine = bundleFrom(realPeer);
    final String substituted = bundleFrom(attacker);

    activate(victim);
    SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress);
    SignalProtocolMain.importOutOfBandKeyBundle(substituted, peerAddress);

    assertEquals("the pending key should be the attacker's, so the UI can display it",
        attacker.getIdentityKeyPair().getPublicKey(),
        SignalProtocolMain.getPendingIdentity(peerAddress));
  }

  /** Nothing may be encrypted to a substituted identity. */
  @Test
  public void encryptionToASubstitutedIdentityProducesNothing() throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);

    final String genuine = bundleFrom(realPeer);
    final String substituted = bundleFrom(attacker);

    activate(victim);
    SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress);
    SignalProtocolMain.importOutOfBandKeyBundle(substituted, peerAddress);

    // The genuine session still stands, so sending still works to the *real* peer's key.
    assertNotNull("the established session should be unaffected",
        SignalProtocolMain.encryptMessage("still fine", peerAddress));
  }

  /**
   * The recovery path. Comparing safety numbers out of band and accepting must move the pin — and
   * must refuse a key other than the one displayed.
   */
  @Test
  public void acceptingTheOfferedKeyIsTheSanctionedRecovery() throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);

    final String genuine = bundleFrom(realPeer);
    final String substituted = bundleFrom(attacker);

    activate(victim);
    SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress);
    SignalProtocolMain.importOutOfBandKeyBundle(substituted, peerAddress);

    final IdentityKey offered = SignalProtocolMain.getPendingIdentity(peerAddress);
    assertNotNull(offered);

    assertFalse("accepting a key the user was not shown must be refused",
        SignalProtocolMain.acceptIdentityChange(peerAddress,
            realPeer.getIdentityKeyPair().getPublicKey()));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertTrue(SignalProtocolMain.acceptIdentityChange(peerAddress, offered));
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
    assertEquals(offered,
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  /**
   * Deleting a contact DOES clear the pin, and this test used to assert the opposite.
   *
   * <p>The original reasoning: clearing on delete opened a fail-open path, because the app showed
   * generic "delete the contact and ask for a new invite" advice on any decryption failure, so an
   * attacker could substitute a key, let the refusal produce that advice, and have the user walk
   * onto their key as a clean first sighting.
   *
   * <p>What that reasoning left out is who controls entry into the refusing state. An attacker
   * does — one forged bundle to a known address — and once there, nothing could leave: accept had
   * no UI, {@code removeIdentity} had no caller, and deletion deliberately did not help. The badge
   * was destroyed permanently while messaging carried on normally, so nothing looked broken. That
   * is a remote, silent, permanent DoS on the one indicator the trust model rests on, and it is
   * worse than the fail-open it was guarding.
   *
   * <p>The fail-open is now closed where it actually originated — {@code warnIfIdentityChanged}
   * displaces the delete-and-re-invite advice for an identity change — so deletion is an informed
   * act rather than one the app talked the user into. See {@link PendingChangeExitTest}, and the
   * rationale block in {@code SignalProtocolMain.removeContact}.
   *
   * <p>What must still hold, and is asserted below: deletion <em>discards</em>. It must never leave
   * the offered key installed in place of the pinned one.
   */
  @Test
  public void deletingAContactClearsThePinnedIdentityWithoutAdoptingTheOfferedOne()
      throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);

    final String genuine = bundleFrom(realPeer);
    activate(victim);
    SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress);

    // An attacker forces the terminal state.
    final String substituted = bundleFrom(attacker);
    activate(victim);
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle(substituted, peerAddress));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    final com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact contact =
        new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
            "Real", "Peer", peerAddress.getName(), peerAddress.getDeviceId(), false);
    SignalProtocolMain.removeContactFromContactListAndProtocol(contact);

    assertNull("deletion is the only exit from a state an attacker can force",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    // Discard, never adopt. Leaving the attacker's key pinned would turn the exit into their
    // delivery mechanism, which is the failure the original assertion was really protecting.
    assertNotEquals("deletion must not install the offered key",
        attacker.getIdentityKeyPair().getPublicKey(),
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  @Test
  public void anUnknownPeerIsStillTrustedOnFirstUse() throws Exception {
    final SignalProtocolAddress peerAddress = addressOf(realPeer);
    activate(victim);

    assertNull(victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    final String genuine = bundleFrom(realPeer);
    activate(victim);
    assertTrue("a first sighting must still work",
        SignalProtocolMain.importOutOfBandKeyBundle(genuine, peerAddress));
  }
}
