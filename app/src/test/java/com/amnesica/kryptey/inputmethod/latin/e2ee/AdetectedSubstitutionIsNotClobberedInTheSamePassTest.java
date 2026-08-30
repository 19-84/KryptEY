package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

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
 * A detected key substitution must still be on the banner at the end of the pass that detected it.
 *
 * <p>{@code selectContact} states the rank in forty lines of comment — "shared name, then rejection,
 * then identity change. A detected key substitution outranks everything and is written last" — and
 * the three decrypt arms write in the reverse of it: the identity-change warning first (from inside
 * {@code decryptMessageAndShowMessageInMainInputField}), then the rejection warning, then the
 * same-key warning. {@code setWarningMessage} is last-writer-wins.
 *
 * <p>Displacement is normally survivable here, and the file's no-yield trade rests on that: every
 * warning is re-derived on the next selection, so a displaced one comes back. This file's own rule
 * says where that argument stops — <em>"Recomputable and never rendered is not a displacement, it is
 * a permanent loss."</em> On these arms the displacer is recomputed in the SAME pass, unconditionally,
 * so the higher-ranked sentence is written and overwritten before anything is drawn. The user never
 * sees it.
 *
 * <p>Both preconditions are attacker-reachable together, which is the part that makes this more than
 * a comment being wrong. The recipe is recorded in this file already, for the verify screen rather
 * than the banner: reject a key, let the attacker's next bundle be re-pinned by trust-on-first-use,
 * then let them post once more to record a pending change. Both store facts then hold at one
 * address, and one paste writes both warnings.
 */
@RunWith(RobolectricTestRunner.class)
public class AdetectedSubstitutionIsNotClobberedInTheSamePassTest {

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
    assertTrue("fixture: the peer's genuine bundle must be accepted",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = newStrip();
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
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

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  private String bannerText() {
    final android.widget.TextView view = strip.findViewById(R.id.e2ee_info_text);
    return view == null ? "" : view.getText().toString();
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

  /** Somebody else's genuine bundle, relabelled to arrive at the peer's address. */
  private com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope substitutedForPeer()
      throws Exception {
    SignalProtocolMain.initialize(null);
    final String impostor = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    return com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
        EnvelopeCodec.fromWire(impostor),
        new com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope(
            EnvelopeCodec.fromWire(impostor).getPreKeyResponse(),
            peerAddress.getName(), peerAddress.getDeviceId()));
  }

  @Test
  public void therejectionWarningMustNotBuryTheSubstitutionThatArrivedWithIt() throws Exception {
    // Reject: the pin and any pending change go, the rejection stays.
    assertTrue("fixture: the rejection must land", SignalProtocolMain.rejectContactKey(bob()));
    // Re-pinned by trust-on-first-use, because rejecting removed the pin. Now BOTH store facts the
    // two warnings read are true at this one address.
    assertTrue("fixture: the peer's bundle must re-pin after the rejection",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));
    assertTrue("precondition: the address must still count as rejected",
        SignalProtocolMain.wasKeyRejected(peerAddress));

    // One paste, carrying a substitution. This raises the identity-change warning inside the
    // decrypt, and then the rejection warning is written over it before the pass ends.
    pasteAndDecrypt(substitutedForPeer());

    assertTrue("the banner must still carry the detected substitution at the end of the pass that "
            + "detected it. This is the app's only durable surface, the rank its own comment states "
            + "puts a substitution above a rejection, and both sentences are written in one pass - "
            + "so the higher one is never drawn rather than merely displaced. Banner now: "
            + bannerText(),
        bannerText().contains("different key"));
  }
}
