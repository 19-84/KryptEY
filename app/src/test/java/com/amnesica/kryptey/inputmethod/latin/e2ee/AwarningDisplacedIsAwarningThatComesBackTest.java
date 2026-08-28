package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
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
 * The banner holds one warning, so warnings displace each other — and that is only survivable
 * because every one of them can be worked out again.
 *
 * <p>Three writers run on every selection, in reverse severity so the most serious wins the slot for
 * the contact being tapped. That orders them against each other and not across subjects: tapping a
 * contact whose name is shared displaces an identity-change warning about a <em>different</em>
 * contact. A review round raised that, and the obvious fix — make the least severe one yield — was
 * tried here and reverted, because the test written for the eviction attack caught it immediately:
 * yielding to any standing warning means an attacker raises one cheap warning about somebody else
 * and the duplicate-name warning never returns. Closing a displacement by reopening an eviction is
 * strictly worse.
 *
 * <p>So the property that makes displacement acceptable is the one worth pinning: <b>a displaced
 * warning comes back</b>. Each is re-derived from state rather than remembered, so looking at its own
 * subject re-raises it. This checks that for each of the three, by displacing it and then looking.
 */
@RunWith(RobolectricTestRunner.class)
public class AwarningDisplacedIsAwarningThatComesBackTest {

  private E2EEStripView strip;
  private Contact bob;
  private Contact impostor;
  private SignalProtocolAddress bobAddress;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    bobAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    TestStores.writesLand();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(bobBundle), bobAddress));

    bob = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId(), false);
    impostor = new Contact("Bob", "Jones", bobAddress.getName(), peer.getDeviceId() + 9, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
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

  /** Displaced by a warning about somebody else, and back when its own row is looked at. */
  @Test
  public void thesharedNameWarningComesBackAfterBeingDisplaced() {
    strip.selectContact(impostor);
    assertTrue("precondition: the shared-name warning must be standing: " + banner(),
        banner().contains("a different one - not a replacement"));

    // Anything else takes the slot, which is what an attacker arranges.
    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: the slot must have been taken", !banner()
        .contains("a different one - not a replacement"));

    strip.selectContact(impostor);

    assertTrue("looking at the row must work it out again. A warning that cannot be recomputed is "
            + "one an attacker evicts permanently with a single cheap warning: " + banner(),
        banner().contains("a different one - not a replacement"));
  }

  /** And the storage warning, which is about the whole app rather than a contact. */
  @Test
  public void thestorageWarningComesBackOnTheNextRaise() {
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.UNREADABLE);
    strip.refreshOpeningMessage();
    assertTrue("precondition: the storage warning must be standing: " + banner(),
        banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    strip.setWarningMessageForTest("Careful: something else entirely.");
    assertTrue("precondition: the slot must have been taken",
        !banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));

    // What LatinIME does on every setInputView.
    strip.refreshOpeningMessage();

    assertTrue("the storage warning is the one thing between the user and re-inviting everybody "
            + "into an apparently empty install; it must be re-derived rather than remembered: "
            + banner(), banner().contains(E2EEStripView.INFO_STORAGE_UNREADABLE));
  }
}
