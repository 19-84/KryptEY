package com.amnesica.kryptey.inputmethod.latin.e2ee;

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
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;
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
 * A contact row can exist with no pinned key, and the arm that pins one there said nothing.
 *
 * <p>The caution — <em>"this key reached you through the messenger and the app cannot tell whose it
 * is, compare the security number by voice"</em> — had both of its callers inside
 * {@code addContact}. So it announced pins made while adding a contact, and no others.
 *
 * <p><b>A row without a key is not exotic; the attacker makes one.</b> Strip the one-time pre-key
 * from a genuine invite — one unsigned byte, covered by neither signature — and the bundle is
 * refused while the contact row, created before the bundle is processed, survives. From then on that
 * address is a <em>known contact</em>, so a later message from it routes to the message arms rather
 * than to the add screen. {@code isTrustedIdentity} returns true whenever nothing is pinned and
 * {@code decrypt} takes its PreKey arm on the ciphertext type alone, so a bundle-less
 * {@code PreKeySignalMessage} pins its own identity key by trust-on-first-use — and the user is
 * shown a decrypted message under "Detected contact".
 *
 * <p>Neither step needs a forged signature. Omitting one optional field is what moved the envelope
 * from the arm that cautions to the arm that said nothing.
 */
@RunWith(RobolectricTestRunner.class)
public class AsilentPinAtAkeylessRowIsCautionedTest {

  private E2EEStripView strip;
  private Contact alice;
  private SignalProtocolAddress aliceAddress;
  private Account impostor;
  private String genuineBundle;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // Alice, whose invite the relay will edit.
    SignalProtocolMain.initialize(null);
    final Account aliceAccount = SignalProtocolMain.getInstance().getAccount();
    aliceAccount.setMessageLogLoader(ArrayList::new);
    aliceAddress = ProtocolAddresses.of(
        aliceAccount.getSignalProtocolAddress().getName(), aliceAccount.getDeviceId());
    genuineBundle = SignalProtocolMain.exportOwnKeyBundle();

    // The impostor, who will speak from Alice's address under their own identity key.
    SignalProtocolMain.initialize(null);
    impostor = SignalProtocolMain.getInstance().getAccount();
    impostor.setMessageLogLoader(ArrayList::new);

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    // Step one: the relay's edit. The bundle is refused; the row survives with no pinned key.
    final PreKeyResponse genuine = EnvelopeCodec.fromWire(genuineBundle).getPreKeyResponse();
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    assertNotNull("precondition: a genuine invite carries a one-time pre-key", device.getPreKey());
    final List<PreKeyResponseItem> devices = new ArrayList<>();
    devices.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    final MessageEnvelope stripped = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(
        new MessageEnvelope(new PreKeyResponse(genuine.getIdentityKey(), devices),
            aliceAddress.getName(), aliceAddress.getDeviceId())));

    ((EditText) strip.findViewById(R.id.e2ee_add_contact_first_name_input_field)).setText("Alice");
    ((EditText) strip.findViewById(R.id.e2ee_add_contact_last_name_input_field)).setText("Smith");
    strip.addContactForTest(stripped);

    alice = strip.chosenContactForTest();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** The harness is real: a contact row with no key is what step one produces. */
  @Test
  public void aatherowExistsAndHoldsNoKey() {
    assertNotNull("the refused bundle must still leave a contact row - that is the premise", alice);
    assertFalse("and it must hold no pinned key, or the second step is not trust-on-first-use",
        SignalProtocolMain.hasPinnedKey(aliceAddress));
  }

  /**
   * Step two: a bundle-less PreKey message from that address pins the impostor's key.
   *
   * <p>The event the add-contact arm calls its most important outcome, on the arm that had no
   * caution at all — and it fires <em>because nothing was noticed</em>, which is what a successful
   * substitution looks like from inside the app.
   */
  @Test
  public void amessageThatPinsAkeyThereIsCautioned() throws Exception {
    // The impostor writes to the victim from Alice's address.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(impostor);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope fromImpostor =
        SignalProtocolMain.encryptMessage("it's me, my phone died", victimAddress);
    assertNotNull(fromImpostor);
    SignalProtocolMain.getInstance().setAccount(victim);

    // Relabelled to Alice's address, with no bundle attached: one omitted field chooses the arm.
    final MessageEnvelope relayed = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(
        new MessageEnvelope(fromImpostor.getCiphertextMessage(), fromImpostor.getCiphertextType(),
            aliceAddress.getName(), aliceAddress.getDeviceId())));

    strip.processSignalMessageForTest(relayed, alice);

    assertTrue("precondition: the key must really have been pinned by this paste",
        SignalProtocolMain.hasPinnedKey(aliceAddress));
    assertTrue("a key reached the user through the messenger and was pinned, at an address the app "
            + "already showed as a known contact. That is the event the add-contact arm calls its "
            + "most important outcome, and it fires because nothing was noticed - which is what a "
            + "successful substitution looks like from inside the app. Banner: " + banner(),
        banner().contains("compare the security number"));
  }
}
