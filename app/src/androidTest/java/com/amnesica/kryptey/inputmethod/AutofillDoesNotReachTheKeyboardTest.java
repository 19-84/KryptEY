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

import java.util.ArrayList;
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
  /** The scaffolding field focused while the keyboard is up, which autofill SHOULD see. */
  private static final String SECOND_FIELD_ID = "autofill_probe_field_two";

  /**
   * Every id in the keyboard's own layout, not just the compose box.
   *
   * <p>The commit that introduced this test claimed "nothing from the keyboard's window" while
   * asserting the absence of a single string. Checking the whole layout costs nothing and makes the
   * sentence true — and if the platform ever does start including IME windows, the compose box is
   * not the only thing in there worth knowing about.
   */
  private static final String[] IME_VIEW_IDS = {
      "e2ee_input_field", "e2ee_strip_view", "e2ee_strip_wrapper", "e2ee_main_wrapper",
      "e2ee_main_button_strip", "e2ee_edit_text_layout", "e2ee_info_text",
      "e2ee_button_encrypt", "e2ee_button_decrypt", "e2ee_button_clear_text",
      "e2ee_button_select_recipient", "e2ee_button_add_recipient", "e2ee_button_chat_logs",
      "e2ee_button_show_help", "e2ee_button_select_encoding_raw",
      "e2ee_button_select_encoding_fairytale", "keyboard_view",
  };

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
   * With the keyboard up, a fresh fill request still carries nothing belonging to it.
   *
   * <p><b>The ordering is the whole test.</b> An earlier version of this asserted the same absence
   * and established nothing, for a reason worth writing down: autofill builds a structure when a
   * view takes focus, the activity focuses its first field in {@code onCreate}, and the test then
   * waited for window focus — the very event that triggers the request. So the only structure it
   * could capture was built before the IME window existed, and the compose box could not have been
   * in it whether or not the platform would ever include such a thing. It was green in both
   * worlds.
   *
   * <p>So this waits until the keyboard is demonstrably bound and connected, and only then moves
   * focus to a <em>second</em> field, which forces a new request at a moment when the keyboard's
   * window is up. The assertion is made against that request specifically, identified by sequence
   * number, rather than against a merged pile of everything the service has ever seen.
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

      // Step 1: get the keyboard genuinely up. isActive alone says a client has an input
      // connection; it does not say the IME's own window was ever created. Waiting for the service
      // as well is what makes "the keyboard window exists" more than an assumption.
      final long bindDeadline = System.currentTimeMillis() + TIMEOUT_MS;
      boolean keyboardUp = false;
      while (System.currentTimeMillis() < bindDeadline) {
        instrumentation.runOnMainSync(() -> {
          typed.field.requestFocus();
          imm.showSoftInput(typed.field, InputMethodManager.SHOW_FORCED);
        });
        if (imm.isActive(typed.field) && imeServiceIsRunning()) {
          keyboardUp = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }
      assertTrue("the keyboard never bound and connected, so there was never a keyboard window to "
          + "look for in any structure. Harness failure, not a finding.", keyboardUp);

      // Step 2: with the keyboard up, move focus. THIS is the request that matters.
      final int before = RecordingAutofillService.requestCount();

      // Retried, and the focus is TOGGLED rather than re-requested.
      //
      // This test failed on two consecutive device runs and passed on a third with nothing changed,
      // and the loop it failed in was a single best-effort focus move followed by a passive wait.
      // The bind loop twenty lines above retries its request on every poll, and this file already
      // records why: "a single best-effort call is why an earlier version timed out on a freshly
      // booted device and passed on one that had been up for a while". The same reasoning applies
      // here and had not been applied.
      //
      // Re-requesting focus on the view that already has it is a no-op and produces no new fill
      // request, so the retry alternates between the two fields - a focus CHANGE is what the
      // framework builds a structure for.
      final long requestDeadline = System.currentTimeMillis() + TIMEOUT_MS;
      boolean toggle = true;
      while (RecordingAutofillService.requestCount() <= before
          && System.currentTimeMillis() < requestDeadline) {
        final boolean toSecond = toggle;
        instrumentation.runOnMainSync(
            () -> (toSecond ? typed.secondField : typed.field).requestFocus());
        toggle = !toggle;
        Thread.sleep(POLL_MS);
      }

      // --- Control 1: a NEW request arrived, after the keyboard was up. ---
      assertTrue("no fill request was produced after the keyboard was already showing. Without one "
              + "there is nothing to inspect that could possibly have contained a keyboard view, "
              + "and any absence below would be an artefact of when the structure was built - "
              + "which is exactly how the previous version of this test managed to prove nothing.",
          RecordingAutofillService.requestCount() > before);

      final RecordingAutofillService.Recorded measured = RecordingAutofillService.latest();
      assertTrue("no recorded request to inspect", measured != null);
      assertTrue("the inspected request must be the one produced after the keyboard came up",
          measured.sequence > before);

      // --- Control 2: that structure is populated and names fields by id. ---
      assertTrue("the post-keyboard request did not contain the field that was just focused ("
              + SECOND_FIELD_ID + "), so either the structure was empty or ids are not reported, "
              + "and the assertion below would pass without looking. Saw: " + measured.viewIds,
          measured.viewIds.contains(SECOND_FIELD_ID));
      assertTrue("the structure reported no windows at all", measured.windowCount >= 1);

      // --- Control 3: the keyboard was still up when that structure was built. ---
      assertTrue("the keyboard's service stopped running before the measurement completed",
          imeServiceIsRunning());

      // --- The measurement: nothing from the keyboard's own layout is in it. ---
      final List<String> leaked = new ArrayList<>();
      for (final String id : IME_VIEW_IDS) {
        if (measured.viewIds.contains(id)) leaked.add(id);
      }
      assertEquals("views from the keyboard's own window were handed to an autofill service. If "
              + COMPOSE_BOX_ID + " is among them, every decrypted message the user has read is "
              + "offered to whatever autofill provider is installed, and FLAG_SECURE does not "
              + "cover autofill. android:importantForAutofill=\"no\" on that view is the one-line "
              + "mitigation, and this failure is the demonstrated need the branch was waiting for. "
              + "Leaked: " + leaked + "; whole structure: " + measured.viewIds,
          0, leaked.size());
    } finally {
      activity.finish();
    }
  }
}
