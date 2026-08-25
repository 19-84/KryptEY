package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
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

  /** Long enough for a cold service start under software emulation, where everything is slow. */
  private static final long BIND_TIMEOUT_MS = 60_000L;
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
   * The measurement: a real editable field asks for input, and the non-exported service starts.
   */
  @Test
  public void thesystemBindsTheNonExportedImeWhenAfieldAsksForInput() throws Exception {
    assertEquals("precondition", IME_ID, selectedIme());

    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    // Against the TARGET context, deliberately. The activity is in the app's debug source set
    // rather than in this test APK, because instrumentation runs inside the target process and
    // startActivitySync refuses to launch an activity that belongs to a different one.
    final Intent intent = new Intent(context(), EditableFieldActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    final Activity activity = instrumentation.startActivitySync(intent);
    try {
      instrumentation.runOnMainSync(() -> {
        final EditableFieldActivity typed = (EditableFieldActivity) activity;
        typed.field.requestFocus();
        final InputMethodManager imm =
            (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        // SHOW_FORCED rather than SHOW_IMPLICIT: an implicit request is one the framework is free
        // to decline (a hardware keyboard attached, for instance), and a declined request would
        // read here as "the service could not be bound".
        imm.showSoftInput(typed.field, InputMethodManager.SHOW_FORCED);
      });

      final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
        if (imeServiceIsRunning()) return;
        Thread.sleep(POLL_MS);
      }
      fail("the input method service never started. It is declared exported=\"false\" in the "
          + "manifest, so if the platform has begun enforcing export rules for input methods, this "
          + "is where that shows up - as a keyboard that can be selected and then never appears.");
    } finally {
      activity.finish();
    }
  }

  /**
   * And the framework agrees it is a usable input method, not merely a process that started.
   *
   * <p>Separate from the assertion above on purpose. A service can be running for reasons unrelated
   * to the input-method framework; being in {@code getEnabledInputMethodList} is the framework's
   * own statement that it will use this one.
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
