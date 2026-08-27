package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.RichInputMethodManager;
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
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Every button on the strip, pressed in a broken state, in one sweep.
 *
 * <p>This is an input method. An unchecked exception out of an {@code OnClickListener} kills the IME
 * process in whatever app the user happens to be typing in, and the states that produce one here —
 * an invalidated Keystore key, a corrupt stored value, a store that will not write — are
 * <em>persistent</em>. So the failure is not a crash, it is crash-on-tap in every app until
 * reinstall, with the keyboard vanishing mid-sentence and nothing naming the cause.
 *
 * <p>The strip carries eighteen click listeners and exactly two unchecked catches. The existing
 * crash test presses three buttons against one broken state, chosen by hand, and the choosing is the
 * weakness: a nineteenth listener added next month is covered by nobody, and the person adding it
 * has no reason to think about it. So this walks the inflated view hierarchy, finds every view that
 * actually has a listener attached, and presses all of them — which means a new button is covered
 * the day it is added rather than the day someone remembers.
 *
 * <p><b>What it does not claim.</b> Pressing a button and not crashing says nothing about whether
 * the button did the right thing; the files around this one are where that is asserted. This pins
 * one property, the one whose blast radius is the whole keyboard rather than one screen.
 */
@RunWith(RobolectricTestRunner.class)
public class NoButtonOnTheStripCanKillTheKeyboardTest {

  private E2EEStripView strip;
  private Account victim;
  private Contact bob;

  @Before
  public void setUp() throws Exception {
    buildEverything();
  }

  /**
   * A whole fresh world: new accounts, new session, new strip.
   *
   * <p>Called once per BUTTON, not once per test, and that is the correction that made this file
   * worth having. The first version pressed every button in hierarchy order against one strip, and
   * the first thing in that order is {@code e2ee_info_text}, whose listener is
   * {@code resetChosenContactAndInfoText} - so it cleared the chosen contact and all fifteen
   * buttons after it took their "no contact chosen" early return. Four green tests, pressing
   * nothing. Removing a real catch from the chat-log screen did not fail any of them.
   */
  private void buildEverything() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    RichInputMethodManager.init(app);

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: a real session, so the send and verify paths are live",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    final BaseInputConnection hostField = new BaseInputConnection(new View(app), true);
    strip.setRichInputConnection(new RichInputConnection(
        new android.inputmethodservice.InputMethodService() {
          @Override
          public android.view.inputmethod.InputConnection getCurrentInputConnection() {
            return hostField;
          }
        }));
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    // Not decoration: several paths return early with no chosen contact, and a button that returns
    // early cannot throw. Without this the sweep would press live controls that do nothing and pass
    // whatever the catches did - the exact hollowness a control run found in the first version of
    // the hand-written crash test beside this one.
    strip.selectContact(bob);
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_input_field))
        .setText("something to send");
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Every view under the strip that really has a listener attached. */
  private List<View> everyLiveButton() {
    final List<View> found = new ArrayList<>();
    collect(strip, found);
    return found;
  }

  private void collect(final View view, final List<View> into) {
    if (view.hasOnClickListeners()) into.add(view);
    if (view instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
    }
  }

  private String nameOf(final View view) {
    try {
      return view.getResources().getResourceEntryName(view.getId());
    } catch (final RuntimeException e) {
      return "id " + view.getId();
    }
  }

  /** What a test does to the fresh strip before the button under test is pressed. */
  private interface BreakIt {
    void apply() throws Exception;
  }

  /**
   * Press every button, each on a strip of its own, and report every one that threw.
   *
   * <p>One strip per button because buttons change the state the next button reads - screens
   * switch, the chosen contact clears, the compose box empties. Sharing a strip does not just make
   * the sweep weaker, it makes it silently vacuous, which is worse: it goes green either way.
   *
   * <p>Reporting all of them rather than the first, so a defect class shows its shape in one run.
   */
  private void pressEverythingAndReport(final String state, final BreakIt breakIt)
      throws Exception {
    final List<Integer> ids = new ArrayList<>();
    for (final View button : everyLiveButton()) ids.add(button.getId());
    assertTrue("this test walks the strip's view hierarchy for anything with a click listener; "
        + "finding almost none means the sweep has stopped sweeping and the greens below are "
        + "worthless. Found: " + ids.size(), ids.size() >= 14);

    final List<String> killed = new ArrayList<>();
    for (final int id : ids) {
      buildEverything();
      breakIt.apply();
      // Through the verify screen too, because that is where the reject listener - the only control
      // that un-pins a key - is attached. It does not exist until the screen has been loaded.
      strip.showVerifyContactForTest(bob);
      strip.showMainViewForTest();

      final View button = strip.findViewById(id);
      if (button == null) continue;
      try {
        button.performClick();
      } catch (final Throwable t) {
        killed.add(nameOf(button) + " threw " + t.getClass().getName() + ": " + t.getMessage());
      }
    }

    assertEquals("pressing these killed the input method process with " + state + ". This is an "
        + "IME: the exception does not land in a dialog, it takes the keyboard out of whatever app "
        + "the user is typing in, and every state below is persistent - so it is crash-on-tap "
        + "until reinstall:\n" + String.join("\n", killed), 0, killed.size());
  }

  /** The ordinary case, which must obviously survive. */
  @Test
  public void ahealthyStripSurvivesBeingPressedEverywhere() throws Exception {
    pressEverythingAndReport("a healthy store", () -> { });
  }

  /**
   * A chat log that will not decrypt.
   *
   * <p>One flipped byte in the sealed log, or a Keystore key invalidated by a credential change.
   * The account still loads and the keyboard still raises, because nothing on the raise path reads
   * the log — so the first sign of trouble is a button.
   */
  @Test
  public void anUnreadableChatLogDoesNotTakeTheKeyboardWithIt() throws Exception {
    pressEverythingAndReport("a chat log that cannot be decrypted",
        () -> victim.setMessageLogLoader(() -> {
          throw new ChatLogUnavailableException("the stored chat log could not be read");
        }));
  }

  /**
   * A store that loads and will not write.
   *
   * <p>The state that looks healthy: everything renders, and every trust decision the user makes is
   * discarded. The notices for that are asserted elsewhere; here the only question is whether the
   * buttons that discover it survive discovering it.
   */
  @Test
  public void astoreThatWillNotWriteDoesNotTakeTheKeyboardWithIt() throws Exception {
    pressEverythingAndReport("a store that will not write", () ->
        SignalProtocolMain.getInstance().setStorageHelperForTest(
            new StorageHelper(RuntimeEnvironment.getApplication(), (ctx, has) -> null) {
              @Override
              public boolean storeAllInformationInSharedPreferences(final Account account) {
                return false;
              }
            }));
  }

  /**
   * And with no account at all, which is what an unreadable store leaves behind.
   *
   * <p>Distinct from the two above: there is no account to dereference, and the paths that reach for
   * one were written when it could not be null. {@code getPreKeyBundle} dereferenced it on its first
   * statement, which is how the Invite button came to throw NullPointerException straight through
   * {@code View.performClick} on exactly this install.
   */
  @Test
  public void nostoredAccountDoesNotTakeTheKeyboardWithIt() throws Exception {
    pressEverythingAndReport("no account, which is what an unreadable store leaves behind", () -> {
      SignalProtocolMain.getInstance().setAccount(null);
      SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    });
  }
}
