package com.amnesica.kryptey.inputmethod.latin.e2ee;

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

/**
 * The duplicate-name warning must survive a cheaper warning taking the slot.
 *
 * <p>Adding a second contact whose rendered name folds onto an existing one raises a warning: one
 * address is one identity, and two rows the user cannot tell apart is the cheapest bypass of the
 * whole trust model. The file calls it "the only control covering the case the pin cannot".
 *
 * <p>It was raised in exactly one place — inside {@code addContact} — and never re-asserted, while
 * the warning slot holds one thing. So the attack was: get a second "Bob Jones" added at an address
 * you control, then post one forged bundle for the genuine Bob. That second warning costs nothing
 * and calls {@code setWarningMessage} unconditionally, replacing the duplicate-name text. The user
 * then does exactly what the app tells them — compares the number for the genuine Bob, it matches
 * because the pin never moved, presses Verify — and the clear is scoped to the contact they just
 * verified, so the slot is emptied. The impostor row is indistinguishable from a healthy contact
 * from then on, and nothing mentions it again.
 *
 * <p>The condition is standing state — two rows sharing a folded name is a fact about the contact
 * list — so the warning is recomputed rather than remembered. A warning that can be asked again
 * cannot be evicted for good.
 */
@RunWith(RobolectricTestRunner.class)
public class AforgedBundleCannotEvictTheDuplicateWarningTest {

  private E2EEStripView strip;
  private Contact genuineBob;
  private Contact impostor;
  /** A bundle the impostor can send from its own address, for the decrypt-path selection. */
  private String impostorBundle;
  private int impostorDeviceId;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress bobAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    // In libsignal's range, and the same value the row below uses. Adding a constant to the peer's
    // device id runs off the end of [1,127] for a third of the ids the generator picks, and the
    // encoder is right to refuse it - the test would then be asserting about an envelope no
    // messenger could ever deliver.
    impostorDeviceId = (peer.getDeviceId() % 127) + 1;

