package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;

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
 * One invariant, checked against every event the messenger can cause: a standing warning survives.
 *
 * <p>{@code mWarningStanding}'s javadoc states this outright — <em>"Nothing the messenger can cause
 * clears it"</em> — and it is the property the whole warning mechanism rests on. A warning that a
 * relay can take down is not a warning; the security event it described has no other surface, and
 * nothing re-raises it.
 *
 * <p>It is written as a mechanical sweep rather than as another example because of how this defect
 * behaves. Four consecutive review rounds each found a violation of it, and <b>each was introduced
 * by the previous round's fix</b> — most recently a "soft warning" that yielded to ordinary notices
 * so that a caution about a different contact could still be shown. That change looked local. It
 * was not: one of the six callers of the writer it relaxed is the password-field notice, which
 * {@code LatinIME} raises on every input session from the host field's {@code inputType} — and the
 * messenger owns the inputType of every field it presents. Focus a password field, and the warning
 * was gone with no user action at all.
 *
 * <p>The pattern is that the erase is never in the code being changed. It is in a caller nobody was
 * looking at. A list of events, applied to every warning, is the shape that finds those; the point
 * is that adding a new writer or a new event costs a build failure rather than a review round.
 *
 * <p><b>What this cannot do:</b> it enumerates the events someone thought of. It does not prove the
 * list is complete, and a genuinely new route into the strip would need adding here by hand. What
 * it does is make the routes that ARE known impossible to reopen quietly.
 */
