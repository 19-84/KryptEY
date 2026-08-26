package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
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
 * An invite the app refused must not advance the UI exactly as an accepted one does.
 *
 * <p>Found by a sweep of Phase 2 after the one-time pre-key check landed. The refusal is a boolean
 * that {@code SignalProtocolMain.decrypt} discards, and on the bundle-only arm the strip decided
 * success by inference: no decrypted message came out, which is what a good re-invite looks like —
 * and equally what a refused one looks like.
 *
 * <p>What makes it more than cosmetic is the compound. The ADD-CONTACT arm creates the contact row
 * before it attempts the session and does not remove it on failure, and it tells the user *"ask
 * your contact to send a fresh one"*. Following that advice moves the next attempt onto the arm
 * that says nothing: the row now exists, so the strip prints "Detected contact" over a contact with
 * no session, and the next send dies with a generic encryption failure. A relay stripping the
 * one-time pre-key from every invite can hold that state open indefinitely.
 */
@RunWith(RobolectricTestRunner.class)
public class RefusedInviteIsNotReportedAsSuccessTest {

  private E2EEStripView strip;
  private Account victim;
  private Account peer;
  private SignalProtocolAddress peerAddress;
  private String genuineBundle;

  private SignalProtocolAddress addressOfVictim() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    activate(peer);
    genuineBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);

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

  /** The relay's edit: delete the one-time pre-key. Both signatures still verify. */
  private MessageEnvelope strippedInvite() throws Exception {
    final PreKeyResponse genuine =
        EnvelopeCodec.fromWire(genuineBundle).getPreKeyResponse();
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    assertNotNull("precondition: a genuine invite carries a one-time pre-key", device.getPreKey());

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    final MessageEnvelope stripped = new MessageEnvelope(
        new PreKeyResponse(genuine.getIdentityKey(), devices),
        peerAddress.getName(), peerAddress.getDeviceId());

    // Through the wire, so this is the object a relay's edit actually produces rather than one
    // assembled in memory that merely resembles it.
    return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(stripped));
  }

  private String bannerText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  private void paste(final MessageEnvelope envelope) throws Exception {
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(envelope))));
  }

  /**
   * The state the app's own advice produces: a contact row with no session behind it.
   *
   * <p>Built through the add-contact path rather than by hand, because the row-without-session
   * state is created by that path and by nothing else — asserting it exists is part of the finding.
   */
  private void contactRowWithoutASession() throws Exception {
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(strippedInvite());

    assertFalse("precondition: the add-contact arm must leave the row behind", 
        victim.getContactList().isEmpty());
    assertFalse("precondition: and no session, or there is nothing to misreport",
        SignalProtocolMain.hasSessionWith(peerAddress));
  }

  @Test
  public void arefusedReInviteIsNotReportedAsAdetectedContact() throws Exception {
    contactRowWithoutASession();

    // The user does what the app told them: asks for a fresh invite. The relay strips it again.
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    final String shown = bannerText();
    assertFalse("a refused invite must not be reported as a detected contact. Nothing was set up: "
            + "the next send will fail with a generic encryption error, and a relay stripping every "
            + "invite can hold this state open forever while the app reports success each time. "
            + "Shown: " + shown,
        shown.contains("Detected contact"));
    assertTrue("and the refusal must be said out loud: " + shown,
        shown.contains("could not be used"));
    assertFalse("the app must still not believe there is a session",
        SignalProtocolMain.hasSessionWith(peerAddress));
  }

  /** The other half: a good invite on the same arm must still be reported as success. */
  @Test
  public void agoodReInviteIsStillReportedAsAdetectedContact() throws Exception {
    contactRowWithoutASession();

    paste(EnvelopeCodec.fromWire(genuineBundle));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("precondition: a genuine invite must build a session",
        SignalProtocolMain.hasSessionWith(peerAddress));
    // Asserted as "no refusal stands" rather than as a particular line, because the banner is
    // repainted after this arm by showChosenContactInMainInfoField - so the last words on screen
    // are "Chosen contact", not "Detected contact", on a healthy path too. Pinning the wrong one of
    // those would be pinning the repaint rather than the outcome.
    final String shown = bannerText();
    assertFalse("a good invite must not raise the refusal warning: " + shown,
        shown.contains("could not be used"));
    assertTrue("and nothing may be left standing over the banner after a healthy invite - without "
        + "this, 'always warn' would pass the test above while breaking every normal re-invite",
        strip.mayOverwriteInfoBanner());
  }

  /** A working session with the peer, which is the state the earlier fix could not see past. */
  private void establishedContact() throws Exception {
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    assertTrue("precondition: a genuine invite must establish a session",
        SignalProtocolMain.hasSessionWith(peerAddress));
  }

  /**
   * The case the first fix could not see: a contact the user already talks to.
   *
   * <p>That fix asked "is there a session", which is true for every established contact regardless
   * of what just happened to the invite — so the refusal never fired on the case the attack aims
   * at. Worse than cosmetic: {@code createPreKeyBundle} throws before {@code buildSession}, which is
   * the only place a bundle-borne substitution is recorded, so the same stripped byte that hides
   * the refusal also hides an identity change. Reporting success is what makes the user stop
   * retrying.
   */
  @Test
  public void arefusedReInviteFromAnEstablishedContactIsStillReported() throws Exception {
    establishedContact();

    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    final String shown = bannerText();
    assertTrue("a refused invite must be reported even when a session already exists - that is "
            + "precisely the case a relay aims at, because the user has no reason to suspect "
            + "anything. Shown: " + shown,
        shown.contains("could not be used"));
    assertFalse("and it must not be called a detected contact: " + shown,
        shown.contains("Detected contact"));
  }

  /**
   * The rotation path, which never asked at all.
   *
   * <p>An honest peer attaches a full bundle to an ordinary message whenever its signed pre-key
   * rotates, so {@code UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE} is routine rather than
   * exotic. Strip the one-time key from one: the bundle is refused, the ciphertext still decrypts
   * under the existing session, and the banner asserted an update that never happened.
   */
  @Test
  public void arefusedBundleAttachedToAmessageIsReported() throws Exception {
    establishedContact();

    // Peer sends a message with a full bundle attached, as a rotation does.
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()),
        addressOfVictim()));
    final MessageEnvelope withMessage =
        SignalProtocolMain.encryptMessage("hello", addressOfVictim());
    assertNotNull(withMessage);
    activate(victim);

    // Bundle AND ciphertext in one envelope, which is what a rotation actually emits. There is no
    // constructor for that pair, so it is assembled through the setters the codec itself uses.
    final MessageEnvelope rotation = new MessageEnvelope(withMessage.getCiphertextMessage(),
        withMessage.getCiphertextType(), peerAddress.getName(), peerAddress.getDeviceId());
    rotation.setPreKeyResponse(strippedInvite().getPreKeyResponse());

    paste(rotation);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    // Asserted as "the refusal is on screen", NOT as "the update line is absent".
    //
    // Measured, and it corrected the first version of this test. The line
    // "Detected contact with updated keybundle" is written through setInfoUnlessWarned and then
    // repainted immediately by showChosenContactInMainInfoField, so on this route it never reaches
    // the user either way - asserting its absence passed against the unfixed code and proved
    // nothing. The user-visible change is the other half: because the refusal now stands as a
    // warning, the repaint is suppressed and the refusal is what remains on screen.
    final String shown = bannerText();
    assertTrue("a bundle refused on the rotation path must be reported. An honest peer attaches a "
            + "full bundle whenever its signed pre-key rotates, so this is the routine case, and "
            + "nothing on this arm asked before - isSessionCreation is false here. Shown: " + shown,
        shown.contains("could not be used"));
  }



  /**
   * And the warning is retracted by the very thing it asks for.
   *
   * <p>It says "ask them to send another". Following that advice used to leave the warning standing
   * over a contact that now works — and because a standing warning suppresses everything passive, a
   * user acting on that text may Reject a perfectly good key, which writes a permanent rejection
   * record.
   */
  @Test
  public void agoodInviteRetractsTheRefusalWarning() throws Exception {
    contactRowWithoutASession();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: the refusal must be standing", bannerText().contains("could not be used"));

    paste(EnvelopeCodec.fromWire(genuineBundle));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("precondition: the good invite must build a session",
        SignalProtocolMain.hasSessionWith(peerAddress));
    assertTrue("following the app's own advice must clear the warning it gave. Otherwise a working "
        + "contact sits under 'Nothing has been set up', every later notice is suppressed, and the "
        + "user may reject a good key on the strength of it.", strip.mayOverwriteInfoBanner());
    assertFalse("and the refusal text must be gone: " + bannerText(),
        bannerText().contains("could not be used"));
  }

  /**
   * The refusal must not be usable to silence the caution shown when a NEW contact is created.
   *
   * <p>It is the only standing warning a relay can raise unilaterally — one deleted byte on an
   * unrelated contact's invite, no user cooperation. Every other one needs prior user action or is
   * itself the attack being flagged. So if it suppressed ordinary notices the way a hard warning
   * does, a relay could raise it and then have the user add an attacker-chosen contact without ever
   * seeing "this key reached you through the messenger and the app cannot tell whose it is" — the
   * one caution that fires precisely because nothing was noticed.
   */
  @Test
  public void arefusalAboutOneContactDoesNotSilenceTheCautionForANewOne() throws Exception {
    establishedContact();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: a refusal must be standing about Bob",
        bannerText().contains("could not be used"));

    // Now an unrelated new contact, from a fresh account at a fresh address.
    SignalProtocolMain.initialize(null);
    final Account carol = SignalProtocolMain.getInstance().getAccount();
    final String carolBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Carol");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Smith");
    strip.addContactForTest(EnvelopeCodec.fromWire(carolBundle));

    final String shown = bannerText();
    assertTrue("the caution for a newly created contact must not be suppressed by a refusal about "
            + "someone else - it is the one notice that fires because nothing was noticed, which is "
            + "what a successful substitution looks like. Shown: " + shown,
        shown.contains("cannot tell whose it is"));
  }

  /**
   * The messenger must not be able to erase the refusal by focusing a password field.
   *
   * <p>An earlier version of this warning was "soft": it yielded to any ordinary notice, so that a
   * caution about a different contact could still be shown. Six callers reach that writer, and one
   * of them is the password-field notice — which {@code LatinIME} raises on EVERY input session
   * from the host field's {@code inputType}, and the messenger owns the inputType of every field it
   * presents. So the erase needed no user action at all: focus a password field, the notice lands
   * and takes the warning with it; focus an ordinary field again and the strip reads "Chosen
   * contact: Bob". Nothing re-raises it — the refusal is per-decrypt and in memory.
   *
   * <p>That contradicted {@code mWarningStanding}'s own javadoc, "Nothing the messenger can cause
   * clears it", which is the property this test pins.
   */
  @Test
  public void thepasswordFieldNoticeDoesNotEraseTheRefusalWarning() throws Exception {
    establishedContact();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertFalse("precondition: a refusal must be standing", strip.mayOverwriteInfoBanner());

    // Exactly what the messenger can arrange: a field it declares as a password, then an ordinary
    // one. No user action, no paste, nothing the user could refuse.
    strip.setHostFieldIsPassword(true);
    strip.setHostFieldIsPassword(false);

    assertFalse("the refusal must survive a notice the messenger can raise at will - it owns the "
            + "inputType of every field it shows, and LatinIME re-raises this on every input "
            + "session. Nothing re-posts the refusal, so erasing it here loses the only surface "
            + "that security event has.",
        strip.mayOverwriteInfoBanner());
  }

  /**
   * And what it says is true where a session survives the refusal.
   *
   * <p>"Nothing has been set up" is right for a first invite and false on the rotation path, where
   * the peer's message decrypts under the existing session and the contact keeps working — the user
   * reads that sentence with the reply in the compose box in front of them.
   */
  @Test
  public void therefusalDoesNotClaimNothingWasSetUpWhenAsessionSurvives() throws Exception {
    establishedContact();

    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(SignalProtocolMain.exportOwnKeyBundle()), addressOfVictim()));
    final MessageEnvelope withMessage =
        SignalProtocolMain.encryptMessage("hello", addressOfVictim());
    assertNotNull(withMessage);
    activate(victim);

    final MessageEnvelope rotation = new MessageEnvelope(withMessage.getCiphertextMessage(),
        withMessage.getCiphertextType(), peerAddress.getName(), peerAddress.getDeviceId());
    rotation.setPreKeyResponse(strippedInvite().getPreKeyResponse());

    paste(rotation);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    final String shown = bannerText();
    assertTrue("precondition: the session must have survived",
        SignalProtocolMain.hasSessionWith(peerAddress));
    assertFalse("the strip must not say nothing was set up while the contact still works and their "
            + "message is on screen: " + shown, shown.contains("Nothing has been set up"));
    assertTrue("it must say what actually happened instead: " + shown,
        shown.contains("what you already had with them is unchanged"));
  }

  /**
   * The caution for a new contact survives a HARD warning too, not just the refusal.
   *
   * <p>The sibling test above uses the refused-invite warning. A sweep pointed out that the premise
   * behind treating that one specially was false: the identity-change warning is hard, and this
   * file says elsewhere that one is something "any messenger can arrange with one forged bundle".
   * So the suppression was still reachable, by a warning the special case did not cover.
   */
  @Test
  public void anidentityChangeWarningAlsoDoesNotSilenceTheCautionForAnewContact() throws Exception {
    establishedContact();

    // A third party's bundle relabelled with Bob's address: a substitution, recorded and refused.
    SignalProtocolMain.initialize(null);
    final String impostorBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    final MessageEnvelope substituted = new MessageEnvelope(
        EnvelopeCodec.fromWire(impostorBundle).getPreKeyResponse(),
        peerAddress.getName(), peerAddress.getDeviceId());

    paste(substituted);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertFalse("precondition: a hard warning must be standing", strip.mayOverwriteInfoBanner());

    SignalProtocolMain.initialize(null);
    final String carolBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Carol");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Smith");
    strip.addContactForTest(EnvelopeCodec.fromWire(carolBundle));

    final String shown = bannerText();
    assertTrue("the caution must be shown even under a hard warning a relay raised: " + shown,
        shown.contains("cannot tell whose it is"));
    assertFalse("and the warning it was shown beside must not have been taken down",
        strip.mayOverwriteInfoBanner());
  }
}
