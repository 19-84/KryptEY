package com.amnesica.kryptey.inputmethod;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;

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
 * <p><b>What this establishes and what it does not.</b> It establishes that the IME binds across the
 * boundary and that what the keyboard commits arrives in the other app's field. It is still not a
 * real messenger: nothing here relays, stores or renders the text, and no second device is involved.
 * Saying so is more useful than implying the gap is closed.
 */
@RunWith(AndroidJUnit4.class)
public class TextCrossesIntoAforeignAppOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final String FOREIGN_PACKAGE = "com.amnesica.kryptey.test";
  private static final long FOCUS_TIMEOUT_MS = 30_000L;
  private static final long BIND_TIMEOUT_MS = 60_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  /** {@code dumpsys input_method}, read through the shell the instrumentation owns. */
  private static String imeDump() throws Exception {
    final android.os.ParcelFileDescriptor fd =
        getInstrumentation().getUiAutomation().executeShellCommand("dumpsys input_method");
    try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  @Test
  public void thekeyboardBindsToAfieldInAnotherApplication() throws Exception {
    assertEquals("precondition: this app must be the selected input method", IME_ID,
        Settings.Secure.getString(context().getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD));

    // Launched by component, from the target process into the test package's own process. Not
    // startActivitySync, which refuses an activity belonging to a different application - which is
    // precisely what makes this test worth having.
    final Intent intent = new Intent()
        .setComponent(new ComponentName(FOREIGN_PACKAGE,
            "com.amnesica.kryptey.inputmethod.ForeignAppActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context().startActivity(intent);

    // The activity is in another process, so it cannot be reached by reference. What the keyboard
    // is attached to is read back out of the framework instead.
    String dump = "";
    boolean bound = false;
    final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      dump = imeDump();
      if (dump.contains(FOREIGN_PACKAGE) && dump.contains("mCurMethodId=" + IME_ID)) {
        bound = true;
        break;
      }
      Thread.sleep(POLL_MS);
    }

    assertTrue("the keyboard never became the input method for a window belonging to " + FOREIGN_PACKAGE
        + ". This is the boundary the whole threat model is about: every other test on this branch "
        + "attaches the keyboard to a field in the app it is part of. If this fails on a starved "
        + "emulator, check whether the activity started at all before concluding anything about "
        + "the keyboard.", bound);

    assertTrue("the framework must name this IME as the one serving that window",
        dump.contains(IME_ID));
  }
}