    // A third identity, so the impostor can offer a bundle of its own rather than a forged one.
    // The address IN the envelope is the impostor's row - the address name is public and the device
    // id is a byte the sender writes.
    SignalProtocolMain.initialize(null);
    final MessageEnvelope impostorEnvelope = SignalProtocolMain.getPreKeyResponseMessage();
    impostorEnvelope.setSignalProtocolAddressName(bobAddress.getName());
    impostorEnvelope.setDeviceId(impostorDeviceId);
    impostorBundle = EnvelopeCodec.toWire(impostorEnvelope);

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    genuineBob = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId(), false);
    // The impostor row: same rendered name, an address the attacker controls.
    impostor = new Contact("Bob", "Jones", bobAddress.getName(), impostorDeviceId, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    contacts.add(impostor);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
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

  private String banner() {
    return String.valueOf(((TextView) strip.findViewById(R.id.e2ee_info_text)).getText());
  }

  /** The harness is real: two rows really do share a name the app folds together. */
  @Test
  public void aatwoRowsShareArenderedName() {
    assertTrue("precondition: the app must consider these two rows a name clash, or this file "
            + "describes an attack it is not testing",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones",
            impostor.getSignalProtocolAddress()));
  }

  /**
   * And the DECRYPT path re-raises it too, which is the path the messenger actually drives.
   *
   * <p>The re-derivation lived in {@code selectContact} - the contact list being tapped - and the
   * three decrypt arms move the recipient through {@code setChosenContact} without it. Which path
   * runs is not the user's choice: the messenger decides what arrives, and the user only presses
   * Decrypt. So the eviction survived after all, one branch over: displace the warning, let the
   * user resolve the displacing one, and from then on every relayed item from the impostor selects
   * it in silence, with the banner reading like a healthy contact and Encrypt aimed at the row the
   * messenger chose.
   *
   * <p>This drives the arm that needs no valid ciphertext to move the recipient, which is also the
   * cheapest one for an attacker: an invite from its own address.
   */
  @Test
  public void theduplicateWarningComesBackWhenTheDecryptPathChoosesTheRow() throws Exception {
    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: something else must hold the slot",
        banner().contains("something else entirely"));

    strip.processPreKeyResponseForTest(EnvelopeCodec.fromWire(impostorBundle), impostor);

    assertTrue("the messenger chooses which path selects a contact, so a control on one of them is "
            + "not a control: the row the user never tapped is now the Encrypt recipient, and the "
            + "banner reads like a healthy contact: " + banner(),
        banner().contains("You already have a contact called"));
  }

  /**
   * Selecting the impostor re-raises the warning, however it was lost.
   *
   * <p>Asserted through selection rather than by replaying the eviction, because selection is the
   * one thing the user must do before sending anything to that row — so it is the moment the
   * warning has to be true again, whatever happened in between.
   */
  @Test
  public void theduplicateWarningComesBackWhenTheRowIsChosen() {
    // Something else takes the slot, which is all an attacker has to arrange.
    strip.setWarningMessageForTest("Careful: something else entirely.");

    strip.selectContact(impostor);

    assertTrue("choosing a row that shares a name with another must say so, whatever else has "
            + "held the banner since. Otherwise one forged bundle for an unrelated contact buries "
            + "the only control covering two rows the user cannot tell apart, and verifying the "
            + "genuine one clears it for good. Banner: " + banner(),
        banner().contains("a different one - not a replacement"));
  }

  /** And a contact with no name clash does not get the warning. */
  @Test
  public void acontactWithNoClashIsNotWarnedAbout() throws Exception {
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    final Contact alone = new Contact("Carol", "Smith",
        genuineBob.getSignalProtocolAddressName(), genuineBob.getDeviceId() + 21, false);
    final ArrayList<Contact> contacts = new ArrayList<>(victim.getContactList());
    contacts.add(alone);
    victim.setContactList(contacts);

    strip.selectContact(alone);

    assertTrue("a re-assertion that fires for everybody would be worse than the defect it fixes: "
            + banner(), !banner().contains("a different one - not a replacement"));
  }

  /**
   * A retired name IS re-asserted — and comparing the number ends it.
   *
   * <p>This test asserted the opposite for one round, and both positions were half right. Raising it
   * on every selection with no action that ends it is a sentence on every send for the life of the
   * install, which is habituation and is what the control's own javadoc is written against. Raising
   * it once and never re-deriving it is an eviction: an attacker displaces it with any cheap
   * warning, the user resolves that one, and the impostor row is indistinguishable from a healthy
   * contact from then on.
   *
   * <p>What was missing was a resolution. The warning's own text says the app "cannot confirm that
   * this is the same person coming back", and comparing the safety number by voice is exactly how
   * the user confirms it — the only way anyone can. So the question is asked in full on every
   * selection, and verifying the contact ends it. The retired entry itself stays, so a LATER contact
   * reusing that name is still warned about: the suppression is scoped to the one address whose
   * number was compared.
   */
  @Test
  public void aretiredNameIsReRaisedUntilTheNumberIsCompared() throws Exception {
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    final ArrayList<Contact> single = new ArrayList<>();
    single.add(genuineBob);
    victim.setContactList(single);

    // Bob reinstalled: the old name is retired at the OLD address, the live row is at a new one.
    victim.retireDisplayName("Bob", "Jones", com.amnesica.kryptey.inputmethod.signalprotocol.util
        .ProtocolAddresses.key(impostor.getSignalProtocolAddress()));

    strip.selectContact(genuineBob);
    assertTrue("a name the user deleted, reappearing, must be said - and said again on the next "
            + "selection, or one cheap warning displaces it for good. Banner: " + banner(),
        banner().contains("deleted a contact called"));

    // The user does what the warning asks: compares the number by voice, and confirms.
    SignalProtocolMain.verifyContact(genuineBob);
    strip.selectContact(genuineBob);

    assertTrue("comparing the number is how the user confirms this IS the same person coming back, "
            + "which is what the warning asks for - so it must stop. A warning that outlives its "
            + "own resolution is one shown on every send forever: " + banner(),
        !banner().contains("deleted a contact called"));
  }
}
