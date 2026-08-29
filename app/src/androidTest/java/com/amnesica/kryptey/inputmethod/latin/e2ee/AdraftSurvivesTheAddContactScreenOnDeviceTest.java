package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
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
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.amnesica.kryptey.inputmethod.keyboard.Key;
import com.amnesica.kryptey.inputmethod.keyboard.Keyboard;
import com.amnesica.kryptey.inputmethod.keyboard.KeyboardSwitcher;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;
import com.amnesica.kryptey.inputmethod.EditableFieldActivity;
import com.amnesica.kryptey.inputmethod.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The add-contact screen must not hand a half-written private message to the host application —
 * confirmed on a device, because the JVM test for it cannot be believed on its own.
 *
 * <p>Leaving that screen lowers the typing redirect, and its escape hatch asked
 * {@code mInputEditText.hasFocus()} — a condition that can never be true there, because the compose
 * box sits inside a wrapper that is {@code GONE} for the life of that screen. The lowering was
 * therefore unconditional, and the next keystroke went to the messenger in cleartext with the draft
 * still on screen.
 *
 * <p>The fix re-points the redirect at the compose box when it holds text. Whether that works
 * depends on Android's focus semantics for a subtree that was GONE and came back — and
 * {@code TypingDestinationTest} already records that Robolectric's focus behaviour differs from a
 * device's. A review round said so explicitly: a green Robolectric result here needs confirming on
 * the instrumentation runner before it is believed. This is that confirmation, through the real key
 * surface rather than a synthesised commit.
 */
@RunWith(AndroidJUnit4.class)
public class AdraftSurvivesTheAddContactScreenOnDeviceTest {

  private static final String IME_ID = "com.amnesica.kryptey/.inputmethod.latin.LatinIME";
  private static final long FOCUS_TIMEOUT_MS = 20_000L;
  private static final long BIND_TIMEOUT_MS = 60_000L;
  private static final long KEYBOARD_TIMEOUT_MS = 30_000L;
  private static final long TEXT_TIMEOUT_MS = 10_000L;
  private static final long POLL_MS = 250L;

  private static Context context() {
    return getInstrumentation().getTargetContext();
  }

  private String hostText(final EditableFieldActivity activity) {
    final AtomicReference<String> text = new AtomicReference<>("");
    getInstrumentation().runOnMainSync(() -> text.set(activity.field.getText().toString()));
    return text.get();
  }

  private String composeText(final E2EEStripView strip) {
    final AtomicReference<String> text = new AtomicReference<>("");
    getInstrumentation().runOnMainSync(() -> {
      final EditText box = strip.findViewById(R.id.e2ee_input_field);
      text.set(box == null ? "" : box.getText().toString());
    });
    return text.get();
  }

