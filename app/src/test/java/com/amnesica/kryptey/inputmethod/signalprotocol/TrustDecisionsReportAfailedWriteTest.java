package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.StorageCryptoException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A trust decision that does not reach disk must be reported, not assumed.
 *
 * <p>Both controls here were writing and returning constants. {@code verifyContactInContactList}
 * ended {@code storeAll...(); return true;} while its own javadoc defines false as "verification
 * could not be recorded" — so the one failure that most plausibly means exactly that was the one it
 * could not report. {@code rejectContactKey} discarded the result outright, and the strip then
 * printed "Forgot the stored key for %s" over a key still pinned on disk.
 *
 * <p>The failure is quiet by construction: SharedPreferences restores the previous file from its
 * .bak while the in-memory map keeps the new value, so reads keep succeeding and nothing looks
 * wrong. The next {@code setInputView} calls {@code reloadAccount}, the on-disk copy replaces the
 * account, and the rejected key is pinned again with no record that it was ever rejected — the
 * silent trust-on-first-use that {@code markKeyRejected} exists to prevent.
 */
@RunWith(RobolectricTestRunner.class)
public class TrustDecisionsReportAfailedWriteTest {

  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");

  private Context context;
  private boolean writesFail;
  private Contact bob;

  private CryptoBox box() {
    return new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }
    };
  }

  /** A helper whose writes can be made to fail the way a full disk makes them fail. */
  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> box()) {
      @Override
      public boolean storeAllInformationInSharedPreferences(final Account account) {
        if (writesFail) return false;
        return super.storeAllInformationInSharedPreferences(account);
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    writesFail = false;

    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(context);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    SignalProtocolMain.getInstance().getAccount().setContactList(contacts);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Verifying reports success when the badge really was written. */
  @Test
  public void verifyingReportsSuccessWhenTheWriteLands() throws Exception {
    assertTrue("a verification that reached disk must report success",
        SignalProtocolMain.verifyContact(bob));
  }

  /** And reports failure when it did not, which is what the strip renders as unavailable. */
  @Test
  public void verifyingReportsFailureWhenTheWriteDoesNot() throws Exception {
    writesFail = true;
    assertFalse("a verification that did not reach disk must say so: the badge would appear and "
        + "then be taken away by the next reload, with the user believing they had compared the "
        + "number", SignalProtocolMain.verifyContact(bob));

    // And the badge must go back, which the boolean alone does not check.
    //
    // setVerified(true) has already been written into the LIVE contact list by the time the write
    // is attempted, so reporting failure without undoing it leaves the app saying two contradictory
    // things: the toast reads "Nothing has been marked as verified" and the contact row renders the
    // verified badge, because isContactKeyTrustworthy agrees with the in-memory flag. That errs
    // open - told it did not record, shown a green badge - until the next reload takes it away.
    final Contact reloaded = SignalProtocolMain.getInstance().getAccount().getContactList().get(0);
    assertFalse("a failed write must not leave the contact marked verified in the live list",
        reloaded.isVerified());
    assertFalse("nor leave the app's own trust predicate agreeing that it is",
        SignalProtocolMain.isContactKeyTrustworthy(reloaded));
  }

  /** Rejecting reports that its record landed. */
  @Test
  public void rejectingReportsThatTheRecordLanded() {
    assertTrue("precondition: there must be a key to forget",
        SignalProtocolMain.rejectContactKey(bob));
    assertTrue("a rejection that reached disk must say so",
        SignalProtocolMain.lastRejectionReachedDisk());
  }

  /** And reports that it did not, separately from whether there was a key to forget. */
  @Test
  public void rejectingReportsAfailedWriteSeparatelyFromWhetherAkeyExisted() {
    writesFail = true;
    final boolean hadPin = SignalProtocolMain.rejectContactKey(bob);

    assertTrue("the return value answers 'was there a key to forget', which is unchanged by the "
        + "write failing", hadPin);
    assertFalse("and the write failing must be reported through its own channel, or the strip "
        + "prints 'Forgot the stored key' over a key that is still pinned on disk and will be back "
        + "on the next raise", SignalProtocolMain.lastRejectionReachedDisk());
  }

  /**
   * The flag must describe THIS rejection, not the last one that got as far as writing.
   *
   * <p>{@code rejectContactKey} returns early when there is no account, and that path used to leave
   * the flag holding a previous outcome - so a caller asking "did this one land" was told about a
   * different one.
   */
  @Test
  public void afailedWriteIsNotRememberedIntoTheNextRejection() {
    writesFail = true;
    SignalProtocolMain.rejectContactKey(bob);
    assertFalse("precondition: the first rejection must have failed to write",
        SignalProtocolMain.lastRejectionReachedDisk());

    // A call that does nothing at all: no contact.
    SignalProtocolMain.rejectContactKey(null);

    assertTrue("a rejection that never happened must not report the previous one's failure",
        SignalProtocolMain.lastRejectionReachedDisk());
  }

  /**
   * A failed verify must put back everything it retracted, not just the badge.
   *
   * <p>Verifying also clears a standing rejection and dismisses a pending identity change, and both
   * are irreversible in memory. Rolling back only {@code setVerified} left the user told "Nothing
   * has been marked as verified" while the live store no longer held the rejection — so
   * {@code wasKeyRejected} went false, the post-rejection warning would not fire the next time a
   * relayed message pinned a key at that address, and the first successful write from any later
   * operation would persist the cleared record for good.
   *
   * <p>That is the silent trust-on-first-use {@code markKeyRejected} exists to prevent, reached from
   * the other direction — and the same argument that shaped {@code rejectContactKey} fifty lines
   * above it.
   */
  @Test
  public void afailedVerifyPutsBackTheRejectionItRetracted() throws Exception {
    assertTrue("precondition: a key must be pinned to reject",
        SignalProtocolMain.rejectContactKey(bob));
    // Re-pin, so Verify has something to compare and the rejection is the standing record.
    final Account peer = freshPeerPinnedAtBobsAddress();
    assertTrue("precondition: the rejection must be on record",
        SignalProtocolMain.wasKeyRejected(bob.getSignalProtocolAddress()));

    writesFail = true;
    assertFalse("precondition: the verify must report failure",
        SignalProtocolMain.verifyContact(bob));

    assertTrue("a verify that could not be recorded must leave the rejection standing. Otherwise "
            + "the user is told nothing was recorded, and the app quietly forgets that they ever "
            + "rejected a key at this address - so the next key pinned there arrives unwarned.",
        SignalProtocolMain.wasKeyRejected(bob.getSignalProtocolAddress()));
  }

  /**
   * ...and it must put back the pending identity change it dismissed.
   *
   * <p>The twin of the case above, and the half nothing enforced: a mutation deleting the
   * {@code recordIdentityChange} restore left the whole suite green, while deleting the
   * {@code markKeyRejected} restore eight lines above it is killed by that test. The two were added
   * together and only one was measured.
   *
   * <p>What a lost pending record costs is the warning itself.
   * {@code hasUnacceptedIdentityChange} is what raises the key-substitution notice on the strip, and
   * it is the last refusal in {@code isContactKeyTrustworthy}. So: an attacker forges a bundle at a
   * pinned address, the strip says a different key was offered, the user opens Verify and confirms
   * the number - and the write does not land. They are told nothing was recorded. The badge rolls
   * back, the rejection rolls back, and the warning about the offered key comes down anyway and
   * stays down, because the dismissal happened in memory and was never re-recorded. The next write
   * from any later operation persists the emptied map.
   *
   * <p>Dismissing before the write and restoring after is deliberate, and moving the dismissal after
   * {@code accountWriteSucceeded()} instead would be worse: that call is what writes the account, so
   * a dismissal after it is not in the bytes that reached disk and the change returns on the next
   * reload - which is every {@code setInputView}. The user answers the warning and meets it again one
   * keyboard raise later, which is a failure this file has already been fixed for twice.
   */
  @Test
  public void afailedVerifyPutsBackThePendingChangeItDismissed() throws Exception {
    // A substitution on record at Bob's address: someone offered a different key and it was refused.
    final Account impostor = freshPeerOfferingAdifferentKeyAtBobsAddress();
    assertTrue("precondition: a pending identity change must stand, or there is nothing to dismiss",
        SignalProtocolMain.hasUnacceptedIdentityChange(bob.getSignalProtocolAddress()));

    writesFail = true;
    assertFalse("precondition: the verify must report failure",
        SignalProtocolMain.verifyContact(bob));

    assertTrue("a verify that could not be recorded must leave the pending change standing. It is "
            + "what raises the substitution warning and the last refusal in isContactKeyTrustworthy, "
            + "so losing it takes the warning down permanently while telling the user nothing was "
            + "recorded - the app forgetting, on their behalf, that a key was ever offered.",
        SignalProtocolMain.hasUnacceptedIdentityChange(bob.getSignalProtocolAddress()));
  }

  /**
   * Offers a DIFFERENT key at Bob's address, which is what records a pending change.
   *
   * <p>Unlike its sibling below, the pin must already be there: {@code recordIdentityChange} is
   * reached when a bundle presents a key that is not the one pinned, so an address with nothing
   * pinned takes the trust-on-first-use arm instead and records nothing.
   */
  private Account freshPeerOfferingAdifferentKeyAtBobsAddress() throws Exception {
    assertNotNull("precondition: a key must already be pinned, or the offer is a first sighting",
        SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
            .getIdentityKeyStore().getIdentity(bob.getSignalProtocolAddress()));

    final Account keep = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(keep);

    // Refused, and that is the point: the pin does not move, and the offer is recorded as pending.
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bundle), bob.getSignalProtocolAddress());
    return keep;
  }

  /** Puts a fresh key at Bob's address, the way a re-invite after a rejection does. */
  private Account freshPeerPinnedAtBobsAddress() throws Exception {
    final Account keep = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(keep);
    assertTrue("precondition: the fresh key must pin",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bundle), bob.getSignalProtocolAddress()));
    return keep;
  }

  /**
   * Re-verifying an already-verified contact must not cost the badge when the write fails.
   *
   * <p>The rollback restored the two retractions faithfully and wrote {@code setVerified(false)} as
   * a constant. That is right for the contact being verified for the first time and wrong for one
   * that was already verified — and re-verifying is one tap on the green badge in the contact list,
   * an ordinary thing to do after any warning. The direction is safe (a badge is lost, never
   * gained), which is exactly why it would have gone unnoticed.
   */
  @Test
  public void afailedReVerifyDoesNotTakeAbadgeTheUserAlreadyHad() throws Exception {
    assertTrue("precondition: the first verify must land", SignalProtocolMain.verifyContact(bob));
    assertTrue("precondition: the contact must be verified",
        SignalProtocolMain.getInstance().getAccount().getContactList().get(0).isVerified());

    writesFail = true;
    assertFalse("the failed write must still be reported", SignalProtocolMain.verifyContact(bob));

    assertTrue("a failed re-verify must leave the badge the user already held. Rolling back to a "
            + "constant false takes away a verification that was recorded, and disk still says "
            + "verified until the next reload puts it back - so the app disagrees with itself.",
        SignalProtocolMain.getInstance().getAccount().getContactList().get(0).isVerified());
  }

  /**
   * Creating a contact must report a write that did not land.
   *
   * <p>The last member of the write family that could not. Rejection, verification, deletion, a sent
   * message and a received one all thread the result up; creation said "Contact X created" and sent
   * the user to compare a security number for a contact that exists in memory only — which the next
   * {@code reloadAccount} reverts, and the host app decides when that happens.
   *
   * <p>The unreadable-storage case was already covered: with no account there is no contact and the
   * strip aborts. The gap was account-loaded-but-write-failed, which is the case that looks healthy.
   */
  @Test
  public void creatingAcontactReportsAwriteThatDidNotLand() throws Exception {
    writesFail = true;

    final Contact created = SignalProtocolMain.addContact(
        "Carol", "Smith", "carol-address", 4);

    assertNotNull("the contact is still created in memory - the strip needs it to keep going",
        created);
    assertFalse("a creation that did not reach disk must say so, or the user is told to verify a "
            + "contact who will not exist after the next keyboard raise",
        SignalProtocolMain.lastContactWriteReachedDisk());
  }
}
