package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

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
 * A refused bundle must not cost the user the message that was stapled to it.
 *
 * <p>The combined arm is the ordinary shape for a signed-pre-key rotation: an honest peer attaches a
 * full bundle to a normal message. Stripping the bundle's one-time pre-key — one unsigned byte a
 * relay can remove — makes it refused while the message beside it still decrypts under the existing
 * session, and the app deliberately still shows that message: "the ciphertext is authenticated by
 * the existing session and has nothing to do with the bundle that was stapled to it."
 *
 * <p>A recipient undo added on that arm read the decrypt method's RETURN value as "nothing
 * decrypted". It does not mean that: the refusal branch displays the plaintext and then returns
 * false, because the boolean means "was this envelope wholly good". So the undo fired on every
 * tampered-but-decrypting message, and {@code setChosenContact(null)} empties the compose box —
 * wiping the message the user had just been handed, along with the recipient. Per message, no
 * crypto, entirely at the relay's discretion: a suppression of the app's main function.
 *
 * <p>This drives that exact envelope and asserts both halves: the message stays on screen, and the
 * recipient does not move.
 */
@RunWith(RobolectricTestRunner.class)
public class ArefusedBundleDoesNotEraseTheMessageItCarriedTest {

  private E2EEStripView strip;
  private Contact bob;
  private MessageEnvelope tamperedButDecrypting;
  /** The same refused bundle with bytes that decrypt to nothing: neither half is any good. */
  private MessageEnvelope tamperedAndUndecryptable;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peer.setMessageLogLoader(ArrayList::new);
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    assertTrue("precondition: the victim must have a session with the peer",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    // The peer's side of the conversation, so the message below is one the victim can really open.
    SignalProtocolMain.getInstance().setAccount(peer);
    assertTrue("precondition: the peer must have a session with the victim",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope message =
        SignalProtocolMain.encryptMessage("the message the user was handed", victimAddress);
    assertNotNull("precondition: the peer must produce a message", message);
    SignalProtocolMain.getInstance().setAccount(victim);

    // One unsigned byte removed from a bundle stapled to that message: refused, and the arm the
    // envelope lands on changes with it.
    final PreKeyResponse genuine = EnvelopeCodec.fromWire(peerBundle).getPreKeyResponse();
    final PreKeyResponseItem device = genuine.getDevices().get(0);
    final List<PreKeyResponseItem> stripped = new ArrayList<>();
    stripped.add(new PreKeyResponseItem(device.getDeviceId(), device.getRegistrationId(),
        device.getSignedPreKey(), null, device.getKyberPreKey()));
    message.setPreKeyResponse(new PreKeyResponse(genuine.getIdentityKey(), stripped));
    // Through the codec, so this is what a relay's edit actually produces.
    tamperedButDecrypting = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(message));

    final MessageEnvelope useless = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(message));
    useless.setCiphertextMessage(new byte[] {3, 9, 9, 9, 9, 9, 9, 9});
    useless.setCiphertextType(2);
    tamperedAndUndecryptable = EnvelopeCodec.fromWire(EnvelopeCodec.toWire(useless));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    strip.selectContact(bob);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String composeBox() {
    return String.valueOf(((EditText) strip.findViewById(R.id.e2ee_input_field)).getText());
  }

  @Test
  public void themessageStaysOnScreenWhenTheStapledBundleIsRefused() {
    strip.processUpdatedPreKeyResponseForTest(tamperedButDecrypting, bob);

    assertTrue("the message decrypted under the existing session and was displayed; the refused "
            + "bundle stapled to it has nothing to do with that, and wiping the compose box hands "
            + "the relay a per-message suppression of the app's main function for one unsigned "
            + "byte. Compose box: " + composeBox(),
        composeBox().contains("the message the user was handed"));
  }

  @Test
  public void therecipientDoesNotMoveWhenTheStapledBundleIsRefused() {
    strip.processUpdatedPreKeyResponseForTest(tamperedButDecrypting, bob);

    assertEquals("the recipient was already this contact, so this envelope moved nothing and there "
            + "is nothing to undo. Nulling it costs the user their draft as well, on demand",
        bob, strip.chosenContactForTest());
  }

  /**
   * And when no recipient was chosen, the undo is real - and must still not take the message.
   *
   * <p>The companion to the test above, and the one that makes the recorded fact load-bearing
   * rather than masked. With a recipient already chosen the undo restores what was there and
   * changes nothing, so a strip that wrongly believed nothing had decrypted would still show the
   * message. Here the recipient genuinely moves back to nobody, {@code setChosenContact} empties
   * the compose box on that change, and the only thing standing between the user and losing the
   * message they were just handed is the strip knowing that it displayed one.
   *
   * <p>It is also the ordinary case: the user presses Decrypt on something the messenger showed
   * them without having tapped a row first.
   */
  @Test
  public void themessageSurvivesEvenWhenTheRecipientReallyDoesGoBack() {
    strip.resetChosenContactAndInfoTextForTest();
    assertNull("precondition: no recipient, so the undo below is a real change",
        strip.chosenContactForTest());

    strip.processUpdatedPreKeyResponseForTest(tamperedButDecrypting, bob);

    assertTrue("the recipient going back must not take the message with it - the compose box is "
            + "emptied whenever the recipient changes, so the strip has to know that it displayed "
            + "a message rather than infer it from a return value that means something else. "
            + "Compose box: " + composeBox(),
        composeBox().contains("the message the user was handed"));
  }

  /**
   * When neither half of the envelope was any good, the recipient goes back to what it WAS.
   *
   * <p>Nothing decrypted and the bundle was refused, so there is genuinely something to undo. The
   * question is what "undo" means: nulling the recipient is not an undo, it is a further change,
   * and it costs the user the recipient they had chosen for themselves - on an envelope the relay
   * composes and delivers whenever it likes. Restoring what was there is a no-op when this envelope
   * moved nothing, which is exactly when there is nothing to undo.
   */
  @Test
  public void auselessEnvelopeReturnsTheRecipientRatherThanClearingIt() {
    strip.processUpdatedPreKeyResponseForTest(tamperedAndUndecryptable, bob);

    assertEquals("the recipient the user chose must come back, not be cleared: the relay picks "
            + "when this envelope arrives, so clearing hands it a way to un-choose the user's "
            + "recipient on demand", bob, strip.chosenContactForTest());
  }
}
