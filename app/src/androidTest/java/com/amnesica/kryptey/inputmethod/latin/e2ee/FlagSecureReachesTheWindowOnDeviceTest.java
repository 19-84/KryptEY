package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.EditableFieldActivity;
import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code FLAG_SECURE} actually reaches the window manager, checked on a device.
 *
 * <p>This is the one change on the branch that REVIVAL.md called "the change that most needs a
 * device before it is trusted", and the reason given was that nothing here could run the keyboard.
 * That stopped being true: the instrumentation suite boots an emulator under software emulation and
 * binds this IME. So the claim is measured rather than carried.
 *
 * <p>What was already tested is <em>which</em> screens ask for the flag — that is ordinary logic and
 * Robolectric covers it. What could not be tested is whether asking does anything. The strip is a
 * view inlined into the IME rather than an Activity, the IME's window is a {@link android.app.Dialog},
 * and window flags on an IME window are exactly the kind of thing that behaves differently from the
 * documentation. So this reads the flag back out of the window manager itself.
 *
 * <p>Asserted as a difference rather than as a fixed string: the sensitive screen must produce a
 * SECURE window where the ordinary keyboard does not. A test that only looked for "SECURE" while the
 * strip was up would pass on a device that marked every IME window secure for its own reasons, and
 * would then be pinning the vendor rather than this app.
 */
