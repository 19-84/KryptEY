package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;

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
import org.robolectric.shadows.ShadowToast;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Pressing Verify must record the number the user actually compared, or refuse.
 *
 * <p>The verify screen is the one place the whole trust model routes through: the user reads twelve
 * digits aloud, the peer confirms them, and the press turns that into a stored decision. The digits
 * are derived from the pinned key when the screen is painted — so if the pin moves while the screen
 * is open, the press would record a decision about a key nobody read out.
 *
 * <p>The app refuses that press and repaints, and the refusal is
 * {@code thenumberOnScreenIsStillTheKeyWeWouldActOn}. Nothing tested it. Neither of the two sentences
 * it shows was named or asserted anywhere in the suite, and deleting the guard — letting the press
 * through — left everything green: a user would verify a number they never saw, which is the one
 * outcome the screen exists to prevent.
 *
 * <p>The move is attacker-reachable and needs no new capability. A rejection clears the pin, and the
 * next bundle at that address is a clean first sighting that pins by trust-on-first-use — so a
 * relayed bundle arriving while the screen sits open changes the digits underneath it.
 */
@RunWith(RobolectricTestRunner.class)
public class AnumberThatMovedUnderTheScreenIsNotWhatGetsVerifiedTest {

  private E2EEStripView strip;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private String impostorBundle;

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
    impostorBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue("fixture: the peer's bundle must pin",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(),
        false));
    victim.setContactList(contacts);

    strip = new E2EEStripView(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
    TestStores.writesLand();
    ShadowToast.reset();
  }

  @After
  public void tearDown() {
    if (strip != null) strip.clear();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private Contact bob() {
    return victim.getContactList().get(0);
  }

  @Test
  public void akeyThatMovedWhileTheScreenWasOpenIsNotTheOneVerified() throws Exception {
    // The user opens the verify screen: the digits on it are derived from the key pinned NOW.
    strip.selectContact(bob());
    strip.loadFingerprintInVerifyContactView();

    // Underneath it, the pin moves. A rejection clears the pin and the next bundle at that address
    // pins by trust-on-first-use, so a relayed one lands a different key while the screen sits open.
    assertTrue("fixture: the rejection must land", SignalProtocolMain.rejectContactKey(bob()));
    final MessageEnvelope substituted = EnvelopeCodec.fromWire(impostorBundle);
    assertTrue("fixture: a different key must be pinned under the open screen",
        SignalProtocolMain.processPreKeyResponseMessage(
            com.amnesica.kryptey.inputmethod.signalprotocol.BundleSigning.asEditedInTransit(
                substituted, new MessageEnvelope(substituted.getPreKeyResponse(),
                    peerAddress.getName(), peerAddress.getDeviceId())),
            peerAddress));
    assertNotNull("precondition: something must be pinned, or this measures an empty screen",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    strip.findViewById(R.id.e2ee_verify_contact_verify_button).performClick();

    assertFalse("the press must not record a decision about a key the user never read out - the "
            + "digits on screen were derived before the pin moved, so verifying here marks an "
            + "identity nobody compared. That is the one outcome this screen exists to prevent",
        SignalProtocolMain.isContactKeyTrustworthy(bob()));
    assertNotNull("...and the user must be told why the press did nothing, or a refused press is "
            + "indistinguishable from a broken button", ShadowToast.getTextOfLatestToast());
    // The DISTINGUISHING half, not the shared opening. Both sentences for this refusal begin
    // "The safety number for X changed while this screen was open, so nothing was recorded", and
    // they were written as two sentences precisely because one cannot cover both states: here a
    // number is still on screen to compare, and in the other the pin is gone and the digits are
    // blank. Asserting the shared part leaves swapping the two arms invisible - measured, before
    // this line was narrowed.
    assertTrue("the sentence must be the one for a number that is still there and has MOVED, not "
            + "the one for a number that is gone - this fixture leaves a key pinned, so the screen "
            + "has digits to compare and telling the user there is no number would be a claim the "
            + "screen disproves. Shown: " + ShadowToast.getTextOfLatestToast(),
        ShadowToast.getTextOfLatestToast().contains("The number below is the current one"));
  }
}
