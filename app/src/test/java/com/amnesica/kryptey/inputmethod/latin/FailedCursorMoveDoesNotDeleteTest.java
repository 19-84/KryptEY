package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

import java.util.ArrayList;
import java.util.List;

/**
 * A delete that depends on a cursor move must not happen when the move failed.
 *
 * <p>Backspace over a selection is two steps: collapse the caret to the end of the selection, then
 * delete backwards over its length. {@code setSelection} was {@code void} with three silent exits —
 * one of them <em>after</em> assigning the new expected cursor — so the call site could not tell.
 * When the move fails the editor still holds the selection while the keyboard's model says the caret
 * is collapsed, and the delete then removes that many characters <b>before the real cursor</b>: text
 * the user never selected.
 *
 * <p>Not exotic. {@code getIC()} returns either the host's connection — the adversary in this threat
 * model, and dead connections return false routinely on an app switch — or the strip's own, which
 * returns false once its editable is gone.
 *
 * <p>Driven through {@code LatinIME.mInputLogic.onCodeInput} rather than by calling
 * {@code setSelection} directly, because the property is about what the call site does with the
 * answer. A test that asserted the return value alone would be true of the method and say nothing
 * about the delete — a mistake this branch has made twice and recorded both times.
 */
@RunWith(RobolectricTestRunner.class)
public class FailedCursorMoveDoesNotDeleteTest {

  /** A host field that can refuse to move the caret, the way a dead connection does. */
  private static final class HostField extends BaseInputConnection {
    final List<int[]> deletes = new ArrayList<>();
    /** Whether to answer getSelectedText with more than the declared selection, as a host may. */
    boolean overAnswerSelectedText;

    @Override
    public CharSequence getSelectedText(final int flags) {
      return overAnswerSelectedText ? "far more text than the selection it declared" : null;
    }
    boolean refuseSelection;

    HostField(final View dummy) {
      super(dummy, false);
    }

