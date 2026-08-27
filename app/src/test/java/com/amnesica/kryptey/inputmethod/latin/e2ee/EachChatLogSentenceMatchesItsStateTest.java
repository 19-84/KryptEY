package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException;
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
 * The chat-log screen has two ways of showing nothing, and they must not swap.
 *
 * <p>"There are no saved messages for this contact" and "your saved messages cannot be opened —
 * they are still on this device" describe opposite situations, and the screen looks identical in
 * both: an empty list under "Message log with: X". The sentence beside it is the ONLY thing that
 * distinguishes them, which makes it load-bearing rather than copy.
 *
 * <p>They swapped. A round meant to fix the second case put the unreadable-log sentence on the
 * empty-history arm as well, reasoning from the exception's name — {@code UnknownContactException}
 * sounds like a lookup that failed. It is not: reaching it means the log was opened, read, and
 * held nothing for this contact, because an unreadable log throws {@code ChatLogUnavailableException}
 * out of the same call and a null account returns null without throwing. So the app told every
 * newly added contact, and every user who had just successfully cleared their history, that their
 * messages "are still on this device" and to "not assume anything here has been removed".
 *
 * <p>Nothing failed, because no test tied either sentence to a state — both arms only had to not
 * crash. This ties them. It asserts the exact text on purpose: the point is which of two sentences
 * a user reads, so a test satisfied by either is not testing the thing that broke.
 */
@RunWith(RobolectricTestRunner.class)
public class EachChatLogSentenceMatchesItsStateTest {

  /** Both sentences, read off the class so a rewording moves them together. */
  private static String sentence(final String field) throws Exception {
    final java.lang.reflect.Field f = E2EEStripView.class.getDeclaredField(field);
    f.setAccessible(true);
    return (String) f.get(strip);
  }

  private static E2EEStripView strip;
  private Contact bob;
  private Account account;

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
    account = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the session must establish",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.selectContact(bob);
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private void openTheChatLog() {
    final View button = strip.findViewById(R.id.e2ee_button_chat_logs);
    assertNotNull("the chat-log button must exist, or this test presses nothing", button);
    button.performClick();
  }

  /**
   * A contact with no history yet — the state every contact starts in.
   *
   * <p>And the state a user is in immediately after a deletion that worked, which is why the wrong
   * sentence here is worse than merely inaccurate: it tells someone their removal may not have
   * happened.
   */
  @Test
  public void anEmptyHistoryIsCalledAnEmptyHistory() throws Exception {
    openTheChatLog();

    assertEquals("a contact with no messages must be told their history is empty, not that it "
            + "cannot be opened. Every contact is in this state before the first message, and so "
            + "is every user who has just cleared their history - telling them their plaintext is "
            + "'still on this device' asserts something false about data they asked to be rid of.",
        sentence("INFO_NO_SAVED_MESSAGES"), ShadowToast.getTextOfLatestToast());
  }

  /**
   * And a log that genuinely will not open still says so.
   *
   * <p>The other half of the pair. Without this, restoring the empty-history sentence could simply
   * have put the original defect back — one sentence on both arms, in the other direction.
   */
  @Test
  public void alogThatCannotBeOpenedSaysThatInstead() throws Exception {
    account.setMessageLogLoader(() -> {
      throw new ChatLogUnavailableException("the stored chat log could not be read");
    });

    openTheChatLog();

    assertEquals("a log that cannot be decrypted must not be reported as an empty history: the "
            + "user's plaintext is still on disk, and someone who believes it is gone stops trying "
            + "to clean it up",
        sentence("INFO_SAVED_MESSAGES_UNREADABLE"), ShadowToast.getTextOfLatestToast());
  }

  /**
   * The two sentences are actually different, so the pair above can fail.
   *
   * <p>If a later edit collapsed them into one constant, both tests would keep passing while the
   * distinction they exist to defend had disappeared.
   */
  @Test
  public void thetwoSentencesAreDistinguishable() throws Exception {
    assertNotNull(sentence("INFO_NO_SAVED_MESSAGES"));
    assertTrue("the two sentences must differ, or asserting which one the user reads proves "
            + "nothing", !sentence("INFO_NO_SAVED_MESSAGES")
            .equals(sentence("INFO_SAVED_MESSAGES_UNREADABLE")));
  }
}
