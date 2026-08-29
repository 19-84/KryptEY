package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Decrypted plaintext must not be one long-press away from the system clipboard.
 *
 * <p>The selection toolbar over an editable field offers Copy, Share and {@code ACTION_PROCESS_TEXT}
 * — the clipboard the messenger reads, and an {@code Intent} extra to any installed app. Nothing in
 * the strip refuses that toolbar directly: there is no
 * {@code setCustomSelectionActionModeCallback} and no {@code textIsSelectable="false"}. What refuses
 * it is the movement method. {@code TextView} builds a selection controller only when its movement
 * method can select arbitrarily, and {@code ScrollingMovementMethod} cannot.
 *
 * <p>So the property is real and accidental: the line was written to scroll a tall message, and
 * swapping it for {@code ArrowKeyMovementMethod} — the ordinary fix for "tapping cannot place the
 * cursor" — would reopen both paths with nothing to notice. This pins the property that is doing
 * the work, on the inflated view, so the mutant is exactly that swap.
 *
 * <p>The insertion toolbar (Paste, Select all) is a separate mechanism and is not what this asserts;
 * a {@code setCustomSelectionActionModeCallback} returning false would suppress the selection
 * toolbar without touching it, which is why that is not the fix used here.
 */
@RunWith(RobolectricTestRunner.class)
public class NoTextIsSelectableOutOfTheStripTest {

  private EditText composeBox() {
    final E2EEStripView strip = new E2EEStripView(new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day), null);
    strip.setListener(new E2EEStripView.Listener() {
      @Override public void onTextInput(final String rawText) { }
      @Override public void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
    }, strip);
    final EditText field = strip.findViewById(R.id.e2ee_input_field);
    assertNotNull("the compose box must inflate", field);
    return field;
  }

  @Test
  public void thecomposeBoxCannotSelectArbitrarily() {
    final EditText field = composeBox();
    assertNotNull("the compose box must have a movement method; without one it also cannot scroll, "
        + "and a reader would not know this line was load-bearing", field.getMovementMethod());

    assertTrue("the compose box can select text arbitrarily, so a long-press offers Copy, Share and "
            + "PROCESS_TEXT over a decrypted message - onto the clipboard the messenger reads, or "
            + "into an Intent extra for any installed app. Nothing else in the strip refuses that "
            + "toolbar: " + field.getMovementMethod().getClass().getName(),
        !field.getMovementMethod().canSelectArbitrarily());
  }

  /**
   * And it still scrolls, so the property was not obtained by removing the movement method.
   *
   * <p>The floor. A null movement method also cannot select arbitrarily, and would satisfy the test
   * above while making a long message unreadable.
   */
  @Test
  public void thecomposeBoxStillScrolls() {
    assertTrue("the compose box must still scroll a tall message",
        composeBox().getMovementMethod() instanceof android.text.method.ScrollingMovementMethod);
  }
}