@RunWith(AndroidJUnit4.class)
public class FlagSecureReachesTheWindowOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final long FOCUS_TIMEOUT_MS = 20_000L;
  private static final long BIND_TIMEOUT_MS = 60_000L;
  private static final long KEYBOARD_TIMEOUT_MS = 30_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  /** {@code dumpsys window}, read through the shell the instrumentation already owns. */
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

  /**
   * The block of {@code dumpsys} describing the input-method window.
   *
   * <p>Narrowed to that window deliberately. The dump contains every window on the device, and any
   * one of them may legitimately be secure — searching the whole thing for "SECURE" would be a test
   * that passes whenever anything on screen is protected.
   */
  private static String imeWindowBlock(final String dump) {
    final int at = dump.indexOf("InputMethod");
    if (at < 0) return "";
    final int next = dump.indexOf("Window #", at);
    return dump.substring(at, next < 0 ? dump.length() : next);
  }

  private static boolean secure(final String block) {
    return block.contains("SECURE");
  }

  /**
   * The block of {@code dumpsys} describing the test host activity's own window.
   *
   * <p>Needed as an anti-vacuity control for the landscape host-secure measurement: if
   * {@code addFlags(FLAG_SECURE)} on the host never took, the question being asked of the IME
   * window is meaningless.
   */
  private static String hostWindowBlock(final String dump) {
    final int at = dump.indexOf("EditableFieldActivity");
    if (at < 0) return "";
    final int start = dump.lastIndexOf("Window #", at);
    final int next = dump.indexOf("Window #", at);
    return dump.substring(start < 0 ? at : start, next < 0 ? dump.length() : next);
  }

  @Test
  public void asensitiveScreenMakesTheImeWindowSecure() throws Exception {
    assertEquals("precondition: this app must be the selected input method", IME_ID,
        Settings.Secure.getString(context().getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD));

    final Instrumentation instrumentation = getInstrumentation();
    final Activity activity = instrumentation.startActivitySync(
        new Intent(context(), EditableFieldActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      final long focusDeadline = System.currentTimeMillis() + FOCUS_TIMEOUT_MS;
      while (!activity.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
        Thread.sleep(POLL_MS);
      }
      assertTrue("the test field never gained window focus; this is a harness failure",
          activity.hasWindowFocus());

      final EditableFieldActivity typed = (EditableFieldActivity) activity;
      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      final long bindDeadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
      boolean active = false;
      while (System.currentTimeMillis() < bindDeadline) {
        instrumentation.runOnMainSync(() -> {
          typed.field.requestFocus();
          imm.showSoftInput(typed.field, InputMethodManager.SHOW_FORCED);
        });
        if (imm.isActive(typed.field)) {
          active = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }
      assertTrue("no input connection was ever established to the test field", active);

      // The LIVE strip, inside the running IME - not one this test constructed. That is the whole
      // point: the flag is applied by LatinIME to its own window, and only the real one has a
      // window at all.
      E2EEStripView strip = null;
      final long keyboardDeadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS;
      while (System.currentTimeMillis() < keyboardDeadline) {
        final MainKeyboardView view = KeyboardSwitcher.getInstance().getMainKeyboardView();
        if (view != null && view.getWidth() > 0) {
          strip = view.getRootView().findViewById(R.id.e2ee_strip_view);
          if (strip != null) break;
        }
        Thread.sleep(POLL_MS);
      }
      assertNotNull("the bound keyboard never produced an E2EE strip to drive", strip);

      final String ordinary = imeWindowBlock(windowDump());
      assertTrue("this test must be able to find the input-method window in dumpsys, or it is "
          + "asserting about an empty string", ordinary.length() > 0);

      final E2EEStripView live = strip;
      instrumentation.runOnMainSync(live::showMessagesListForTest);
      instrumentation.waitForIdleSync();
      Thread.sleep(1_000L);   // the flag change travels to the window manager asynchronously
      final String sensitive = imeWindowBlock(windowDump());

      instrumentation.runOnMainSync(live::showMainViewForTest);
      instrumentation.waitForIdleSync();
      Thread.sleep(1_000L);
      final String afterwards = imeWindowBlock(windowDump());

      assertTrue("the chat log is on screen and the IME window is not marked secure. The flag is "
              + "requested - Robolectric proves the strip asks for it - so this says the request "
              + "does not reach the window manager on this platform, and every screen holding "
              + "decrypted plaintext is screenshottable. Window block was:\\n" + sensitive,
          secure(sensitive));
      assertTrue("the ordinary keyboard must NOT be secure, or the assertion above is measuring "
              + "the device rather than this app - a platform that marks all IME windows secure "
              + "would satisfy it with the flag never applied at all",
          !secure(ordinary));
      // And the same question in landscape, which is a different window mode entirely.
      //
      // values-land/config.xml is the only folder in the tree that sets config_use_fullscreen_mode
      // true. Android resolves smallestWidth above orientation, so values-sw430dp and larger give
      // false on tablets - but on a sub-430dp phone none of those match and values-land wins, and
      // this emulator is --device pixel, which is 411dp. onEvaluateFullscreenMode ANDs that bool
      // with the framework's own answer, so in landscape the keyboard runs in the platform's
      // fullscreen/extract mode, with an ExtractEditText this app does not own and has never
      // considered.
      //
      // Nobody has looked at that mode. This does not audit it - it asks the one question that
      // decides whether the audit is urgent: does the app's only protective flag still reach the
      // window there? FLAG_SECURE is what stops every screen holding decrypted plaintext being
      // screenshottable, and a second window mode is exactly where a flag applied to "the window"
      // can quietly apply to the wrong one.
      activity.setRequestedOrientation(
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      instrumentation.waitForIdleSync();
      Thread.sleep(2_000L);

      instrumentation.runOnMainSync(live::showMessagesListForTest);
      instrumentation.waitForIdleSync();
      Thread.sleep(1_500L);
      final String landscapeSensitive = imeWindowBlock(windowDump());

      instrumentation.runOnMainSync(live::showMainViewForTest);
      instrumentation.waitForIdleSync();
      activity.setRequestedOrientation(
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      instrumentation.waitForIdleSync();
      Thread.sleep(1_500L);

      assertTrue("this test must still find the input-method window after a rotation, or the "
          + "assertion below is reading an empty string", landscapeSensitive.length() > 0);

      // Anti-vacuity: the rotation must actually have happened.
      //
      // If setRequestedOrientation does not take on this device, the block below is the portrait
      // one again and the landscape assertion is a duplicate of the assertion above it - green,
      // and measuring nothing about the second window mode. The window's own reported geometry is
      // what says which way round the screen is, so it is compared rather than assumed.
      System.out.println("MEASURED portrait block:\n" + sensitive);
      System.out.println("MEASURED landscape block:\n" + landscapeSensitive);
      assertTrue("the IME window is identical before and after the rotation, so the rotation did "
              + "not take and the landscape assertion below is measuring portrait a second time",
          !landscapeSensitive.equals(sensitive));
      assertTrue("the chat log is on screen in LANDSCAPE and the IME window is not marked secure. "
              + "In landscape this keyboard runs in the platform's fullscreen/extract mode - "
              + "values-land is the only folder setting config_use_fullscreen_mode true, and it "
              + "wins on any phone under 430dp - so this is a second window mode in which none of "
              + "this project's measured properties had been checked. Window block was:\n"
              + landscapeSensitive,
          secure(landscapeSensitive));

      // The question the extract-mode audit could not settle by reading.
      //
      // In landscape this keyboard runs in the platform's fullscreen/extract mode, and the
      // framework mirrors the HOST field's text into an ExtractEditText inside the IME's own
      // window. isShowingSensitiveContent() enumerates this app's own views and model state and has
      // no term for anything it does not own - so with an empty compose box and no strip screen up,
      // the IME window is deliberately not secure while displaying another application's text.
      //
      // What decides whether that costs anything is whether the PLATFORM marks the IME window
      // secure on its own when the input target is secure. If it does, there is nothing here. If it
      // does not, there is a worse question, because onSensitiveContentVisibilityChanged calls
      // clearFlags(FLAG_SECURE) unconditionally - this app would then be stripping a flag the
      // platform had set. Measured rather than reasoned about, either way.
      instrumentation.runOnMainSync(() -> activity.getWindow()
          .addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE));
      activity.setRequestedOrientation(
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      instrumentation.waitForIdleSync();
      Thread.sleep(2_000L);

      final String withSecureHost = windowDump();
      final String hostBlock = hostWindowBlock(withSecureHost);
      assertTrue("anti-vacuity: the HOST window must actually be secure, or the assertion below is "
              + "asking about a protection that was never applied. Host block was:\n" + hostBlock,
          secure(hostBlock));

      final boolean imeSecureBesideSecureHost = secure(imeWindowBlock(withSecureHost));
      System.out.println("MEASURED landscape, secure host: imeSecure=" + imeSecureBesideSecureHost);

      instrumentation.runOnMainSync(() -> activity.getWindow()
          .clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE));
      activity.setRequestedOrientation(
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      instrumentation.waitForIdleSync();
      Thread.sleep(1_500L);

      // Pinned as measured, not asserted as desired.
      //
      // This is an exposure, recorded in REVIEW-SETTLED and deliberately not fixed: in landscape
      // the keyboard runs in extract mode, the framework mirrors the host's text into a view inside
      // the IME's window, and isShowingSensitiveContent has no term for content this app does not
      // own - so a host protecting itself with FLAG_SECURE loses that through the keyboard.
      //
      // Not fixed because the obvious fix is one this project already made and reverted: a
      // FLAG_SECURE IME window blanks the entire system screenshot, so the predicate was narrowed
      // to the states where this app's own plaintext is on screen. Widening it for extract mode
      // reinstates that under another name.
      //
      // Pinned false rather than left unasserted so the state is recorded and cannot change
      // unnoticed. If this ever goes true - a platform that propagates the flag, or a deliberate
      // fix here - this test fails and the ledger entry needs rewriting rather than quietly
      // rotting, which is the failure mode this branch has corrected in its own documents four
      // times.
      assertFalse("the IME window is now secure beside a secure host in landscape. That is better "
              + "than what was measured, but it means the recorded exposure is stale - update "
              + "REVIEW-SETTLED rather than deleting this assertion",
          imeSecureBesideSecureHost);

      assertTrue("and the flag must come back off when the sensitive screen closes, or ordinary "
              + "typing stops screenshotting for the rest of the keyboard's life",
          !secure(afterwards));
    } finally {
      activity.finish();
    }
  }
}
