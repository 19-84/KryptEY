package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Switching contacts must not let the previous contact's safety number paint itself in late.
 *
 * <p>The digits count up over a second. Nothing cancelled those animators once, so switching
 * contacts left the previous contact's animation running: a moment later it painted that number into
 * the views, under the new contact's name, on the one screen whose whole purpose is comparing the
 * number by voice. The animators are cancelled now — and REVIVAL.md records that the fix has no test,
 * because under Robolectric an un-cancelled animator delivers no further frames once the looper is
 * idled past the view change, so the late repaint never happens and the test passes either way.
 *
 * <p>This is that test, on a device, where the frames are real. It is the same reasoning as the
 * FLAG_SECURE device test: a property that only exists in a real frame loop has to be asserted in
 * one.
 */
@RunWith(AndroidJUnit4.class)
public class AcontactSwitchDoesNotRepaintThePreviousNumberTest {

  private static final int[] CODE_VIEWS = {
      R.id.code_first, R.id.code_second, R.id.code_third, R.id.code_fourth,
      R.id.code_fifth, R.id.code_sixth, R.id.code_seventh, R.id.code_eighth,
      R.id.code_ninth, R.id.code_tenth, R.id.code_eleventh, R.id.code_twelth};

  private E2EEStripView strip;
  private Contact first;
  private Contact second;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peerOne = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress firstAddress = ProtocolAddresses.of(
        peerOne.getSignalProtocolAddress().getName(), peerOne.getDeviceId());
    final String firstBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account peerTwo = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress secondAddress = ProtocolAddresses.of(
        peerTwo.getSignalProtocolAddress().getName(), peerTwo.getDeviceId());
    final String secondBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account me = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(firstBundle), firstAddress));
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(secondBundle), secondAddress));

    first = new Contact("Ann", "One", firstAddress.getName(), firstAddress.getDeviceId(), false);
    second = new Contact("Bea", "Two", secondAddress.getName(), secondAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(first);
    contacts.add(second);
    me.setContactList(contacts);

    final Context context = new ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        R.style.KeyboardTheme_LXX_Pure_Day);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      strip = new E2EEStripView(context, null);
      strip.setListener(new E2EEStripView.Listener() {
        @Override public void onTextInput(final String rawText) { }
        @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
      }, strip);
    });
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private String digitsOnScreen() {
    final StringBuilder shown = new StringBuilder();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      for (final int id : CODE_VIEWS) {
        final TextView view = strip.findViewById(id);
        assertNotNull("every digit view must exist", view);
        shown.append(view.getText());
      }
    });
    return shown.toString();
  }

  private String numberFor(final Contact contact) throws Exception {
    return SignalProtocolMain.getFingerprint(contact).getDisplayableFingerprint().getDisplayText();
  }

  @Test
  public void thepreviousContactsNumberDoesNotArriveAfterTheSwitch() throws Exception {
    final String firstNumber = numberFor(first);
    final String secondNumber = numberFor(second);
    assertNotEquals("fixture: the two contacts must have different numbers, or this measures "
        + "nothing", firstNumber, secondNumber);

    // Open the first contact and let its count-up get under way, but not finish.
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> strip.showVerifyContactForTest(first));
    Thread.sleep(200L);

    // Switch while it is still running.
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> strip.showVerifyContactForTest(second));

    // Well past the end of both animations.
    Thread.sleep(2_000L);

    assertEquals("the first contact's animation painted its number in after the switch, so the "
            + "screen shows one contact's name above another contact's safety number - on the "
            + "screen whose whole purpose is comparing that number by voice",
        secondNumber, digitsOnScreen());
  }
}
