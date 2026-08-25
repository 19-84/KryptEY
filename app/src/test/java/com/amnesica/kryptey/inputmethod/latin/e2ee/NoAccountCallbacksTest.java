package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * Every button on the strip, on an install where no account is loaded.
 *
 * <p>This is NOT the {@code UNREADABLE} state {@code UnreadableStorageScreensTest} covers, and the
 * difference is the whole point. {@code UNREADABLE} means protocol data is on disk and cannot be
 * decrypted; the strip notices, posts {@code INFO_STORAGE_UNREADABLE} as a standing warning, and
 * the text watcher disables Encrypt and Decrypt off the back of that banner. When there is no
 * protocol data at all — a reload that yields nothing, a storage helper that could not be built —
 * {@code storageState()} reports {@code NONE}, which is the correct answer for a fresh install and
 * is what {@code openingMessage} renders as the ordinary "No contact chosen" line. Nothing is
 * disabled, nothing warns, and {@code mAccount} is still null.
 *
 * <p>In that state the strip's own clipboard listener is what arms the trap. It needs no account —
 * {@code decodeMessage} and {@code getMessageType} both run happily — so the first KryptEY-shaped
 * thing the messenger posts writes "Keybundle detected" over the banner, and the watcher reads any
 * banner other than the two named ones as a reason to ENABLE both action buttons. The user then
 * presses the button the app just told them to press.
 *
 * <p>Measured before the guards this file exists for, each straight out of {@code
 * View.performClick} with nothing above it to catch an unchecked throw:
 *
 * <ul>
 *   <li>Decrypt — {@code NullPointerException: Cannot invoke "Account.getName()" because the return
 *       value of "SignalProtocolMain.getAccount()" is null}, from {@code getAccountName} through
 *       {@code decryptMessageInClipboard}, whose only catch is for {@code IOException}.
 *   <li>Chat logs — the same line, reached from {@code loadMessagesIntoMessagesListView}, whose only
 *       catch is for {@code UnknownContactException}.
 *   <li>Add contact — {@code NullPointerException: Cannot invoke
 *       "Account.addContactToContactList(Contact)"}, from {@code createAndAddContactToList} through
 *       {@code E2EEStrip.createAndAddContactToContacts}, which catches the two checked contact
 *       exceptions and nothing else. This is the screen the Decrypt press above lands on.
 *   <li>Encrypt — {@code NullPointerException: Cannot invoke "Account.getSignalProtocolStore()"},
 *       from {@code encrypt} through {@code encryptAndSendInputFieldContent}, which catches
 *       {@code TooManyCharsException} and {@code IOException}.
 * </ul>
 *
 * <p>Out of a click listener there is nothing left to catch any of them, so the input-method process
 * dies — in whatever app the user was typing in, not in KryptEY. That is a denial of service on the
 * only interface the app has, and the messenger chooses when it happens: it picks the payload that
 * enables the button.
 */
@RunWith(RobolectricTestRunner.class)
public class NoAccountCallbacksTest {

  private E2EEStripView strip;
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    // One account exists just long enough to mint a genuine bundle - this is the messenger's
    // payload, and it has to be a real one so the clipboard listener recognises it.
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    assertNotNull("precondition: the messenger's payload must be a real key bundle", peerBundle);

    // ...and then the install that receives it has none.
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    strip.refreshOpeningMessage();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private View decryptButton() {
    return strip.findViewById(R.id.e2ee_button_decrypt);
  }

