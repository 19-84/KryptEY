package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

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
}
