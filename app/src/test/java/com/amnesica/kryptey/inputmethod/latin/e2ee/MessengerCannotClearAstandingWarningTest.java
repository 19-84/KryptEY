package com.amnesica.kryptey.inputmethod.latin.e2ee;

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
  }

  private java.util.List<Warning> everyWarning() {
    final java.util.List<Warning> warnings = new java.util.ArrayList<>();
    warnings.add(new Warning() {
      @Override public String name() { return "a generic warning about a contact"; }
      @Override public void raise() {
        strip.setWarningMessageAboutForTest("Careful: something is wrong with Bob's key.", bob());
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the refused-invite warning"; }
      @Override public void raise() throws Exception {
        // Through the real path: a relay strips the one-time pre-key from a re-invite.
        pasteAndDecrypt(strippedInvite());
      }
    });
    warnings.add(new Warning() {
      @Override public String name() { return "the identity-change warning"; }
      @Override public void raise() throws Exception {
        // A third party's bundle relabelled with Bob's address: a substitution.
        SignalProtocolMain.initialize(null);
        final String impostor = SignalProtocolMain.exportOwnKeyBundle();
        SignalProtocolMain.getInstance().setAccount(victim);
        final com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope substituted =
            new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
                EnvelopeCodec.fromWire(impostor).getPreKeyResponse(),
                peerAddress.getName(), peerAddress.getDeviceId());
        pasteAndDecrypt(substituted);
      }
    });
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
    return new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
        new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
            genuine.getIdentityKey(), devices),
        peerAddress.getName(), peerAddress.getDeviceId());
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
    forEveryWarning("an incoming key bundle from an unrelated address",
        () -> strip.processIncomingEnvelopeForTest(unrelatedInvite()));
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
}