    @Override
    public boolean setSelection(final int start, final int end) {
      if (refuseSelection) return false;
      return super.setSelection(start, end);
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

  /**
   * Anti-vacuity guard: the ordinary path really does reach the delete.
   *
   * <p>Without this, the assertion below passes on a harness that never gets there at all — which
   * is how a control on this branch has been hollow more than once.
   */
  @Test
  public void anordinarySelectionIsStillDeleted() {
    ime.mInputLogic.onUpdateSelection(3, 8);
    pressBackspace();

    assertEquals("the harness must reach deleteSurroundingText, or the test below proves nothing",
        1, hostConnection.deletes.size());
    assertEquals("and delete exactly the selection's length", 5, hostConnection.deletes.get(0)[0]);
  }

  /** And when the caret cannot be moved, nothing is deleted. */
  @Test
  public void arefusedCursorMoveDeletesNothing() {
    ime.mInputLogic.onUpdateSelection(3, 8);
    hostConnection.refuseSelection = true;

    pressBackspace();

    assertTrue("the caret could not be collapsed, so the editor still holds the selection while "
            + "the keyboard's model says the cursor is at its end. Deleting five characters now "
            + "takes them from BEFORE the real cursor - text the user never selected, and in the "
            + "recapitalise path from a buffer that can hold decrypted plaintext. Deletes seen: "
            + hostConnection.deletes.size(),
        hostConnection.deletes.isEmpty());
  }

  /**
   * A refused move must leave the model where it was, not merely stop the delete.
   *
   * <p>The guard added at the call sites stopped the immediate wrong delete and left a desync
   * behind: the expected-selection model was committed <em>before</em> the editor was asked, and the
   * failure path returned with it already moved. Afterwards the model says "caret collapsed at the
   * end" while the editor still holds the selection — so {@code hasSelection()} answers false, the
   * NEXT backspace takes the single-character branch against a selection the model has forgotten,
   * and every later commit advances from a position the editor does not share.
   *
   * <p>Checked through a second backspace rather than by reading the fields: what matters is that
   * the keyboard still knows there is a selection to delete once the connection recovers.
   */
  @Test
  public void arefusedCursorMoveLeavesTheSelectionIntact() {
    ime.mInputLogic.onUpdateSelection(3, 8);
    hostConnection.refuseSelection = true;
    pressBackspace();
    assertTrue("precondition: the refused move must not have deleted anything",
        hostConnection.deletes.isEmpty());

    // The connection recovers - an app switch coming back, a strip regaining its editable.
    hostConnection.refuseSelection = false;
    pressBackspace();

    assertEquals("after a refused move the keyboard must still know a selection is there. If the "
            + "model was left collapsed, this second press deletes one character instead of the "
            + "five the user has selected - and every later commit is offset from the editor.",
        1, hostConnection.deletes.size());
    assertEquals("and it must delete the whole selection", 5, hostConnection.deletes.get(0)[0]);
  }

  /**
   * A pointer slide must not leave the caret model inverted.
   *
   * <p>{@code setSelection} is the second writer of {@code mExpectedSelStart/End} and, unlike
   * {@code resetCachesUponCursorMoveAndReturnSuccess}, it does not order its arguments — that
   * method's own comment says the invariant "belongs to the pair rather than to either reader" and
   * names what it prevents: a negative count sizing a service-lifetime buffer.
   *
   * <p>{@code onMovePointer} was the one caller that could produce {@code start > end}, because
   * {@code getUnicodeSteps} can return up to twice the step count it was handed when the host
   * over-answers {@code getSelectedText} — the one host reply that is not clamped. Its sibling
   * {@code onMoveDeletePointer}, nine lines below, has guarded against exactly this all along.
   *
   * <p>An inverted model makes {@code hasSelection()} true with {@code end < start}, so the next
   * backspace computes a <em>negative</em> {@code numCharsDeleted} and hands it to
   * {@code deleteTextBeforeCursor}, which sizes the composing buffer from it and moves the caret
   * model forward on a backspace.
   */
  @Test
  public void apointerSlideMustNotInvertTheCaretModel() {
    ime.mInputLogic.onUpdateSelection(10, 13);

    // The host answers getSelectedText with far more than the three characters it declared, which
    // is what drives getUnicodeSteps past the distance to the selection start.
    hostConnection.overAnswerSelectedText = true;

    ime.onMovePointer(-5);

    assertTrue("the caret model must never be left inverted: the next backspace reads "
            + "end - start as the number of characters to delete",
        ime.mInputLogic.mConnection.getExpectedSelectionStart()
            <= ime.mInputLogic.mConnection.getExpectedSelectionEnd());

    hostConnection.deletes.clear();
    pressBackspace();

    for (final int[] delete : hostConnection.deletes) {
      assertTrue("a backspace must never ask the editor to delete a negative number of "
              + "characters: " + delete[0], delete[0] >= 0);
    }
  }

  /**
   * Anti-vacuity guard for the test below: the delete-pointer slide really does reach
   * {@code setSelection} and really does move the caret model.
   *
   * <p>Without this, the refusal below passes on a harness that never got as far as the guard - the
   * mistake this file's other anti-vacuity guard exists to stop.
   */
  @Test
  public void adeletePointerSlideStillMovesTheCaret() {
    ime.mInputLogic.onUpdateSelection(10, 13);
    hostConnection.overAnswerSelectedText = true;

    ime.onMoveDeletePointer(2);

    assertEquals("a two-step slide must move the caret model's start by two",
        12, ime.mInputLogic.mConnection.getExpectedSelectionStart());
    assertEquals("and leave its end where it was",
        13, ime.mInputLogic.mConnection.getExpectedSelectionEnd());
  }

  /**
   * The same property as {@link #apointerSlideMustNotInvertTheCaretModel}, on the other slide.
   *
   * <p>{@code onMoveDeletePointer} carries a {@code start > end} guard identical to the one
   * {@code onMovePointer} was given, and its comment says so - "the guard the sibling nine lines
   * below has had all along". Only one of the two arms was ever driven: deleting this one's guard
   * left the whole suite green.
   *
   * <p>Both arms compute {@code start} from {@code getUnicodeSteps}, which can return up to twice
   * the step count it was handed when the host over-answers {@code getSelectedText} - the one host
   * reply that is not clamped - so both can ask {@code setSelection} for a reversed pair. It is
   * this arm that reaches it with a plain forward slide on the delete key, because it adds the
   * steps to the selection START while comparing against the unmoved END.
   *
   * <p>The consequence is the one the sibling's comment names: {@code setSelection} does not order
   * its arguments, an inverted model makes {@code hasSelection()} true with {@code end < start},
   * and the next backspace hands {@code deleteTextBeforeCursor} a NEGATIVE character count - which
   * sizes the service-lifetime composing buffer from it and moves the caret model forward on a
   * backspace.
   */
  @Test
  public void adeletePointerSlideMustNotInvertTheCaretModel() {
    ime.mInputLogic.onUpdateSelection(10, 13);

    // The host answers getSelectedText with far more than the three characters it declared, which
    // is what lets getUnicodeSteps hand back the full slide.
    hostConnection.overAnswerSelectedText = true;

    ime.onMoveDeletePointer(5);

    assertTrue("the caret model must never be left inverted: the next backspace reads "
            + "end - start as the number of characters to delete. Model was "
            + ime.mInputLogic.mConnection.getExpectedSelectionStart() + ".."
            + ime.mInputLogic.mConnection.getExpectedSelectionEnd(),
        ime.mInputLogic.mConnection.getExpectedSelectionStart()
            <= ime.mInputLogic.mConnection.getExpectedSelectionEnd());

    hostConnection.deletes.clear();
    pressBackspace();

    assertEquals("the backspace must still reach the editor", 1, hostConnection.deletes.size());
    assertEquals("and must delete exactly the three characters the host declared selected, not a "
            + "negative count computed from a caret model the slide inverted",
        3, hostConnection.deletes.get(0)[0]);
  }
}
