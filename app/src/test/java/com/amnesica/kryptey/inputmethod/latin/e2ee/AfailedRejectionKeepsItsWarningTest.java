package com.amnesica.kryptey.inputmethod.latin.e2ee;

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

/**
 * A rejection that did not reach disk must not take its warning with it.
 *
 * <p>Reject is the control that un-pins a key: the only correct action when trust-on-first-use
 * pinned an impostor, and the only one that does not assume the pin is genuine. It cleared the
 * standing warning and caution <em>before</em> writing, unconditionally.
 *
 * <p>So when the account write failed, the toast said so for about three and a half seconds while
 * the persistent surface had already been wiped, and nothing put it back. On the next
 * {@code reloadAccount} — which the host app can force with a theme change — the rejected key is
 * pinned again and {@code rejectedAddresses} is empty. That is precisely the silent
 * trust-on-first-use {@code markKeyRejected} exists to prevent, with the app's only lasting record
 * of it gone.
 *
 * <p>{@code verifyContact} learned to roll back on a failed write first, {@code removeContact} next.
 * This was the last member of the family clearing unconditionally.
 */
@RunWith(RobolectricTestRunner.class)
public class AfailedRejectionKeepsItsWarningTest {

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
    strip.selectContact(bob);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
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

  private void pressReject() {
    strip.showVerifyContactForTest(bob);
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();
  }

  /**
   * Asserted on the model, not on the painted banner.
   *
   * <p>{@code clearStandingWarning} does not repaint — it lowers the flag and leaves the text on
   * screen until something else writes there. So reading the banner would pass whether or not the
   * warning was cleared, which is a control this project has already been caught writing twice. The
   * flag is the decision; the clipboard test below is what proves the screen follows it.
   */
  @Test
  public void arejectionThatDidNotLandLeavesTheWarningStanding() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteFail();

    pressReject();

    assertTrue("the rejection is not on disk, so the next reloadAccount pins that key again with "
            + "rejectedAddresses empty. The warning is the only lasting record that anything was "
            + "wrong, and it must not be the thing that goes.",
        strip.warningIsStandingForTest());
  }

  /** And the model agrees with the screen, or the messenger erases it with ordinary traffic. */
  @Test
  public void andTheNextClipboardEventCannotErodeIt() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    makeTheWriteFail();

    pressReject();
    strip.onClipboardHoldsDecryptableItemForTest();

    assertTrue("the flag must still be up, or the next clipboard event overwrites the warning "
            + "with 'Keybundle detected': " + banner(), banner().contains(WARNING));
  }

  /** A rejection that landed still clears it, or the deliberate response has no effect. */
  @Test
  public void arejectionThatLandsStillClearsTheWarning() {
    strip.setWarningMessageAboutForTest(WARNING, bob);
    TestStores.writesLand();

    pressReject();

    assertFalse("rejecting the key a warning is about is the deliberate response to it; a warning "
            + "that survives its own resolution is one the user can never put down.",
        strip.warningIsStandingForTest());
  }
}
