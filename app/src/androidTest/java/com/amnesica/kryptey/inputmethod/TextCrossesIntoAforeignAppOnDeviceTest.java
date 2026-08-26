package com.amnesica.kryptey.inputmethod;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;


import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The keyboard serving a field that belongs to a different application.
 *
 * <p>Every report on this branch has had to name the same gap: nothing moves text through another
 * app. The instrumentation suite drives the keyboard against a field in the app under test, so the
 * input connection never crosses a package boundary — and "the messenger is the adversary" is a
 * statement about exactly that boundary.
 *
 * <p>There was a second application on the device the whole time. The test APK is
 * {@code com.amnesica.kryptey.test}: its own package, its own process, installed beside the app.
 * {@link ForeignAppActivity} lives there, so the keyboard attaching to its field is doing what it
 * does for a messenger.
 *
 * <p><b>What this establishes and what it does not.</b> It establishes that this IME binds to a
 * field owned by another package. It does NOT commit any text — the sibling test does that. It is
 * still not a real messenger: nothing here relays, stores or renders anything, and no second device
 * is involved. Saying so is more useful than implying the gap is closed.
 */
@RunWith(AndroidJUnit4.class)
public class TextCrossesIntoAforeignAppOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final String FOREIGN_PACKAGE = "com.amnesica.kryptey.test";
  private static final long BIND_TIMEOUT_MS = 60_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }


  /** A shell command, read through the automation the instrumentation owns. */
  private static String shell(final String command) throws Exception {
    final android.os.ParcelFileDescriptor fd =
        getInstrumentation().getUiAutomation().executeShellCommand(command);
    try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  @org.junit.After
  public void tearDown() throws Exception {
    // The foreign activity must not outlive this test. See ForeignAppActivity.EXTRA_FINISH_AFTER_MS
    // for why a timer alone was not enough.
    shell("am broadcast -a " + ForeignAppActivity.ACTION_FINISH);
  }

  @Test
  public void thekeyboardBindsToAfieldInAnotherApplication() throws Exception {
    // A nonce per launch, because clearing logcat is not enough: the sibling test's activity lives
    // for tens of seconds after its test ends and posts a binding marker every 250ms, so a marker
    // in the buffer is not necessarily about the activity this test started.
    final String nonce = "bind-" + android.os.SystemClock.elapsedRealtimeNanos();
    shell("logcat -c");
    assertEquals("precondition: this app must be the selected input method", IME_ID,
        Settings.Secure.getString(context().getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD));

    // Launched by component, from the target process into the test package's own process. Not
    // startActivitySync, which refuses an activity belonging to a different application - which is
    // precisely what makes this test worth having.
    final Intent intent = new Intent()
        .setComponent(new ComponentName(FOREIGN_PACKAGE,
            "com.amnesica.kryptey.inputmethod.ForeignAppActivity"))
        // Longer than this test's own bind deadline, so the subject cannot outlive-fail the test.
        .putExtra(ForeignAppActivity.EXTRA_FINISH_AFTER_MS, 120_000L)
        .putExtra(ForeignAppActivity.EXTRA_NONCE, nonce)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    context().startActivity(intent);

    // Asked of the foreign process about its own field, not of dumpsys.
    //
    // The first version of this scanned `dumpsys input_method` for the package name and for
    // `mCurMethodId=<this IME>`. Neither establishes a binding. mCurMethodId is the SELECTED input
    // method - true from the moment the harness runs `ime set`, before any test starts, which this
    // repo already records elsewhere in as many words - and the package name appears in the dump's
    // start-input history, so it survives long after the IME has moved on. Two unanchored
    // substrings over one dump, with nothing tying the served window to that package: it would have
    // passed on residue left by another test in the same run.
    boolean bound = false;
    final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline && !bound) {
      for (final String line : shell("logcat -d -s " + ForeignAppActivity.TAG).split("\n")) {
        if (line.contains(ForeignAppActivity.BOUND_MARKER)
            && line.contains("nonce=" + nonce) && line.contains("active=true")) bound = true;
      }
      if (!bound) Thread.sleep(POLL_MS);
    }

    assertTrue("the keyboard never became the input method for a window belonging to " + FOREIGN_PACKAGE
        + ". This is the boundary the whole threat model is about: every other test on this branch "
        + "attaches the keyboard to a field in the app it is part of. If this fails on a starved "
        + "emulator, check whether the activity started at all before concluding anything about "
        + "the keyboard.", bound);

    // Nothing further asserted here. An earlier line checked imeDump().contains(IME_ID), which is
    // true on any device where this APK is merely installed - dumpsys prints the InputMethodInfo of
    // every installed IME - and the claim it was captioned with is already established soundly by
    // the DEFAULT_INPUT_METHOD precondition above. It was the same unanchored-substring pattern this
    // file exists to retire.
  }
}