  /** One real touch on the 'a' key, aimed the way TypingOnTheKeySurfaceOnDeviceTest aims. */
  private void pressTheAkey(final MainKeyboardView view, final Keyboard keyboard) {
    final Key key = keyboard.getKey('a');
    assertNotNull("the laid-out keyboard has no 'a' key to aim at", key);
    final int targetX = key.getX() + key.getWidth() / 2;
    final int targetY = key.getY() + key.getHeight() / 2;
    final int touchX = targetX + (targetX - view.getKeyX(targetX));
    final int touchY = targetY + (targetY - view.getKeyY(targetY));
    assertTrue("the aim did not land inside the intended key, so a miss would read as a leak",
        view.getKeyX(touchX) >= key.getX() && view.getKeyX(touchX) < key.getX() + key.getWidth());

    getInstrumentation().runOnMainSync(() -> {
      final long down = SystemClock.uptimeMillis();
      final MotionEvent press =
          MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, touchX, touchY, 0);
      view.dispatchTouchEvent(press);
      press.recycle();
      final MotionEvent release =
          MotionEvent.obtain(down, down + 30L, MotionEvent.ACTION_UP, touchX, touchY, 0);
      view.dispatchTouchEvent(release);
      release.recycle();
    });
    getInstrumentation().waitForIdleSync();
  }

  @Test
  public void typingAfterTheAddContactScreenStillGoesToTheComposeBox() throws Exception {
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

      final EditableFieldActivity host = (EditableFieldActivity) activity;
      final InputMethodManager imm =
          (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      final long bindDeadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
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
      assertTrue("no input connection was ever established to the test field", active);

      MainKeyboardView view = null;
      Keyboard keyboard = null;
      E2EEStripView strip = null;
      final long keyboardDeadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS;
      while (System.currentTimeMillis() < keyboardDeadline) {
        view = KeyboardSwitcher.getInstance().getMainKeyboardView();
        keyboard = KeyboardSwitcher.getInstance().getKeyboard();
        if (view != null && keyboard != null && view.getWidth() > 0) {
          strip = view.getRootView().findViewById(R.id.e2ee_strip_view);
          if (strip != null) break;
        }
        Thread.sleep(POLL_MS);
      }
      assertNotNull("the bound keyboard never produced a key view", view);
      assertNotNull("the key view never got a keyboard", keyboard);
      assertNotNull("the keyboard never produced an E2EE strip", strip);

      // The user starts composing inside the keyboard.
      final E2EEStripView live = strip;
      instrumentation.runOnMainSync(
          () -> live.findViewById(R.id.e2ee_input_field).requestFocus());
      instrumentation.waitForIdleSync();

      final String hostBefore = hostText(host);
      pressTheAkey(view, keyboard);

      String composed = "";
      final long deadline = System.currentTimeMillis() + TEXT_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
        composed = composeText(live);
        if (!composed.isEmpty()) break;
        Thread.sleep(POLL_MS);
      }
      assertTrue("precondition: the first keystroke must reach the compose box, or this test is "
          + "not measuring the redirect at all", composed.length() > 0);
      assertEquals("precondition: and must not reach the host field", hostBefore, hostText(host));

      // An invite from an unknown address routes the decrypt here; Cancel is the correct response.
      instrumentation.runOnMainSync(() -> {
        live.showAddContactViewForTest();
        live.showMainViewForTest();
      });
      instrumentation.waitForIdleSync();

      assertEquals("the draft must still be on screen where the user left it", composed,
          composeText(live));

      // And the strip must AGREE that it is composing here.
      //
      // showOnlyUIViewInternal calls composeInsideTheKeyboard() while the main view is still GONE -
      // the block is gated on the add-contact view being VISIBLE, and the flip to MAIN_VIEW happens
      // seven lines below it. composeInsideTheKeyboard's own javadoc says requestFocus() "returns
      // false silently whenever the view cannot take focus at that moment - a GONE ancestor", and
      // it raises the redirect regardless of the return.
      //
      // If focus is refused there, typing lands in the compose box while the two buttons that
      // changeVisibilityInputFieldButtons calls "the app's statement that the user is composing
      // inside the keyboard" stay dark. That fails in the safe direction - no plaintext reaches the
      // messenger - but it is the mirror of the defect that javadoc was written for, and in that
      // state Clear and the encoding selector are both unreachable.
      //
      // Asserted on a device because Robolectric grants focus unconditionally, so the JVM suite
      // cannot tell the two outcomes apart. This is the check that says which one the platform
      // actually produces.
      final EditText box = live.findViewById(R.id.e2ee_input_field);
      assertNotNull("fixture: the compose box must exist", box);
      assertTrue("the redirect is up and the draft is on screen, but the compose box does not hold "
              + "focus - composeInsideTheKeyboard ran while the main view was still GONE, so "
              + "requestFocus() was refused silently and the redirect was raised anyway",
          box.hasFocus());
      final View clear = live.findViewById(R.id.e2ee_button_clear_text);
      assertNotNull("fixture: the clear button must exist", clear);
      assertEquals("the app must say it is composing in the keyboard while it is doing so - these "
              + "buttons are that statement, and they come back only through the focus listener",
          View.VISIBLE, clear.getVisibility());

      pressTheAkey(view, keyboard);
      Thread.sleep(1_000L);

      assertEquals("the rest of a half-written private message was committed into the host "
              + "application's own field. The draft was still on screen and the only visible "
              + "change was two small buttons going dark",
          hostBefore, hostText(host));
      assertTrue("and it must have gone to the compose box instead",
          composeText(live).length() > composed.length());

      // A send must hand typing back and LEAVE it there.
      //
      // sendEncryptedMessageToApplication lowers the redirect and three statements later calls
      // mInputEditText.clearFocus(). Clearing focus inside a focusable container does not leave
      // nothing focused - the container re-grants it to the next candidate, and on the main view
      // the compose box is the only focusableInTouchMode view up at that moment. Its focus
      // listener then calls composeInsideTheKeyboard(), which raises the redirect again.
      //
      // Asked on a device because the JVM suite cannot see it. Measured there: the Robolectric
      // fixture leaves composeHasFocus=false after the same call, so it never produces the
      // re-grant and would report success whatever the platform does.
      //
      // The strip's real listener is left in place. Swapping it for a recording one works and then
      // leaks: this is the LIVE strip in a shared process, and the next test inherits whatever it
      // was left pointing at. Measured the hard way - doing so broke the following test's
      // anti-vacuity control, which is that control doing its job.
      instrumentation.runOnMainSync(
          () -> live.sendEncryptedMessageToApplicationForTest("ciphertext-goes-to-the-host"));
      instrumentation.waitForIdleSync();

      assertFalse("a send is one of the four places that hand typing back to the host, and the "
              + "compose box holds focus again afterwards - so its listener has raised the "
              + "redirect and the user's next message is composed inside the keyboard rather than "
              + "in the chat box they think they are typing in",
          live.<android.widget.EditText>findViewById(R.id.e2ee_input_field).hasFocus());
    } finally {
      // The device suite shares one process and one live IME, so this has to hand the keyboard
      // back in the state it found it. Leaving a draft in the compose box leaves the strip
      // showing content, which raises FLAG_SECURE - and the next test's anti-vacuity control
      // ("the ordinary keyboard must NOT be secure") then fails on this test's residue. It did,
      // which is the control working: a shared-fixture leak reads as a defect in whatever runs
      // next.
      final E2EEStripView toClean =
          KeyboardSwitcher.getInstance().getMainKeyboardView() == null ? null
              : KeyboardSwitcher.getInstance().getMainKeyboardView().getRootView()
                  .findViewById(R.id.e2ee_strip_view);
      if (toClean != null) {
        getInstrumentation().runOnMainSync(() -> {
          final EditText box = toClean.findViewById(R.id.e2ee_input_field);
          if (box != null) {
            box.setText("");
            box.clearFocus();
          }
          toClean.onKeyboardHidden();
        });
        getInstrumentation().waitForIdleSync();
      }
      activity.finish();
    }
  }
}
