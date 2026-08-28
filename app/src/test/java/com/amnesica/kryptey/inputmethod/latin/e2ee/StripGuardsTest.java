package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Guards inside {@code E2EEStripView} that no test could reach.
 *
 * <p>A review of this file found that SEVEN mutations survive the whole suite, including deleting
 * the substitution warning outright. The cause was not subtlety - it was that nothing had ever
 * inflated the view, so every guard in the 1300 lines holding the user's decisions was defended by
 * nothing. These drive the real methods.
 */
@RunWith(RobolectricTestRunner.class)
public class StripGuardsTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // Peer and attacker minted before the victim, so initialize(null) still creates fresh
    // identities rather than reloading.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  private TextView infoField() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view;
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  /**
   * The substitution warning must actually be shown.
   *
   * <p>Making {@code warnIfIdentityChanged} return false unconditionally - deleting the entire
   * warning - survived all 624 tests before this. The trust model spends most of its effort making
   * sure a substituted key is refused AND that the user is told; the second half had no coverage at
   * the point where it reaches a screen.
   */
  @Test
  public void asubstitutionIsWarnedAboutOnScreen() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    assertTrue("warnIfIdentityChanged must report that it warned", strip.warnIfIdentityChanged(bob()));

    final String shown = infoField().getText().toString();
    assertTrue("the banner must name the contact and say the key was refused: " + shown,
        shown.contains("Bob") && shown.toLowerCase().contains("refused"));
  }

  /** And it must NOT warn when nothing is pending - a warning that always fires teaches dismissal. */
  @Test
  public void nowarningWhenNothingIsPending() {
    assertFalse("nothing is pending, so nothing may be claimed",
        strip.warnIfIdentityChanged(bob()));
    assertEquals("and the banner must be untouched",
        E2EEStripView.INFO_NO_CONTACT_CHOSEN_TEXT, infoField().getText().toString());
  }

  /**
   * A name imitating an address tag must be refused by the real validation path.
   *
   * <p>{@code displayNameImitatesATag} was tested directly; the call to it from
   * {@code providedContactInformationIsValid} was not, so deleting the call survived. A predicate
   * nothing invokes is not a control - the tag exists precisely so a name cannot impersonate one.
   */
  @Test
  public void anameImitatingAtagIsRefused() {
    assertFalse("a first name shaped like an address tag must be refused",
        strip.providedContactInformationIsValid("#ab12-cd34", "Jones"));
    assertFalse("and a last name",
        strip.providedContactInformationIsValid("Bob", "#ab12-cd34"));
    assertTrue("while an ordinary name is accepted",
        strip.providedContactInformationIsValid("Bob", "Jones"));
  }

  private android.view.View verifyButton() {
    return strip.findViewById(R.id.e2ee_verify_contact_verify_button);
  }

  /**
   * The verify screen must not show one contact's digits under another contact's name.
   *
   * <p>Deleting {@code clearFingerprintViews()} from the no-fingerprint branch reverted a
   * documented fix and survived the whole suite: the previous contact's safety number stayed on
   * screen while the header named a contact it does not belong to. A user comparing those digits by
   * voice would be confirming the wrong key - and confirming is exactly what that screen is for.
   */
  @Test
  public void theverifyScreenBlanksWhenThereIsNoFingerprint() {
    // A pinned contact: real digits appear.
    strip.verifyContact(bob());
    final String pinnedDigits = firstCodeSegment();
    assertFalse("precondition: a pinned contact must show digits", pinnedDigits.isEmpty());

    // A contact with nothing pinned: getFingerprint returns null.
    final Contact stranger = new Contact("Never", "Seen", "never-pinned-uuid", 11, false);
    final ArrayList<Contact> contacts = new ArrayList<>(victim.getContactList());
    contacts.add(stranger);
    victim.setContactList(contacts);

    strip.verifyContact(stranger);

    assertEquals("the previous contact's digits must not remain under this contact's name",
        "", firstCodeSegment());
  }

  /**
   * And confirmation must be impossible while there are no digits to have compared.
   *
   * <p>Inverting {@code clearFingerprintViews} to ENABLE the buttons also survived. That is the one
   * runtime call standing between a blank verify screen and a user pressing verify over nothing -
   * the layout declares no {@code android:enabled}, so the default is enabled.
   */
  @Test
  public void confirmationIsDisabledWhenThereAreNoDigits() {
    final Contact stranger = new Contact("Never", "Seen", "never-pinned-uuid", 11, false);
    final ArrayList<Contact> contacts = new ArrayList<>(victim.getContactList());
    contacts.add(stranger);
    victim.setContactList(contacts);

    strip.verifyContact(stranger);

    assertNotNull("the verify button must exist", verifyButton());
    assertFalse("verify must be disabled with no safety number on screen",
        verifyButton().isEnabled());
  }

  /** And enabled again for a contact that does have one, or the screen is simply broken. */
  @Test
  public void confirmationIsEnabledWhenDigitsAreShown() {
    strip.verifyContact(bob());

    assertTrue("verify must be available once there is a number to compare",
        verifyButton().isEnabled());
  }

  private String firstCodeSegment() {
    final TextView code = strip.findViewById(R.id.code_first);
    assertNotNull("the verify screen must have digit views", code);
    return code.getText().toString();
  }

  /**
   * The contact banner must never be written over a standing warning.
   *
   * <p>Making the caller's {@code if (!mIdentityWarningStanding)} unconditional survived the whole
   * suite, and that guard is a documented fix: without it the identity-change warning was
   * overwritten one frame after being set, on the clipboard path - the only path an attacker's
   * envelope takes - leaving a three-and-a-half-second toast as the entire signal over a screen
   * that otherwise looked like an ordinary success.
   *
   * <p>The caller's flag is cleared immediately after that check, so it protects exactly one frame.
   * This asserts the property at the place that has to hold for every frame after it.
   */
  @Test
  public void thecontactBannerNeverOverwritesAstandingWarning() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");

    strip.showChosenContactInMainInfoField();

    assertEquals("a warning must outlive the routine banner that follows it",
        "Someone offered a different key for Bob.", infoField().getText().toString());
  }

  /**
   * Nor does the opening message, on a rebuild while a warning is standing.
   *
   * <p>The third writer of this banner, and the one that had no test. {@code refreshOpeningMessage}
   * is called by {@code LatinIME.setInputView} on every configuration change — a theme switch, night
   * mode at sunset, a rotation an app can force — and it runs <em>before</em> {@code adoptState}
   * restores the carried warning. Making its {@code else if (!mWarningStanding)} unconditional
   * survived the whole strip suite.
   *
   * <p>The sibling case is covered from the other side: when storage is unreadable this method
   * raises a warning, and two tests pin that. Nothing asked what it does when storage is fine and a
   * warning from some other source is already on screen — which is the identity-change and
   * post-reject case, the two banners with no second guard behind them.
   *
   * <p>Found by mutation rather than by reading, after {@code mayOverwriteInfoBanner},
   * {@code setInfoUnlessWarned}, {@code showChosenContactInMainInfoField}, the password-field guard
   * and {@code adoptState}'s carried-warning arm were each mutated and each killed by 2 to 10 tests.
   * This was the one that survived.
   */
  @Test
  public void theopeningMessageNeverOverwritesAstandingWarning() {
    // Storage is fine; the warning comes from elsewhere - a substituted key, or a rejected one.
    SignalProtocolMain.setStorageStateForTest(
        com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper.StorageState.READABLE);
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");

    strip.refreshOpeningMessage();

    assertEquals("a rebuild must not wipe a security warning with the opening message. This runs on "
            + "every configuration change, before the carried warning is restored, so an attacker "
            + "who can provoke one - or who waits for night mode - gets the banner cleared for free",
        "Someone offered a different key for Bob.", infoField().getText().toString());
  }

  /** And once the user has acted, the routine banner appears normally. */
  @Test
  public void thecontactBannerAppearsOnceTheWarningIsCleared() {
    strip.setWarningMessageForTest("Someone offered a different key for Bob.");
    strip.selectContact(bob());

    strip.showChosenContactInMainInfoField();

    assertTrue("after a deliberate action the ordinary banner must return: "
            + infoField().getText(),
        infoField().getText().toString().contains("Bob"));
  }

  /**
   * Every screen holding plaintext, a conversation, safety numbers or contact identities must be
   * marked as not-for-capture.
   *
   * <p>Nothing in this project had ever set {@code FLAG_SECURE} - not one occurrence in the whole
   * source tree - and the E2EE surface is a view inlined into the IME rather than an Activity, so
   * it never inherited one either. For an app whose premise is that the messenger cannot read the
   * message, painting the message into a screen-recordable window undoes the property at the last
   * step.
   *
   * <p>This tests WHICH screens count, which is the decision. Whether the window flag then behaves
   * is a device question and is stated as unverified where it is applied.
   */
  @Test
  public void everySensitiveScreenIsMarkedForProtection() {
    strip.showMessagesListForTest();
    assertTrue("the chat log holds the whole decrypted conversation",
        strip.isShowingSensitiveContent());

    strip.showVerifyContactForTest(bob());
    assertTrue("the verify screen holds the digits the user is about to compare by voice",
        strip.isShowingSensitiveContent());

    strip.showContactListForTest();
    assertTrue("the contact list holds who the user talks to, and the tags telling them apart",
        strip.isShowingSensitiveContent());
  }

  /** And a main view with decrypted text in it counts; an empty one does not. */
  @Test
  public void themainViewCountsOnlyWhileItHoldsSomething() {
    strip.showMainViewForTest();
    assertFalse("an empty compose field is not sensitive - and marking it so would block "
            + "screenshots of ordinary typing for no gain", strip.isShowingSensitiveContent());

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_input_field))
        .setText("meet me at the safe house");
    assertTrue("a decrypted message in the field is exactly what must not be captured",
        strip.isShowingSensitiveContent());
  }

  /**
   * Encrypt and decrypt must be refused over a password field.
   *
   * <p>Decrypting writes the plaintext into whatever field has focus. Over another app's password
   * box that hands a decrypted message to that app's own storage, its autofill, and anything it
   * syncs - and the user asked for none of it. Nothing checked: the strip is inlined into the
   * keyboard, so it appears over every field the keyboard serves and offered both actions there.
   */
  @Test
  public void theActionsAreRefusedOverApasswordField() {
    assertTrue("precondition: actions are available over an ordinary field",
        strip.actionsAreAvailable());

    strip.setHostFieldIsPassword(true);

    assertFalse("encrypt and decrypt must be off over a password field",
        strip.actionsAreAvailable());
    assertEquals("and the strip must say why, or the buttons look broken",
        E2EEStripView.INFO_PASSWORD_FIELD, infoField().getText().toString());
  }

  /** And anything already staged is dropped, not left addressed to a password box. */
  @Test
  public void enteringApasswordFieldDropsStagedContent() {
    strip.selectContact(bob());
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_input_field))
        .setText("a decrypted message");

    strip.setHostFieldIsPassword(true);

    assertEquals("staged plaintext must not survive into a password field", "",
        ((android.widget.EditText) strip.findViewById(R.id.e2ee_input_field))
            .getText().toString());
  }

  /** Leaving the password field restores them. */
  @Test
  public void leavingApasswordFieldRestoresTheActions() {
    strip.setHostFieldIsPassword(true);
    strip.setHostFieldIsPassword(false);

    assertTrue("the refusal must not be sticky", strip.actionsAreAvailable());
  }

  /**
   * The whole decrypted conversation must not survive the keyboard being dismissed.
   *
   * <p>Clearing the compose field left the chat-log screen visible and its adapter populated with
   * every decrypted message. The IME view is not recreated when the user switches apps, so the full
   * history was still on screen the next time the keyboard rose, in whatever app that was.
   * {@code FLAG_SECURE} stops a screenshot of that; it does nothing about the person next to you.
   */
  @Test
  public void thechatLogDoesNotSurviveTheKeyboardBeingHidden() {
    victim.addUnencryptedMessage(bob(), new StorageMessage(
        StorageMessage.chatLogKey(peerAddress.getName(), peerAddress.getDeviceId()),
        peerAddress.getName(),
        victim.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        "the plaintext that must not outlive the keyboard"));

    strip.selectContact(bob());
    // The button the user presses, not a test-only door - the load happens on the click.
    assertTrue(strip.findViewById(R.id.e2ee_button_chat_logs).performClick());

    final android.widget.ListView list = strip.findViewById(R.id.e2ee_messages_list);
    assertNotNull("the chat-log list must exist", list);
    assertTrue("the log must actually be populated, or this test proves nothing",
        list.getAdapter() != null && list.getAdapter().getCount() > 0);

    // Through the dismissal entry point, not one of its three parts. isShowingSensitiveContent is
    // the window's FLAG_SECURE decision, not "which screen is up": it also answers true on the main
    // view while the banner names the chosen recipient, and clearDecryptedContent deliberately does
    // not un-choose anyone - forgetChosenRecipient does, and onKeyboardHidden is what runs both.
    strip.onKeyboardHidden();

    assertFalse("the chat log must not still be on screen",
        strip.isShowingSensitiveContent());
    assertTrue("and its contents must be gone, not merely hidden one press away",
        list.getAdapter() == null || list.getAdapter().getCount() == 0);
  }

  /**
   * The identity-change warning must STAND, driven through the real production writer.
   *
   * <p>{@code asubstitutionIsWarnedAboutOnScreen} checks the banner appears. It does not check the
   * warning is marked as standing - and replacing {@code setWarningMessage} with a plain
   * {@code setInfoTextViewMessage} in {@code warnIfIdentityChanged} survived the whole suite,
   * because the standing behaviour was only ever exercised through the test-only door. A test that
   * goes in by the side entrance proves the side entrance works.
   */
  @Test
  public void thesubstitutionWarningStandsAgainstClipboardTraffic() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));
    final String warned = infoField().getText().toString();

    strip.onClipboardChangedForTest();

    assertEquals("the warning posted by warnIfIdentityChanged must survive the messenger's next "
        + "post - erasing it costs the attacker one extra message", warned,
        infoField().getText().toString());
  }

  /** And a failed paste must not erase it either - that is one ordinary chat line. */
  @Test
  public void anundecodablePasteDoesNotEraseTheWarning() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));
    final String warned = infoField().getText().toString();

    strip.resetChosenContactAndInfoTextForTest();

    assertEquals("a paste that fails to decode must not clear a security warning", warned,
        infoField().getText().toString());
  }

  /**
   * The post-rejection warning must stand too - it guards the exact replay it names.
   *
   * <p>The user reported a mismatched safety number, so the pin was dropped. The forged bundle that
   * provoked that is still in the messenger's hands, and re-delivering it is a clean first sighting
   * as far as the store is concerned. The banner saying so is the only thing between the user and
   * silently pinning the attacker - a warning the next chat line may not wipe.
   */
  @Test
  public void thepostRejectionWarningStandsAgainstClipboardTraffic() throws Exception {
    SignalProtocolMain.rejectContactKey(bob());
    victim.setContactList(new ArrayList<>());

    final MessageEnvelope replayed = com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning
        .asEditedInTransit(EnvelopeCodec.fromWire(attackerBundle), new MessageEnvelope(
            EnvelopeCodec.fromWire(attackerBundle).getPreKeyResponse(),
            peerAddress.getName(), peerAddress.getDeviceId()));

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(replayed);

    final String warned = infoField().getText().toString();
    assertTrue("re-pinning at an address the user rejected must say so: " + warned,
        warned.toLowerCase().contains("not to trust keys arriving"));

    // Unreadable storage suppresses a passive overwrite on its own, and this fixture reads that way
    // once a contact has been written - so without pinning the state here, the assertion below
    // would hold whether the warning was marked as standing or not.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.onClipboardChangedForTest();

    assertEquals("and that warning must survive the messenger's next post", warned,
        infoField().getText().toString());
  }

  /**
   * The duplicate-name warning must stand as well.
   *
   * <p>It is the only control covering the case the pin cannot: a SECOND contact under a name the
   * user already knows, at an address the messenger owns. A clean first sighting fires nothing else,
   * so if this banner can be wiped by the next line of chat it is worth very little.
   */
  @Test
  public void theduplicateNameWarningStandsAgainstOneMorePost() throws Exception {
    final MessageEnvelope secondInvite = EnvelopeCodec.fromWire(attackerBundle);

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(secondInvite);

    final String warned = infoField().getText().toString();
    assertTrue("a second Bob Jones at a different address must be called out: " + warned,
        warned.contains("Bob"));

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.onClipboardChangedForTest();

    assertEquals("and saying so once, erasable by the attacker's next message, is not saying so",
        warned, infoField().getText().toString());
  }

  /**
   * A failed session must not paint generic advice over a warning that is already standing.
   *
   * <p>The post-rejection banner is posted before the session attempt. When that attempt then fails
   * - a spliced bundle is one way, and it is exactly what the rejected attacker would send next -
   * the failure branch used to overwrite it with "delete the contact and ask for a new invite":
   * the same advice the warning exists to talk the user out of, and it left the standing flag set
   * over text that is not a warning at all, so nothing passive could correct it afterwards.
   */
  @Test
  public void afailedSessionDoesNotOverwriteAStandingWarning() throws Exception {
    SignalProtocolMain.rejectContactKey(bob());
    victim.setContactList(new ArrayList<>());

    // The peer's real identity key over the attacker's signed pre-key: the signature no longer
    // verifies, so session creation fails after the warning has already been posted.
    final PreKeyResponse spliced = new PreKeyResponse(
        EnvelopeCodec.fromWire(peerBundle).getPreKeyResponse().getIdentityKey(),
        EnvelopeCodec.fromWire(attackerBundle).getPreKeyResponse().getDevices());
    // The attacker's own signature travels with the fields it took: it verifies against the
    // attacker's identity key and not against the peer's, which is what a splice looks like now.
    final MessageEnvelope envelope =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            EnvelopeCodec.fromWire(attackerBundle),
            new MessageEnvelope(spliced, peerAddress.getName(), peerAddress.getDeviceId()));

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(envelope);

    // This test used to assert the opposite, and its premise was the defect.
    //
    // It required the post-rejection warning to be "the thing on screen" in exactly this state -
    // a bundle whose signature does not verify, so buildSession refuses it and NOTHING is pinned.
    // That warning says "this IS a new key for that address". There is no key at that address; the
    // invite was refused. And the standing false warning then suppressed the true message, so the
    // user was told a new key had arrived and not told the invite had failed.
    //
    // What must hold is the reverse: no false claim, and the failure said out loud. The property
    // the old assertion was protecting - that failure advice cannot paint over a REAL standing
    // warning - is still enforced, and is now tested with the duplicate-name warning below, which
    // is about contact rows rather than about a key and so does not depend on anything pinning.
    final String shown = infoField().getText().toString();
    assertFalse("nothing was pinned, so the strip must not claim a new key arrived: " + shown,
        shown.contains("new key for that address"));
    assertTrue("and the failure must be said out loud, which the false warning used to suppress: "
        + shown, shown.contains("send a fresh one"));
  }

  /**
   * But a warning that is TRUE in this state must still not be painted over by failure advice.
   *
   * <p>The half of the old assertion that survives. A duplicate-name warning is about two contact
   * rows, not about a key, so a refused bundle does not make it false — and the generic
   * delete-and-re-invite advice must not replace it.
   */
  @Test
  public void afailedSessionDoesNotOverwriteAtrueStandingWarning() throws Exception {
    // Load-bearing, and its absence made this test vacuous for a round. setUp leaves a Bob Jones
    // at peerAddress; re-adding the identical contact throws DuplicateContactException inside
    // createAndAddContactToContacts, so addContact took abortContactAdding and returned long
    // before createSessionWithContact was called. The failure branch this test exists to pin was
    // never reached, and deleting the !mWarningStanding guard it protects would not have failed
    // it - the test passed only because "send a fresh one" was never a candidate string.
    victim.setContactList(new ArrayList<>());

    strip.setWarningMessageForTest("You already have a contact called Bob Jones.");

    final PreKeyResponse spliced = new PreKeyResponse(
        EnvelopeCodec.fromWire(peerBundle).getPreKeyResponse().getIdentityKey(),
        EnvelopeCodec.fromWire(attackerBundle).getPreKeyResponse().getDevices());
    // The attacker's own signature travels with the fields it took: it verifies against the
    // attacker's identity key and not against the peer's, which is what a splice looks like now.
    final MessageEnvelope envelope =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            EnvelopeCodec.fromWire(attackerBundle),
            new MessageEnvelope(spliced, peerAddress.getName(), peerAddress.getDeviceId()));

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(envelope);

    final String shown = infoField().getText().toString();
    assertTrue("a true standing warning must survive a failed session: " + shown,
        shown.contains("already have a contact"));
    assertFalse("and must not be replaced by the generic failure advice: " + shown,
        shown.contains("send a fresh one"));
  }

  /**
   * Choosing the contact from the list must not be what makes the warning go away.
   *
   * <p>A pending identity change is a state, not a notice that has been read. selectContact cleared
   * the standing flag and wrote "Chosen contact: Bob" over the substitution warning, so the screen
   * the user opens when something looks wrong was also the screen that made it stop looking wrong -
   * and nothing would raise it again short of another failed decryption.
   */
  @Test
  public void choosingTheContactDoesNotDismissAPendingIdentityChange() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue("precondition: a change must be pending",
        SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    strip.selectContact(bob());

    final String shown = infoField().getText().toString();
    assertFalse("the all-clear must not stand in for the warning: " + shown,
        shown.startsWith("Chosen contact"));
    assertTrue("the substitution warning must be re-asserted: " + shown,
        shown.toLowerCase().contains("number"));

    // And it must still be standing, not merely painted once.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.onClipboardChangedForTest();
    assertEquals("re-asserted, then wiped by the next post, is no better", shown,
        infoField().getText().toString());
  }


  /**
   * Pressing Decrypt on whatever the messenger posted must not erase a standing warning.
   *
   * <p>The press is the user's, but the payload is the attacker's, so "Detected contact: Bob"
   * landing on the banner cost one ordinary message to arrange - the same one-extra-post erasure
   * the standing flag prevents everywhere else. Decryption still happens; only the banner is left
   * alone.
   *
   * <p>The warning is raised by the duplicate-name route rather than the substitution one on
   * purpose: with a change pending for that address the decrypt attempt throws before it reaches
   * the banner at all, so a test written that way passes whether the guard is there or not.
   */
  @Test
  public void decryptingThemessengersPayloadDoesNotEraseAStandingWarning() throws Exception {
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(EnvelopeCodec.fromWire(attackerBundle));

    final String warned = infoField().getText().toString();
    assertTrue("precondition: the duplicate-name warning must be standing: " + warned,
        warned.contains("already have a contact"));

    final android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("",
        com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder.encode(peerBundle)));

    assertTrue("precondition: the decrypt button must be pressable",
        strip.findViewById(R.id.e2ee_button_decrypt).performClick());

    // Asserted on the warning itself rather than on the whole banner. A decrypt identifies a
    // sender, which moves the chosen recipient, and the banner now carries a "Sending to: X" line
    // under a standing warning so the screen cannot name one contact while addressing another - so
    // the captured banner above legitimately differs by that line afterwards. What must not change
    // is the warning, and what must not appear is the routine banner it would have been replaced by.
    final String afterwards = infoField().getText().toString();
    assertTrue("the warning must survive the user pressing decrypt on the attacker's payload: "
        + afterwards, afterwards.contains("already have a contact"));
    assertFalse("and must not have been replaced by the routine detected-contact line: " + afterwards,
        afterwards.contains("Detected contact"));
  }

  private android.widget.EditText firstNameField() {
    return strip.findViewById(R.id.e2ee_add_contact_first_name_input_field);
  }

  /**
   * Declining an invite must not be what clears the warning about it.
   *
   * <p>Cancel wrote "No contact chosen" straight onto the banner. Declining an unexpected invite is
   * the correct response to a suspicious one, so the action a careful user takes was the action
   * that erased the reason they took it - and the message was wrong on its own terms whenever a
   * contact was in fact chosen.
   */
  @Test
  public void cancellingAnInviteDoesNotEraseAStandingWarning() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));
    final String warned = infoField().getText().toString();

    assertTrue(strip.findViewById(R.id.e2ee_add_contact_cancel_button).performClick());

    assertEquals("declining the invite must leave the warning about it on screen", warned,
        infoField().getText().toString());
  }

  /** And the name typed for a declined invite must not be waiting on the next one's screen. */
  @Test
  public void cancellingAnInviteForgetsTheNameThatWasTypedForIt() {
    firstNameField().setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");

    assertTrue(strip.findViewById(R.id.e2ee_add_contact_cancel_button).performClick());

    assertEquals("the next invite's screen must not open pre-filled with the last one's name",
        "", firstNameField().getText().toString());
  }

  /**
   * Comparing the number and acting on it IS what puts a warning down.
   *
   * <p>The flag used to come down on ARRIVING at the verify screen, which let a user look, back out
   * and leave the change pending with nothing standing. Moving it onto the two presses is only
   * correct if the presses actually do it - otherwise the warning becomes permanent and the user has
   * no way to dismiss a banner they have dealt with, which trains them to ignore it.
   */
  @Test
  public void sayingTheNumberDoesNotMatchPutsTheWarningDown() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));

    strip.showVerifyContactForTest(bob());
    assertTrue("precondition: the reject button must be pressable",
        strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick());

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    final String before = infoField().getText().toString();
    strip.onClipboardChangedForTest();
    assertNotEquals("after the user has said the number does not match, the banner must be "
        + "writable again", before, infoField().getText().toString());
  }

  /** And so does confirming it. */
  @Test
  public void confirmingTheNumberPutsTheWarningDown() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));

    strip.showVerifyContactForTest(bob());
    assertTrue("precondition: the verify button must be pressable",
        strip.findViewById(R.id.e2ee_verify_contact_verify_button).performClick());

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    final String before = infoField().getText().toString();
    strip.onClipboardChangedForTest();
    assertNotEquals("after the user has compared and confirmed, the banner must be writable again",
        before, infoField().getText().toString());
  }

  /**
   * Who the user talks to must not ride through an app switch on the banner.
   *
   * <p>"Chosen contact: Bob #a1b2" survived the keyboard being dismissed, so it was on screen when
   * the keyboard next rose - in whatever app that was.
   */
  @Test
  public void therecipientIsForgottenWhenTheKeyboardIsDismissed() {
    strip.selectContact(bob());
    assertTrue("precondition: the banner must name the contact",
        infoField().getText().toString().startsWith("Chosen contact"));

    strip.onKeyboardHidden();

    assertFalse("the next app must not be told who the user talks to: "
            + infoField().getText(),
        infoField().getText().toString().contains("Bob"));
  }

  /**
   * A refused invite must not leave the recipient where the envelope's address said.
   *
   * <p>The plain-message arm gives the recipient back when nothing decrypted, with a comment
   * explaining exactly why: the address that moved it "is an unsigned header the relay copies out
   * of any envelope that contact ever sent". The two bundle arms did not, and which arm handles an
   * envelope is decided by field presence alone - so the undo cost one appended field to escape.
   * Staple a bundle built to be refused onto any ciphertext and the recipient moves on an envelope
   * where nothing was accepted and nothing decrypted.
   *
   * <p>The refused bundle here is a real one from another identity, offered at the address where
   * the genuine peer's key is pinned - which is the substitution the pin exists to refuse.
   */
  @Test
  public void arefusedBundleDoesNotLeaveTheRecipientWhereTheEnvelopeSaid() throws Exception {
    final MessageEnvelope forged = EnvelopeCodec.fromWire(attackerBundle);
    forged.setSignalProtocolAddressName(peerAddress.getName());
    forged.setDeviceId(peerAddress.getDeviceId());

    strip.processPreKeyResponseForTest(
        EnvelopeCodec.fromWire(EnvelopeCodec.toWire(forged)), bob());

    assertNull("nothing was accepted and nothing decrypted, so the recipient must go back - "
            + "otherwise Encrypt is aimed at whoever an unsigned header named, on an arm the "
            + "attacker selects by appending one field",
        strip.chosenContactForTest());
  }

  /**
   * And forgetting the recipient must not become the messenger's warning eraser.
   *
   * <p>Any app may hide the keyboard whenever it likes, so this runs on demand for an attacker. It
   * is the reason the recipient is dropped rather than the banner blanked.
   */
  @Test
  public void forgettingTheRecipientLeavesAStandingWarningAlone() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));
    final String warned = infoField().getText().toString();

    strip.onKeyboardHidden();

    assertEquals("hiding the keyboard must not erase a security warning - an app can do that at "
        + "will", warned, infoField().getText().toString());
  }

  /**
   * The recipient really is gone, not merely unnamed.
   *
   * <p>Clearing the text while keeping the recipient would be worse than leaving both: encryption
   * would go to a contact the screen no longer names.
   */
  @Test
  public void forgettingTheRecipientAlsoStopsTheNextMessageGoingToThem() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));

    strip.onKeyboardHidden();

    assertNull("even with the warning still owning the banner, there must be no recipient left",
        strip.chosenContactForTest());
  }

  /**
   * A warning carried across a rebuild must still be STANDING, not merely visible.
   *
   * <p>The lifecycle test that found this asserts the banner text survives a configuration change.
   * That passes even if only the text is carried and the standing flag is not - and a warning whose
   * flag is down is erased by the messenger's next post, which is the entire family of defects the
   * flag exists to prevent. The control proved it: dropping the flag from the carry left that test
   * green.
   */
  @Test
  public void awarningCarriedAcrossArebuildIsStillStanding() {
    SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress);
    assertTrue(strip.warnIfIdentityChanged(bob()));
    final String warned = infoField().getText().toString();

    // What LatinIME does when the system hands it a new input view.
    final E2EEStripView.CarriedState carried = strip.surrenderState();
    final E2EEStripView rebuilt = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    rebuilt.adoptState(carried);

    final TextView rebuiltBanner = rebuilt.findViewById(R.id.e2ee_info_text);
    assertEquals("precondition: the warning text must survive the rebuild", warned,
        rebuiltBanner.getText().toString());

    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    rebuilt.onClipboardChangedForTest();

    assertEquals("and must still be standing afterwards - a carried warning the next post can wipe "
        + "is the defect this flag exists to prevent, reached by rotating the phone", warned,
        rebuiltBanner.getText().toString());
  }

  /**
   * The digit animators are actually stopped, not merely forgotten.
   *
   * <p>This fix has been carried as unverified since it was written, on the grounds that the defect
   * it prevents — the previous contact's safety number repainting into the new contact's screen a
   * second later — cannot be reproduced under Robolectric, which delivers no further animator frames
   * once the looper is idled past a view change. That is still true, and it is why this asserts
   * something narrower and checkable instead: that the animators are cancelled and released.
   *
   * <p>Worth the distinction rather than leaving the whole thing unverified. It does not prove the
   * digits stay blank on a device; it proves the code does the one thing the device behaviour
   * depends on, so a future change that drops the cancel fails here rather than only on hardware
   * nobody in this environment can run. The device half stays on the list in REVIVAL.md.
   *
   * <p>It also matters beyond repainting: a running {@code ValueAnimator} is held by the
   * process-wide {@code AnimationHandler} through a listener capturing a digit view, so an
   * uncancelled one keeps a discarded strip alive - the retention shape that kept whole
   * conversations reachable through the clipboard listener.
   */
  @Test
  public void blankingTheDigitsStopsTheAnimatorsRatherThanForgettingThem() throws Exception {
    strip.verifyContact(bob());

    final java.lang.reflect.Field field =
        E2EEStripView.class.getDeclaredField("mCodeAnimators");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final java.util.List<android.animation.ValueAnimator> animators =
        (java.util.List<android.animation.ValueAnimator>) field.get(strip);

    assertFalse("precondition: showing a safety number must start animators, or this proves nothing",
        animators.isEmpty());
    final java.util.List<android.animation.ValueAnimator> started =
        new java.util.ArrayList<>(animators);
    assertTrue("precondition: they must be running", started.get(0).isRunning());

    // What surrenderState and the blank-screen path both call.
    strip.onKeyboardHidden();

    for (final android.animation.ValueAnimator animator : started) {
      assertFalse("every digit animator must be cancelled - an uncancelled one goes on painting "
          + "the previous contact's number into this contact's screen, and is itself held by the "
          + "process-wide AnimationHandler through a listener capturing a digit view",
          animator.isRunning());
    }
    assertTrue("and the list must be released, or the strip keeps growing one per verify screen",
        animators.isEmpty());
  }
}
