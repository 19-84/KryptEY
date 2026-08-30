package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.EditableFieldActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Who sets {@code FLAG_SECURE} on the IME window in landscape — the platform, or nobody.
 *
 * <p>Measured already and recorded: in landscape this keyboard runs in the platform's extract mode,
 * the framework mirrors the host field's text into a view inside the IME's window, and the IME
 * window is not secure even when the host is. What that measurement could not say is whether the
 * platform sets the flag and this app then <em>clears</em> it —
 * {@code onSensitiveContentVisibilityChanged} calls {@code clearFlags} unconditionally — or whether
 * the platform never sets it at all. The difference decides whether this is a defect in this app or
 * a property of the platform.
 *
 * <p>The first probe could not answer it. Removing the unconditional clear and re-running the
 * existing test left the flag set from an earlier phase of that same test, where a sensitive strip
 * screen had legitimately raised it — so the landscape assertion passed for the wrong reason and
 * the run tripped a different control instead.
 *
 * <p>This class exists to be un-confoundable. It never shows a sensitive strip screen, so this app
 * never calls {@code addFlags} at all. Any {@code SECURE} on the IME window here can only have come
 * from the platform. Run it with the clear disabled and the answer is unambiguous:
 *
 * <pre>
 *   KRYPTEY_TEST_CLASS=…WhoSetsFlagSecureInExtractModeOnDeviceTest ./tools/test-on-emulator
 * </pre>
 *
 * <p>As committed it pins the state as it stands, so the question stays visible and a change in the
 * platform's behaviour fails a test rather than passing unnoticed.
 */
@RunWith(AndroidJUnit4.class)
public class WhoSetsFlagSecureInExtractModeOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final long TIMEOUT_MS = 60_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  private static String windowDump() throws Exception {
    final ParcelFileDescriptor fd =
        getInstrumentation().getUiAutomation().executeShellCommand("dumpsys window windows");
    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static String blockFor(final String dump, final String marker) {
    final int at = dump.indexOf(marker);
    if (at < 0) return "";
    final int start = dump.lastIndexOf("Window #", at);
    final int next = dump.indexOf("Window #", at);
    return dump.substring(start < 0 ? at : start, next < 0 ? dump.length() : next);
  }

  @Test
  public void nobodySetsItWhenTheStripIsShowingNothingSensitive() throws Exception {
    // Asserted, not assumed. A skipped device test is the quiet kind of absence this repo has a
    // guard against: it runs, gives up, and is reported as a pass.
    org.junit.Assert.assertEquals("precondition: this app must be the selected input method",
        IME_ID, Settings.Secure.getString(
            context().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));

    final Instrumentation instrumentation = getInstrumentation();
    final Activity activity = instrumentation.startActivitySync(
        new Intent(context(), EditableFieldActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      final EditableFieldActivity host = (EditableFieldActivity) activity;

      // The host protects itself, and rotates. No strip screen is ever shown, so this app has no
      // reason to raise FLAG_SECURE and never does.
      instrumentation.runOnMainSync(
          () -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
      activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      instrumentation.waitForIdleSync();

      final long focusDeadline = System.currentTimeMillis() + TIMEOUT_MS;
      while (!activity.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
        Thread.sleep(POLL_MS);
      }
      assertTrue("the host field never gained window focus; harness failure",
          activity.hasWindowFocus());

      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      final long bindDeadline = System.currentTimeMillis() + TIMEOUT_MS;
      boolean active = false;
      while (System.currentTimeMillis() < bindDeadline) {
        instrumentation.runOnMainSync(() -> {
          host.field.requestFocus();
          imm.showSoftInput(host.field, InputMethodManager.SHOW_FORCED);
        });
        if (imm.isActive(host.field)) {
          active = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }
      assertTrue("no input connection was ever established; harness failure", active);

      // And wait for the IME's own WINDOW, not just the connection.
      //
      // isActive says a client has an input connection; it does not say the keyboard window was
      // ever created, and dumpsys has no input-method window to describe until it is. The sibling
      // class records that distinction in as many words and waits for the key view; this test did
      // not, and failed twice on "must find the input-method window" - a harness failure it
      // correctly refused to report as a measurement.
      final long keyboardDeadline = System.currentTimeMillis() + TIMEOUT_MS;
      boolean keyboardUp = false;
      while (System.currentTimeMillis() < keyboardDeadline) {
        final com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView view =
            com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher.getInstance()
                .getMainKeyboardView();
        if (view != null && view.getWidth() > 0) {
          keyboardUp = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }
      assertTrue("the keyboard window was never created, so there is nothing to measure; harness "
          + "failure rather than a finding", keyboardUp);
      Thread.sleep(2_000L);

      final String dump = windowDump();
      final String hostBlock = blockFor(dump, "EditableFieldActivity");
      // Sliced FORWARD from the marker, matching the helper in the sibling class that is known to
      // work. Slicing backward to the previous "Window #" - which is right for the host, whose
      // marker sits inside its entry - lands before the IME's entry and returns an empty string.
      final int imeAt = dump.indexOf("InputMethod");
      final int imeNext = imeAt < 0 ? -1 : dump.indexOf("Window #", imeAt);
      final String imeBlock = imeAt < 0 ? ""
          : dump.substring(imeAt, imeNext < 0 ? dump.length() : imeNext);

      assertTrue("anti-vacuity: the host window must actually be secure, or this measures nothing. "
          + "Host block:\n" + hostBlock, hostBlock.contains("SECURE"));
      assertTrue("this test must find the input-method window, or it reads an empty string",
          imeBlock.length() > 0);

      // The pinned answer. With no sensitive strip screen shown, this app never raised the flag -
      // so if the IME window were secure, only the platform could have done it.
      assertFalse("the IME window is secure in landscape beside a secure host, with this app never "
              + "having raised the flag. That means the platform DOES set it - and the "
              + "unconditional clearFlags in onSensitiveContentVisibilityChanged is therefore "
              + "stripping a protection the platform applied, which is a defect in this app rather "
              + "than a platform limit. Rewrite the ledger entry and fix the clear",
          imeBlock.contains("SECURE"));
    } finally {
      instrumentation.runOnMainSync(() -> {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      });
      activity.finish();
    }
  }
}
