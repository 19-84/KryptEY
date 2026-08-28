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

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    genuineBob = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId(), false);
    // The impostor row: same rendered name, an address the attacker controls.
    impostor = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId() + 9, false);
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
}
