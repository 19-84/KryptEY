package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
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
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * The relay's edit: delete the one-time pre-key.
   *
   * <p>Both of libsignal's own signatures still verify, because each covers only its own key. What
   * refuses this now is the issuing signature, which covers the whole bundle and travels unchanged
   * from the invite the relay copied - a relay can edit or sign, not both. The tests below are
   * about what the app SAYS when a bundle is refused, and that is unchanged by which check refused
   * it.
   */
  private MessageEnvelope strippedInvite() throws Exception {
    final PreKeyResponse genuine =
        EnvelopeCodec.fromWire(genuineBundle).getPreKeyResponse();
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    assertNotNull("precondition: a genuine invite carries a one-time pre-key", device.getPreKey());

    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    final MessageEnvelope stripped =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            EnvelopeCodec.fromWire(genuineBundle),
            new MessageEnvelope(new PreKeyResponse(genuine.getIdentityKey(), devices),
                peerAddress.getName(), peerAddress.getDeviceId()));

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

  /**
   * A PreKeySignalMessage labelled with the peer's address and carrying no bundle.
   *
   * <p>The two-step this file documents: strip the one-time pre-key from an invite so the bundle is
   * refused and the row is left keyless, then send one of these. It pins by trust-on-first-use with
   * no bundle to retract anything.
   */
  private MessageEnvelope bundlelessPreKeyMessage() throws Exception {
    final String victimBundle;
    activate(victim);
    victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), addressOfVictim()));
    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("hello", addressOfVictim());
    assertNotNull(sent);

    activate(victim);
    return new MessageEnvelope(sent.getCiphertextMessage(), sent.getCiphertextType(),
        peerAddress.getName(), peerAddress.getDeviceId());
  }

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
    // The guard this line exists for is "always warn would pass the test above" - so it asks about
    // WARNINGS. A compare-the-number caution does stand here and is correct: this paste pinned a
    // key at an address that held none, which is exactly the event that caution describes, and it
    // was previously said only when the pin happened during addContact.
    assertFalse("no warning may be left standing after a healthy invite - without this, 'always "
        + "warn' would pass the test above while breaking every normal re-invite",
        strip.warningIsStandingForTest());
    assertTrue("and the caution that does stand must be the compare-the-number one, not a "
            + "refusal: " + shown, shown.contains("compare the security number"));
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
    final MessageEnvelope strippedForRotation = strippedInvite();
    rotation.setPreKeyResponse(strippedForRotation.getPreKeyResponse());
    // The signature travels with the bundle it was made over, exactly as it does on the wire.
    rotation.setBundleSignature(strippedForRotation.getBundleSignature());

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
    assertFalse("following the app's own advice must clear the WARNING it gave. Otherwise a "
        + "working contact sits under a refusal, and the user may reject a good key on the "
        + "strength of it.", strip.warningIsStandingForTest());
    assertFalse("and the refusal text must be gone: " + bannerText(),
        bannerText().contains("could not be used"));
    // What stands instead is the compare-the-number caution, and that is correct rather than
    // residue. This paste pinned a key that reached the user through the messenger, which is the
    // same event the add-contact arm cautions about - it was simply never said on this path,
    // because both callers of that caution were inside addContact. A contact row can exist with no
    // pinned key (a refused bundle leaves exactly that), so the pin can happen here.
    assertTrue("a key was pinned by this paste, so the user must be told to compare the number - "
            + "the same sentence the add-contact arm posts for the same event: " + bannerText(),
        bannerText().contains("compare the security number"));
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
    final MessageEnvelope strippedForRotation = strippedInvite();
    rotation.setPreKeyResponse(strippedForRotation.getPreKeyResponse());
    // The signature travels with the bundle it was made over, exactly as it does on the wire.
    rotation.setBundleSignature(strippedForRotation.getBundleSignature());

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
    final MessageEnvelope substituted =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            EnvelopeCodec.fromWire(impostorBundle),
            new MessageEnvelope(EnvelopeCodec.fromWire(impostorBundle).getPreKeyResponse(),
                peerAddress.getName(), peerAddress.getDeviceId()));

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

  /**
   * The third state: nothing existed, the bundle was refused, and its ciphertext pinned a key.
   *
   * <p>The two-message split answered the rotation path and left this arm on the wrong sentence —
   * and on the reassuring one. The check asked {@code hasSessionWith} <em>after</em> the decrypt,
   * but the decrypt itself creates a session: {@code decryptMessage}'s PREKEY arm pins by
   * trust-on-first-use whenever the address holds no key, and a refused attached bundle does not
   * stop it, because the {@code PreKeySignalMessage} carries its own identity key.
   *
   * <p>So the strip said "what you already had with them is unchanged" at the precise moment a
   * messenger-supplied key was pinned with nothing else noticing — and on this arm the
   * contact-creation caution does not fire either, so that was the only thing on the banner.
   */
  @Test
  public void arefusedBundleWhoseMessagePinsAkeySaysSo() throws Exception {
    contactRowWithoutASession();
    assertFalse("precondition: nothing may be pinned yet",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    // The peer can build a real PreKeySignalMessage to the victim - an attacker relaying the
    // victim's own invite can do exactly this.
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), addressOfVictim()));
    final MessageEnvelope opening = SignalProtocolMain.encryptMessage("hello", addressOfVictim());
    assertNotNull(opening);
    activate(victim);

    // Stripped bundle stapled to that opening message.
    final MessageEnvelope both = new MessageEnvelope(opening.getCiphertextMessage(),
        opening.getCiphertextType(), peerAddress.getName(), peerAddress.getDeviceId());
    final MessageEnvelope strippedForBoth = strippedInvite();
    both.setPreKeyResponse(strippedForBoth.getPreKeyResponse());
    both.setBundleSignature(strippedForBoth.getBundleSignature());

    paste(both);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("precondition: the attached message must have pinned a key by trust-on-first-use, "
        + "or this is not the state under test", SignalProtocolMain.hasPinnedKey(peerAddress));

    final String shown = bannerText();
    assertFalse("the strip must not reassure the user that nothing changed at the moment a "
            + "messenger-supplied key was pinned: " + shown,
        shown.contains("unchanged"));
    assertFalse("nor claim nothing was set up, when a key is pinned and the contact is usable: "
            + shown, shown.contains("Nothing has been set up"));
    assertTrue("it must say a key was set up anyway and send the user to compare it: " + shown,
        shown.contains("set up a key for them anyway"));
    assertTrue("and it must give the one instruction that helps here: " + shown,
        shown.contains("by voice"));
  }

  /**
   * The caution must survive the gesture its own text invites.
   *
   * <p>It is painted beside a standing warning, and it used to be written straight into the view
   * and stored nowhere — while every repaint rebuilds the banner from the warning's text alone. So
   * hiding the keyboard dropped it, a rotation dropped it, and tapping the contact row dropped it:
   * the row the caution is telling the user to go and verify.
   *
   * <p>The invariant sweep cannot see this. It watches {@code mWarningStanding}, and the flag
   * survives all three — this is an erase of text with the warning left standing.
   */
  @Test
  public void thecautionForAnewContactSurvivesTappingTheContactRow() throws Exception {
    establishedContact();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertFalse("precondition: a warning must be standing", strip.mayOverwriteInfoBanner());

    SignalProtocolMain.initialize(null);
    final String carolBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Carol");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Smith");
    strip.addContactForTest(EnvelopeCodec.fromWire(carolBundle));
    assertTrue("precondition: the caution must be on the banner to begin with",
        bannerText().contains("cannot tell whose it is"));

    // The user does exactly what the caution asks: opens the contact to compare the number.
    strip.selectContact(victim.getContactList().get(victim.getContactList().size() - 1));

    assertTrue("the caution must survive selecting the contact - that is the gesture it asks for, "
            + "and it is also what a rotation or hiding the keyboard does. Banner now: "
            + bannerText(),
        bannerText().contains("cannot tell whose it is"));
  }

  /**
   * A key that was already pinned is not a key this paste set up.
   *
   * <p>The predicate asked whether a SESSION appeared, and the sentence it chose asserts a KEY was
   * pinned "and this app cannot tell whose it is". Those come apart on a path the app's own advice
   * produces: {@code deleteContact} removes the session and deliberately KEEPS the pinned identity,
   * so after a delete-and-re-invite the peer's next message builds a session against a key that was
   * already trusted. The message decrypted precisely BECAUSE it matched that pin — which the same
   * app treats elsewhere as proof of identity, when it suppresses the duplicate-name warning at a
   * retired name on the same address.
   */
  @Test
  public void asessionRebuiltOnAnalreadyPinnedKeyIsNotCalledAnewKey() throws Exception {
    establishedContact();
    assertTrue("precondition: a key must be pinned", SignalProtocolMain.hasPinnedKey(peerAddress));

    // The user follows the app's delete-and-re-invite advice.
    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));
    assertTrue("precondition: deleting the contact keeps the pinned identity",
        SignalProtocolMain.hasPinnedKey(peerAddress));
    assertFalse("precondition: and removes the session",
        SignalProtocolMain.hasSessionWith(peerAddress));

    // The peer replies with a PreKey message; the relay strips the attached bundle's one-time key.
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(peer);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), addressOfVictim()));
    final MessageEnvelope reply = SignalProtocolMain.encryptMessage("still me", addressOfVictim());
    assertNotNull(reply);
    activate(victim);

    final MessageEnvelope both = new MessageEnvelope(reply.getCiphertextMessage(),
        reply.getCiphertextType(), peerAddress.getName(), peerAddress.getDeviceId());
    final MessageEnvelope strippedForBoth = strippedInvite();
    both.setPreKeyResponse(strippedForBoth.getPreKeyResponse());
    both.setBundleSignature(strippedForBoth.getBundleSignature());

    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(both);

    final String shown = bannerText();
    assertFalse("the app must not tell the user a new key was set up that it cannot vouch for, "
            + "when the key was already pinned and the message decrypted because it matched. "
            + "Shown: " + shown,
        shown.contains("set up a key for them anyway"));
  }

  /** Adds Carol through the real path and returns her row. */
  private Contact addCarol() throws Exception {
    SignalProtocolMain.initialize(null);
    final String carolBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Carol");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Smith");
    strip.addContactForTest(EnvelopeCodec.fromWire(carolBundle));
    assertTrue("precondition: the caution must be on the banner",
        bannerText().contains("cannot tell whose it is"));
    return victim.getContactList().get(victim.getContactList().size() - 1);
  }

  /**
   * The caution belongs to its own contact, not to whatever warning shares the banner.
   *
   * <p>It was cleared inside {@code setWarningMessage}, on the reasoning that a caution belongs to
   * the warning it was shown beside. That is false on a fact: {@code setWarningMessage} does not
   * distinguish replacing a warning from re-posting the identical one, and
   * {@code warnIfIdentityChanged} re-posts on every decrypt. So one more relayed message from Bob -
   * ordinary traffic, and the Bob warning tells the user to compare numbers, not to stop reading -
   * destroyed the caution about Carol, whose key the attacker supplied. The Bob warning stands the
   * whole time, so this is an erase of text with the flag up: the invariant sweep cannot see it.
   */
  @Test
  public void thecautionSurvivesAnotherMessageFromTheContactTheWarningIsAbout() throws Exception {
    establishedContact();

    // A substituted bundle for Bob raises the identity-change warning.
    SignalProtocolMain.initialize(null);
    final String impostorBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    final MessageEnvelope substituted =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
            EnvelopeCodec.fromWire(impostorBundle),
            new MessageEnvelope(EnvelopeCodec.fromWire(impostorBundle).getPreKeyResponse(),
                peerAddress.getName(), peerAddress.getDeviceId()));
    paste(substituted);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertFalse("precondition: a warning about Bob must be standing",
        strip.mayOverwriteInfoBanner());

    addCarol();

    // One more message from Bob. warnIfIdentityChanged re-posts the SAME warning.
    paste(substituted);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("the caution about Carol must survive another message from Bob. Re-posting the same "
            + "warning is not a deliberate response to anything, and the attacker chooses when it "
            + "happens. Banner now: " + bannerText(),
        bannerText().contains("cannot tell whose it is"));
  }

  /**
   * And it survives when there is no warning at all, which is the common case.
   *
   * <p>The no-warning branch wrote straight to the view and stored nothing, so every repaint erased
   * it - a clipboard post, hiding the keyboard, tapping the contact row, a rotation. That branch is
   * the one the call site argues matters most: it fires when the app noticed nothing, which is
   * exactly what a successful substitution looks like.
   */
  @Test
  public void thecautionSurvivesOrdinaryEventsWhenNoWarningStands() throws Exception {
    final Contact carol = addCarol();
    assertFalse("precondition: no warning may be standing in this case - the caution must be the "
        + "only thing holding the banner", strip.warningIsStandingForTest());

    strip.onClipboardChangedForTest();
    assertTrue("a clipboard post must not erase it - the messenger owns the clipboard: "
        + bannerText(), bannerText().contains("cannot tell whose it is"));

    strip.selectContact(carol);
    assertTrue("nor tapping the contact row, which is the gesture it asks for: " + bannerText(),
        bannerText().contains("cannot tell whose it is"));
  }

  /**
   * Tapping the banner is not a deliberate response to the caution.
   *
   * <p>{@code resetChosenContactAndInfoText} is the other unconditional banner writer, and the
   * caution work did not reach it: it guarded on {@code mWarningStanding} alone. It runs when the
   * user taps the banner - a natural thing to do with a notice you have just read - and when any
   * contact is deleted, so deleting Alice painted "No contact chosen" over a caution about Carol.
   * That is the cross-contact erase {@code clearCautionIfAbout} is scoped to prevent, arriving one
   * line after it.
   *
   * <p>The flag stays up throughout, so this is again an erase of text with the warning standing -
   * which is why the invariant sweep now asserts the words survive and not only that something is
   * on the banner.
   */
  @Test
  public void thecautionSurvivesTappingTheBanner() throws Exception {
    addCarol();
    assertFalse("precondition: the caution must be the only thing holding the banner",
        strip.warningIsStandingForTest());

    strip.findViewById(R.id.e2ee_info_text).performClick();

    assertTrue("tapping the banner must not erase the caution: " + bannerText(),
        bannerText().contains("cannot tell whose it is"));
  }

  /**
   * The banner must not go on naming a recipient the strip has moved away from.
   *
   * <p>A caution carries the same "Sending to: X" line a warning does, and the repaint that keeps
   * it current fired only for warnings. So opening someone else's verify screen - a badge tap in
   * the contact list, an ordinary thing to do while a notice tells you to compare a number - left
   * the banner saying "Sending to: Carol" with the chosen contact moved to Bob, and Encrypt
   * encrypts to the chosen contact.
   *
   * <p>Not visible to the invariant sweep, and that is worth saying rather than assuming otherwise:
   * the sweep checks that a standing item's words survive, and here they do. What went stale is the
   * line underneath them.
   */
  @Test
  public void thebannerDoesNotNameArecipientTheStripHasMovedAwayFrom() throws Exception {
    establishedContact();
    final Contact carol = addCarol();
    strip.selectContact(carol);
    assertTrue("precondition: the banner must name Carol as the recipient: " + bannerText(),
        bannerText().contains("Sending to: " + SignalProtocolMain.displayLabelFor(carol)));

    // A badge tap on Bob's row opens his verify screen and moves the chosen contact.
    strip.showVerifyContactForTest(victim.getContactList().get(0));

    final String shown = bannerText();
    assertFalse("the banner must not still name Carol once the strip has moved to Bob - Encrypt "
            + "encrypts to the chosen contact, so this is the banner saying something false about "
            + "where the next message goes. Shown: " + shown,
        shown.contains("Sending to: " + SignalProtocolMain.displayLabelFor(carol)));
  }

  /**
   * And a password field must not erase the caution even for the moment it is up.
   *
   * <p>The notice is transient and heals when the field stops being a password field, so the
   * invariant sweep - which fires both transitions in one event - only ever observes the healed
   * state. For a warning the mid-state is harmless, because the notice is suppressed outright. For
   * the caution it was not: the messenger owns the inputType of every field it presents, so it
   * chooses when this happens and for how long.
   */
  @Test
  public void apasswordFieldDoesNotEraseTheCautionWhileItIsUp() throws Exception {
    addCarol();

    strip.setHostFieldIsPassword(true);

    assertTrue("the caution must survive the password-field notice while that notice is up, not "
            + "only after it clears: " + bannerText(),
        bannerText().contains("cannot tell whose it is"));
  }

  /**
   * A substitution detected while a caution stands must still be warned about.
   *
   * <p>`warnIfIdentityChanged` is the thing that RAISES the identity-change warning on the
   * add-contact arm — `createSessionWithContact` only shows a Toast, and the ciphertext path that
   * would otherwise post it is not taken by a bundle-only invite. It sat on the right of an `&amp;&amp;`
   * whose left term was widened to cover the caution, so a standing caution stopped it being called
   * at all: the app detected a key substitution at a pinned address and put nothing on the only
   * surface that persists.
   *
   * <p>The state is ordinary. Adding any contact leaves a caution standing; deleting a contact is
   * the app's own advice after a decryption failure, which the messenger can induce; and deleting
   * one deliberately KEEPS the pinned identity, which is what makes the next invite at that address
   * a detectable substitution rather than a first sighting.
   */
  @Test
  public void asubstitutionIsWarnedAboutEvenWhileAcautionStands() throws Exception {
    establishedContact();
    assertTrue("precondition: Bob's key must be pinned", SignalProtocolMain.hasPinnedKey(peerAddress));

    // The user follows the app's advice and deletes Bob. The pin outlives the row.
    SignalProtocolMain.removeContactFromContactListAndProtocol(victim.getContactList().get(0));
    assertTrue("precondition: the pin must survive the deletion",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    addCarol();
    assertFalse("precondition: only a caution stands, no warning",
        strip.warningIsStandingForTest());

    // A forged bundle at Bob's address: different identity, so the change is recorded and refused.
    SignalProtocolMain.initialize(null);
    final String impostorBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field))
        .setText("Bob");
    ((android.widget.EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field))
        .setText("Jones");
    strip.addContactForTest(new MessageEnvelope(
        EnvelopeCodec.fromWire(impostorBundle).getPreKeyResponse(),
        peerAddress.getName(), peerAddress.getDeviceId()));

    assertTrue("a detected key substitution must reach the banner even when a caution is already "
            + "standing. The banner holds both; a caution is not a reason to stay silent about the "
            + "highest-signal security event the protocol produces.",
        strip.warningIsStandingForTest());
  }

  /**
   * An accepted rotation must not be reported as refused when its message fails to decrypt.
   *
   * <p><b>This does not pin the announcement, and it is named for what it does pin.</b> The
   * announcement was gated on a return value that had stopped meaning "the bundle was accepted";
   * that gate is corrected, and the correction is <em>unobservable</em>, because the line it
   * controls is repainted by {@code showChosenContactInMainInfoField} before any user sees it. A
   * reviewer checked and the old and new implementations leave a byte-identical banner. Naming this
   * test after the announcement would claim coverage it cannot have.
   *
   * <p>What it does pin is worth having on its own: a rotation whose bundle was accepted must not be
   * reported as <em>refused</em> because the message stapled to it failed to decrypt. Replay and
   * out-of-order delivery are ordinary and the relay chooses when they happen; refusals are standing
   * warnings, which survive the repaint by design.
   */
  @Test
  public void agoodRotationWhoseMessageFailsIsNotReportedAsRefused() throws Exception {
    establishedContact();

    // Adding a contact leaves the creation caution standing, and a standing item correctly holds
    // the banner - so it would suppress the rotation notice and this test would be about the wrong
    // thing. The user clears it the way the caution asks: compare the number and press Verify.
    strip.selectContact(victim.getContactList().get(0));
    strip.loadFingerprintInVerifyContactView();
    strip.findViewById(R.id.e2ee_verify_contact_verify_button).performClick();
    assertTrue("precondition: the banner must be free before the rotation arrives",
        strip.mayOverwriteInfoBanner());

    // A genuine rotation: a full, valid bundle attached to a message that will not decrypt.
    activate(peer);
    final String freshBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);

    final byte[] undecryptable = new byte[48];
    for (int i = 0; i < undecryptable.length; i++) undecryptable[i] = (byte) (i * 11 + 5);
    final MessageEnvelope rotation = new MessageEnvelope(undecryptable,
        org.signal.libsignal.protocol.message.CiphertextMessage.WHISPER_TYPE,
        peerAddress.getName(), peerAddress.getDeviceId());
    rotation.setPreKeyResponse(EnvelopeCodec.fromWire(freshBundle).getPreKeyResponse());
    // A GENUINE rotation: the issuer's bundle and its own signature, unedited.
    rotation.setBundleSignature(EnvelopeCodec.fromWire(freshBundle).getBundleSignature());

    paste(rotation);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    // Asserted as "no refusal is claimed", not as "the update line is on the banner".
    //
    // Measured, and it changed what this test could honestly claim: the informational line IS
    // written, and decryptMessageInClipboard's trailing showChosenContactInMainInfoField repaints
    // over it before the user sees anything - the same repaint that made the "Detected contact"
    // gate inert. So on this route the announcement is not observable either way, and a test
    // asserting it would be testing the repaint order rather than the app.
    //
    // What IS observable, and what actually matters, is that a rotation whose bundle was accepted
    // must not be reported as refused. The refusal is a standing warning, which survives the
    // repaint by design.
    final String shown = bannerText();
    assertFalse("a rotation whose bundle was accepted must not be reported as refused just because "
            + "the message stapled to it did not decrypt - replay and out-of-order delivery are "
            + "ordinary, and the relay chooses when they happen. Shown: " + shown,
        shown.contains("could not be used"));
    assertTrue("and nothing may be left standing over the banner after a healthy rotation",
        strip.mayOverwriteInfoBanner());
  }

  /**
   * A refused invite is still said when a warning already holds the banner.
   *
   * <p>The banner suppression is right — a refusal must not paint over a detected key substitution —
   * but the FACT was being suppressed with the sentence. So an attacker who first raises any cheap
   * warning, which one forged bundle does, could then strip the one-time pre-key from every
   * subsequent invite (one unsigned byte, covered by neither signature) and "it does not verify,
   * which means it was changed on the way here" was never shown at all. That claim is strictly
   * stronger than anything the pin caution says, and it was the half being dropped.
   *
   * <p>It is said on a channel that cannot displace the warning. Three and a half seconds is a poor
   * surface for it and is strictly better than nothing.
   */
  @Test
  public void arefusedInviteIsRecordedEvenWhenAwarningHoldsTheBanner() throws Exception {
    contactRowWithoutASession();
    // Any warning at all, which is what an attacker arranges first.
    strip.setWarningMessageForTest("Careful: something else entirely.");

    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("the standing warning must survive - suppressing the refusal's banner text is "
            + "correct: " + bannerText(), bannerText().contains("something else entirely"));
    // Asserted on the surface the user actually gets, because that is the whole of what the app
    // does here. A field recording the fact was tried and removed: nothing read it, so it was a
    // variable rather than a separation, and the separation that mattered was making the toast
    // unconditional.
    assertTrue("the refusal must still be said. Suppressing it with the banner text let one cheap "
            + "warning buy silence on every tampered invite that followed.",
        org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null
            && org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
                .contains("changed on the way here"));
  }

  /**
   * And it comes back when the user looks at the contact, like every other warning here.
   *
   * <p>This was the one warning nothing re-derived. The banner holds a single warning and any other
   * writer takes it — including the condition warning that a keyboard raise re-raises on every
   * raise while a store fault stands, which is a rate the messenger sets by presenting a text
   * field. {@code selectContact} re-derives the shared name, the rejection and the identity change,
   * so painting over those is a displacement; painting over this one was an erasure, and what it
   * erased is the notice bought back from a relay that otherwise gets silence for one stripped
   * unsigned byte per message.
   *
   * <p>The sentence is remembered rather than a flag, because the three outcomes have three texts
   * and they are not interchangeable — one says nothing was set up, one says a key was pinned
   * anyway, one says what you already had is unchanged. Re-deriving from a flag would have to pick,
   * and picking wrong writes a false sentence onto the only durable surface this app has.
   */
  @Test
  public void arefusedInviteComesBackWhenTheContactIsLookedAt() throws Exception {
    contactRowWithoutASession();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    final String said = bannerText();
    assertTrue("precondition: the refusal must be standing: " + said,
        said.contains("changed on the way here"));

    // Anything else takes the slot. A raise while a store fault stands does this on its own.
    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: the slot must have been taken",
        !bannerText().contains("changed on the way here"));

    strip.selectContact(strip.chosenContactForTest());

    assertTrue("looking at the contact must work it out again. Without that, one cheap warning "
            + "erases 'that invite was changed on the way here' for good - and the raise that "
            + "re-raises a storage warning does it on a schedule the messenger picks: "
            + bannerText(),
        bannerText().contains("changed on the way here"));
    assertEquals("and it must be the sentence that was actually said, not a re-guess at which of "
            + "the three outcomes applied", said, bannerText());
  }

  /** And a later good invite retracts it, so it does not come back after the user has fixed it. */
  @Test
  public void agoodInviteStopsTheRefusalComingBack() throws Exception {
    contactRowWithoutASession();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: the refusal must be standing",
        bannerText().contains("changed on the way here"));

    // They send another, and this one arrives intact.
    paste(EnvelopeCodec.fromWire(genuineBundle));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    strip.selectContact(strip.chosenContactForTest());

    assertTrue("a refusal the user has already resolved must not be re-asserted on every "
            + "selection: the record is retracted by the same event that retracts the warning: "
            + bannerText(),
        !bannerText().contains("changed on the way here"));
  }

  /**
   * The fact is recorded whether or not the sentence is painted.
   *
   * <p>This is the case the recomputability was FOR, and the round that added the record put the
   * write inside the guard that decides whether to paint — so the erasure was still open in exactly
   * the scenario its own commit message described. Two reviewers found it independently.
   *
   * <p>The attack costs the relay one cheap warning and then one unsigned byte per message: raise
   * anything at all, and every tampered invite afterwards takes the toast-only arm. If the fact is
   * not written there, "that invite was changed on the way here" exists nowhere once those three
   * and a half seconds elapse.
   */
  @Test
  public void arefusalIsRecordedEvenWhenItsSentenceIsSuppressed() throws Exception {
    contactRowWithoutASession();
    // The cheap warning that takes the slot first.
    strip.setWarningMessageForTest("Careful: something else entirely.");

    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertTrue("precondition: the sentence must be suppressed, which is correct: " + bannerText(),
        !bannerText().contains("changed on the way here"));

    // The user resolves the unrelated warning, and looks at the contact.
    strip.resetChosenContactAndInfoTextForTest();
    strip.selectContact(victim.getContactList().get(0));

    assertTrue("the refusal must have been recorded while it was being suppressed, or one cheap "
            + "warning still buys a relay silence on every tampered invite that follows - for one "
            + "unsigned byte each, which is the trade this warning exists to close: " + bannerText(),
        bannerText().contains("changed on the way here"));
  }

  /**
   * And answering it ends it. Verifying, rejecting or deleting — the three deliberate responses
   * this project's own classification names as what ends an event warning.
   *
   * <p>The record had only the third, so the warning came back on the next selection after the user
   * had done exactly what it asked, held {@code mWarningStanding}, and suppressed every routine
   * line for that contact for the life of the process.
   */
  @Test
  public void answeringTherefusalEndsIt() throws Exception {
    contactRowWithoutASession();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: the refusal must be standing", 
        bannerText().contains("changed on the way here"));

    // Reject, not Verify. A refused invite leaves the row keyless, so verifyContact refuses for
    // the right reason - there is no number to have compared - and returns before its clears. The
    // response actually available here is the one the escape hatch keeps live while a warning about
    // this contact stands, and it is the sharper case: rejectContactKey discards the pin, so a
    // refusal that came back afterwards would come back FALSE, claiming a key had been set up at an
    // address whose key had just been discarded.
    strip.showVerifyContactForTest(strip.chosenContactForTest());
    strip.findViewById(R.id.e2ee_verify_contact_reject_button).performClick();

    strip.selectContact(victim.getContactList().get(0));

    assertTrue("a warning that survives the deliberate response the whole family rests on is how "
            + "users learn to ignore the banner - and this one holds mWarningStanding, so it "
            + "suppresses every routine line for that contact for the life of the process: "
            + bannerText(),
        !bannerText().contains("changed on the way here"));
  }

  /**
   * A remembered sentence must not outlive the state it describes.
   *
   * <p>"Nothing has been set up" is retracted by a later good BUNDLE. A bundle-less PreKey message
   * from the same address pins a messenger-supplied key by trust-on-first-use and carries no
   * bundle, so it retracts nothing — and the next selection repainted "Nothing has been set up. Ask
   * them to send another" over an address that now holds a key the app is encrypting to. That is
   * the danger of storing a sentence, and it is the state a successful substitution leaves the app
   * in.
   *
   * <p>Upgraded rather than dropped: the invite really was changed in transit, and the sentence it
   * becomes is the one written for this state, which tells the user to compare the number.
   */
  @Test
  public void aremeberedRefusalDoesNotOutliveTheStateItDescribes() throws Exception {
    contactRowWithoutASession();
    paste(strippedInvite());
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
    assertTrue("precondition: the plain refusal must be standing: " + bannerText(),
        bannerText().contains("Nothing has been set up"));

    // A key arrives with no bundle at all, and is pinned by trust-on-first-use.
    strip.processSignalMessageForTest(bundlelessPreKeyMessage(), strip.chosenContactForTest());
    assertTrue("precondition: a key must now be pinned at that address",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    strip.selectContact(victim.getContactList().get(0));

    assertTrue("the durable surface must not say nothing was set up over a pinned key the app is "
            + "encrypting to: " + bannerText(),
        !bannerText().contains("Nothing has been set up"));
    assertTrue("and it must still say the invite was changed in transit, which is still true: "
            + bannerText(), bannerText().contains("changed on the way here"));
  }
}
