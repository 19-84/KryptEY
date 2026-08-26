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
}
