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

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

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
}