  /** What the messenger does: posts a bundle the user copies. The listener does the rest. */
  private void theMessengerPostsAKeyBundle() {
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("", RawEncoder.encode(peerBundle)));
    ShadowLooper.idleMainLooper();
  }

  /**
   * The premise, asserted rather than assumed: this state is NOT the one the storage warning
   * covers, and it leaves the Decrypt button live.
   */
  @Test
  public void thepremise() {
    assertEquals("precondition: no protocol data means NONE, which is also what a fresh install "
            + "reports - this is why the storage warning does not fire here",
        StorageHelper.StorageState.NONE, SignalProtocolMain.storageState());
    assertNull("precondition: and there is no account behind it",
        SignalProtocolMain.getInstance().getAccount());
    assertEquals("precondition: so the strip shows its ordinary opening line",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, banner());
    assertFalse("precondition: that line is one of the two that disable the action buttons",
        decryptButton().isEnabled());

    theMessengerPostsAKeyBundle();

    assertTrue("precondition: and one post by the messenger is enough to re-enable them - the "
            + "clipboard listener needs no account, so it writes its own banner and the text "
            + "watcher enables both buttons. Banner: " + banner(),
        decryptButton().isEnabled());
  }

  /** Pressing the button the app just invited the user to press must not kill the keyboard. */
  @Test
  public void pressingDecryptDoesNotKillTheProcess() {
    theMessengerPostsAKeyBundle();

    decryptButton().performClick();   // must not throw

    assertEquals("the bundle is from an address this install does not know - with no contact list "
            + "there is no address it does know - so the add-contact screen is the right landing "
            + "place, and reaching it at all means nothing was thrown on the way",
        View.VISIBLE, strip.findViewById(R.id.e2ee_add_contact_wrapper).getVisibility());
  }

  /** And the Add button on the screen that press lands on must not kill it either. */
  @Test
  public void pressingAddContactDoesNotKillTheProcess() {
    theMessengerPostsAKeyBundle();
    decryptButton().performClick();
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Mallory");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("M");
    ShadowToast.reset();

    strip.findViewById(R.id.e2ee_add_contact_button).performClick();   // must not throw

    final String toast = ShadowToast.getTextOfLatestToast();
    assertNotNull("failing silently is not enough - the user filled in a form and pressed Add",
        toast);
    assertTrue("and it must say the contact was not created, because it was not. Shown: " + toast,
        toast.contains("Could not create contact"));
    assertNull("nothing may be left chosen after a refused add - the encrypt path reads it",
        strip.chosenContactForTest());
  }

  /**
   * The chat-log button, which is on the main view and which no banner ever disables.
   *
   * <p>Driven through {@code selectContact}, which is exactly what a contact row's click listener
   * calls ({@code ListAdapterContacts.getView} binds {@code v -> mListener.selectContact(contact)}).
   */
  @Test
  public void pressingChatLogsDoesNotKillTheProcess() {
    strip.selectContact(new Contact("Bob", "B", java.util.UUID.randomUUID().toString(), 7, false));
    assertNotNull("precondition: a contact must be chosen, or the button does nothing at all",
        strip.chosenContactForTest());

    strip.findViewById(R.id.e2ee_button_chat_logs).performClick();   // must not throw

    assertEquals("and the log screen still opens, empty, which is the truth about it",
        View.VISIBLE, strip.findViewById(R.id.e2ee_messages_list_wrapper).getVisibility());
  }

  /** The Encrypt button, one layer behind the other three. */
  @Test
  public void pressingEncryptDoesNotKillTheProcess() {
    strip.selectContact(new Contact("Bob", "B", java.util.UUID.randomUUID().toString(), 7, false));
    ((EditText) strip.findViewById(R.id.e2ee_input_field)).setText("the meeting is at nine");
    theMessengerPostsAKeyBundle();   // re-enables the button
    ShadowToast.reset();

    strip.findViewById(R.id.e2ee_button_encrypt).performClick();   // must not throw

    final String toast = ShadowToast.getTextOfLatestToast();
    assertNotNull("a send that cannot happen must be reported, not silent", toast);
    assertTrue("and it must say the message was not encrypted - what must NOT happen is the "
            + "plaintext being handed to the messenger. Shown: " + toast,
        toast.contains("could not be encrypted"));
    assertEquals("the plaintext must still be in the compose box and nowhere else",
        "the meeting is at nine",
        ((EditText) strip.findViewById(R.id.e2ee_input_field)).getText().toString());
  }
}
