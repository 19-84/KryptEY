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

  /**
   * The caret question must be answered about the field the user is actually in.
   *
   * <p>{@code mExpectedSelStart/End} are written only by {@code onUpdateSelection} — the host
   * describing its own field — so while typing is redirected they belong to a field the user is not
   * in, and the messenger owns them. {@code hasSelection} has refused to answer with them since a
   * review round found what one backspace did; {@code getExpectedSelection*} did not, and those are
   * what the pointer-slide handlers compute a caret move from before handing it to
   * {@code setSelection}, which has no guard either.
   *
   * <p>So the messenger got to choose where in the user's half-written private message the next
   * characters land. No disclosure — the text still goes to the strip — but it garbles a draft the
   * user is about to encrypt, at a moment the messenger picks.
   *
   * <p>Answered from the strip's own view rather than refused. Refusing would disable the gestures
   * inside the compose box, and {@code handleBackspaceEvent} reads a false from
   * {@code setSelection} as "nothing happened" and skips its delete.
   */
  @Test
  public void thecaretQuestionIsAnsweredAboutTheFieldTheUserIsIn() {
    final android.widget.EditText compose =
        new android.widget.EditText(RuntimeEnvironment.getApplication());
    compose.setText("the meeting is at nine");
    android.text.Selection.setSelection(compose.getText(), 4);

    final RichInputConnection connection = new RichInputConnection(ime);
    connection.setOtherIC(compose);
    connection.setShouldUseOtherIC(true);

    // The host reports a selection for ITS OWN field, which is a number the messenger chooses.
    connection.resetCachesUponCursorMoveAndReturnSuccess(900, 900);

    assertEquals("while typing is redirected, the caret question must be answered about the "
            + "compose box - the host's numbers describe a field the user is not in, and the "
            + "pointer-slide handlers compute a caret move from these before applying it to the "
            + "user's draft", 4, connection.getExpectedSelectionStart());
    assertEquals(4, connection.getExpectedSelectionEnd());
  }

  /**
   * And a compose box with no selection span must not fall through to the host either.
   *
   * <p>The state above sets the selection explicitly, and so does every other test on this
   * accessor - which is exactly why none of them can see this. {@code getExpectedSelectionStart}
   * answers from the strip only while both {@code selectionStart()} and {@code selectionEnd()} are
   * non-negative; {@code E2EEInputConnection} returns -1 for each when the {@code Editable} has no
   * {@code SELECTION_START}/{@code END} span, and then the method falls past both guards and
   * returns the host's number.
   *
   * <p>That is the state the decrypt path leaves the box in. {@code TextView.setText} installs a
   * fresh {@code Editable} and calls {@code mMovement.initialize(...)}; the compose box's movement
   * method is {@code ScrollingMovementMethod}, whose inherited {@code initialize} is empty, so
   * nothing sets a selection. The two decrypt arms then raise the redirect in the same breath as
   * the {@code setText} that installed the buffer - so between the decrypt and the user's first
   * keystroke, the caret question is answered with numbers the messenger chose, about a field the
   * user is not in. With space-swipe or delete-swipe enabled that is where the caret lands inside
   * the peer's plaintext the user is about to reply to.
   *
   * <p>Zero is the answer rather than the host's number: an unselected buffer has its caret at the
   * start, and the alternative - refusing to answer - has no expressible form, since the accessor
   * returns an int that callers subtract.
   */
  @Test
  public void acomposeBoxWithNoSelectionSpanIsStillNotTheHostsField() {
    final android.widget.EditText compose =
        new android.widget.EditText(RuntimeEnvironment.getApplication());
    compose.setMovementMethod(new android.text.method.ScrollingMovementMethod());
    // No Selection.setSelection: setText is what the decrypt arms call, and it leaves none.
    compose.setText("meet me at the usual place");
    assertTrue("fixture: this test is only meaningful while the box reports no selection - if the "
            + "platform starts seeding one, the fall-through it is about is unreachable and this "
            + "must be rewritten rather than deleted",
        android.text.Selection.getSelectionStart(compose.getText()) < 0);

    final RichInputConnection connection = new RichInputConnection(ime);
    connection.setOtherIC(compose);
    connection.setShouldUseOtherIC(true);
    connection.resetCachesUponCursorMoveAndReturnSuccess(900, 900);

    assertEquals("with the redirect up and no selection in the compose box, the caret question was "
            + "answered with the host's number - the messenger's, about a field the user is not in",
        0, connection.getExpectedSelectionStart());
    assertEquals(0, connection.getExpectedSelectionEnd());
  }

  /** And with the redirect down the host's numbers are the right answer, which is the point. */
  @Test
  public void thehostsNumbersAreStillUsedWhenTypingGoesToTheHost() {
    final RichInputConnection connection = new RichInputConnection(ime);
    connection.resetCachesUponCursorMoveAndReturnSuccess(7, 7);

    assertEquals("with no redirect the host's own field is the field the user is in", 7,
        connection.getExpectedSelectionStart());
    assertEquals(7, connection.getExpectedSelectionEnd());
  }

  /**
   * A backwards drag in the compose box must not invert the pair either.
   *
   * <p>{@code resetCachesUponCursorMoveAndReturnSuccess} normalises the pair it writes, and says
   * why: the invariant belongs to the pair rather than to either reader, and what it prevents is a
   * negative count sizing a service-lifetime buffer. Answering from the strip's view without
   * ordering would have made that invariant true of one arm and not the other — and no reader asks
   * which arm it is talking to. An ordinary {@code TextView} leaves
   * {@code Selection.getSelectionStart()} greater than {@code getSelectionEnd()} after a backwards
   * drag, so this is not hypothetical.
   */
  @Test
  public void abackwardsSelectionInTheComposeBoxIsStillOrdered() {
    final android.widget.EditText compose =
        new android.widget.EditText(RuntimeEnvironment.getApplication());
    compose.setText("the meeting is at nine");
    // Anchor after the caret: what a right-to-left drag leaves behind.
    android.text.Selection.setSelection(compose.getText(), 11, 4);

    final RichInputConnection connection = new RichInputConnection(ime);
    connection.setOtherIC(compose);
    connection.setShouldUseOtherIC(true);

    assertTrue("start must not exceed end, or handleBackspaceEvent computes a negative count and "
            + "hands it to deleteTextBeforeCursor, which sizes the composing buffer from it",
        connection.getExpectedSelectionStart() <= connection.getExpectedSelectionEnd());
    assertEquals(4, connection.getExpectedSelectionStart());
    assertEquals(11, connection.getExpectedSelectionEnd());
  }
}
