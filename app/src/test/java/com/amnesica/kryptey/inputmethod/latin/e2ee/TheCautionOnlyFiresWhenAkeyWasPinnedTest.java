package com.amnesica.kryptey.inputmethod.latin.e2ee;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
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
 * The caution says a key was pinned, so it must not fire when none was.
 *
 * <p>The caution added last commit — <em>"This key reached you through the messenger and the app
 * cannot tell whose it is, compare the security number by voice"</em> — was posted unconditionally
 * on the arm that handles a message with no bundle. That arm attempts a decrypt and discards its
 * result, and a decrypt that fails pins nothing.
 *
 * <p><b>What the messenger buys with one crafted paste.</b> Put an envelope on the clipboard with no
 * bundle and arbitrary bytes as its ciphertext. The type is decided by field presence, so it reaches
 * this arm; there is no session, so the decrypt throws and returns null; the row has already been
 * created. The app then tells the user a key arrived that it cannot attribute and sends them to
 * compare a security number — for which there is no fingerprint, so that screen renders blank
 * digits, and its Reject button is re-armed only when a <em>warning</em> stands, which a caution is
 * not. A false security claim pointing at a screen that can do nothing.
 *
 * <p>And because a standing caution makes {@code mayOverwriteInfoBanner} refuse, the banner is then
 * held for the life of the strip: every later clipboard hint is suppressed. The only way down is
 * deleting the contact.
 *
 * <p>The file already had the right shape twice — {@code warnIfKeyWasRejected} is gated on an actual
 * pin, and {@code decryptMessageAndShowMessageInMainInputField} computes {@code keyPinnedByThisPaste}
 * before choosing its wording. The new caution was written without either.
 */
@RunWith(RobolectricTestRunner.class)
public class TheCautionOnlyFiresWhenAkeyWasPinnedTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String genuineBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    genuineBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
      // This fixture depends on writes landing; see TestStores.
    TestStores.writesLand();
}

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    final TextView view = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(view);
    return String.valueOf(view.getText());
  }

  /** Bytes that are not a message, in an envelope with no bundle beside them. */
  private MessageEnvelope rubbishCiphertext() throws Exception {
    return EnvelopeCodec.fromWire(EnvelopeCodec.toWire(new MessageEnvelope(
        new byte[] {3, 9, 9, 9, 9, 9, 9, 9}, 2, peerAddress.getName(),
        peerAddress.getDeviceId())));
  }

  @Test
  public void adecryptThatPinsNothingClaimsNothing() throws Exception {
    strip.addContactForTest(rubbishCiphertext());

    assertFalse("precondition: this paste must really pin nothing, or the assertion below is about "
            + "a different state",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    assertFalse("the app told the user a key arrived that it cannot attribute, and sent them to "
            + "compare a security number, when no key was pinned at all. The verify screen it "
            + "points at has no fingerprint to show and its Reject button stays dark, because that "
            + "is re-armed only for a standing WARNING. Banner: " + banner(),
        banner().contains("compare the security number"));
  }

  /**
   * And the caution must not paint over the accurate refusal beside it.
   *
   * <p>An envelope carrying both a bundle and a message runs both arms. When the bundle is refused,
   * the first arm writes "Could not set up a session from that invite. Ask your contact to send a
   * fresh one" — a plain line, not a standing item — and the second arm's repaint composes the
   * banner from the standing items alone, so that sentence is simply gone. What replaces it is a
   * claim that a contact was created and a key arrived: the exact inversion these commits exist to
   * remove.
   */
  @Test
  public void arefusedBundleKeepsItsRefusalOnScreen() throws Exception {
    // A bundle this app REFUSES: the relay's edit, one deleted one-time pre-key. Building this
    // case from a genuine bundle - as the first version did - establishes a session, pins a key,
    // and tests the opposite of what the name says.
    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse genuine =
        EnvelopeCodec.fromWire(genuineBundle).getPreKeyResponse();
    final com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem device =
        genuine.getDevices().get(0);
    assertNotNull("precondition: a genuine invite carries a one-time pre-key", device.getPreKey());
    final java.util.List<com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem>
        devices = new ArrayList<>();
    devices.add(new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem(
        device.getDeviceId(), device.getRegistrationId(), device.getSignedPreKey(), null,
        device.getKyberPreKey()));
    final MessageEnvelope both = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(
        new MessageEnvelope(
            new com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse(
                genuine.getIdentityKey(), devices),
            peerAddress.getName(), peerAddress.getDeviceId())));
    both.setCiphertextMessage(new byte[] {3, 9, 9, 9, 9, 9, 9, 9});
    both.setCiphertextType(2);

    strip.addContactForTest(EnvelopeCodec.fromWire(EnvelopeCodec.toWire(both)));

    assertFalse("a claim that a key arrived must not replace the sentence saying the invite was "
            + "refused - that sentence is the only thing telling the user to ask for a fresh one. "
            + "Banner: " + banner(),
        banner().contains("This key reached you through the messenger"));
  }

  /** The genuine case still gets its caution, or the gate has removed the notice entirely. */
  @Test
  public void arealpinStillGetsTheCaution() throws Exception {
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));

    assertTrue("precondition: a genuine invite must really pin a key",
        SignalProtocolMain.hasPinnedKey(peerAddress));
    assertTrue("the caution that fires because nothing was noticed must survive being gated: "
            + banner(), banner().contains("compare the security number"));
  }

  /**
   * A pin that survived a deletion is not a key that just arrived.
   *
   * <p>{@code removeContact} keeps the pinned identity on purpose — that is what stops a later
   * invite claiming to be the same person from being accepted silently — while removing the row. So
   * after a deletion the add-contact screen is reachable at an address that still holds a pin, and
   * "is a key pinned here?" answers yes about an event that happened weeks ago.
   *
   * <p>Asking that question was the first attempt at this gate, and it is wrong in both directions
   * that matter: it fires on the dishonest path, where the messenger reposts arbitrary bytes at a
   * deleted contact's address, and on the honest one, where the peer's message decrypts precisely
   * BECAUSE it matched the key the app already trusted — which the file elsewhere spends a paragraph
   * explaining is the opposite of an unattributable new key. The gate now asks whether the pin
   * <em>changed</em>.
   */
  @Test
  public void apinLeftBehindByAdeletionIsNotAnewKey() throws Exception {
    // Add Bob properly, so a key is pinned at his address.
    strip.addContactForTest(EnvelopeCodec.fromWire(genuineBundle));
    assertTrue("precondition: the genuine add must pin a key",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    // Delete him. The row goes; the pin deliberately stays.
    strip.removeContact(strip.chosenContactForTest());
    assertTrue("precondition: deletion must keep the pin - that is what this test is about",
        SignalProtocolMain.hasPinnedKey(peerAddress));

    // The messenger reposts arbitrary bytes at that address; the user adds the contact again.
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Bob");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Jones");
    strip.addContactForTest(rubbishCiphertext());

    assertFalse("nothing was pinned by this add - the key has been there since before the "
            + "deletion - so the app must not announce that a key just reached the user through "
            + "the messenger. Saying so also holds the banner for the life of the strip, because a "
            + "standing caution makes mayOverwriteInfoBanner refuse. Banner: " + banner(),
        banner().contains("This key reached you through the messenger"));
  }
}
