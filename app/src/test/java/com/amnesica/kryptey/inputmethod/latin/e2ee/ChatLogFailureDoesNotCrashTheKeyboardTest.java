package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * An unreadable chat log must cost the user their history, not their keyboard.
 *
 * <p>Refusing to present an unreadable log as an empty one is right — an account that believes
 * there is no history gets that written to disk by the next save, and the log is plaintext the user
 * cannot recover from anywhere else. But the first version of that refusal threw an unchecked
 * exception out of three {@code OnClickListener}s, and this is an input method: an uncaught
 * exception kills the IME process in whatever app the user happens to be typing in. The condition
 * that produces it is persistent — an invalidated Keystore key, a corrupt value — so it would have
 * been crash-on-tap, in every app, until reinstall. The account loads fine and the keyboard raises
 * normally, because nothing on the raise path reads the log, so the first sign of trouble would be
 * the keyboard vanishing mid-sentence.
 *
 * <p>The codebase already holds this line elsewhere and says why:
 * {@code getClassFromSharedPreferences} catches {@code RuntimeException} precisely so a corrupt
 * stored value cannot crash the keyboard on every raise. This is the same rule applied to the same
 * data one layer up.
 *
 * <p>Each test drives a real button on a real inflated strip, because the property is about what
 * escapes a listener, and a test that called the method directly would not be testing that.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatLogFailureDoesNotCrashTheKeyboardTest {

  private E2EEStripView strip;
private Contact bob;
  /** Kept so the receive-path test can write as the peer. */
  private Account peerAccount;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // A real session, because a send with no session returns before it ever reaches the log - and
    // a control run showed that a send-button test without one passes whether or not the catch
    // exists. Same two-account setup the other strip tests use.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAccount = peer;
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the session must establish, or the send never reaches the log",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);

    // Exactly what a store holding a log it cannot decrypt produces.
    account.setMessageLogLoader(() -> {
      throw new ChatLogUnavailableException("the stored chat log could not be read");
    });

    final BaseInputConnection hostField = new BaseInputConnection(new View(app), true);
    final InputMethodService messengerSide = new InputMethodService() {
      @Override
      public InputConnection getCurrentInputConnection() {
        return hostField;
      }
    };

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setRichInputConnection(new RichInputConnection(messengerSide));
    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) { }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    // Choosing a contact is not decoration. Both the chat-log screen and the send path return
    // early when chosenContact is null, so without this the two button tests press controls that
    // do nothing and pass whether or not the catches exist - which is exactly what the control run
    // showed the first version of this file doing.
    strip.selectContact(bob);
  }

  /** The strip really is in the state where those buttons do something. */
  @Test
  public void aacontactIsChosenSoTheButtonsUnderTestAreLive() {
    assertNotNull("a contact must be chosen, or the chat-log and send paths return early and this "
        + "file tests nothing", strip.chosenContactForTest());
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Opening the chat-log screen. */
  @Test
  public void thechatLogButtonDoesNotKillTheKeyboard() {
    final View button = strip.findViewById(R.id.e2ee_button_chat_logs);
    assertNotNull("the chat-log button must exist, or this test presses nothing", button);
    button.performClick();
  }

  /** Pressing Encrypt, which records the plaintext as part of sending. */
  @Test
  public void thesendButtonDoesNotKillTheKeyboard() {
    final EditText compose = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose field must inflate, or this test measures nothing", compose);
    compose.setText("a message that cannot be recorded");

    final View button = strip.findViewById(R.id.e2ee_button_encrypt);
    assertNotNull(button);
    button.performClick();
  }

  /** Deleting a contact, which has to sweep that contact's messages out of the log. */
  @Test
  public void deletingAcontactDoesNotKillTheKeyboard() {
    strip.removeContact(bob);
  }

  /**
   * A deletion that was REFUSED must not put a standing warning down.
   *
   * <p>Deleting the contact a warning names is the one deliberate response the verify screen cannot
   * offer once the row is gone, so it clears the warning — but the clear ran before the deletion,
   * and the deletion can be refused. Sweeping that contact's messages needs the chat log, so with
   * an unreadable log {@code removeContact} throws and the contact list is untouched.
   *
   * <p>That left the worst of both: the contact still there, the flag down, and the warning text
   * still on screen. The screen says "warning" while the model says none, so
   * {@code mayOverwriteInfoBanner} answers true and the messenger's next clipboard event replaces
   * the app's only lasting warning with "Keybundle detected". A one-tap warning eraser for anyone
   * whose log will not read, which does not even delete the contact.
   */
  @Test
  public void arefusedDeletionDoesNotClearTheWarning() {
    final String warning = "Careful: someone offered a different key for Bob.";
    strip.setWarningMessageAboutForTest(warning, bob);

    strip.removeContact(bob);   // refused: the log cannot be read

    final android.widget.TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);
    assertTrue("the deletion was refused, so the warning it would have answered must still stand: "
        + banner.getText(), banner.getText().toString().contains(warning));

    // And the flag must still be up, or the next clipboard event overwrites it.
    strip.onClipboardHoldsDecryptableItemForTest();
    assertTrue("a refused deletion left the model believing no warning stands, so ordinary "
            + "clipboard traffic erased it: " + banner.getText(),
        banner.getText().toString().contains(warning));
  }

  /**
   * And the harness is real: the log genuinely refuses to load.
   *
   * <p>Without this the three tests above would pass against a loader that quietly worked, and this
   * file would be pressing buttons and asserting nothing.
   */
  @Test
  public void athelogUnderTestReallyIsUnreadable() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    try {
      account.getUnencryptedMessages();
      throw new AssertionError("the fixture must refuse to load the log");
    } catch (final ChatLogUnavailableException expected) {
      assertTrue(expected.getMessage().contains("could not be read"));
    }
  }

  /**
   * The fourth place, and the one the exception's own javadoc did not know about.
   *
   * <p>{@code ChatLogUnavailableException} says "the three places that must survive it", and this
   * file was the guarantee for those three: the log screen, the send, and deleting a contact. The
   * receive path is a fourth. {@code SignalProtocolMain.decrypt} files every incoming message in
   * the log, has no catch, and the throw was absorbed two frames up by a catch-all added for an
   * unrelated JNI reason.
   *
   * <p>Two things went wrong there, and the second is the serious one. The message had
   * <em>decrypted</em>; the user was shown "decryption failed" for a message the app read
   * perfectly. And the persist below the throw never ran, so the advanced ratchet - and on the
   * PREKEY arm the replaced one-time pre-key - stayed in memory.
   *
   * <p>One flipped byte in the sealed log makes it permanent: GCM fails with no key needed, so
   * every incoming message reads as a decryption failure forever. This codebase's own analysis is
   * that the generic decryption-failure advice drives users to delete the contact and re-invite,
   * which is a key-substitution window. A corrupted chat log became an amplifier for substitution,
   * through advice given about the wrong problem.
   */
  @Test
  public void areceivedMessageSurvivesAnunreadableChatLog() throws Exception {
    // Bob writes to the victim. Built as Bob against the victim's own bundle.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(peerAccount);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope fromBob = SignalProtocolMain.encryptMessage("the meeting moved", victimAddress);
    assertNotNull(fromBob);
    SignalProtocolMain.getInstance().setAccount(victim);

    final String plaintext = SignalProtocolMain.decryptMessage(fromBob,
        ProtocolAddresses.of(bob.getSignalProtocolAddressName(), bob.getDeviceId()));

    assertEquals("an unreadable chat log must not turn a message that decrypted into a decryption "
        + "failure. The plaintext is right here; refusing to hand it over sends the user toward "
        + "delete-and-re-invite, which is a key-substitution window opened by advice about a "
        + "different problem.", "the meeting moved", plaintext);
    assertTrue("and the app must record that the LOG write failed, not that decryption did - the "
        + "two need opposite advice", SignalProtocolMain.lastChatLogWriteFailed());
  }

  /**
   * A log WRITE that does not land is the same outcome for the user as a log that cannot be read.
   *
   * <p>The flag exists so the app can say "this message was read, but it could not be added to your
   * saved history" instead of "decryption failed" — because the second sends the user toward
   * delete-and-re-invite, which is a key-substitution window. It only ever covered the READ half:
   * {@code StorageHelper.storeMessageLog} swallowed its exception and the caller then returned true
   * on the strength of the account batch alone, so a failed commit produced the identical outcome —
   * message delivered, absent from the history — with the notice never firing.
   */
  @Test
  public void afailedChatLogWriteIsReportedTheSameWayAnUnreadableOneIs() throws Exception {
    // A log that reads fine, so only the write can fail.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(java.util.ArrayList::new);

    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(peerAccount);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope fromBob =
        SignalProtocolMain.encryptMessage("the meeting moved", victimAddress);
    assertNotNull(fromBob);
    SignalProtocolMain.getInstance().setAccount(victim);

    // A storage helper whose LOG write fails while everything else succeeds.
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, hasExistingData) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });

    final String plaintext = SignalProtocolMain.decryptMessage(fromBob,
        ProtocolAddresses.of(bob.getSignalProtocolAddressName(), bob.getDeviceId()));

    assertEquals("the message must still be delivered", "the meeting moved", plaintext);
    assertTrue("a log write that did not land must be reported the same way an unreadable log is - "
            + "the user's history is missing a message either way, and the alternative is the app "
            + "saying nothing at all about it",
        SignalProtocolMain.lastChatLogWriteFailed());
  }

  /**
   * The send half of "delivered but not recorded".
   *
   * <p>The receive half reported a failed log write and the send half did not, so a message could go
   * to the messenger and be missing from the history with nothing said. Arguably more visible than
   * the incoming case — the user watched themselves send it — and equally unexplained.
   */
  @Test
  public void afailedChatLogWriteOnSendIsReported() throws Exception {
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(java.util.ArrayList::new);

    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, hasExistingData) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });

    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("going out now",
        ProtocolAddresses.of(bob.getSignalProtocolAddressName(), bob.getDeviceId()));

    assertNotNull("the message must still be sent - the storage failure is not the send failing",
        sent);
    assertTrue("a sent message that did not reach the log must be reported, or it disappears from "
            + "the history with the ciphertext already handed to the messenger and no explanation",
        SignalProtocolMain.lastChatLogWriteFailed());
  }
}
