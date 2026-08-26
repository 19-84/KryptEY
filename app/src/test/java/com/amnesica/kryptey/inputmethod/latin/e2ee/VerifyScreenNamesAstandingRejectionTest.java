package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
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
 * The screen where the irreversible decision is made must say what that decision undoes.
 *
 * <p>Pressing Verify clears a standing rejection — {@code rejectedAddresses} is documented as
 * retired only by a fresh comparison, and {@code isContactKeyTrustworthy} ranks a standing rejection
 * <em>above</em> a verified badge. Yet the verify screen said nothing about one. It carried a notice
 * for a pending identity change and nothing for a rejection, so the user reached the one screen that
 * un-does their earlier "these numbers do not match" and read only the ordinary comparison advice.
 *
 * <p>The reason the pending-change notice exists — "tell the user a key was offered BEFORE they
 * compare, so they compare attentively" — applies here at least as strongly.
 */
@RunWith(RobolectricTestRunner.class)
public class VerifyScreenNamesAstandingRejectionTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: a key must be pinned first",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
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

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private String verifyScreenText() {
    final TextView view = strip.findViewById(R.id.e2ee_verify_contact_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /** Reject the key, then let the peer's bundle be re-pinned, which is the attacker's move. */
  private void rejectThenLetItBeRePinned() throws Exception {
    assertTrue("precondition: rejection must succeed", SignalProtocolMain.rejectContactKey(bob()));
    assertTrue("precondition: the rejection must stand",
        SignalProtocolMain.wasKeyRejected(bob().getSignalProtocolAddress()));
    assertTrue("precondition: the bundle must be re-pinned, which is what leaves a fingerprint to "
            + "compare on the verify screen",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));
  }

  @Test
  public void theverifyScreenSaysAkeyWasPreviouslyRefused() throws Exception {
    rejectThenLetItBeRePinned();

    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    final String shown = verifyScreenText();
    assertTrue("the verify screen must say a key for this contact was previously refused - "
            + "pressing Verify here is what clears that refusal, and the user was being asked to "
            + "do it without being told what it undoes. Shown: " + shown,
        shown.contains("did not match") || shown.contains("refused"));
    assertTrue("and it must say that confirming clears the refusal, since that is the consequence: "
        + shown, shown.contains("clears"));
  }

  /** The ordinary case is unchanged: no rejection, no extra notice. */
  @Test
  public void averifyScreenWithNoRejectionKeepsTheOrdinaryAdvice() {
    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    final String shown = verifyScreenText();
    assertFalse("a contact with no rejection must not be told one stands: " + shown,
        shown.contains("did not match"));
    assertTrue("and must still get the comparison advice: " + shown,
        shown.contains("read the numbers above out to them"));
  }

  /**
   * When a change is pending AND a rejection stands, the screen says both.
   *
   * <p>One extra post reaches this state: reject, let the attacker's bundle be re-pinned, then let
   * them post again so a change is recorded pending. Confirming here calls both
   * {@code clearRejection} and {@code dismissIdentityChange} — so a screen showing only the
   * pending-change text tells the user confirming dismisses one warning while it silently retires
   * the refusal as well. That is the omission the rejection notice was added to close, reappearing
   * in the state that needs it most.
   */
  @Test
  public void bothNoticesAppearWhenAchangeIsPendingAndArejectionStands() throws Exception {
    rejectThenLetItBeRePinned();

    // One more forged bundle, from a different key, so a change is recorded as pending.
    SignalProtocolMain.initialize(null);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(attackerBundle), peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(bob().getSignalProtocolAddress()));
    assertTrue("precondition: and the rejection must still stand",
        SignalProtocolMain.wasKeyRejected(bob().getSignalProtocolAddress()));

    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    final String shown = verifyScreenText();
    assertTrue("the pending change must still be named: " + shown,
        shown.contains("offered a different key"));
    assertTrue("and so must the standing refusal, because confirming clears that too: " + shown,
        shown.contains("did not match") && shown.contains("clears"));
  }

  /**
   * And the buttons come down for a contact with no pinned key.
   *
   * <p>They are members that persist across selections and were only ever enabled, never disabled —
   * so viewing a pinned contact and then one with no key left Verify and Reject live over blanked
   * digits. The store refuses the press, which is what actually holds the line; offering a control
   * that cannot work is still wrong.
   */
  @Test
  public void theverifyButtonsAreDisabledForAcontactWithNoPinnedKey() throws Exception {
    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();
    final View verify = strip.findViewById(R.id.e2ee_verify_contact_verify_button);
    final View reject = strip.findViewById(R.id.e2ee_verify_contact_reject_button);
    assertNotNull(verify);
    assertNotNull(reject);
    assertTrue("precondition: a pinned contact must enable them", verify.isEnabled());

    // Now a contact with no pin at all.
    final Contact stranger = new Contact("Carol", "Smith", "carol-address", 4, false);
    victim.getContactList().add(stranger);
    strip.selectContact(stranger);
    strip.loadFingerprintInVerifyContactView();

    // Proof we actually reached the no-fingerprint arm. Without this the test can pass by never
    // getting there - which is what a control run showed the first version doing.
    assertTrue("this test must reach the no-fingerprint branch, or it asserts nothing about it. "
        + "Shown: " + verifyScreenText(), verifyScreenText().contains("No security number"));

    assertFalse("Verify stayed live for a contact with no pinned key, over blanked digits",
        verify.isEnabled());
    assertFalse("and so did Reject", reject.isEnabled());
  }
}
