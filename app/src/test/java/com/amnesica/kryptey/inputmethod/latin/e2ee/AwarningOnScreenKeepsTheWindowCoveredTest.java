package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A warning naming a contact is sensitive whether or not that contact is still the recipient.
 *
 * <p>{@code FLAG_SECURE} was asked of {@code chosenContact} alone, on the stated premise that "every
 * main-view banner that names a contact is written on a path where it is set". Two events the host
 * app can force break that premise while leaving the banner exactly where it was:
 *
 * <ul>
 *   <li><b>Hiding the keyboard.</b> {@code onKeyboardHidden} calls {@code forgetChosenRecipient} — a
 *       recipient change to null. The warning text is deliberately untouched, because a standing
 *       warning the messenger can clear is not a warning.</li>
 *   <li><b>A configuration change.</b> {@code adoptState} restores the warning and the caution and
 *       deliberately does not restore {@code chosenContact}.</li>
 * </ul>
 *
 * <p>In both, the flag came down while the window displayed "Someone offered a different key for Bob
 * #a1b2" — who the user talks to, plus the tag distinguishing them from a second contact of the same
 * name, which is the pair the predicate's own javadoc gives as its reason for existing. Nothing
 * raises it again until a recipient is chosen, so the window is capturable for as long as the user
 * reads the warning.
 */
@RunWith(RobolectricTestRunner.class)
public class AwarningOnScreenKeepsTheWindowCoveredTest {

  private E2EEStripView strip;
  private Contact bob;
  private final AtomicBoolean secure = new AtomicBoolean(false);

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
    TestStores.writesLand();

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) {
        secure.set(sensitive);
      }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.showMainViewForTest();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    return String.valueOf(((android.widget.TextView)
        strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  @Test
  public void hidingTheKeyboardDoesNotUncoverAstandingWarning() {
    strip.setWarningMessageAboutForTest("Someone offered a different key for Bob.", bob);
    strip.selectContact(bob);

    strip.onKeyboardHidden();

    assertTrue("the warning is still the thing on screen and it names the contact: " + banner(),
        banner().contains("Bob"));
    assertTrue("so the window must still be covered. Hiding the keyboard is something any app can "
            + "do, and the recipient it clears is not what makes this screen sensitive - the "
            + "sentence naming the contact is.",
        secure.get());
  }

  @Test
  public void arebuildDoesNotUncoverAstandingWarning() {
    strip.setWarningMessageAboutForTest("Someone offered a different key for Bob.", bob);
    strip.selectContact(bob);

    final E2EEStripView.CarriedState carried = strip.surrenderState();
    final E2EEStripView rebuilt = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    rebuilt.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) {
        secure.set(sensitive);
      }
    }, rebuilt);
    rebuilt.adoptState(carried);

    final String shown = String.valueOf(((android.widget.TextView)
        rebuilt.findViewById(R.id.e2ee_info_text)).getText());
    assertTrue("precondition: the warning must survive the rebuild - that is deliberate: " + shown,
        shown.contains("Bob"));
    assertTrue("and so must the cover. A configuration change is something the host app can force, "
            + "and adoptState restores the warning without the recipient on purpose.",
        secure.get());
  }

  /**
   * And an ordinary caution does NOT hold the flag up forever.
   *
   * <p>The first fix for this counted every standing item. A caution goes up after every successful
   * contact add and comes down only when the user verifies, rejects or deletes that contact — so the
   * flag would be up whenever the keyboard was, from the first contact onward. A {@code FLAG_SECURE}
   * window blanks the entire system screenshot, so that silently breaks screenshots device-wide
   * during ordinary typing in every app, which is the opposite of the decision the predicate's own
   * javadoc records.
   *
   * <p>The residue is real and stated rather than hidden: a caution naming a contact is capturable
   * once the recipient has been forgotten. Warnings are the rarer, sharper case and the one the gap
   * was found in.
   */
  @Test
  public void anordinaryCautionDoesNotBlankScreenshotsForever() {
    strip.selectContact(bob);
    strip.setCautionForTest("Contact Bob Jones created. Compare the security number by voice.", bob);

    strip.onKeyboardHidden();

    assertTrue("with no recipient and no warning, ordinary typing must screenshot normally - the "
            + "flag being up here means every app on the device loses screenshots whenever this "
            + "keyboard is raised", !secure.get());
  }
}
