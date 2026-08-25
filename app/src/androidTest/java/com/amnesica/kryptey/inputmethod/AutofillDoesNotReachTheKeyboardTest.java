package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Whether the decrypted-message box is offered to whatever autofill service the user has enabled.
 *
 * <p>REVIVAL.md has carried this as an open question since the layouts were audited, and it is a
 * real one rather than a theoretical tidiness point. The compose box (`e2ee_input_field`) holds
 * peer plaintext after a decrypt. It is an ordinary {@code EditText}, nothing in the layout or the
 * code sets {@code importantForAutofill}, and an {@code EditText} is autofill-eligible by default.
 * {@code FLAG_SECURE} is no help here — it protects pixels, and autofill reads text. So if the
 * framework reaches views inside an IME's own window, every decrypted message is handed to a
 * third-party autofill service, and the app's central promise is broken by a default.
 *
 * <p>The document deliberately did <em>not</em> add {@code importantForAutofill="no"} as a
 * precaution, on the grounds that this branch has twice added guards it could not demonstrate a
 * need for. It wrote down the experiment instead: enable an autofill service and look at what it is
 * given. {@link RecordingAutofillService} is that service and this is that experiment.
 *
 * <p><b>The controls matter more than the assertion.</b> A test that asserts "the compose box was
 * not in the structure" passes trivially if autofill never ran, if the structure was empty, or if
 * the keyboard was never on screen — three ways to conclude "safe" while measuring nothing, and
 * this branch has already shipped one test that did exactly that. So before the absence is
 * believed, this test establishes that autofill actually fired, that the structure it produced
 * identifies fields by id and contains the host's own field, and that the keyboard was bound and
 * connected at the time.
 */
@RunWith(AndroidJUnit4.class)
public class AutofillDoesNotReachTheKeyboardTest {

  /** The compose box that holds decrypted plaintext. */
  private static final String COMPOSE_BOX_ID = "e2ee_input_field";
  /** The scaffolding field in the host activity, which autofill SHOULD see. */
  private static final String HOST_FIELD_ID = "autofill_probe_field";

  private static final String AUTOFILL_SERVICE =
      "com.amnesica.kryptey/com.amnesica.kryptey.inputmethod.RecordingAutofillService";
  private static final String IME_SERVICE = "com.amnesica.kryptey.inputmethod.latin.LatinIME";
  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";

  private static final long TIMEOUT_MS = 120_000L;
  private static final long POLL_MS = 500L;

  private static Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private static String setting(final String key) {
    return Settings.Secure.getString(context().getContentResolver(), key);
  }

  private static boolean imeServiceIsRunning() {
    final ActivityManager manager =
        (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
    final List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(200);
    if (services == null) return false;
    for (final ActivityManager.RunningServiceInfo info : services) {
      if (IME_SERVICE.equals(info.service.getClassName())) return true;
    }
    return false;
  }

  @Before
  public void setUp() {
    RecordingAutofillService.reset();
  }

  /**
   * The id this test looks for is a real, inflated view id.
   *
   * <p>Without this the whole measurement rots into a tautology the day someone renames the field:
   * a string that matches nothing is absent from every structure forever, and the test reports
   * safety with more confidence each time. The layout inflates {@code ee2e_main_view} in
   * {@code E2EEStripView}'s constructor, so the box exists whenever the keyboard's input view does
   * — but the constant here is a hand-written string, and that is the part that can drift.
   */
  @Test
  public void abthecomposeBoxIdActuallyExists() {
    final int id = context().getResources().getIdentifier(
        COMPOSE_BOX_ID, "id", context().getPackageName());
    assertTrue("no view id named '" + COMPOSE_BOX_ID + "' exists in the app any more. This test "
        + "searches autofill structures for that name; if it names nothing, it can only ever pass. "
        + "Rename the constant to match the layout.", id != 0);
  }

  /**
   * The preconditions, asserted rather than assumed.
   *
   * <p>Both of these need shell privileges the test process does not have, so
   * {@code tools/test-on-emulator} sets them. If either is missing the measurement below would
   * "pass" while proving nothing at all, which is the outcome to avoid at all costs here.
   */
  @Test
  public void aboththeAutofillServiceAndTheKeyboardAreSelected() {
    assertEquals("the recording autofill service must be the enabled one; tools/test-on-emulator "
            + "sets this with 'settings put secure autofill_service'",
        AUTOFILL_SERVICE, setting("autofill_service"));
    assertEquals("this keyboard must be the selected input method, or the compose box is never on "
        + "screen and its absence from the structure means nothing", IME_ID,
        setting("default_input_method"));
  }

  /**
   * With the keyboard up over a real text field, autofill never sees the compose box.
   */
  @Test
  public void thecomposeBoxIsNeverOfferedToTheAutofillService() throws Exception {
    assertEquals("precondition", AUTOFILL_SERVICE, setting("autofill_service"));
    assertEquals("precondition", IME_ID, setting("default_input_method"));

    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Intent intent = new Intent(context(), EditableFieldActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    final Activity activity = instrumentation.startActivitySync(intent);
    try {
      final EditableFieldActivity typed = (EditableFieldActivity) activity;
      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);

      final long focusDeadline = System.currentTimeMillis() + 30_000L;
      while (!activity.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
        Thread.sleep(POLL_MS);
      }
      assertTrue("the host field never gained window focus; this is a harness failure, not a "
          + "finding about autofill", activity.hasWindowFocus());

      // Drive the keyboard up and keep asking, for the same reason the binding test does: binding
      // is asynchronous and the first request can land before the framework is ready.
      final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
      boolean keyboardConnected = false;
      while (System.currentTimeMillis() < deadline) {
        instrumentation.runOnMainSync(() -> {
          typed.field.requestFocus();
          imm.showSoftInput(typed.field, InputMethodManager.SHOW_FORCED);
        });
        if (imm.isActive(typed.field) && RecordingAutofillService.requestCount() > 0) {
          keyboardConnected = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }

      // --- Control 1: autofill actually ran. Without this the absence below is meaningless. ---
      assertTrue("the autofill service was never asked to fill anything, so this test measured "
              + "nothing. Absence of the compose box from zero structures is not evidence. Check "
              + "that the service is enabled and that the host field is autofill-eligible.",
          RecordingAutofillService.requestCount() > 0);

      // --- Control 2: the structure is populated and names fields by id. ---
      final List<String> seen = RecordingAutofillService.seenViewIds();
      assertTrue("autofill ran but never reported the host activity's own field (" + HOST_FIELD_ID
              + "). Either the structure was empty or ids are not being reported, and in both "
              + "cases the assertion below would pass without looking. Saw: " + seen,
          seen.contains(HOST_FIELD_ID));

      // --- Control 3: the keyboard was actually up and connected while this happened. ---
      assertTrue("the keyboard never established an input connection, so its compose box was "
              + "never on screen and could not have appeared in any structure regardless",
          keyboardConnected);
      assertTrue("the keyboard's own service was not running during the measurement",
          imeServiceIsRunning());

      // --- The measurement. ---
      assertFalse("the decrypted-message compose box (" + COMPOSE_BOX_ID + ") was handed to an "
              + "autofill service. Everything the user has decrypted is offered to whatever "
              + "autofill provider is installed, and FLAG_SECURE does not cover autofill. "
              + "android:importantForAutofill=\"no\" on that view is the one-line mitigation, and "
              + "this failing test is the demonstrated need the branch was waiting for. Saw: "
              + seen, seen.contains(COMPOSE_BOX_ID));
    } finally {
      activity.finish();
    }
  }
}
