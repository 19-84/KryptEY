package com.amnesica.kryptey.inputmethod;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;
import com.amnesica.kryptey.inputmethod.latin.e2ee.E2EEStripView;
import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * What the keyboard hands to another application is ciphertext, measured across the process boundary.
 *
 * <p>The strip's send path has been tested on device, but always into a field the app under test
 * owns. This drives the same button with the keyboard attached to {@link ForeignAppActivity}, which
 * lives in the test APK — a different package, a different process. That is the boundary the threat
 * model is about: everything on the far side of it is the messenger's.
 *
 * <p>The verdict comes back through logcat and carries no text. The field's contents are in another
 * process, so the test cannot read them by reference; the plaintext is passed IN as an extra and the
 * comparison happens over there, so neither half is ever written to a device-wide log. A test whose
 * subject is "the plaintext must not cross this boundary" should not be the thing that publishes it.
 */
@RunWith(AndroidJUnit4.class)
public class CiphertextCrossesIntoAforeignAppOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final String FOREIGN_PACKAGE = "com.amnesica.kryptey.test";
  private static final String SECRET = "the meeting moved to nine";
  private static final long TIMEOUT_MS = 60_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  @After
  public void tearDown() throws Exception {
    // First: tell the foreign activity to go. The timer is only a backstop for a test that dies
    // before reaching here; leaving it up on a timer is what made three unrelated tests fail with
    // "something else holds focus".
    shell("am broadcast -a " + ForeignAppActivity.ACTION_FINISH);
    // The live IME's protocol state and its strip were borrowed for this test; hand both back.
    //
    // The strip is the important half. Leaving a contact chosen leaves the IME window FLAG_SECURE,
    // and the first run of this test did exactly that - failing FlagSecureReachesTheWindowOnDevice
    // Test, which asserts the ordinary keyboard is NOT secure. A test that contaminates the suite
    // is worse than a missing one, because the failure lands somewhere else.
    final MainKeyboardView view = KeyboardSwitcher.getInstance().getMainKeyboardView();
    final E2EEStripView strip =
        view == null ? null : view.getRootView().findViewById(R.id.e2ee_strip_view);
    if (strip != null) {
      getInstrumentation().runOnMainSync(() -> {
        // selectContact(null) is public and is what lowers FLAG_SECURE: isShowingSensitiveContent
        // counts a chosen recipient, and setChosenContact notifies the window.
        strip.selectContact(null);
      });
    }
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private static String shell(final String command) throws Exception {
    final ParcelFileDescriptor fd =
        getInstrumentation().getUiAutomation().executeShellCommand(command);
    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  @Test
  public void whatReachesTheOtherAppIsNotTheMessage() throws Exception {
    assertEquals("precondition: this app must be the selected input method", IME_ID,
        Settings.Secure.getString(context().getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD));

    // Attributable to THIS launch: the sibling test's activity outlives its test and keeps posting.
    final String nonce = "send-" + android.os.SystemClock.elapsedRealtimeNanos();
    shell("logcat -c");

    final Instrumentation instrumentation = getInstrumentation();
    instrumentation.getTargetContext().startActivity(new Intent()
        .setComponent(new ComponentName(FOREIGN_PACKAGE,
            "com.amnesica.kryptey.inputmethod.ForeignAppActivity"))
        .putExtra(ForeignAppActivity.EXTRA_SECRET, SECRET)
        // Longer than this test's own deadlines. At 45s against three 60s waits the subject could
        // self-destruct before the click, and the failure would read as "nothing arrived" - pointing
        // at the app rather than at the harness.
        .putExtra(ForeignAppActivity.EXTRA_FINISH_AFTER_MS, 240_000L)
        .putExtra(ForeignAppActivity.EXTRA_NONCE, nonce)
        // CLEAR_TASK as well as NEW_TASK: Intent.filterEquals ignores extras, so this intent is
        // filter-identical to the sibling test's and would otherwise bring ITS task forward without
        // creating an instance or delivering onNewIntent. That instance was launched without the
        // secret, so its verdict would report containsSecret=false unconditionally - the headline
        // assertion passing no matter what crossed.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

    // Wait until the OTHER process reports the keyboard is actually serving its field. Without
    // this the send has nowhere to go: the first run of this test committed into a connection that
    // did not exist yet and asserted that nothing had arrived.
    boolean bound = false;
    final long bindDeadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (System.currentTimeMillis() < bindDeadline && !bound) {
      for (final String line : shell("logcat -d -s " + ForeignAppActivity.TAG).split("\n")) {
        if (line.contains(ForeignAppActivity.BOUND_MARKER)
            && line.contains("nonce=" + nonce) && line.contains("active=true")) bound = true;
      }
      if (!bound) Thread.sleep(POLL_MS);
    }
    assertTrue("the keyboard never became active for the other application's field, so nothing "
        + "below would measure a send across the boundary", bound);

    // The live strip, inside the running IME, attached to the other app's field.
    E2EEStripView strip = null;
    final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      final MainKeyboardView view = KeyboardSwitcher.getInstance().getMainKeyboardView();
      if (view != null && view.getWidth() > 0) {
        strip = view.getRootView().findViewById(R.id.e2ee_strip_view);
        if (strip != null) break;
      }
      Thread.sleep(POLL_MS);
    }
    assertNotNull("the keyboard never came up over the other application's field", strip);

    // Two accounts and a session, in the live process the IME is running in.
    //
    // resetForTest FIRST, and it is the most important line in this method. The live IME installed
    // a real Keystore-backed StorageHelper when setInputView ran, and initializeStorageHelper
    // returns early on a null context WITHOUT clearing that field - which resetForTest's own javadoc
    // records as the trap. Without this, initialize(null) refuses to generate over existing data,
    // both "accounts" are the same on-disk identity, the session is built with itself, and the
    // click drives the real encrypt: the fabricated contact list, the self-pin, and a chat-log entry
    // holding the SECRET plaintext all get committed to the device's real encrypted store. Green
    // either way, because a self-encryption is still not the plaintext - so the damage would be
    // silent. A test whose thesis is "the plaintext must not leave" must not be the thing that
    // writes it to disk.
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account bob = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress bobAddress =
        ProtocolAddresses.of(bob.getSignalProtocolAddress().getName(), bob.getDeviceId());
    final String bobBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    final Account alice = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: Alice must accept Bob's invite",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(bobBundle), bobAddress));
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", bobAddress.getName(), bobAddress.getDeviceId(), false));
    alice.setContactList(contacts);
    SignalProtocolMain.setStorageStateForTest(StorageHelper.StorageState.READABLE);

    final E2EEStripView live = strip;
    instrumentation.runOnMainSync(() -> {
      live.selectContact(alice.getContactList().get(0));
      final EditText compose = live.findViewById(R.id.e2ee_input_field);
      if (compose != null) compose.setText(SECRET);
      live.findViewById(R.id.e2ee_button_encrypt).performClick();
    });
    instrumentation.waitForIdleSync();

    // The verdict, from the other process.
    String verdict = null;
    final long verdictDeadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (System.currentTimeMillis() < verdictDeadline && verdict == null) {
      for (final String line : shell("logcat -d -s " + ForeignAppActivity.TAG).split("\n")) {
        if (line.contains(ForeignAppActivity.MARKER) && line.contains("nonce=" + nonce)) {
          verdict = line.trim();
        }
      }
      if (verdict == null) Thread.sleep(POLL_MS);
    }

    assertNotNull("nothing ever arrived in the other application's field. The strip committed "
        + "through the IME's own connection, which is the one pointing at that field - if nothing "
        + "landed, the send did not cross the process boundary at all.", verdict);
    // A real length, not merely non-zero. "length=0 absent" would be satisfied by a single stray
    // character, and this test's whole claim is that what crossed is a ciphertext envelope.
    final java.util.regex.Matcher length =
        java.util.regex.Pattern.compile("length=(\\d+)").matcher(verdict);
    assertTrue("the verdict must report a length: " + verdict, length.find());
    assertTrue("what arrived is too short to be an encrypted envelope, so something else landed in "
            + "that field: " + verdict,
        Integer.parseInt(length.group(1)) > 50);
    assertTrue("the other process must have been told what to look for, or containsSecret=false "
            + "means only that it was never given anything to compare: " + verdict,
        verdict.contains("haveSecret=true"));
    assertTrue("the message itself must not reach the other application - that is the whole point "
            + "of the app, and this is the first test that asks it across a real process boundary. "
            + "Verdict: " + verdict,
        verdict.contains("containsSecret=false"));
  }
}