@RunWith(RobolectricTestRunner.class)
public class MessengerCannotClearAstandingWarningTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String peerBundle;

  @Before
  public void setUp() throws Exception {
    freshFixture();
}

  private void freshFixture() throws Exception {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = newStrip();
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  
    // Inside freshFixture, not in setUp: this is called again per test to rebuild the world, and
    // each rebuild re-initialises SignalProtocolMain, which drops the helper with it.
    TestStores.writesLand();
}

  @After
  public void tearDown() {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private E2EEStripView newStrip() {
    final E2EEStripView created = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    created.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, created);
    return created;
  }

  private String bannerText() {
    final android.widget.TextView view = strip.findViewById(R.id.e2ee_info_text);
    return view == null ? "" : view.getText().toString();
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  /**
   * Every kind of warning the app can leave standing.
   *
   * <p>The cross product is the point, and the first version of this file did not have it: it
   * raised one generic warning, and a control proved that the round-10 defect - which lived on the
   * refused-invite warning specifically - walked straight through. A sweep over events alone
   * measures only the warnings the author happened to construct.
   */
  private interface Warning {
    String name();

    void raise() throws Exception;

    /**
     * A distinctive phrase from what this warning puts on screen.
     *
     * <p>Because watching the flag is not enough, and two review rounds proved it. A warning can be
     * left standing while the words are repainted away - the caution was erased that way by a
     * clipboard post, and by a warning about an unrelated contact being re-posted - and in both
     * cases {@code mayOverwriteInfoBanner} kept answering "something is standing" while the user
     * looked at a banner that no longer said it.
     */
    String fragment();
  }

  private java.util.List<Warning> everyWarning() {
    final java.util.List<Warning> warnings = new java.util.ArrayList<>();
    warnings.add(new Warning() {
      @Override public String name() { return "a generic warning about a contact"; }
      @Override public String fragment() { return "something is wrong with Bob"; }
      @Override public void raise() {
        strip.setWarningMessageAboutForTest("Careful: something is wrong with Bob's key.", bob());
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the refused-invite warning"; }
      @Override public String fragment() { return "could not be used"; }
      @Override public void raise() throws Exception {
        // Through the real path: a relay strips the one-time pre-key from a re-invite.
        pasteAndDecrypt(strippedInvite());
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the identity-change warning"; }
      @Override public String fragment() { return "different key"; }
      @Override public void raise() throws Exception {
        // A third party's bundle relabelled with Bob's address: a substitution.
        SignalProtocolMain.initialize(null);
        final String impostor = SignalProtocolMain.exportOwnKeyBundle();
        SignalProtocolMain.getInstance().setAccount(victim);
        // Only the address is relabelled; the impostor's bundle and its own signature are intact,
        // which is what makes this a substitution rather than an edit the signature would catch.
        final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope substituted =
            com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
                EnvelopeCodec.fromWire(impostor),
                new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
                    EnvelopeCodec.fromWire(impostor).getPreKeyResponse(),
                    peerAddress.getName(), peerAddress.getDeviceId()));
        pasteAndDecrypt(substituted);
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the post-rejection re-pin warning"; }
      @Override public String fragment() { return "not to trust keys arriving"; }
      @Override public void raise() throws Exception {
        assertTrue(SignalProtocolMain.rejectContactKey(bob()));
        // A fresh bundle at the rejected address re-pins, which is what the warning is about.
        SignalProtocolMain.initialize(null);
        final String fresh = SignalProtocolMain.exportOwnKeyBundle();
        SignalProtocolMain.getInstance().setAccount(victim);
        final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope rePin =
            com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
                EnvelopeCodec.fromWire(fresh),
                new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
                    EnvelopeCodec.fromWire(fresh).getPreKeyResponse(),
                    peerAddress.getName(), peerAddress.getDeviceId()));
        pasteAndDecrypt(rePin);
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the duplicate-name warning"; }
      @Override public String fragment() { return "already have a contact"; }
      @Override public void raise() throws Exception {
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
        strip.addContactForTest(unrelatedInvite());
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the same-address-different-name warning"; }
      @Override public String fragment() { return "would be a second name for the same person"; }
      @Override public void raise() throws Exception {
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_first_name_input_field)).setText("Robert");
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
        strip.addContactForTest(EnvelopeCodec.fromWire(peerBundle));
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the new-contact caution"; }
      @Override public String fragment() { return "cannot tell whose it is"; }
      @Override public void raise() throws Exception {
        // Not a warning, but a standing item with the same requirement - and the one a review round
        // found erased twice, by events this sweep already lists.
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_first_name_input_field)).setText("Carol");
        ((android.widget.EditText) strip.findViewById(
            R.id.e2ee_add_contact_last_name_input_field)).setText("Smith");
        strip.addContactForTest(unrelatedInvite());
      }
    });
    // INFO_STORAGE_UNREADABLE is deliberately NOT in this list, and the reason is worth writing
    // down rather than leaving as an omission. This sweep observes the invariant through
    // mayOverwriteInfoBanner, which returns false when EITHER a warning stands OR storage is
    // unreadable - and the storage warning is only ever raised while storage is unreadable. So both
    // the precondition and the assertion would hold whether or not the warning survived: a row that
    // can never fail, reported as coverage. The property it would test is enforced by a different
    // mechanism anyway (storageIsUnreadable() is asked directly at every decision point, not
    // through the flag), and StripWarningErasureTest covers that path.
    return warnings;
  }

  /** The relay's edit: an invite with its one-time pre-key deleted. */
  private com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope strippedInvite()
      throws Exception {
    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse genuine =
        EnvelopeCodec.fromWire(peerBundle).getPreKeyResponse();
    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem device =
        genuine.getDevices().get(0);
    final java.util.List<com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem>
        devices = new java.util.ArrayList<>();
    devices.add(new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem(
        device.getDeviceId(), device.getRegistrationId(), device.getSignedPreKey(), null,
        device.getKyberPreKey()));
    // The issuer's own signature over content it no longer covers: a relay can copy an invite and
    // edit a field, and cannot sign the result.
    return com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
        EnvelopeCodec.fromWire(peerBundle),
        new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
                genuine.getIdentityKey(), devices),
            peerAddress.getName(), peerAddress.getDeviceId()));
  }

  private void pasteAndDecrypt(
      final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope envelope)
      throws Exception {
    final android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("",
        com.amnesica.kryptey.inputmethod.signalprotocol.encoding.RawEncoder.encode(
            EnvelopeCodec.toWire(envelope))));
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();
  }

  /** An action to perform on the strip once a warning stands. */
  private interface Event {
    void fire() throws Exception;
  }

  /**
   * Applies one event to EVERY kind of warning, rebuilding the fixture between them.
   *
   * <p>Rebuilding matters: the warnings are raised through the real paths, which leave real
   * protocol state behind, and a second warning raised on top of the first would not be the state
   * under test.
   */
  private void forEveryWarning(final String event, final Event fire) throws Exception {
    for (final Warning warning : everyWarning()) {
      freshFixture();
      warning.raise();
      assertFalse("precondition: " + warning.name() + " must actually be standing, or this event "
          + "is being applied to nothing", strip.mayOverwriteInfoBanner());

      fire.fire();

      assertFalse(warning.name() + " was cleared by " + event + ". That event is one the messenger "
          + "can cause, so this makes the warning worthless: the security event it described has "
          + "no other surface and nothing re-raises it. mWarningStanding's own javadoc says "
          + "nothing the messenger can cause may clear it.", strip.mayOverwriteInfoBanner());

      // And the WORDS, which is the half the flag cannot speak for. Two rounds found erasures that
      // left the flag up and repainted the banner, and what the user reads is the banner.
      assertTrue(warning.name() + " still counts as standing after " + event + ", but its text is "
              + "gone from the banner - which is the whole of it, as far as the user is concerned. "
              + "Banner now: " + bannerText(),
          bannerText().contains(warning.fragment()));
    }
  }

  /**
   * The host field's inputType, which the messenger chooses for every field it shows.
   *
   * <p>The round-10 defect exactly: {@code LatinIME} calls this on every input session, so a
   * messenger that presents a password field - "your session expired, re-enter your PIN" - fired
   * the notice, and the notice took the warning with it.
   */
  @Test
  public void apasswordFieldNoticeDoesNotClearIt() throws Exception {
    forEveryWarning("the host field being declared a password field, then ordinary again", () -> {
      strip.setHostFieldIsPassword(true);
      strip.setHostFieldIsPassword(false);
    });
  }

  /** The clipboard, whose contents and timing the messenger controls completely. */
  @Test
  public void aclipboardChangeDoesNotClearIt() throws Exception {
    forEveryWarning("a clipboard change", () -> strip.onClipboardChangedForTest());
  }

  /**
   * An invite for somebody else, pasted.
   *
   * <p>{@code processPreKeyResponse} writes {@code INFO_PRE_KEY_DETECTED} at its head, before it
   * knows whose invite this is - so a warning about Bob must survive an invite concerning Alice.
   */
  /**
   * An invite from somebody unrelated, pasted while a warning about Bob stands.
   *
   * <p>Deliberately NOT Bob's own invite. A good invite from Bob retracts the refused-invite
   * warning about Bob, and that is the intended remedy - its own text asks the user to get one. The
   * first version of this test pasted Bob's bundle and read that retraction as an erase, which is
   * the test being wrong rather than the app. What must not happen is an invite concerning a
   * DIFFERENT address taking down a warning about Bob, and {@code processPreKeyResponse} writes
   * {@code INFO_PRE_KEY_DETECTED} at its head, before it knows whose invite it is holding.
   */
  @Test
  public void aninviteForAdifferentContactDoesNotClearIt() throws Exception {
    // Through the clipboard and the Decrypt button, NOT processIncomingEnvelopeForTest. That seam
    // always drives processSignalMessage whatever the envelope type, so a bundle-only invite ran
    // the "no contact found" arm - a Toast and a screen switch - instead of processPreKeyResponse,
    // whose first statement is the banner write this row exists to check. The row could not fail
    // for any warning it was crossed with.
    forEveryWarning("an incoming key bundle from an unrelated address",
        () -> pasteAndDecrypt(unrelatedInvite()));
  }

  /** A genuine invite from a third party at their own address. */
  private com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope unrelatedInvite()
      throws Exception {
    final Account keep = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account stranger = SignalProtocolMain.getInstance().getAccount();
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(keep);
    final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope invite =
        EnvelopeCodec.fromWire(bundle);
    invite.setSignalProtocolAddressName(stranger.getSignalProtocolAddress().getName());
    invite.setDeviceId(stranger.getDeviceId());
    return invite;
  }

  /**
   * A configuration change, which destroys and rebuilds the strip.
   *
   * <p>Messenger-reachable: an app can force a rotation or a theme change, and night mode arrives
   * on its own at sunset. The warning has to be carried, which is why {@code CarriedState} has
   * three fields for it and a test that fails when a fourth is added without a decision.
   */
  @Test
  public void arebuildDoesNotClearIt() throws Exception {
    forEveryWarning("a configuration change rebuilding the strip", () -> {
      final E2EEStripView rebuilt = newStrip();
      rebuilt.adoptState(strip.surrenderState());
      strip.clear();
      strip = rebuilt;
    });
  }

  /** Navigating the strip's own screens is not a deliberate response to anything. */
  /**
   * Hiding the keyboard, which any app may do whenever it likes.
   *
   * <p>Named by a review round as missing from this list two rounds ago, and it is the event behind
   * one of the erasures found since: {@code LatinIME.onWindowHidden} reaches
   * {@code forgetChosenRecipient} and from there the banner writer that did not know about the
   * caution. Listed now rather than left as something someone noticed once.
   */
  /**
   * Tapping the banner, which is what a notice invites the reader to do.
   *
   * <p>Added because the round-14 defect lived on this path and no event in this list reached it:
   * hiding the keyboard and selecting a contact both go through
   * {@code showChosenContactInMainInfoField}, while the banner tap goes through
   * {@code resetChosenContactAndInfoText} - a second unconditional writer that had to be taught the
   * same thing separately. Two writers, one rule, and the sweep only exercised one of them.
   */
  /**
   * Accepting an invite that then fails to build a session.
   *
   * <p>{@code addContactForTest} was used in this file only as a RAISER, never as an event - so the
   * whole add-contact route was invisible to the sweep, and a review round found the session-failure
   * line erasing a standing caution from exactly there. A path that only ever appears as a way to
   * set up state is a path nothing is checking.
   */
  @Test
  public void anotherContactWhoseInviteFailsDoesNotClearIt() throws Exception {
    forEveryWarning("adding a contact whose invite does not verify", () -> {
      ((android.widget.EditText) strip.findViewById(
          R.id.e2ee_add_contact_first_name_input_field)).setText("Dave");
      ((android.widget.EditText) strip.findViewById(
          R.id.e2ee_add_contact_last_name_input_field)).setText("Smith");
      strip.addContactForTest(splicedInvite());
    });
  }

  /**
   * Opening someone else's verify screen, which moves the chosen recipient.
   *
   * <p>The banner carries "Sending to: X" for a caution exactly as for a warning, and the repaint
   * that keeps that line current fired only for warnings - so it went on naming the old recipient
   * while Encrypt encrypted to the new one. Reached by tapping a badge in the contact list, which
   * is an ordinary thing to do while a notice is on screen telling you to compare a number.
   */
  @Test
  public void openingAnotherContactsVerifyScreenDoesNotClearIt() throws Exception {
    forEveryWarning("opening another contact's verify screen",
        () -> strip.showVerifyContactForTest(bob()));
  }

  /** A bundle whose signature cannot verify: one peer's identity over another's keys. */
  private com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope splicedInvite()
      throws Exception {
    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse genuine =
        EnvelopeCodec.fromWire(peerBundle).getPreKeyResponse();
    final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope stranger =
        unrelatedInvite();
    // The stranger's own signature travels with the fields taken from their invite: it verifies
    // against the stranger's identity key, and this bundle claims the peer's - which is exactly
    // what a splice between two identities now looks like on the wire.
    return com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(stranger,
        new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
                genuine.getIdentityKey(), stranger.getPreKeyResponse().getDevices()),
            stranger.getSignalProtocolAddressName(), stranger.getDeviceId()));
  }

  @Test
  public void tappingTheBannerDoesNotClearIt() throws Exception {
    forEveryWarning("tapping the banner",
        () -> strip.findViewById(R.id.e2ee_info_text).performClick());
  }

  @Test
  public void hidingTheKeyboardDoesNotClearIt() throws Exception {
    forEveryWarning("the host app hiding the keyboard", () -> strip.onKeyboardHidden());
  }

  @Test
  public void movingBetweenScreensDoesNotClearIt() throws Exception {
    forEveryWarning("moving between the strip's screens", () -> {
      strip.showContactListForTest();
      strip.showMessagesListForTest();
      strip.showMainViewForTest();
    });
  }

  /**
   * And choosing who to talk to is not a response to a warning about a key.
   *
   * <p>Tapping a contact row is a user action, but it is one the attacker's own text invites - and
   * a round found the strip clearing warnings here, which turned "read this and decide" into
   * "tap anything and it goes away".
   */
  @Test
  public void choosingAcontactDoesNotClearIt() throws Exception {
    forEveryWarning("selecting a contact", () -> strip.selectContact(bob()));
  }

  /**
   * Every warning the strip can raise is either swept above or excused here, by name.
   *
   * <p>Without this, the sweep covers the warnings its author happened to think of — which is
   * precisely how its first draft came to miss the one the round-10 defect lived on. The list of
   * events is a judgement that cannot be mechanised; the list of WARNINGS can be, because each one
   * is a constant handed to a warning writer in this file.
   *
   * <p>So a new warning fails this test until somebody either builds a raiser for it or writes down
   * why sweeping it would measure nothing. Both answers are fine. Neither being given is not.
   */
  @Test
  public void everywarningTheStripCanRaiseIsSweptOrExcused() throws java.io.IOException {
    final java.nio.file.Path source = mainSources()
        .resolve("com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java");
    assertTrue("expected to find " + source, java.nio.file.Files.exists(source));
    // Comments stripped first. This file discusses its own constants heavily - the identity-change
    // warning is named in prose in a method that raises a different one - and a scanner that reads
    // commentary as code reports warnings that are not raised anywhere.
    final String text = withoutComments(new String(java.nio.file.Files.readAllBytes(source),
        java.nio.charset.StandardCharsets.UTF_8));

    // Every INFO_ constant that reaches a warning writer, resolved through the METHOD rather than
    // through a character window.
    //
    // The first version scanned +-400 characters around each call. That missed
    // INFO_IDENTITY_CHANGED_EXISTING by sixteen characters of comment, and missed the duplicate-name
    // constants by forty-seven thousand, because those are chosen inside a helper. Three of the
    // eight names in SWEPT were therefore inert: deleting them failed nothing, and a new warning
    // written the way the identity-change warning is written - composed into a local, then handed
    // to the writer - would have passed with no raiser and no excuse. A guard that reports coverage
    // it does not have is worse than no guard, and this one was guarding the most important warning
    // in the file.
    final java.util.Set<String> raised = new java.util.TreeSet<>();
    for (final String body : methodsThatRaiseWarnings(text)) {
      final java.util.regex.Matcher name =
          java.util.regex.Pattern.compile("INFO_[A-Z0-9_]+").matcher(body);
      while (name.find()) raised.add(name.group());
    }
    // Eight is what the file actually raises today. Set to the real number rather than a loose
    // floor: a floor with slack in it is how the previous version could lose three warnings and
    // still claim to be reading the source.
    assertTrue("this test reads the strip's warning call sites out of its source and found only "
        + raised.size() + " (" + raised + "). If warnings were genuinely removed, lower this; if "
        + "not, the resolution has broken and the guard is no longer reading what it claims",
        raised.size() >= 8);

    final java.util.Set<String> accounted = new java.util.TreeSet<>(SWEPT);
    accounted.addAll(EXCUSED.keySet());
    accounted.addAll(NOT_WARNINGS.keySet());

    final java.util.List<String> unaccounted = new java.util.ArrayList<>();
    for (final String warning : raised) {
      if (!accounted.contains(warning)) unaccounted.add(warning);
    }

    assertEquals("these warnings can be raised by the strip but are neither swept by this file nor "
        + "excused in EXCUSED. A warning nobody constructs is a warning nobody tests: the sweep "
        + "then measures the warnings its author thought of, which is how its first draft missed "
        + "the one a review round had just found a defect on. Add a raiser, or add an entry to "
        + "EXCUSED saying why sweeping it would measure nothing:\n"
        + String.join("\n", unaccounted), 0, unaccounted.size());
  }

  /**
   * The body of every method that hands something to a warning writer, plus the bodies of the
   * helpers those methods call to compose the message.
   *
   * <p>One level of indirection is enough for this file and is stated rather than assumed: the
   * duplicate-name warning is chosen by {@code duplicateNameMessage}, and nothing else composes a
   * warning more than one call deep. A second level would need adding if that changes - and the
   * count assertion above is what would notice.
   */
  private static java.util.List<String> methodsThatRaiseWarnings(final String text) {
    final java.util.List<String> bodies = new java.util.ArrayList<>();
    final java.util.regex.Matcher call = java.util.regex.Pattern.compile(
        "(?:setWarningMessage|setInviteRefusalWarning)\\s*\\(").matcher(text);
    final java.util.Set<String> helpers = new java.util.TreeSet<>();
    while (call.find()) {
      final String body = enclosingMethod(text, call.start());
      bodies.add(body);
      // Helper calls that compose a message, e.g. duplicateNameMessage(...).
      final java.util.regex.Matcher helper =
          java.util.regex.Pattern.compile("\\b([a-z][A-Za-z0-9]*Message)\\s*\\(").matcher(body);
      while (helper.find()) helpers.add(helper.group(1));
    }
    for (final String helper : helpers) {
      final int at = text.indexOf(" " + helper + "(");
      if (at > 0) bodies.add(enclosingMethod(text, at));
    }
    return bodies;
  }

  /** From the signature line above {@code position} to the closing brace at method indentation. */
  private static String enclosingMethod(final String text, final int position) {
    int start = text.lastIndexOf("\n  private ", position);
    start = Math.max(start, text.lastIndexOf("\n  public ", position));
    start = Math.max(start, text.lastIndexOf("\n  void ", position));
    start = Math.max(start, text.lastIndexOf("\n  boolean ", position));
    start = Math.max(start, text.lastIndexOf("\n  String ", position));
    if (start < 0) start = 0;
    final int end = text.indexOf("\n  }", position);
    return text.substring(start, end < 0 ? text.length() : end);
  }

  private static String withoutComments(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\n]*", " ");
  }

  private static java.nio.file.Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final java.nio.file.Path path = java.nio.file.Paths.get(candidate);
      if (java.nio.file.Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + java.nio.file.Paths.get("").toAbsolutePath());
  }

  /** Warnings this file raises through their real paths and sweeps every event against. */
  private static final java.util.Set<String> SWEPT = new java.util.TreeSet<>(java.util.Arrays.asList(
      "INFO_INVITE_REFUSED", "INFO_INVITE_REFUSED_SESSION_KEPT",
      "INFO_INVITE_REFUSED_BUT_KEY_PINNED", "INFO_IDENTITY_CHANGED_EXISTING",
      "INFO_PINNED_AFTER_REJECT", "INFO_DUPLICATE_CONTACT_NAME", "INFO_RETIRED_CONTACT_NAME",
      "INFO_SAME_ADDRESS_DIFFERENT_NAME"));

  /**
   * Constants the scan reaches that are not warnings at all.
   *
   * <p>Resolution is by METHOD, so a notice written in the same method as a warning is collected
   * too. That over-collection is deliberate: it is safer to name every constant the scan touches
   * than to narrow the scan until it misses one, which is precisely how the previous version lost
   * the identity-change warning. Each of these is written through {@code setInfoTextViewMessage} or
   * a {@code Toast}, neither of which sets {@code mWarningStanding}.
   */
  private static final java.util.Map<String, String> NOT_WARNINGS = new java.util.TreeMap<>();

  static {
    NOT_WARNINGS.put("INFO_MESSAGE_DECRYPTION_FAILED", "a Toast, in the same method as the refusal warning");
    NOT_WARNINGS.put("INFO_MESSAGE_NOT_SAVED", "a Toast, same method");
    NOT_WARNINGS.put("INFO_SESSION_CREATION_FAILED", "a plain banner line in addContact, deliberately not a warning");
    NOT_WARNINGS.put("INFO_NO_CONTACT_CHOSEN_TEXT", "the opening banner, in the same method as the storage warning");
    NOT_WARNINGS.put("INFO_CONTACT_NOT_SAVED",
        "a Toast in addContact, which also raises warnings - it reports a failed write and does not "
            + "set mWarningStanding");
    NOT_WARNINGS.put("INFO_PASSWORD_FIELD",
        "an informational line that never sets mWarningStanding. It appears inside "
            + "refreshOpeningMessage because that method takes a condition warning DOWN and must "
            + "then choose what to paint: over a password field the opening line would lose the "
            + "notice, and nothing would bring it back until the guard next transitions. Sweeping "
            + "it would measure nothing, because it is re-derived from the host field's inputType "
            + "on every input session - and the messenger owns that inputType, which is why "
            + "setInfoUnlessWarned refuses to let it overwrite anything");
    NOT_WARNINGS.put("INFO_SESSION_NOT_SAVED",
        "the sibling of the line above, and it appears in addContact for the same reason: when the "
            + "contact ROW landed and the session write did not, the row sentence is false in both "
            + "of its claims and its advice is delete-and-re-invite. It is a caution and a Toast, "
            + "not a warning - it never sets mWarningStanding - and it is retired by a later landed "
            + "write rather than by anything the messenger can do");
  }

  /** And the ones deliberately not swept, each with the reason sweeping it would prove nothing. */
  private static final java.util.Map<String, String> EXCUSED = new java.util.TreeMap<>();

  static {
    EXCUSED.put("INFO_STORAGE_UNREADABLE",
        "observed through mayOverwriteInfoBanner, which returns false from storageIsUnreadable() "
            + "alone - so both the precondition and the assertion hold regardless of the warning, "
            + "and the row could never fail");
    EXCUSED.put("INFO_CONTACTS_UNREADABLE",
        "raised by refreshOpeningMessage from a state the messenger cannot enter or leave: the "
            + "stored contact list failing to decrypt. It is re-derived on every raise rather than "
            + "remembered, so an erase would be undone by the next setInputView and sweeping it "
            + "would measure the re-raise rather than the erase - the same reason its sibling above "
            + "is excused. What matters about it is that it is SAID at all, which is pinned by "
            + "AnUnreadableContactListIsNotOverwrittenTest");
  }
}
