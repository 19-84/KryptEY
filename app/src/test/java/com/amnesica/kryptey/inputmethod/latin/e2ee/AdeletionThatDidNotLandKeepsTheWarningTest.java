package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * A deletion that was performed in memory and never written must not take the warning with it.
 *
 * <p>Deleting the contact a warning names is the one deliberate response the verify screen cannot
 * offer once the row is gone, so a successful deletion clears the warning. There are two ways for
 * that deletion not to happen, and the file already handles one: a deletion <em>refused</em> because
 * the chat log will not read. The other is a deletion that ran in memory and whose write did not
 * land.
 *
 * <p>In that state the row is gone from the list right now — which is precisely why clearing looked
 * correct, and precisely why it was wrong. The next {@code reloadAccount} brings the contact, its
 * pinned key and its messages back, and the app's only lasting warning about that key does not come
 * back with them. The messenger chooses when that raise happens, so it chooses when the warning
 * disappears and the key returns.
 *
 * <p>The cost is a warning standing over a contact temporarily absent from the list. That is the
 * same trade the unreadable-log arm settles the same way: a warning with no visible subject is
 * confusing; a subject that returns with no warning is a silent key-substitution window.
 */
@RunWith(RobolectricTestRunner.class)
public class AdeletionThatDidNotLandKeepsTheWarningTest {

  private E2EEStripView strip;
  private Contact bob;
  private static final String WARNING = "Careful: someone offered a different key for Bob.";
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * A store whose writes land.
   *
   * <p>Stated rather than assumed. The first version of the positive control below left the default
   * helper in place and went red, because in this fixture the default write does not succeed either
   * - so "a deletion that landed" was never being tested, and had the production gate been wrong in
   * the other direction nothing here would have noticed.
   */

