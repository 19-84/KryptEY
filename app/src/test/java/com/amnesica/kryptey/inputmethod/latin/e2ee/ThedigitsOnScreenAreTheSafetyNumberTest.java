package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
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
import org.robolectric.shadows.ShadowLooper;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;

import java.util.ArrayList;

/**
 * The number on screen must be the safety number, and the safety number must be the agreed one.
 *
 * <p>Everything this app does about trust reduces to one instruction: compare these digits by voice.
 * Nothing tested the digits. Two mutants survived the whole suite, measured:
 *
 * <ul>
 *   <li>rendering every one of the twelve groups as the FIRST group. Both honest sides render the
 *       same degraded value, so they still match — the comparison silently stops being a check while
 *       looking identical, and the number drops from about two hundred bits to about seventeen.</li>
 *   <li>changing the iteration count from 5200. Two installs on different builds then show different
 *       numbers for the same pair of keys, the app tells both users that means an attack, and its
 *       advice for a mismatch is a rejection that permanently discards a correct pin.</li>
 * </ul>
 *
 * <p>Neither shows up as a broken screen. Both are agreements that quietly stop meaning anything,
 * which is why the assertions here are about relationships rather than values: the views must equal
 * what the protocol computes, and what the protocol computes must equal what an independently
 * constructed generator at Signal's iteration count produces. Pinning literal digits as a golden
 * vector would break on any legitimate libsignal change, and the reflex fix for that is to update
 * the expected value — which is the one change that must never be made quietly.
 */
@RunWith(RobolectricTestRunner.class)
public class ThedigitsOnScreenAreTheSafetyNumberTest {

  private static final int[] CODE_VIEWS = {
      R.id.code_first, R.id.code_second, R.id.code_third, R.id.code_fourth,
      R.id.code_fifth, R.id.code_sixth, R.id.code_seventh, R.id.code_eighth,
      R.id.code_ninth, R.id.code_tenth, R.id.code_eleventh, R.id.code_twelth};

  private E2EEStripView strip;
  private Contact bob;
  private Account victim;
  private IdentityKey peerIdentity;

  @Before
  public void setUp() throws Exception {
    final Application app = RuntimeEnvironment.getApplication();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerIdentity = peer.getSignalProtocolStore().getIdentityKeyPair().getPublicKey();
    final SignalProtocolAddress peerAddress = ProtocolAddresses.of(
        peer.getSignalProtocolAddress().getName(), peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    TestStores.writesLand();
    assertTrue("precondition: a key must be pinned, or there is no number to show",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(peerBundle), peerAddress));

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
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** What the twelve views read, after the count-up animation has finished. */
  private String digitsOnScreen() {
    strip.showVerifyContactForTest(bob);
    ShadowLooper.idleMainLooper();
    final StringBuilder shown = new StringBuilder();
    for (final int id : CODE_VIEWS) {
      final TextView view = strip.findViewById(id);
      assertNotNull("every digit view must exist", view);
      shown.append(view.getText());
    }
    return shown.toString();
  }

  @Test
  public void thescreenShowsTheFingerprintTheProtocolComputed() throws Exception {
    final String computed = SignalProtocolMain.getFingerprint(bob)
        .getDisplayableFingerprint().getDisplayText();
    assertEquals("precondition: a displayable fingerprint is sixty digits", 60, computed.length());

    assertEquals("the digits on screen must be the safety number, in order. Rendering the same "
            + "group twelve times leaves both honest sides agreeing on a number worth about "
            + "seventeen bits, and nothing about the screen looks wrong",
        computed, digitsOnScreen());
  }

  @Test
  public void thefingerprintIsTheOneSignalsParametersProduce() throws Exception {
    final IdentityKey mine = victim.getSignalProtocolStore().getIdentityKeyPair().getPublicKey();

    // Constructed here, at Signal's iteration count, and compared against what production built.
    // Stated once, in a test, so that changing it in production is a visible decision rather than a
    // silent one - two installs on different counts show different numbers for the same keys, and
    // this app's advice for a mismatch discards a correct pin permanently.
    final String expected = new NumericFingerprintGenerator(5200)
        .createFor(2, mine.serialize(), mine, peerIdentity.serialize(), peerIdentity)
        .getDisplayableFingerprint().getDisplayText();

    assertEquals("the safety number must be the one Signal's parameters produce, or two installs "
            + "cannot agree and the app calls their disagreement an attack",
        expected, SignalProtocolMain.getFingerprint(bob)
            .getDisplayableFingerprint().getDisplayText());
  }
}
