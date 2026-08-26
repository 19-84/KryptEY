package com.amnesica.kryptey.inputmethod;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.keyboard.Key;
import com.amnesica.kryptey.inputmethod.keyboard.Keyboard;
import com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A finger lands on the keyboard's own key surface, and a character comes out.
 *
 * <p>This closes the gap every report on this branch has had to name: nothing typed on the IME's own
 * keys. Twenty-four device tests drive the E2EE strip, the keystore and the protocol, and every one
 * of them reaches the keyboard through a method call. The path a user actually takes — a MotionEvent
 * on {@link MainKeyboardView}, through {@code PointerTracker} and the key detector, into
 * {@code LatinIME.onCodeInput}, through the input logic and out of the input connection — had no
 * coverage at all, on device or off.
 *
 * <p>It runs against the <em>live</em> keyboard: the service the framework bound, its real inflated
 * view, the keyboard it actually laid out, at the coordinates that view reports. Nothing here is
 * constructed by the test but the touch.
 *
 * <p><b>What this does not claim.</b> The touch is aimed using the view's own
 * {@link MainKeyboardView#getKeyX}/{@link MainKeyboardView#getKeyY} converters, so it is not
 * independent evidence that key hit-boxes map correctly — using a component's own mapping to aim at
 * it and then concluding the mapping is right would be circular, and this file says so rather than
 * letting the reader assume otherwise. Aiming is fixture; the claim is the path behind it. What is
 * genuinely established is that a touch delivered to the key surface reaches the input connection as
 * the character that key bears — which is the half that had nothing.
 */
@RunWith(AndroidJUnit4.class)
public class TypingOnTheKeySurfaceOnDeviceTest {

  private static final String IME_ID =
      "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final long FOCUS_TIMEOUT_MS = 20_000L;
  private static final long BIND_TIMEOUT_MS = 60_000L;
  private static final long KEYBOARD_TIMEOUT_MS = 30_000L;
  private static final long TEXT_TIMEOUT_MS = 10_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  /** The key surface only exists while the framework has the keyboard bound to a real field. */
  @Test
  public void atouchOnAkeyPutsThatKeysCharacterIntoTheField() throws Exception {
    assertEquals("precondition: this app must be the selected input method", IME_ID,
        Settings.Secure.getString(context().getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD));

    final Instrumentation instrumentation = getInstrumentation();
    final Intent intent = new Intent(context(), EditableFieldActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    final Activity activity = instrumentation.startActivitySync(intent);
    try {
      final long focusDeadline = System.currentTimeMillis() + FOCUS_TIMEOUT_MS;
      while (!activity.hasWindowFocus() && System.currentTimeMillis() < focusDeadline) {
        Thread.sleep(POLL_MS);
      }
      assertTrue("the test field never gained window focus; nothing below measures the keyboard. "
          + "This is a harness failure, not a finding.", activity.hasWindowFocus());

      final EditableFieldActivity typed = (EditableFieldActivity) activity;
      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);

      // Same retry shape as ImeBindsDespiteExportedFalseTest, and for the same measured reason:
      // binding is asynchronous and a single request can land before the framework is ready.
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

      // Bound is not the same as laid out. The view exists before it has a width, and a touch at a
      // coordinate inside a zero-width view lands on nothing - which would read here as "typing is
      // broken" rather than "the test was early".
      MainKeyboardView view = null;
      Keyboard keyboard = null;
      final long keyboardDeadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS;
      while (System.currentTimeMillis() < keyboardDeadline) {
        view = KeyboardSwitcher.getInstance().getMainKeyboardView();
        keyboard = KeyboardSwitcher.getInstance().getKeyboard();
        if (view != null && keyboard != null && view.getWidth() > 0 && view.getHeight() > 0) break;
        Thread.sleep(POLL_MS);
      }
      assertNotNull("the bound keyboard never produced a main key view", view);
      assertNotNull("the main key view never got a keyboard", keyboard);
      assertTrue("the key view never gained a size, so no coordinate is inside it",
          view.getWidth() > 0 && view.getHeight() > 0);

      final Key key = keyboard.getKey('a');
      assertNotNull("the laid-out keyboard has no 'a' key, so this test cannot aim at one", key);

      // Solve for the view coordinate whose converted position is the key's centre. The mapping is
      // a translation (-paddingLeft, -paddingTop + verticalCorrection), so one correction is exact;
      // it is asserted rather than trusted below.
      final int targetX = key.getX() + key.getWidth() / 2;
      final int targetY = key.getY() + key.getHeight() / 2;
      final int touchX = targetX + (targetX - view.getKeyX(targetX));
      final int touchY = targetY + (targetY - view.getKeyY(targetY));

      assertTrue("the aim did not land inside the intended key horizontally - the coordinate "
              + "mapping is not the translation this test assumes, so the touch below would hit "
              + "some other key and the failure would be blamed on typing",
          view.getKeyX(touchX) >= key.getX() && view.getKeyX(touchX) < key.getX() + key.getWidth());
      assertTrue("the aim did not land inside the intended key vertically",
          view.getKeyY(touchY) >= key.getY() && view.getKeyY(touchY) < key.getY() + key.getHeight());

      final String before = textOf(typed);

      final MainKeyboardView target = view;
      instrumentation.runOnMainSync(() -> {
        final long down = SystemClock.uptimeMillis();
        final MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
            touchX, touchY, 0);
        target.dispatchTouchEvent(press);
        press.recycle();
        final MotionEvent release = MotionEvent.obtain(down, down + 30L, MotionEvent.ACTION_UP,
            touchX, touchY, 0);
        target.dispatchTouchEvent(release);
        release.recycle();
      });

      // The character crosses a process-internal input connection and a handler, so it is polled
      // rather than read once.
      String after = before;
      final long textDeadline = System.currentTimeMillis() + TEXT_TIMEOUT_MS;
      while (System.currentTimeMillis() < textDeadline) {
        after = textOf(typed);
        if (!after.equals(before)) break;
        Thread.sleep(POLL_MS);
      }

      assertEquals("a touch on the 'a' key must put an 'a' into the field it is typing into. This "
              + "is the whole point of a keyboard, and until this test nothing on the branch "
              + "exercised it: every other device test calls into the keyboard rather than "
              + "touching it. Field held \"" + before + "\" before the touch.",
          before + "a", after);
    } finally {
      activity.finish();
    }
  }

  private String textOf(final EditableFieldActivity activity) {
    final String[] holder = new String[1];
    getInstrumentation().runOnMainSync(() -> holder[0] = activity.field.getText().toString());
    return holder[0];
  }
}