  /**
   * What {@code reloadAccount} does: the stored account replaces the one in memory.
   *
   * <p>Built rather than read back, because the helper under test refuses writes, so nothing was
   * ever stored. The account this produces is the one the store still holds - Bob's key pinned, no
   * rejection recorded - which is exactly what the user's un-persisted decision is discarded in
   * favour of.
   */
  private void theAccountOnDiskReplacesTheOneInMemory() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account onDisk = SignalProtocolMain.getInstance().getAccount();
    onDisk.setMessageLogLoader(ArrayList::new);
    assertTrue("fixture: the stored account still pins Bob's key",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), bob.getSignalProtocolAddress()));
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    onDisk.setContactList(contacts);
  }

  private void makeTheWriteLand() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }
        });
  }

  /** The log reads fine, so the deletion is performed; only the write fails. */
  /** Bob's own bundle, so a rotation for him records a refusal at HIS address. */
  private String peerBundleForTest() {
    return peerBundle;
  }

  private void makeTheWriteFail() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return false;
          }
        });
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return String.valueOf(view.getText());
  }

  @Test
  public void awritefailureLeavesTheWarningStanding() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteFail();

    strip.removeContact(bob);

    assertTrue("the contact, its pinned key and its messages all come back at the next raise; the "
            + "warning about that key must come back with them, and the only way it can is by "
            + "never having gone down. Banner: " + banner(),
        banner().contains(WARNING));
  }

  /**
   * And the model must agree with the screen, or the messenger erases it anyway.
   *
   * <p>Asserting the rendered text alone is the hollow control this project has hit twice: the flag
   * being down while the text is still painted is the exact wedge — {@code mayOverwriteInfoBanner}
   * then answers true and the next clipboard event replaces the warning with "Keybundle detected".
   */
  @Test
  public void andTheNextClipboardEventCannotErodeIt() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteFail();

    strip.removeContact(bob);
    strip.onClipboardHoldsDecryptableItemForTest();

    assertTrue("the flag must still be up, or ordinary messenger traffic overwrites the warning "
            + "and the app's only lasting record of the key substitution is gone: " + banner(),
        banner().contains(WARNING));
  }

  /**
   * A deletion that DID land still clears it, or this file has broken the thing it is protecting.
   */
  @Test
  public void adeletionThatLandsStillClearsTheWarning() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteLand();

    strip.removeContact(bob);

    assertFalse("deleting the contact a warning names is the deliberate response to it; a warning "
            + "that survives its own resolution is one the user cannot ever put down. Banner: "
            + banner(), banner().contains(WARNING));
  }

  /**
   * A deletion that did not land leaves the contact where the user can try again.
   *
   * <p>Before this, the row was pruned in memory and nowhere else: it left the list while disk still
   * held it, its pinned key and its messages. The user was correctly told the deletion was not saved
   * — and then had no way to retry, because every route to a contact goes through the contact list.
   * Its verify screen was gone, so Reject and Verify were unreachable; a second delete had nothing
   * to delete. Any standing item about that contact then had no deliberate response left, and a
   * caution holds the banner for the life of the process.
   *
   * <p>Restoring the row is also what the app already says happens — "they and their saved messages
   * will come back" — which until now was true only after a reload the user cannot trigger.
   */
  @Test
  public void awritefailureLeavesTheContactWhereTheUserCanRetry() {
    makeTheWriteFail();

    strip.removeContact(bob);

    assertTrue("the contact must still be in the list. Without it there is no verify screen, no "
            + "second delete, and no deliberate response to anything standing about them - the "
            + "dead end the escape hatch on the verify screen exists to prevent, reached from the "
            + "one direction that removes the screen itself.",
        SignalProtocolMain.getInstance().getAccount().getContactList().contains(bob));
  }

  /** And a deletion that landed really is gone, or the rollback has swallowed the feature. */
  @Test
  public void adeletionThatLandsStillRemovesTheContact() {
    TestStores.writesLand();

    strip.removeContact(bob);

    assertFalse("a successful deletion must still delete",
        SignalProtocolMain.getInstance().getAccount().getContactList().contains(bob));
  }

  /**
   * And what comes back is everything, not just the row.
   *
   * <p>A partial rollback is worse than none, because the account batch writes the WHOLE in-memory
   * account: the first later successful write — sending to somebody else, receiving anything —
   * persists whatever half-state was left behind. Restoring only the contact left it permanently
   * session-less and history-less, after the app had said the deletion did not happen and the
   * messages would come back. Every message from them would then fail to decrypt, and this app's
   * standard advice for that is delete-and-re-invite, which is the key-substitution window.
   */
  @Test
  public void awritefailureRestoresTheMessagesAndTheSessionToo() throws Exception {
    final org.signal.libsignal.protocol.SignalProtocolAddress bobAddress =
        com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.of(
            bob.getSignalProtocolAddressName(), bob.getDeviceId());
    final Account account = SignalProtocolMain.getInstance().getAccount();

    assertNotNull("precondition: a message must be logged, or the restore has nothing to prove",
        SignalProtocolMain.encryptMessage("before the deletion", bobAddress));
    final int messagesBefore = account.getUnencryptedMessages().size();
    assertTrue("precondition: the log must hold it", messagesBefore > 0);
    assertTrue("precondition: a session must exist",
        account.getSignalProtocolStore().containsSession(bobAddress));

    makeTheWriteFail();
    strip.removeContact(bob);

    assertEquals("the messages must come back with the row - the app said they would, and the "
            + "next successful write persists whatever is in memory at that moment",
        messagesBefore, account.getUnencryptedMessages().size());
    assertTrue("and so must the session, or the contact is restored unusable: every message from "
            + "them fails to decrypt, and the advice for that is delete-and-re-invite",
        account.getSignalProtocolStore().containsSession(bobAddress));
  }

  /**
   * A deletion that left the messages behind says so.
   *
   * <p>The store is two files with two commits — the log first, the account second — and the
   * deletion's success was reported from the second alone. A log commit that fails while the account
   * commit succeeds produced a deletion reported as complete: no notice, the standing warning about
   * that contact cleared, the row gone from disk, and that contact's plaintext still in the log file
   * owned by no row. `belongsTo` compares the full rendered address, so nothing matches it
   * afterwards — it is beyond the one erasure action the user has.
   *
   * <p>That is the outcome the app's own deletion refusal calls the worse of the two, and the help
   * text now promises the opposite.
   */
  @Test
  public void adeletionThatCouldNotRemoveTheMessagesSaysSo() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;   // the account file commits
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;  // the log file does not
          }
        });

    strip.removeContact(bob);

    assertTrue("the contact really is deleted - the account write landed",
        !SignalProtocolMain.getInstance().getAccount().getContactList().contains(bob));
    assertTrue("but their plaintext is still on the device and nothing can reach it, which the "
            + "user must be told: reporting this as a clean deletion is the app breaking the one "
            + "promise its help text makes about erasure. Banner: " + banner(),
        banner().contains("could not be deleted"));
  }

  /** And a clean deletion says nothing of the sort. */
  @Test
  public void acleanDeletionDoesNotClaimMessagesWereLeftBehind() {
    TestStores.writesLand();

    strip.removeContact(bob);

    assertTrue("a deletion where both files committed must not warn about leftover plaintext: "
            + banner(), !banner().contains("could not be deleted"));
  }

  /**
   * The leftover-plaintext notice survives the next ordinary contact add.
   *
   * <p>It shared the single caution slot, so the routine "Contact X created. Compare the security
   * number" posted on the very next add destroyed it — and a messenger can time that: relay an
   * invite straight after the failed deletion, the user adds the contact because adding contacts is
   * what this app is for, and the notice is gone. Nothing re-asserts it. It is the user's only
   * chance to learn of a condition no screen can otherwise show and their one erasure action cannot
   * reach.
   */
  @Test
  public void thestoreNoticeSurvivesTheNextContactAdd() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });
    strip.removeContact(bob);
    assertTrue("precondition: the notice must be up: " + banner(),
        banner().contains("could not be deleted"));

    // Any routine caution about any contact.
    strip.setCautionForTest("Contact Carol Smith created. Compare the security number by voice.",
        bob);

    assertTrue("a routine caution about a contact must not destroy a notice about the store - they "
            + "are different facts and the store one is never said again: " + banner(),
        banner().contains("could not be deleted"));
  }

  /**
   * And verifying somebody does not clear it either.
   *
   * <p>The notice names no contact, because the contact it is about is gone. {@code
   * clearCautionIfAbout} reads a null address as "about anyone", so while it lived in the caution
   * slot, verifying or deleting <em>anybody</em> took it down.
   */
  @Test
  public void thestoreNoticeIsNotClearedByActingOnSomeoneElse() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });
    strip.removeContact(bob);
    assertTrue("precondition", banner().contains("could not be deleted"));

    strip.selectContact(bob);
    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the reject button must be live, or nothing is being driven",
        strip.findViewById(R.id.e2ee_verify_contact_reject_button).isEnabled());
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    assertTrue("precondition: the rejection must have landed, or the listener's clears never ran",
        SignalProtocolMain.lastRejectionReachedDisk());

    // Repainted the way production does, because the clear nulls the FIELD and nothing repaints on
    // that path - so reading the banner straight after the click reads a stale string and passes
    // whether or not the sentence has been erased. Asserting against the text that produces the
    // view instead of the view is the hollow control this project keeps catching; this is the same
    // mistake with the two swapped, and the mutant caught it.
    strip.selectContact(bob);

    assertTrue("acting on a contact says nothing about whether the log still holds somebody else's "
            + "plaintext: " + banner(), banner().contains("could not be deleted"));
  }

  /**
   * And the notice is retired once a later message-log write has landed.
   *
   * <p>Without this test the clear can simply stop being called and nothing fails: a private method
   * with no callers compiles, and a notice that is never retired looks exactly like a notice. That
   * is not hypothetical — the call site was added, lost when a file was restored from an older
   * snapshot, and the notice became permanent. It holds the banner, so every informational line in
   * the app was suppressed for the life of the process.
   *
   * <p>The resolution is real rather than a timeout: the orphaned entries were already removed from
   * the in-memory log by the deletion, so the next log write that lands persists the pruned log and
   * the plaintext is gone.
   */
  @Test
  public void thestoreNoticeIsRetiredOnceAlaterLogWriteLands() throws Exception {
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });
    strip.removeContact(bob);
    assertTrue("precondition: the notice must be up: " + banner(),
        banner().contains("could not be deleted"));

    // Storage recovers, and a log write lands.
    final long before = SignalProtocolMain.messageLogWritesLanded();
    TestStores.writesLand();
    SignalProtocolMain.getInstance().getAccount().getUnencryptedMessages();
    // Adding somebody, rather than verifying Bob: Bob really was deleted in this scenario - the
    // account write landed and only the log write failed - so there is nothing of his to verify.
    SignalProtocolMain.addContact("Carol", "Smith", bob.getSignalProtocolAddressName(),
        bob.getDeviceId() + 7);
    assertTrue("precondition: a log write must actually have landed, or this test is asserting the "
            + "notice goes away for no reason",
        SignalProtocolMain.messageLogWritesLanded() > before);

    // Any repaint asks.
    strip.selectContact(bob);

    assertTrue("the pruned log has reached disk, so the plaintext the notice is about is gone and "
            + "the sentence must go with it - a notice that outlives its condition is the "
            + "habituation failure this whole surface is built to avoid: " + banner(),
        !banner().contains("could not be deleted"));
  }

  /**
   * A landed write settles a refusal, not a deletion that never happened.
   *
   * <p>The failed-delete arm deliberately records no send-refusal, because expiring one used to send
   * the message on the first tap of the still-dark button. But the expiry keyed on the ADDRESS, so a
   * refusal recorded by an <em>earlier</em> failure at that address retired the deletion notice
   * anyway — and a failed deletion rolls the row back into the list, so the entry survives the sweep
   * that drops entries for contacts that no longer exist.
   *
   * <p>Two storage failures in sequence and the user is left believing the deletion succeeded, while
   * the contact, its pinned key and its stored plaintext are all still there and the screen is
   * byte-identical to a healthy one.
   */
  @Test
  public void alandedWriteDoesNotEraseThedeletionNotice() throws Exception {
    // First failure: a rotation for BOB whose write does not land, which records a refusal at
    // Bob's address. The address is the whole point - an earlier version of this test recorded one
    // at a different address, so the expiry never matched it and the test passed with the defect in
    // place.
    strip.selectContact(bob);
    makeTheWriteFail();
    strip.processPreKeyResponseForTest(EnvelopeCodec.fromWire(peerBundleForTest()), bob);
    assertTrue("precondition: a refusal must be recorded for this contact",
        strip.refusalCountForTest() == 1);

    // Second failure: the deletion does not reach disk either.
    strip.removeContact(bob);
    assertTrue("precondition: the deletion notice must be up: " + banner(),
        banner().contains("was not removed"));

    // Storage recovers and any write lands.
    TestStores.writesLand();
    SignalProtocolMain.addContact("Carol", "Smith", bob.getSignalProtocolAddressName(),
        bob.getDeviceId() + 31);
    strip.selectContact(bob);

    assertTrue("a landed write settles 'this row is not on disk'. It says nothing about a deletion "
            + "that was never attempted again - and erasing that notice leaves the user believing "
            + "the deletion succeeded while the contact, its key and its plaintext are all still "
            + "here: " + banner(),
        banner().contains("was not removed"));
  }

  /**
   * The deletion notice is not settled by anything that settles the other storage cautions.
   *
   * <p>Every other member of the {@code *_NOT_SAVED} family reports a write that did not land and is
   * ended by a later one that does, or by simply retrying the operation. This one reports that a
   * contact the user tried to remove is still on disk, with their pinned key and their plaintext, so
   * nothing a later write does settles it — and the caution slot is single, so any of them replaced
   * it, flag and all. One relayed message carrying a bundle is enough: the rotation's own write
   * fails during the same disk-full episode, posts "a key update could not be saved", and the
   * sentence about the deletion is gone. Worse, the replacement IS settled by a later landed write,
   * so the next successful write clears that too and the screen reads like an ordinary success.
   */
  @Test
  public void alesserStorageCautionCannotReplaceTheDeletionNotice() {
    makeTheWriteFail();
    strip.removeContact(bob);
    assertTrue("precondition: the deletion notice must be standing: " + banner(),
        banner().contains("was not removed"));

    // Any other storage caution, posted while the same failure is still in effect.
    strip.setStorageCautionForTest("A key update from Bob Jones could not be saved.", bob);

    assertTrue("a caution about a write that did not land must not displace the one saying a "
            + "contact the user tried to delete is still here with their key and their messages - "
            + "that one is settled by nothing a later write does: " + banner(),
        banner().contains("was not removed"));
  }

  /**
   * And acting on the contact does not clear it either — only deleting them again, successfully.
   *
   * <p>Verifying or rejecting a contact you have just failed to delete is a plausible next move,
   * and it took the sentence down. {@code retireTheStorageCautionFor} already refused to touch a
   * deletion caution for exactly this reason; the scoped clear beside it did not.
   */
  @Test
  public void actingOnTheContactDoesNotClearTheDeletionNotice() {
    makeTheWriteFail();
    strip.removeContact(bob);
    assertTrue("precondition: the deletion notice must be standing: " + banner(),
        banner().contains("was not removed"));

    // The storage trouble ends - the device is unlocked, space is freed - and the user rejects the
    // key of the contact they failed to delete. That is a plausible next move for somebody who
    // wanted them gone, and it is the reachable route: both clears on that listener are gated on
    // the response reaching disk, so while writes are still failing nothing gets that far. The
    // deletion notice is still TRUE at this point: the row, its key and its messages were never
    // removed from disk.
    makeTheWriteLand();
    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the reject button must be live, or nothing is being driven",
        strip.findViewById(R.id.e2ee_verify_contact_reject_button).isEnabled());
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    assertTrue("precondition: the rejection must have landed, or the listener's clears never ran",
        SignalProtocolMain.lastRejectionReachedDisk());

    // Repainted the way production does, because the clear nulls the FIELD and nothing repaints on
    // that path - so reading the banner straight after the click reads a stale string and passes
    // whether or not the sentence has been erased. Asserting against the text that produces the
    // view instead of the view is the hollow control this project keeps catching; this is the same
    // mistake with the two swapped, and the mutant caught it.
    strip.selectContact(bob);

    assertTrue("only a deletion that lands ends this one. The row, the pinned key and the "
            + "plaintext are all still on disk and come back at the next raise: " + banner(),
        banner().contains("was not removed"));
  }

  /**
   * The exit exists, which is what keeps the two tests above from being a dead end.
   *
   * <p>This file has closed a "notice nobody can clear" twice. The deletion notice is ended by the
   * one event that makes it false: deleting the contact again, and having it land.
   */
  @Test
  public void adeletionThatLandsEndsTheDeletionNotice() {
    makeTheWriteFail();
    strip.removeContact(bob);
    assertTrue("precondition: the deletion notice must be standing", 
        banner().contains("was not removed"));

    // The device is unlocked, space is freed, and they try again.
    makeTheWriteLand();
    strip.removeContact(bob);

    assertTrue("a notice with no exit is the dead end this file has closed twice; the deletion "
            + "landing is the event that ends this one: " + banner(),
        !banner().contains("was not removed"));
  }

  /**
   * A rejection that did not reach disk says so on the surface that lasts.
   *
   * <p>It was the last member of the {@code *_NOT_SAVED} family with only a toast, and the one
   * whose consequence is worst. Measured in {@code ArejectionThatDidNotLandIsForgottenTest}: the
   * key the user reported as not matching is pinned again at the next reload, AND the address is
   * unmarked, so the next bundle there is a clean first sighting and nothing warns. The user
   * performed this app's strongest deliberate refusal, was told for three and a half seconds that
   * it had not stuck, and had no way to check afterwards.
   */
  @Test
  public void afailedRejectionSaysSoDurably() {
    makeTheWriteFail();

    strip.showVerifyContactForTest(bob);
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    strip.selectContact(bob);

    assertTrue("the rejected key comes back at the next raise and the address is unmarked; three "
            + "and a half seconds is not where that belongs: " + banner(),
        banner().contains("could not write to its own storage"));
  }

  /**
   * A failed rejection does not displace a failed deletion's notice.
   *
   * <p>The rejection notice was first classified as a second protected kind, and that was wrong
   * twice over. It made two protected notices compete in one slot with neither yielding, so a
   * failed rejection during the same disk-full episode overwrote "that contact was not removed"
   * and nothing ever re-raised it. And it stranded the rejection notice itself: rejecting removes
   * the pin, so that contact's verify screen has no number and both buttons go dark, and "reject
   * again" - the exit the protection demanded - could not be reached.
   *
   * <p>Measured instead: {@code rejectContactKey} has no rollback, so the state the user asked for
   * is already in memory and the next landed write puts it on disk. A failed rejection is settled
   * by a later landed write, like the rest of the family, and yields to the one notice that is not.
   */
  @Test
  public void afailedRejectionDoesNotDisplaceAfailedDeletionsNotice() {
    makeTheWriteFail();
    strip.removeContact(bob);
    assertTrue("precondition: the deletion notice must be standing: " + banner(),
        banner().contains("was not removed"));

    // The same episode: the user rejects the key of the contact they failed to delete, and that
    // write fails too.
    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the reject button must be live",
        strip.findViewById(R.id.e2ee_verify_contact_reject_button).isEnabled());
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    assertFalse("precondition: this rejection must NOT have landed",
        SignalProtocolMain.lastRejectionReachedDisk());
    strip.selectContact(bob);

    assertTrue("the deletion notice is the one nothing a later write settles, so it outranks a "
            + "notice that a later write does settle. Overwritten here, it is never re-raised - "
            + "removeContact is its only writer and it posts only on a fresh failed attempt: "
            + banner(),
        banner().contains("was not removed"));
  }

  /**
   * And an ordinary storage caution comes down when a later write lands, which is its exit.
   *
   * <p>Until now the only thing that retired one was the refusal sweep, and that acts only on an
   * address that also has a not-on-disk entry. A caution raised without one could be ended only by
   * acting on the contact it names - and after a failed rejection those controls are gone, because
   * rejecting removed the pin, so the verify screen has no number and both buttons are dark. That
   * is the dead end this file has closed twice, arrived at from a new direction.
   */
  @Test
  public void anordinaryStorageCautionRetiresWhenAlaterWriteLands() throws Exception {
    makeTheWriteFail();
    strip.showVerifyContactForTest(bob);
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    strip.selectContact(bob);
    assertTrue("precondition: the rejection notice must be standing: " + banner(),
        banner().contains("could not write to its own storage"));

    // The disk frees up and the user does the next ordinary thing - pressing Invite, which
    // allocates a one-time pre-key and saves the account. For a rejection that is not incidental:
    // the decision was already in memory, so the write that lands is what puts it on disk.
    makeTheWriteLand();
    final long before = SignalProtocolMain.accountWritesLanded();
    SignalProtocolMain.exportOwnKeyBundle();
    assertTrue("precondition: a write must have landed",
        SignalProtocolMain.accountWritesLanded() > before);
    strip.selectContact(bob);

    assertFalse("a caution a later landed write settles must come down when one does, or it is a "
            + "sentence with no exit holding the banner for the life of the process: " + banner(),
        banner().contains("could not write to its own storage"));
  }

  /**
   * A failed rejection at an address already marked on disk says nothing, because nothing was lost.
   *
   * <p>The notice claims "it will not be remembered the next time the keyboard opens". Pressing
   * Reject again at an address whose mark already reached disk changes nothing, so that clause is
   * false: the earlier mark is on disk and will be remembered. The toast beside it already asks
   * both questions - whether there was a key to forget, and whether the address was already
   * rejected - and the durable sentence has to ask them too, precisely because it is durable and
   * protected from being painted over.
   */
  @Test
  public void asecondFailedRejectionAtAmarkedAddressSaysNothing() {
    makeTheWriteLand();
    strip.showVerifyContactForTest(bob);
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    assertTrue("precondition: the first rejection must have landed",
        SignalProtocolMain.lastRejectionReachedDisk());
    assertTrue("precondition: the address must be marked on disk",
        SignalProtocolMain.wasKeyRejected(bob.getSignalProtocolAddress()));

    // A warning about Bob stands again, which is what keeps Reject reachable with no pin - the
    // deliberate escape hatch, so a standing warning always has a response available.
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteFail();
    strip.showVerifyContactForTest(bob);
    assertTrue("precondition: the escape hatch must have kept Reject live",
        strip.findViewById(R.id.e2ee_verify_contact_reject_button).isEnabled());

    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    strip.selectContact(bob);

    assertFalse("nothing was lost: there was no key to forget and the mark was already on disk, so "
            + "'it will not be remembered the next time the keyboard opens' is false. A durable "
            + "sentence the user can catch out is one they stop believing: " + banner(),
        banner().contains("could not write to its own storage"));
  }

  /**
   * A later write settles the rejection notice only if it carried the rejection.
   *
   * <p>The whole argument for classifying a failed rejection as settled-by-a-later-write is that
   * {@code rejectContactKey} leaves the decision in memory, so the next landed write persists it.
   * That holds until the in-memory account is replaced — and {@code reloadAccount} does exactly
   * that on a theme change the host app can force, restoring the pinned key and emptying the
   * rejected set. Its write-back is deliberately not counted, so the reload is invisible to the
   * counter, and the caution and its captured count are both carried across the same rebuild.
   *
   * <p>The caution then survived the event that made it permanently true, and the next unrelated
   * landed write retired it — taking away the only durable sentence saying the refused key had come
   * back, at the moment it actually had.
   */
  @Test
  public void awriteThatDidNotCarryTheRejectionDoesNotRetireItsNotice() throws Exception {
    makeTheWriteFail();
    strip.showVerifyContactForTest(bob);
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
    strip.selectContact(bob);
    assertTrue("precondition: the rejection notice must be standing: " + banner(),
        banner().contains("could not write to its own storage"));

    // The reload the host app can force: the on-disk account replaces memory, so the rejection the
    // user made is gone and the key they refused is pinned again. Built rather than read back,
    // because the failing helper never wrote anything - this is the account reloadAccount would
    // load, which is one that never saw the rejection.
    theAccountOnDiskReplacesTheOneInMemory();
    makeTheWriteLand();
    assertFalse("precondition: the reload must have discarded the rejection",
        SignalProtocolMain.wasKeyRejected(bob.getSignalProtocolAddress()));
    assertTrue("precondition: and the refused key must be pinned again",
        SignalProtocolMain.hasPinnedKey(bob.getSignalProtocolAddress()));

    // Any later write at all.
    final long before = SignalProtocolMain.accountWritesLanded();
    SignalProtocolMain.exportOwnKeyBundle();
    assertTrue("precondition: a write must land",
        SignalProtocolMain.accountWritesLanded() > before);
    strip.selectContact(bob);

    assertTrue("the write did not carry the rejection - the reload had already thrown it away - so "
            + "it settles nothing. Retiring here removes the only durable sentence saying the "
            + "refused key is back, exactly when it is: " + banner(),
        banner().contains("could not write to its own storage"));
  }

  /**
   * Retiring a caution must leave the banner saying what every other path says.
   *
   * <p>The count half of the store notice's idiom was copied into the new exit and the repaint half
   * was not. {@code warningWithRecipient} returns null when nothing stands and no contact is
   * chosen, so a bare {@code setText} painted the banner blank — and an empty banner matches no
   * prefix in {@code disablesActionButtons}, so the buttons came back on over an empty strip.
   *
   * <p>The no-recipient case is not a corner: the Invite path posts its caution with a null
   * contact, and giving that caution an exit is what the method was added for. The existing
   * retirement test cannot see this because it selects a contact before reading the banner, which
   * repaints over the damage.
   */
  @Test
  public void retiringAcautionLeavesTheBannerSayingWhatItShould() throws Exception {
    // The Invite path's caution, which is the one that names no contact: "the Invite button on the
    // contact list is the flow for a NEW contact", so chosenContact is null there. Giving that
    // caution an exit is what the retirement was added for, and it is the case where the repaint
    // had nothing to fall back on.
    makeTheWriteFail();
    strip.setStorageCautionForTest("Your invite could not be saved - the app could not write to "
        + "its own storage.", null);
    strip.resetChosenContactAndInfoTextForTest();
    assertTrue("precondition: a caution with no contact must be standing: " + banner(),
        banner().contains("could not write to"));

    makeTheWriteLand();
    SignalProtocolMain.exportOwnKeyBundle();
    // The sweep itself, with nothing writing the banner after it - so what is asserted is the
    // retirement's own repaint rather than whatever the next line happens to paint over it.
    strip.refusalCountForTest();

    assertFalse("the banner must never be left empty: nothing matches an empty string in "
            + "disablesActionButtons, so the buttons come back on over a strip with no recipient",
        banner().trim().isEmpty());
    assertTrue("and it must say what every other no-recipient path says, so the buttons follow it: "
        + banner(), banner().contains("No contact chosen"));
  }
}
