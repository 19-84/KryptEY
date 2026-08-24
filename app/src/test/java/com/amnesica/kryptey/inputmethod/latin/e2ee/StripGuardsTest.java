package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        peerAddress.getName(), peerAddress.getName(),
        victim.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        "the plaintext that must not outlive the keyboard"));

    strip.selectContact(bob());
    // The button the user presses, not a test-only door - the load happens on the click.
    assertTrue(strip.findViewById(R.id.e2ee_button_chat_logs).performClick());

    final android.widget.ListView list = strip.findViewById(R.id.e2ee_messages_list);
    assertNotNull("the chat-log list must exist", list);
    assertTrue("the log must actually be populated, or this test proves nothing",
        list.getAdapter() != null && list.getAdapter().getCount() > 0);

    strip.clearDecryptedContent();

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

    final MessageEnvelope replayed = new MessageEnvelope(
        EnvelopeCodec.fromWire(attackerBundle).getPreKeyResponse(),
        peerAddress.getName(), peerAddress.getDeviceId());

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(replayed);

    final String warned = infoField().getText().toString();
    assertTrue("re-pinning at an address the user rejected must say so: " + warned,
        warned.toLowerCase().contains("reject") || warned.toLowerCase().contains("did not match"));

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
    final MessageEnvelope envelope =
        new MessageEnvelope(spliced, peerAddress.getName(), peerAddress.getDeviceId());

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(envelope);

    final String shown = infoField().getText().toString();
    assertFalse("a standing warning must not be replaced by the generic failure advice: " + shown,
        shown.contains("send a fresh one"));
    assertTrue("the post-rejection warning must still be the thing on screen: " + shown,
        shown.toLowerCase().contains("did not match"));
  }
}
