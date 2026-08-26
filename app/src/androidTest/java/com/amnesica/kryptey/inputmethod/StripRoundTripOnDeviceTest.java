package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView;
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
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The strip's own UI, driven on a real device: type a message, press Encrypt, read it back.
 *
 * <p>Everything that exercises the strip today runs under Robolectric, which is the right place for
 * its logic and cannot speak for its behaviour on a phone: the views are shadows, the layout is
 * never measured, and libsignal is the desktop build. The instrumentation suite, meanwhile, covers
 * the Keystore, the IME binding and the protocol — but has never touched the strip.
 *
 * <p>So this is the first test that puts the two halves together: a real inflated strip, real
 * Android views, the real Android libsignal, and the button a user actually presses. What it
 * asserts is the app's whole purpose in one line — what leaves the keyboard is not the message, and
 * the intended recipient gets the message back.
 *
 * <p>Deliberately NOT a substitute for driving the keyboard through a real messenger. Nothing here
 * types on the IME's own key surface or moves text through another app; that still has no coverage,
 * and saying so is more useful than implying this closes it.
 */
@RunWith(AndroidJUnit4.class)
public class StripRoundTripOnDeviceTest {

  private static final String MESSAGE = "the meeting moved to nine";

  private Context context;
  private E2EEStripView strip;
  private Account alice;
  private Account bob;
  private SignalProtocolAddress bobAddress;
  private final AtomicReference<String> committed = new AtomicReference<>();

  private static SignalProtocolAddress addressOf(final Account account) {
    return ProtocolAddresses.of(account.getSignalProtocolAddress().getName(),
        account.getDeviceId());
  }

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    // Bob first, so his bundle exists for Alice to accept.
    SignalProtocolMain.initialize(null);
    bob = SignalProtocolMain.getInstance().getAccount();
    bobAddress = addressOf(bob);
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    alice = SignalProtocolMain.getInstance().getAccount();
    assertTrue("Alice must be able to accept Bob's invite on this device",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobBundle), bobAddress));

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(),
        false));
    alice.setContactList(contacts);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip = new E2EEStripView(
          new ContextThemeWrapper(context, R.style.KeyboardTheme_LXX_Pure_Day), null);
      strip.setListener(new E2EEStripView.Listener() {
        @Override
        public void onTextInput(final String rawText) {
          committed.set(rawText);   // what the keyboard would put into the messenger
        }

        @Override
        public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
      }, strip);
    });
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Type, press Encrypt, and check what left the keyboard — then that Bob can read it. */
  @Test
  public void amessageTypedIntoTheStripLeavesAsCiphertextAndComesBack() throws Exception {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip.selectContact(alice.getContactList().get(0));
      final EditText compose = strip.findViewById(R.id.e2ee_input_field);
      assertNotNull("the compose field must inflate on a real device", compose);
      compose.setText(MESSAGE);
      strip.findViewById(R.id.e2ee_button_encrypt).performClick();
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    final String wire = committed.get();
    assertNotNull("pressing Encrypt must hand something to the messenger", wire);
    assertFalse("what leaves the keyboard must not be the message itself", wire.contains(MESSAGE));

    // And the intended recipient gets it back.
    SignalProtocolMain.getInstance().setAccount(bob);
    assertEquals("Bob must be able to read what Alice's strip produced, on this device",
        MESSAGE, SignalProtocolMain.decryptMessage(
            EnvelopeCodec.fromWire(wire.trim()), addressOf(alice)));
  }
}
