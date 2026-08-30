package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;

import com.amnesica.kryptey.inputmethod.R;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * A tap delivered through another app's window is not the user comparing a safety number.
 *
 * <p>The verified badge is this app's single strongest claim: it asserts that the user read twelve
 * digits aloud to their contact and heard the same ones back. One tap on Verify makes that claim,
 * clears any rejection record, and takes down a standing warning. There is no confirmation step
 * anywhere in this app, by design — a standing warning must always leave one deliberate response
 * available, and an extra step is one more place for it to become unreachable.
 *
 * <p>That design assumes the tap came from the user. Android tells a view when it did not:
 * {@code MotionEvent.FLAG_WINDOW_IS_OBSCURED} is set when another window was over the touched
 * point. An app holding "Display over other apps" can place a full-screen
 * {@code TYPE_APPLICATION_OVERLAY} — a higher policy layer than {@code TYPE_INPUT_METHOD} — mark it
 * {@code FLAG_NOT_TOUCHABLE}, and let taps fall through to whatever it has drawn over. The
 * platform's own blocking of untrusted touches arrived in API 31; this app supports 26 and
 * advertises "Android 8.0 or newer", so 26–30 is the exposed range.
 *
 * <p>The consequence is specific and worse than a mis-tap: a verified badge certifying a comparison
 * that never happened, on the one control every user-facing document names as the thing that tells
 * a chat partner apart from the messenger.
 *
 * <p>Deliberately NOT fixed with {@code android:filterTouchesWhenObscured}. That discards the touch
 * silently whenever any window overlaps — a blue-light filter, a screen dimmer, a chat head,
 * accessibility magnification — so Verify would go dead with no explanation for everyone running a
 * night-light app. This project has that failure recorded already, as "the refusal that locked the
 * keyboard". The refusal here says why.
 */
@RunWith(RobolectricTestRunner.class)
public class AtapThroughAnoverlayIsNotAcomparisonTest {

  private E2EEStripView strip;
  private Contact bob;

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
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    victim.setMessageLogLoader(ArrayList::new);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
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

  /**
   * A touch, with or without the platform's "another window was over this point" flag.
   *
   * <p>Built through the pointer-properties overload because it is the only {@code obtain} that
   * takes a flags argument, and the flag is the entire subject of this test.
   */
  private static MotionEvent touch(final int flags) {
    final MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
    properties[0].id = 0;
    properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
    final MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
    coords[0].x = 1f;
    coords[0].y = 1f;
    coords[0].pressure = 1f;
    coords[0].size = 1f;
    return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1, properties, coords, 0, 0,
        1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags);
  }

  /**
   * The decision itself, driven directly.
   *
   * <p>The first version of this test dispatched touch events at the button and asserted the key
   * was not marked compared. It passed - and its anti-vacuity twin, the same tap with no overlay
   * flag, failed: Robolectric does not turn a dispatched touch into a click without layout, so
   * NEITHER tap did anything and the obscured case was green for no reason. That is the fourth time
   * in this branch a test has passed for the fixture's reason rather than the code's, and it was
   * caught the same way each time: by asserting that the ordinary case still works.
   */
  @Test
  public void atapThroughAnotherWindowIsRefusedAndSaidSo() throws Exception {
    strip.showVerifyContactForTest(bob);

    final java.lang.reflect.Method decide = E2EEStripView.class
        .getDeclaredMethod("tapCameThroughAnotherWindow", MotionEvent.class);
    decide.setAccessible(true);

    assertTrue("a touch carrying FLAG_WINDOW_IS_OBSCURED must be refused: it arrived through some "
            + "other application's window, and Verify asserts the user compared twelve digits by "
            + "voice - the one claim this app makes that nothing on screen lets them re-derive",
        (Boolean) decide.invoke(strip, touch(MotionEvent.FLAG_WINDOW_IS_OBSCURED)));

    final android.widget.TextView banner = strip.findViewById(R.id.e2ee_info_text);
    assertNotNull(banner);
    assertTrue("the refusal must say why. Discarding the touch in silence is what "
            + "filterTouchesWhenObscured does, and it turns every security control dark for anyone "
            + "running a screen dimmer with nothing on screen to explain it: " + banner.getText(),
        banner.getText().toString().contains("drawn over this keyboard"));
  }

  /**
   * And an ordinary touch must be let through, which is the half that makes the refusal safe.
   *
   * <p>Refusing everything satisfies the assertion above, and is exactly the failure mode the
   * platform's own filter produces on a device running a night-light app.
   */
  @Test
  public void anordinaryTouchIsNotRefused() throws Exception {
    strip.showVerifyContactForTest(bob);

    final java.lang.reflect.Method decide = E2EEStripView.class
        .getDeclaredMethod("tapCameThroughAnotherWindow", MotionEvent.class);
    decide.setAccessible(true);

    assertFalse("an unobscured touch must be let through, or the guard has simply broken every "
            + "security control for everyone", (Boolean) decide.invoke(strip, touch(0)));
  }

  /**
   * And the controls that make or destroy a security claim must actually be wired to it.
   *
   * <p>The decision above is only worth anything where it is applied. Testing the wiring through
   * the views is what failed: Robolectric will not turn a dispatched touch into a click without
   * layout, so nothing observable distinguishes a wired button from an unwired one. This reads the
   * source instead and says so - it checks that the guard is applied, not that it works, and the
   * three tests above are what check that it works.
   *
   * <p>Scoped to the two controls whose single tap makes or destroys a security claim. Verify
   * asserts a comparison the user cannot re-derive; Reject destroys a pin and writes a record this
   * file calls deliberately permanent. The delete-contact button in the contact-list adapter and
   * the recipient picker are the obvious next two and are NOT covered here - recorded as a gap
   * rather than implied closed.
   */
  @Test
  public void thecontrolsThatMakeAsecurityClaimAreWiredToTheGuard() throws Exception {
    java.nio.file.Path here = java.nio.file.Paths.get("").toAbsolutePath();
    while (here != null && !java.nio.file.Files.exists(
        here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertNotNull("the repository root must be findable", here);
    final String source = new String(java.nio.file.Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");

    for (final String button : new String[] {
        "mVerifyContactVerifyButton", "mVerifyContactRejectButton"}) {
      assertTrue(button + " makes or destroys a security claim on one unconfirmed tap and is not "
              + "wired to refuseTapsThroughAnotherWindow. An app drawing over the keyboard can "
              + "then harvest that tap: a verified badge certifying a comparison nobody made, or a "
              + "pin destroyed",
          source.contains("refuseTapsThroughAnotherWindow(" + button + ")"));
    }
  }

  /**
   * A partially-obscured touch is deliberately let through.
   *
   * <p>Partial obscuring is the ordinary state under a status-bar chip or a notification-shade
   * peek. Refusing it is the silent dead button by another route, so the guard reads only the
   * fully-obscured flag - and that choice is pinned rather than left to be re-derived.
   */
  @Test
  public void apartiallyObscuredTouchIsStillTheUsers() throws Exception {
    final java.lang.reflect.Method decide = E2EEStripView.class
        .getDeclaredMethod("tapCameThroughAnotherWindow", MotionEvent.class);
    decide.setAccessible(true);

    assertFalse("a partially-obscured touch is ordinary - a status-bar chip is enough to set it - "
            + "so refusing it would dead-button the app for routine screen furniture",
        (Boolean) decide.invoke(strip,
            touch(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)));
  }
}
