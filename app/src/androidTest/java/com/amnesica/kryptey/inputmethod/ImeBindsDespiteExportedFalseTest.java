package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * The keyboard is declared {@code android:exported="false"}, and the system binds it anyway.
 *
 * <p>This settles a question REVIVAL.md recorded as unanswerable and that I then answered wrongly
 * from an armchair. The manifest declares the IME service non-exported alongside
 * {@code android:permission="android.permission.BIND_INPUT_METHOD"}. InputMethodManagerService runs
 * in the system_server process under a different UID, so on the plain reading of {@code exported}
 * it should not be able to bind the service at all — and the failure would not be subtle, it would
 * be a keyboard that never appears.
 *
 * <p>A commit message on this branch asserted the opposite of the manifest: that an input method
 * "must be exported". That was reasoning, not measurement, and it was wrong. The manifest says
 * false, the app has shipped that way, and the platform accepts it. What is actually going on is
 * that {@code exported} is not the gate here: a service carrying the {@code BIND_INPUT_METHOD}
 * signature permission is bound by the system through the input-method framework rather than
 * through ordinary component export rules.
 *
 * <p>Why this needs a device and cannot be a manifest assertion: reading {@code exported="false"}
 * out of the XML is what produced two contradictory conclusions already. The only thing that
 * settles it is whether the service actually runs.
 *
 * <p><b>The measurement is deliberately indirect in one specific way.</b> Selecting an input method
 * writes {@code secure default_input_method} and nothing more; {@code dumpsys input_method} will
 * report a selected method that has never been bound, which is precisely the state this test found
 * the emulator in before it was written. So the assertion is not "is it selected" — it is whether
 * the service is *running*, observed after a real editable field asks for input. A service of our
 * own package is visible to us through {@link ActivityManager#getRunningServices} without any
 * permission, which is what makes this checkable from inside the app.
 */
@RunWith(AndroidJUnit4.class)
public class ImeBindsDespiteExportedFalseTest {

  private static final String IME_SERVICE =
      "com.amnesica.kryptey.inputmethod.latin.LatinIME";
  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";

  /** Long enough for a cold bind under software emulation, where everything is slow. */
  private static final long BIND_TIMEOUT_MS = 120_000L;
  /** Window focus arrives much sooner than a bind, or it is not coming at all. */
  private static final long FOCUS_TIMEOUT_MS = 30_000L;
  private static final long POLL_MS = 500L;

  private static Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private static boolean imeServiceIsRunning() {
    final ActivityManager manager =
        (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
    // On O+ this returns only the caller's own services. The IME is ours, which is the whole
    // reason this is observable at all.
    final List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(200);
    if (services == null) return false;
    for (final ActivityManager.RunningServiceInfo info : services) {
      if (IME_SERVICE.equals(info.service.getClassName())) return true;
    }
    return false;
  }

  private static String selectedIme() {
    return Settings.Secure.getString(
        context().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
  }

  /**
   * The precondition, asserted rather than assumed.
   *
   * <p>Enabling and selecting an input method needs shell privileges the test process does not
   * have, so {@code tools/test-on-emulator} does it before the run. If that step is skipped, every
   * assertion below would fail for a reason that has nothing to do with what is being tested, and
   * the message says so rather than leaving someone to guess.
   */
  @Test
  public void akryptEyIsTheSelectedInputMethod() {
    assertEquals("this keyboard must be the selected input method before the binding can be "
            + "tested. tools/test-on-emulator does this with 'ime enable' and 'ime set'; if you are "
            + "running the suite by hand, do the same.",
        IME_ID, selectedIme());
  }

  /**
   * The measurement: a real editable field asks for input, and an input connection is established.
   *
   * <p><b>What this asserts and why it is not "the service is running".</b> An earlier version of
   * this test polled {@link ActivityManager#getRunningServices} and returned the moment the service
   * appeared. That could pass without measuring anything: the runner selects the keyboard before
   * instrumentation starts, and any window-focus event on the device — the launcher, another test —
   * makes IMMS bind the selected method. The service would already be running on the first poll,
   * and the activity launch, the focus request and {@code showSoftInput} would all contribute
   * nothing to the result. Worse, it would pass in exactly the broken case it exists to catch: an
   * IME bound to something else while this field never gets focus and never becomes typable.
   *
   * <p>So the property asserted is {@link InputMethodManager#isActive(View)} — the framework
   * holding a live input connection <em>to this field</em>. That cannot be inherited from a bind
   * that happened before the test, because the field did not exist then. The running-service check
   * is kept as a second, weaker condition rather than the whole measurement.
   */
  @Test
  public void thesystemBindsTheNonExportedImeWhenAfieldAsksForInput() throws Exception {
    assertEquals("precondition", IME_ID, selectedIme());

    // Recorded rather than asserted. Finding it already bound does not invalidate the run - the
    // connection assertion below is specific to a field that does not exist yet - but it does
    // change what a failure means, and that is worth printing rather than reconstructing later.
    final boolean runningBefore = imeServiceIsRunning();

    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    // Against the TARGET context, deliberately. The activity is in the app's debug source set
    // rather than in this test APK, because instrumentation runs inside the target process and
    // startActivitySync refuses to launch an activity that belongs to a different one.
    final Intent intent = new Intent(context(), EditableFieldActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    final Activity activity = instrumentation.startActivitySync(intent);
    try {
      // startActivitySync returns when the activity is RESUMED, which is not the same as its
      // window having focus - and showSoftInput against an unfocused window is declined and does
      // nothing, silently. Waiting for focus first is what separates "the request was refused"
      // from "the keyboard could not be bound", which is the distinction this whole file exists
      // to make and which the first version of it blurred.
      final long focusDeadline = System.currentTimeMillis() + FOCUS_TIMEOUT_MS;
      while (!activity.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
        Thread.sleep(POLL_MS);
      }
      assertTrue("the test field never gained window focus, so nothing below would measure the "
          + "keyboard. On an emulator this usually means something else holds focus - an ANR "
          + "dialog is the recurring cause, and tools/test-on-emulator sets hide_error_dialogs "
          + "for that reason. This is a harness failure, not a finding about the keyboard.",
          activity.hasWindowFocus());

      final EditableFieldActivity typed = (EditableFieldActivity) activity;
      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);

      // Re-requested on every poll rather than once. Binding an input method is asynchronous and
      // the first request can land before the framework is ready for this window; a single
      // best-effort call is why an earlier version timed out on a freshly booted device and
      // passed on one that had been up for a while.
      //
      // Measured, not assumed: showSoftInput is NOT what establishes the connection here. A
      // control that deleted this call entirely still passed, because the activity focuses the
      // field in onCreate and the framework starts input on focus. The call is kept because it is
      // what a user pressing a text box actually causes, and because the retry loop around it is
      // what makes this survive a cold device - but the load-bearing act is the focused field, and
      // saying otherwise in a comment would be the kind of unearned claim this file exists to
      // stop being made.
      final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
      boolean active = false;
      while (System.currentTimeMillis() < deadline) {
        instrumentation.runOnMainSync(() -> {
          typed.field.requestFocus();
          // SHOW_FORCED rather than SHOW_IMPLICIT: an implicit request is one the framework is
          // free to decline, and a declined request would read here as a failure to bind.
          imm.showSoftInput(typed.field, InputMethodManager.SHOW_FORCED);
        });
        if (imm.isActive(typed.field)) {
          active = true;
          break;
        }
        Thread.sleep(POLL_MS);
      }

      assertTrue("no input connection was ever established to the test field. The keyboard is "
          + "declared exported=\"false\"; if the platform has begun enforcing export rules for "
          + "input methods this is where it would show up - but a starved emulator and a "
          + "focus-stealing dialog produce this same result, so check what the device was doing "
          + "before concluding anything about the manifest. Service running before this test: "
          + runningBefore + "; running now: " + imeServiceIsRunning(), active);

      assertTrue("an input connection exists but this app's own input method service is not "
          + "running, which should be impossible when it is the selected method - something else "
          + "is serving the connection", imeServiceIsRunning());
    } finally {
      activity.finish();
    }
  }

  /**
   * The framework lists it among the enabled input methods.
   *
   * <p>Deliberately modest about what this proves. {@code getEnabledInputMethodList} is a read of
   * {@code Settings.Secure.enabled_input_methods} cross-referenced against {@code PackageManager};
   * it binds nothing and does not touch {@code exported} semantics. An earlier javadoc here claimed
   * it corroborated the binding, which it does not — it corroborates that the harness step ran and
   * that the package manager considers this a well-formed input method, which is worth having and
   * is not the same thing.
   */
  @Test
  public void theframeworkListsItAmongTheEnabledInputMethods() {
    final InputMethodManager imm =
        (InputMethodManager) context().getSystemService(Context.INPUT_METHOD_SERVICE);
    boolean found = false;
    for (final android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
      if (IME_SERVICE.equals(info.getServiceName())) found = true;
    }
    assertTrue("the framework does not list this keyboard among the enabled input methods, so "
        + "whatever else is true, the system will not use it", found);
  }
}
