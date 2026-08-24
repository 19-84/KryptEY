package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;


/**
 * Out-of-band key exchange, which is the only thing that closes the first-contact gap.
 *
 * <p>Trust-on-first-use cannot detect a hostile messenger substituting keys at first contact —
 * there is no earlier key to have changed, so the identity-change machinery has nothing to fire on.
 * The assurance has to come from the bundle never travelling through that messenger, and since the
 * two are indistinguishable afterwards from the stored key alone, provenance is recorded at import.
 */
public class OutOfBandExchangeTest {

  private Account alice;
  private Account bob;

  @Before
  public void createBothParties() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** Assign to a local before use: this switches the active account as a side effect. */
  private String bundleOf(final Account a) throws Exception {
    activate(a);
    return SignalProtocolMain.exportOwnKeyBundle();
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private static Contact contactFor(final Account a) {
    return new Contact("Peer", "Account", a.getSignalProtocolAddress().getName(),
        a.getDeviceId(), false);
  }

  // --------------------------------------------------------------- mechanism

  @Test
  public void aBundleCanBeExportedAsTransferableText() throws Exception {
    activate(alice);
    final String exported = SignalProtocolMain.exportOwnKeyBundle();

    assertNotNull(exported);
    assertTrue("export should be non-trivial", exported.length() > 100);
    assertNotNull("the export must parse back as a bundle",
        EnvelopeCodec.fromWire(exported).getPreKeyResponse());
  }

  /** The full out-of-band flow: Alice hands Bob her bundle by some trusted channel. */
  @Test
  public void aSessionCanBeBuiltEntirelyOutOfBand() throws Exception {
    activate(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(bob);
    assertTrue("out-of-band import failed",
        SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, addressOf(alice)));

    // And the resulting session actually works.
    final MessageEnvelope message =
        SignalProtocolMain.encryptMessage("hello out of band", addressOf(alice));
    assertNotNull(message);

    activate(alice);
    assertEquals("hello out of band", SignalProtocolMain.decryptMessage(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(message)), addressOf(bob)));
  }

  @Test
  public void importRejectsTextThatIsNotABundle() {
    activate(bob);
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle("not a bundle", addressOf(alice)));
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle(null, addressOf(alice)));
    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle("", addressOf(alice)));
  }

  /** A plain message is valid wire text but is not a key bundle; importing it must fail. */
  @Test
  public void importRejectsAMessageEnvelope() throws Exception {
    activate(alice);
    final String aliceBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(bob);
    SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, addressOf(alice));
    final String plainMessage =
        EnvelopeCodec.toWire(SignalProtocolMain.encryptMessage("hi", addressOf(alice)));

    assertFalse("a message envelope is not a key bundle",
        SignalProtocolMain.importOutOfBandKeyBundle(plainMessage, addressOf(alice)));
  }

  // -------------------------------------------------------------- provenance

  /**
   * Provenance is recorded — as information, not as trust.
   *
   * <p>An earlier version asserted that an out-of-band import alone made a contact trustworthy.
   * That was unsound: the exported bundle is byte-identical to the one the invite flow sends, so
   * the code can only observe that the import method was called, never how the bytes travelled. A
   * user who copies an invite out of the messenger and pastes it here produces a key that went
   * entirely in-band. Granting trust on that basis also suppressed the prompt to compare safety
   * numbers, so on first contact it promoted a substituted bundle from "unverified pin" to
   * "checked".
   */
  @Test
  public void anOutOfBandImportRecordsProvenanceButNotTrust() throws Exception {
    final String aliceBundle = bundleOf(alice);
    final SignalProtocolAddress aliceAddress = addressOf(alice);

    activate(bob);
    final Contact contact = contactFor(alice);
    assertFalse("nothing imported yet", SignalProtocolMain.isContactKeyTrustworthy(contact));

    assertTrue(SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, aliceAddress));

    assertTrue("the import must still be recorded",
        bob.getSignalProtocolStore().getIdentityKeyStore().isKeyOutOfBand(aliceAddress));
    assertFalse("but an unverifiable transfer must not substitute for comparing the number",
        SignalProtocolMain.isContactKeyTrustworthy(contact));
  }

  /** The in-band path must NOT confer it. */
  @Test
  public void anInBandImportDoesNotRecordProvenance() throws Exception {
    final String aliceBundle = bundleOf(alice);
    final SignalProtocolAddress aliceAddress = addressOf(alice);

    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), aliceAddress));

    assertFalse("a bundle through the messenger is not out-of-band",
        bob.getSignalProtocolStore().getIdentityKeyStore().isKeyOutOfBand(aliceAddress));
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(contactFor(alice)));
  }

  /**
   * Comparison is the only thing that confers trust, whichever way the key arrived.
   *
   * <p>The fixture pins Alice's key first. It used to assert that a contact marked verified was
   * trustworthy with NOTHING pinned for that address, which is a state the app should never
   * produce and now explicitly refuses: there is no key for the badge to be about, so a stale
   * verified flag over an empty address read as trustworthy. The claim this test makes - that
   * provenance does not confer trust and comparison does - is unaffected.
   */
  @Test
  public void onlyAnExplicitComparisonConfersTrust() throws Exception {
    final String aliceBundle = bundleOf(alice);
    activate(bob);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(aliceBundle), addressOf(alice)));

    final Contact unverified = contactFor(alice);
    assertFalse("a pinned key alone is not trust",
        SignalProtocolMain.isContactKeyTrustworthy(unverified));

    final Contact verified = new Contact("A", "B", alice.getSignalProtocolAddress().getName(),
        alice.getDeviceId(), true);
    assertTrue("comparing the number is what confers it",
        SignalProtocolMain.isContactKeyTrustworthy(verified));
  }

  @Test
  public void aFailedImportRecordsNothing() throws Exception {
    final SignalProtocolAddress aliceAddress = addressOf(alice);
    activate(bob);

    assertFalse(SignalProtocolMain.importOutOfBandKeyBundle("not a bundle", aliceAddress));
    assertFalse("a failed import must not confer provenance",
        bob.getSignalProtocolStore().getIdentityKeyStore().isKeyOutOfBand(aliceAddress));
  }

  /** Provenance must not survive a key it no longer describes. */
  @Test
  public void provenanceIsDroppedWhenTheKeyIsForgotten() throws Exception {
    final String aliceBundle = bundleOf(alice);
    final SignalProtocolAddress aliceAddress = addressOf(alice);
    activate(bob);
    SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, aliceAddress);

    bob.getSignalProtocolStore().getIdentityKeyStore().removeIdentity(aliceAddress);

    assertFalse(bob.getSignalProtocolStore().getIdentityKeyStore().isKeyOutOfBand(aliceAddress));
  }

  @Test
  public void provenanceSurvivesSerialization() throws Exception {
    final String aliceBundle = bundleOf(alice);
    final SignalProtocolAddress aliceAddress = addressOf(alice);
    activate(bob);
    SignalProtocolMain.importOutOfBandKeyBundle(aliceBundle, aliceAddress);

    final var reloaded = JsonUtil.fromJson(
        JsonUtil.toJson(bob.getSignalProtocolStore()),
        com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl.class);

    assertTrue("provenance was lost across a restart",
        reloaded.getIdentityKeyStore().isKeyOutOfBand(aliceAddress));
  }

  @Test
  public void theTrustQueryIsSafeWithNoAccount() {
    SignalProtocolMain.getInstance().setAccount(null);
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(contactFor(alice)));
    assertFalse(SignalProtocolMain.isContactKeyTrustworthy(null));
  }
}
