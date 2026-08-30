package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * A warning that asks for a comparison must not come straight back once the user has made it.
 *
 * <p>The duplicate-name warning says: <em>"check with them by voice before sending anything"</em>.
 * It is re-derived from {@code hasContactWithSameDisplayName} on every {@code selectContact}, and
 * that predicate is `live || retired`. The retired half honours the answer — it skips an address the
 * user has verified. The live half has no such term.
 *
 * <p>So for two live rows sharing a folded name — a peer running this on two devices, or a peer who
 * reinstalled while the user kept the old row for its history — the user is told to compare, does,
 * watches the warning clear, and meets the identical sentence on the next selection. Forever.
 *
 * <p>That is worse than a warning that never lowers, and this file already records why: while
 * {@code mWarningStanding} is up, {@code setInfoUnlessWarned} is suppressed, so every ordinary line
 * the strip has to say is gone for that contact — and the sentence that will one day be about a real
 * impostor is byte-identical to the one that has been on screen for months.
 *
 * <p>What this test pins is the wording, not a suppression. Making the live half honour verification
 * would hand an attacker the silencer: a relayed row shows the same safety number as the genuine
 * one, so verifying it is exactly what an attacker wants the user to do. The sentence must instead
 * name an action that ends it.
 */
@RunWith(RobolectricTestRunner.class)
public class AwarningTheUserAnsweredMustNotReturnUnchangedTest {

  private E2EEStripView strip;
  private Contact first;
  private Contact second;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account other = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress otherAddress = ProtocolAddresses.of(
        other.getSignalProtocolAddress().getName(), other.getDeviceId());
    final String otherBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(otherBundle), otherAddress));

    // Two LIVE rows sharing a name, each with its own key. The honest shape: a peer who reinstalled
    // while the user kept the old row, or one person on two devices.
    first = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    second = new Contact("Bob", "Jones", otherAddress.getName(), otherAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(first);
    contacts.add(second);
    victim.setContactList(contacts);

    strip = new E2EEStripView(
        new ContextThemeWrapper(app, R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
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
    return view.getText().toString();
  }

  @Test
  public void thewarningNamesAnactionThatEndsIt() throws Exception {
    strip.selectContact(first);
    assertTrue("precondition: two live rows share a name, so the warning must be up: " + banner(),
        banner().contains("already have a contact"));

    // The user does what the sentence asks and compares the number.
    assertTrue("fixture: the comparison must be recordable", SignalProtocolMain.verifyContact(first));
    strip.selectContact(second);
    strip.selectContact(first);

    final String afterAnswering = banner();
    final boolean stillWarning = afterAnswering.contains("already have a contact");

    // Pinned as it is: the warning does return, and that is not being changed here, because
    // suppressing it on verification is what an attacker wants - a relayed row shows the same
    // safety number as the genuine one, so it verifies green.
    //
    // What must be true is that the sentence names something the user can DO to end it. Without
    // that the app repeats an instruction the user has already followed, and a warning repeated
    // after it has been answered is one the user stops reading - which is the state this project
    // calls cries-wolf and treats as a defect, because the sentence that will one day be about a
    // real impostor is byte-identical.
    assertTrue("the duplicate-name warning returns after the user compares the number (still "
            + "warning: " + stillWarning + "), so it must name an action that ends it. It says to "
            + "compare, which the user has now done, and nothing in it tells them what to do with "
            + "two rows they cannot tell apart: " + afterAnswering,
        afterAnswering.contains("delete the one"));
  }
}
