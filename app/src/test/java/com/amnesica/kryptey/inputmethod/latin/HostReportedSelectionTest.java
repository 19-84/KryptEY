package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.amnesica.kryptey.inputmethod.event.Event;
import com.amnesica.kryptey.inputmethod.latin.common.Constants;
import com.amnesica.kryptey.inputmethod.latin.settings.Settings;
import com.amnesica.kryptey.inputmethod.latin.settings.SettingsValues;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The size of a delete is a number the host chose, and nothing checks its sign.
 *
 * <p>{@code mExpectedSelStart} / {@code mExpectedSelEnd} are written from exactly two places, and
 * both are the host application describing its own field: {@code EditorInfo.initialSelStart/End}
 * at the start of an input session, and {@code onUpdateSelection}, which the framework delivers
 * verbatim from {@code InputMethodManager.updateSelection} - an ordinary, unprivileged call whose
 * four integers the calling app picks. Neither entry point validates anything: not a range, not an
 * order, not a sign.
 *
 * <p>{@code InputLogic.handleBackspaceEvent} then computes the size of the delete as a bare
 * subtraction of those two numbers:
 *
 * <pre>
 *   final int numCharsDeleted = mConnection.getExpectedSelectionEnd()
 *       - mConnection.getExpectedSelectionStart();
 *   mConnection.deleteTextBeforeCursor(numCharsDeleted);
 * </pre>
 *
 * <p>and {@code hasSelection()} - the predicate that selects this branch - is
 * {@code mExpectedSelEnd != mExpectedSelStart}, which a reversed pair satisfies. So a host that
 * reports end &lt; start makes {@code numCharsDeleted} negative, and inside
 * {@code deleteTextBeforeCursor} the first thing that number does is size a buffer:
 *
 * <pre>
 *   final int remainingChars = mComposingText.length() - beforeLength;
 *   if (remainingChars &gt;= 0) mComposingText.setLength(remainingChars);
 * </pre>
 *
 * <p>{@code mComposingText} lives on the one {@code RichInputConnection} the service owns, so the
 * length of a service-lifetime buffer becomes a number the messenger sends. The same negative goes
 * on to {@code mExpectedSelStart -= beforeLength}, which moves the believed cursor <em>forward</em>
 * on a backspace, and out through {@code deleteSurroundingText(beforeLength, 0)} into the host's own
 * connection.
 *
 * <p>{@code ComposeBoxCursorCrossTalkTest} closed the neighbouring hole - the same host numbers
 * eating the user's draft while typing is redirected - and did it inside {@code hasSelection()} with
 * {@code if (shouldUseOtherIC) return false;}. That guard is about <em>whose</em> field the numbers
 * describe. This one is about whether the numbers are a selection at all, and it is live on the
 * ordinary path where the redirect is down.
 *
 * <p>Harness copied from {@link ComposeBoxCursorCrossTalkTest}, with the redirect DOWN and a
 * recording stand-in for the messenger's own field.
 */
@RunWith(RobolectricTestRunner.class)
public class HostReportedSelectionTest {

  /** Stands in for the messenger's own text box, and records what the keyboard asks of it. */
  private static final class HostField extends BaseInputConnection {
    final List<int[]> deletes = new ArrayList<>();

    HostField(final View dummy) {
      super(dummy, false);
    }

    @Override
    public boolean deleteSurroundingText(final int beforeLength, final int afterLength) {
      deletes.add(new int[] {beforeLength, afterLength});
      return true;
    }
  }

  private EditorInfo hostField;
  private HostField hostConnection;
  private LatinIME ime;

  @Before
  public void setUp() {
    final Context app = RuntimeEnvironment.getApplication();
    Settings.init(app);
    RichInputMethodManager.init(app);

    hostField = new EditorInfo();
    hostField.inputType = InputType.TYPE_CLASS_TEXT;
    hostField.imeOptions = EditorInfo.IME_ACTION_NONE;

    hostConnection = new HostField(new View(app));

    ime = new LatinIME() {
      @Override
      public EditorInfo getCurrentInputEditorInfo() {
        return hostField;
      }

      @Override
      public InputConnection getCurrentInputConnection() {
        return hostConnection;
      }
    };
  }

  private SettingsValues values() {
    Settings.getInstance().loadSettings(new InputAttributes(hostField, false));
    return Settings.getInstance().getCurrent();
  }

  private void pressBackspace() {
    ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
        Event.NOT_A_CODE_POINT, Constants.CODE_DELETE, 0, 0, false));
  }

  /** The keyboard's own copy of the text being composed - a buffer as old as the service. */
  private static int composingLength(final RichInputConnection connection) {
    try {
      final Field f = RichInputConnection.class.getDeclaredField("mComposingText");
      f.setAccessible(true);
      return ((StringBuilder) f.get(connection)).length();
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Anti-vacuity guard: an ordinary forward selection really does drive the selection branch.
   *
   * <p>If this fails the harness is not reaching {@code deleteTextBeforeCursor} at all and
   * everything below proves nothing.
   */
  @Test
  public void aforwardSelectionDeletesItsOwnLength() {
    ime.mInputLogic.onUpdateSelection(3, 8);

    pressBackspace();

    assertEquals("one delete must have been asked of the host", 1, hostConnection.deletes.size());
    assertEquals("and it must be the five characters the host said were selected",
        5, hostConnection.deletes.get(0)[0]);
  }

  /**
   * A reversed selection must not turn a delete into a negative one.
   *
   * <p>{@code deleteSurroundingText}'s contract is a count of characters; a negative is not a
   * smaller delete, it is a number the host chose arriving in an API that never expected one.
   */
  @Test
  public void areversedSelectionMustNotProduceAnegativeDelete() {
    ime.mInputLogic.onUpdateSelection(10, 5);

    pressBackspace();

    for (final int[] delete : hostConnection.deletes) {
      assertTrue("the keyboard asked the host to delete " + delete[0] + " characters before the "
          + "cursor - a count the host itself chose the sign of", delete[0] >= 0);
    }
  }

  /**
   * And it must not GROW the keyboard's composing buffer.
   *
   * <p>This is the arithmetic itself: {@code remainingChars = length - beforeLength} with a
   * negative {@code beforeLength} is {@code length + |beforeLength|}, and it is handed straight to
   * {@code setLength}. Five here; the host picks the number, and the buffer is the service's.
   */
  @Test
  public void areversedSelectionMustNotGrowTheComposingBuffer() {
    ime.mInputLogic.onUpdateSelection(10, 5);

    pressBackspace();

    assertEquals("a backspace must never lengthen the keyboard's own copy of the composing text",
        0, composingLength(ime.mInputLogic.mConnection));
  }

  /**
   * The whole of it, at the magnitude that stops being a nuisance and becomes a dead keyboard.
   *
   * <p>{@code setLength(Integer.MAX_VALUE)} is an {@code OutOfMemoryError}. It is an {@code Error},
   * so none of the {@code catch (Exception)} handlers this branch added around the clipboard and
   * the click listeners stops it, and it comes out of an ordinary key press rather than a paste -
   * the input-method process dies in whatever app the user is typing in. Two integers, sent by
   * {@code InputMethodManager.updateSelection}, which needs no permission at all.
   */
  @Test
  public void ahostileSelectionMustNotBeAbleToKillTheKeyboard() {
    ime.mInputLogic.onUpdateSelection(Integer.MAX_VALUE, 0);

    try {
      pressBackspace();
    } catch (final OutOfMemoryError e) {
      fail("one backspace after the host reported a reversed selection tried to allocate the "
          + "buffer the host asked for: " + e);
    }
  }
}
