package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

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
   * A bare rejection must not leave a false, unclearable warning one tap away.
   *
   * <p>Rejecting removes the identity, the pending change and the session, so there is no key at
   * that address at all. Tapping the row afterwards — the ordinary gesture, and the one the
   * warning's own last sentence invites — used to post {@code INFO_PINNED_AFTER_REJECT}, which
   * states as fact that "this IS a new key for that address".
   *
   * <p>Worse than being false: it was unclearable. Following its instruction to the verify screen
   * finds no fingerprint, so {@code clearFingerprintViews} disables Verify <em>and</em> Reject —
   * both deliberate responses physically unavailable — while the flag rides across strip rebuilds
   * and suppresses every routine banner from then on. The only exits were deleting the contact or
   * the attacker delivering another key.
   */
  @Test
  public void abareRejectionDoesNotWarnAboutAkeyThatDoesNotExist() throws Exception {
    assertTrue("precondition: rejection must succeed", SignalProtocolMain.rejectContactKey(bob()));
    assertTrue("precondition: the rejection must stand",
        SignalProtocolMain.wasKeyRejected(bob().getSignalProtocolAddress()));
    assertFalse("precondition: and nothing may be pinned at that address",
        SignalProtocolMain.hasPinnedKey(bob().getSignalProtocolAddress()));

    strip.selectContact(bob());

    final String banner =
        ((TextView) strip.findViewById(R.id.e2ee_info_text)).getText().toString();
    assertFalse("the strip claimed a new key had been pinned for a contact that has no key at "
            + "all - and neither Verify nor Reject is available to put that warning down: " + banner,
        banner.contains("new key for that address"));
  }

  /**
   * A bundle that is REFUSED must not be reported as a new key either.
   *
   * <p>The same wedge as the bare rejection, entered from a different door, and the door a messenger
   * can open. {@code buildSession} catches {@code InvalidKeyException} when a signed pre-key's
   * signature does not verify, logs, and returns false without saving an identity — and
   * {@code decrypt} discards that return value. So an envelope that pins nothing used to raise
   * "this IS a new key for that address" anyway, at an address holding none, with the verify screen
   * it points at showing no fingerprint.
   *
   * <p>Staged here the cheap way: an ordinary message relabelled as a pre-key envelope. It cannot
   * parse as one, so nothing pins — which is exactly the state the signature failure produces, and
   * is one of the entry points the review named.
   */
  @Test
  public void arefusedBundleAtArejectedAddressIsNotReportedAsAnewKey() throws Exception {
    assertTrue(SignalProtocolMain.rejectContactKey(bob()));
    assertFalse("precondition: nothing may be pinned after a rejection",
        SignalProtocolMain.hasPinnedKey(bob().getSignalProtocolAddress()));

    // Something that declares itself a pre-key envelope and is not one.
    final MessageEnvelope refused = new MessageEnvelope(
        new byte[] {9, 9, 9, 9}, org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE,
        peerAddress.getName(), peerAddress.getDeviceId());
    strip.processIncomingEnvelopeForTest(refused);

    assertFalse("nothing was pinned, so the strip must not claim a new key arrived",
        SignalProtocolMain.hasPinnedKey(bob().getSignalProtocolAddress()));
    final String banner =
        ((TextView) strip.findViewById(R.id.e2ee_info_text)).getText().toString();
    // Positive control. Without it, a fixture whose envelope failed to resolve to a contact takes
    // the sender == null arm, never writes the banner, and this test passes asserting nothing -
    // the same vacuity the no-fingerprint test guards against explicitly.
    assertTrue("the envelope must have reached the arm under test, or the absence below means "
        + "nothing: " + banner, banner.contains("Detected contact"));
    assertFalse("a refused bundle was reported as a new key at an address that holds none: "
        + banner, banner.contains("new key for that address"));
  }

  /**
   * And whatever warning does stand, the user always has one deliberate response.
   *
   * <p>The invariant behind both wedges. Verify and Reject both live on the verify screen and both
   * go down when there is no fingerprint — which is exactly when no key is pinned. Any warning that
   * can stand in that state was a dead end: told something is wrong, sent here by the warning's own
   * text, nothing to press, and the banner suppressing every routine message from then on.
   */
  @Test
  public void awarningWithNoFingerprintStillLeavesRejectAvailable() throws Exception {
    assertTrue(SignalProtocolMain.rejectContactKey(bob()));
    strip.setWarningMessageAboutForTest("Careful: something is wrong with Bob's key.", bob());

    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    final View verify = strip.findViewById(R.id.e2ee_verify_contact_verify_button);
    final View reject = strip.findViewById(R.id.e2ee_verify_contact_reject_button);
    assertNotNull(verify);
    assertNotNull(reject);
    assertFalse("Verify must stay down - there is no number on screen to confirm",
        verify.isEnabled());
    assertTrue("Reject must stay available, or a standing warning with no pinned key is a dead end "
        + "the user cannot leave", reject.isEnabled());
  }

  /**
   * Reject with nothing pinned must not claim it forgot a stored key.
   *
   * <p>Reject is deliberately available in that state — it is the only deliberate response left
   * when a warning stands and there is no number to compare. But its confirmation opened "Forgot
   * the stored key for %s", and in that state nothing was stored. The action is real and worth
   * having; only the sentence was wrong.
   */
  @Test
  public void rejectingWithNothingPinnedDoesNotClaimAkeyWasForgotten() throws Exception {
    assertTrue(SignalProtocolMain.rejectContactKey(bob()));
    assertFalse("precondition: nothing may be pinned",
        SignalProtocolMain.hasPinnedKey(bob().getSignalProtocolAddress()));
    strip.setWarningMessageAboutForTest("Careful: something is wrong with Bob's key.", bob());

    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();
    org.robolectric.shadows.ShadowToast.reset();
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();

    final String toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
    assertNotNull("pressing Reject must say something", toast);
    assertFalse("it claimed to have forgotten a stored key when there was none: " + toast,
        toast.contains("Forgot the stored key"));
    // The state here is "you already rejected the key that was stored", NOT "nothing was ever
    // stored". This test used to assert only the words "no stored key", which both readings
    // satisfy - so when the message was rewritten to say "none had been stored yet" it stayed
    // green while telling the user the opposite of the truth. A key was stored; they are the
    // person who reported it as wrong.
    assertTrue("Reject must say the key was already forgotten by the rejection, not that none was "
        + "ever stored: " + toast, toast.contains("already forgotten"));
    assertFalse("and must not deny that a key was ever stored for this contact: " + toast,
        toast.contains("none had been stored yet"));
  }

  /**
   * The other no-pin state, which the same message used to describe with the same words.
   *
   * <p>"Nothing is pinned" covers two opposite situations and they need opposite sentences. Here
   * nothing ever was pinned, so claiming the app was handed a wrong key would fabricate a security
   * event - and that is exactly what the first version of this branch did, having inherited the
   * sentence from the message for a real rejection.
   */
  @Test
  public void rejectingAcontactThatNeverHadAkeySaysSoAndInventsNothing() throws Exception {
    final Contact stranger = new Contact("Dave", "Smith", "dave-address", 5, false);
    victim.getContactList().add(stranger);
    assertFalse("precondition: nothing pinned",
        SignalProtocolMain.hasPinnedKey(stranger.getSignalProtocolAddress()));
    assertFalse("precondition: and no rejection on record either",
        SignalProtocolMain.wasKeyRejected(stranger.getSignalProtocolAddress()));

    strip.setWarningMessageAboutForTest("Careful: something is wrong.", stranger);
    strip.selectContact(stranger);
    strip.loadFingerprintInVerifyContactView();
    org.robolectric.shadows.ShadowToast.reset();
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();

    final String toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
    assertNotNull("pressing Reject must say something", toast);
    assertTrue("it must say no key had been stored: " + toast,
        toast.contains("none had been stored yet"));
    assertFalse("and must not claim the app was ever given a wrong key for them - nothing was ever "
        + "offered at this address, so that is a security event that did not happen: " + toast,
        toast.contains("wrong key"));
  }

  /**
   * A deliberate response about one contact must not put down a warning about another.
   *
   * <p>Reject and Verify are the two controls that mean "I have dealt with this", and both cleared
   * the banner unconditionally — while {@code removeContact} had been scoped to the address for
   * exactly this reason. The duplicate-name warning is never re-asserted, so clearing it from the
   * wrong screen loses it for good.
   */
  @Test
  public void rejectingOneContactLeavesAwarningAboutAnotherStanding() throws Exception {
    final Contact stranger = new Contact("Erin", "Smith", "erin-address", 6, false);
    victim.getContactList().add(stranger);

    strip.setWarningMessageAboutForTest("Careful: something is wrong with Bob's key.", bob());

    // Reject is live for Erin only because a warning stands - the enablement is not scoped to the
    // contact the warning is about, which is what makes this reachable in one tap.
    strip.selectContact(stranger);
    strip.loadFingerprintInVerifyContactView();
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();

    // Asserted through mayOverwriteInfoBanner rather than through the banner's text, because
    // clearing a standing warning does not repaint the banner - the words stay on screen either
    // way, so reading them proves nothing. What actually changes is whether the next passive,
    // messenger-driven event is allowed to paint over the warning, and that is the consequence
    // that matters: the first version of this test read the text and passed against the unfixed
    // code, which is the same hollow-control mistake this file keeps finding elsewhere.
    assertFalse("a warning about Bob must survive a deliberate response about Erin. Once it stops "
            + "standing, the next clipboard event overwrites it - and nothing re-asserts the "
            + "duplicate-name warning, so it is gone for good.",
        strip.mayOverwriteInfoBanner());
  }

  /**
   * And the verify screen itself stops describing a rejected address as untouched.
   *
   * <p>In the no-pin-after-rejection cell the screen said "No security number is available for this
   * contact yet. Ask them for a key bundle first." — which describes an address nothing has ever
   * happened at, on the one screen where the user decides whether to reject again.
   */
  @Test
  public void theverifyScreenAfterArejectionDoesNotReadAsUntouched() throws Exception {
    assertTrue(SignalProtocolMain.rejectContactKey(bob()));
    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    final String shown = verifyScreenText();
    assertFalse("the screen must not say a number is unavailable 'yet' at an address whose key the "
        + "user already rejected: " + shown, shown.contains("available for this contact yet"));
    assertTrue("it must name the rejection instead: " + shown,
        shown.contains("did not match"));
    // And must not borrow the pinned-key wording: the digits on this screen are blank, so telling
    // the user to compare "the number below" is a different false claim in the same cell.
    assertFalse("it must not point at a number that is not on screen: " + shown,
        shown.contains("number below"));
  }

  /** But once a key IS pinned there again, the warning is real and must fire. */
  @Test
  public void arejectionFollowedByAnewKeyStillWarnsOnSelection() throws Exception {
    rejectThenLetItBeRePinned();
    assertTrue("precondition: a key must be pinned again",
        SignalProtocolMain.hasPinnedKey(bob().getSignalProtocolAddress()));

    strip.selectContact(bob());

    final String banner =
        ((TextView) strip.findViewById(R.id.e2ee_info_text)).getText().toString();
    assertTrue("a key pinned at an address the user rejected is exactly what this warning is for: "
        + banner, banner.contains("new key for that address"));
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
