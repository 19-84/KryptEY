package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
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
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * "It could not be saved because the history cannot be opened" — said while the history opens.
 *
 * <p>One flag, {@code mLastChatLogWriteFailed}, is raised by two different failures: a log that
 * cannot be READ, and a log that reads fine but whose WRITE does not land. The notice behind it
 * named only the first. On the second the user can disprove it in one tap — the chat-log button
 * opens the history the toast just said cannot be opened, and the message it said was not saved is
 * sitting in it, because it is in memory and will vanish at the next raise.
 *
 * <p>That is worth a test rather than a wording pass, because the failure mode is not "the sentence
 * reads badly". It is that this app's whole trust surface is sentences: the only thing separating a
 * key substitution from an ordinary reconnection is a notice the user is asked to believe. A notice
 * they can catch out on a storage error is a notice they will not believe about a key.
 *
 * <p>So this drives the write-failure arm for real and asserts both halves: the notice fires, and
 * the state it describes is one where the history genuinely does open.
 */
@RunWith(RobolectricTestRunner.class)
public class AnoticeMayNotNameAcauseTheScreenDisprovesTest {

  private E2EEStripView strip;
  private Contact bob;
  private Account victim;
  private SignalProtocolAddress bobAddress;
  private MessageEnvelope fromBob;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    // The log READS perfectly. That is the whole point of this fixture.
    victim.setMessageLogLoader(ArrayList::new);

    // Bob writes to us.
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(peer);
    peer.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    fromBob = SignalProtocolMain.encryptMessage("the meeting moved", victimAddress);
    assertNotNull(fromBob);
    SignalProtocolMain.getInstance().setAccount(victim);

    // ...and only the log WRITE fails.
    SignalProtocolMain.getInstance().setStorageHelperForTest(
        new StorageHelper(app, (ctx, hasExistingData) -> null) {
          @Override
          public boolean storeAllInformationInSharedPreferences(final Account account) {
            return true;
          }

          @Override
          public boolean lastMessageLogWriteSucceeded() {
            return false;
          }
        });

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.selectContact(bob);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private static String sentence(final Object owner, final String field) throws Exception {
    final java.lang.reflect.Field f = E2EEStripView.class.getDeclaredField(field);
    f.setAccessible(true);
    return (String) f.get(owner);
  }

  /** The harness is real: this is the write half, and the read half is working. */
  @Test
  public void aathisIsAwriteFailureAndNotAreadFailure() throws Exception {
    assertEquals("precondition: the log must read fine, or this file is testing the other arm",
        0, SignalProtocolMain.getInstance().getAccount().getUnencryptedMessages().size());
    // And the write is what fails: delivering a message raises the flag even though the read above
    // succeeded. Asserted through the same call the notice is driven by, rather than by reading the
    // stub back - a stub that agrees with itself proves nothing about the path.
    assertNotNull(SignalProtocolMain.decryptMessage(fromBob, bobAddress));
    assertTrue("precondition: the write must be the thing that fails",
        SignalProtocolMain.lastChatLogWriteFailed());
  }

  /**
   * The notice must not claim the history cannot be opened, because it can.
   *
   * <p>Asserted against the state rather than against a wording: the test opens the chat log in the
   * same state the notice describes, and demands the app not contradict itself between the two.
   */
  @Test
  public void thenoticeDoesNotClaimSomethingTheNextTapDisproves() throws Exception {
    final String plaintext = SignalProtocolMain.decryptMessage(fromBob, bobAddress);
    assertEquals("precondition: the message must be delivered", "the meeting moved", plaintext);
    assertTrue("precondition: the failed log write must be recorded, or there is no notice to check",
        SignalProtocolMain.lastChatLogWriteFailed());

    final String notice = sentence(strip, "INFO_MESSAGE_NOT_SAVED");

    // The claim under test, in the state that would falsify it.
    ShadowToast.reset();
    final View chatLog = strip.findViewById(R.id.e2ee_button_chat_logs);
    assertNotNull(chatLog);
    chatLog.performClick();
    final String whatTheLogScreenSaid = ShadowToast.getTextOfLatestToast();
    assertFalse("the chat log opens in this state - it did not report itself unreadable - so a "
            + "notice saying the history 'cannot be opened' is disprovable in one tap. This app's "
            + "trust surface is sentences; one the user can catch out about storage is one they "
            + "will not believe about a key.",
        sentence(strip, "INFO_SAVED_MESSAGES_UNREADABLE").equals(whatTheLogScreenSaid));

    assertFalse("the notice for a failed WRITE must not name a failed READ as its cause: "
            + notice, notice.contains("cannot be opened"));
  }

  /**
   * And it still says the thing that IS true on both arms.
   *
   * <p>The fix for a false cause clause is to drop the clause, not the notice — the user must still
   * learn that the message they just read will not be in their history.
   */
  @Test
  public void thenoticeStillSaysTheMessageIsMissingFromTheHistory() throws Exception {
    final String notice = sentence(strip, "INFO_MESSAGE_NOT_SAVED");
    assertTrue("the notice must still tell the user the record is missing: " + notice,
        notice.contains("could not be added to your saved history"));
    assertTrue("and must still say the message itself is fine, so nobody re-invites over a storage "
            + "error: " + notice, notice.contains("nothing needs to be sent again"));
  }
}
