package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The post-rejection warning has to reach the screen on the route a rejection actually leaves
 * behind.
 *
 * <p>{@code rejectedAddresses} is the one record deliberately made to outlive
 * {@code removeIdentity}: the address is unpinned, so the next bundle to arrive WILL be pinned by
 * trust-on-first-use and nothing can prevent that - what the record buys is that the pin is a
 * <em>warned</em> event rather than a silent first sighting. {@code PostRejectWindowTest} pins that
 * the record survives; this asks the next question, which is whether the user is ever shown it.
 *
 * <p>They are not, on the route the attacker uses. {@code INFO_PINNED_AFTER_REJECT} is written from
 * exactly one place, {@code showAddContactView}'s Add button - the screen for an envelope whose
 * address <em>no contact holds</em>. And rejecting deliberately keeps the contact row: it forgets
 * the key, the session and the badge, and leaves the row in the list. So on the very next paste
 * {@code extractContactFromEnvelope} finds that row, {@code processPreKeyResponse} takes its
 * {@code sender != null} arm, and that arm never asks {@code wasKeyRejected}. The bundle is pinned
 * under an ordinary "Detected contact:" line.
 *
 * <p>The state left by a completed rejection is therefore what routes the retry past the warning
 * written for it. The warning is reachable only if the user ALSO deletes the contact - which the
 * app never suggests here, and which the rest of the design argues against.
 */
@RunWith(RobolectricTestRunner.class)
public class PostRejectWarningReachTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String attackerBundle;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: a key must be pinned for this address first",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    // Wired as LatinIME wires it. The strip is passed as its own input view: setListener only
    // looks up the main keyboard view in it, which is absent here and is not what this is about.
    strip.setListener(new E2EEStripView.Listener() {
      @Override
      public void onTextInput(final String rawText) { }

      @Override
      public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    // An unreadable store suppresses the clipboard path on its own; pin it readable so nothing
    // below can pass for that reason.
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private String infoText() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return view.getText().toString();
  }

  /**
   * Pastes a bundle carrying the peer's address, as the messenger presents it.
   *
   * <p>The address name and device id are plaintext envelope fields nothing signs, and the
   * messenger reads both off the genuine bundle - which is the premise the whole first-contact
   * argument rests on. Re-labelling the envelope here is that step, not a test convenience.
   */
  private void pasteAsIfFromThePeer(final String wire) throws Exception {
    final MessageEnvelope original = EnvelopeCodec.fromWire(wire);
    // Address-only relabelling, which the issuing signature does not cover by design.
    final MessageEnvelope relabelled =
        com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(original,
            new MessageEnvelope(original.getPreKeyResponse(),
                peerAddress.getName(), peerAddress.getDeviceId()));
    final ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("",
        RawEncoder.encode(EnvelopeCodec.toWire(relabelled))));
  }

  @Test
  public void abundleArrivingAtArejectedAddressIsWarnedAboutOnScreen() throws Exception {
    // The user compared safety numbers by voice, they did not match, and they pressed "does not
    // match". This is exactly what the reject button's click listener does to the model.
    assertTrue("precondition: a pinned key must have been forgotten",
        SignalProtocolMain.rejectContactKey(bob()));
    assertTrue("precondition: the rejection must be on record",
        SignalProtocolMain.wasKeyRejected(peerAddress));
    assertNull("precondition: nothing must be pinned for this address any more",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse("precondition: rejecting keeps the contact row - that is what routes the next "
        + "paste away from the add-contact screen", victim.getContactList().isEmpty());

    // The attacker simply re-delivers. The user pastes it and presses Decrypt.
    pasteAsIfFromThePeer(attackerBundle);
    strip.findViewById(R.id.e2ee_button_decrypt).performClick();

    assertNotNull("precondition: the re-delivered bundle is pinned - the address is genuinely "
            + "unpinned, so trust-on-first-use accepts it and nothing can stop that",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    final String toast = ShadowToast.getTextOfLatestToast();
    final String shown = infoText() + " | " + (toast == null ? "" : toast);
    assertTrue("a key pinned at an address the user rejected must not be a silent first sighting. "
            + "INFO_PINNED_AFTER_REJECT is written only from the add-contact screen, and rejecting "
            + "keeps the contact row, so this paste took the 'contact already known' arm and the "
            + "rejection record was consulted by nothing. What the user was shown: " + shown,
        shown.contains("not to trust keys arriving"));
  }
}
