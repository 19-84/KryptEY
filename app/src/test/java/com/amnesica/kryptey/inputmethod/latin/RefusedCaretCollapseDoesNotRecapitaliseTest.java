package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
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
import java.util.Locale;

/**
 * The other half of "a delete that depends on a cursor move must not happen when the move failed".
 *
 * <p>{@link FailedCursorMoveDoesNotDeleteTest} covers the backspace arm of that pair. Recapitalise
 * is the second arm and it is written the same way: collapse the caret to the end of the selection,
 * delete backwards over its length, then commit a rewritten copy. Its guard —
 * {@code if (!mConnection.setSelection(selectionEnd, selectionEnd)) return;} — carries a comment
 * saying it is the "same pair as the backspace path, with a commit after it", and that it is
 * <em>worse</em> here because "the buffer being rewritten can hold up to
 * MAX_CHARACTERS_FOR_RECAPITALIZATION characters of whatever the user had selected — including
 * decrypted plaintext". Deleting that guard left the whole suite green: only the backspace arm was
 * ever driven.
 *
 * <p>The failure is ordinary rather than exotic. {@code getIC()} returns either the host
 * application's connection — the adversary in this threat model, and a connection that goes dead on
 * any app switch — or the strip's own, which returns false once its editable is gone. When the
 * collapse is refused the editor still holds the selection while the keyboard's model believes the
 * caret is at its end, so {@code deleteSurroundingText} counts its characters back from the
 * <em>selection start</em> instead: it eats text the user never selected, and the recapitalised
 * string is then committed at that wrong offset.
 *
 * <p>Asserted on the host field's own contents rather than on a call that did not happen. "No
 * delete was recorded" is equally true of a harness that never reached the code at all, which is
 * how a control on this branch has read as coverage more than once; the exact string left in the
 * editor is only true of the behaviour under test.
 */
@RunWith(RobolectricTestRunner.class)
public class RefusedCaretCollapseDoesNotRecapitaliseTest {

  /** What the host's field holds, with "defgh" selected. */
  private static final String ORIGINAL = "abcdefghij";
  private static final int SELECTION_START = 3;
  private static final int SELECTION_END = 8;

  /**
   * A host field that really holds text, so a delete at the wrong offset is visible as text.
   *
   * <p>{@code fullEditor} is true deliberately: in dummy mode {@code BaseInputConnection} routes
   * {@code finishComposingText} through {@code sendCurrentText}, which <em>clears</em> the editable
   * before the path under test ever runs, and every assertion below would then be about an empty
   * field.
   */
  private static final class HostField extends BaseInputConnection {
    private final Editable content = new SpannableStringBuilder(ORIGINAL);

    /** Whether the caret refuses to move, the way a dead connection does. */
    boolean refuseSelection;

    HostField(final View dummy) {
      super(dummy, true /* fullEditor */);
      Selection.setSelection(content, SELECTION_START, SELECTION_END);
    }

    @Override
    public Editable getEditable() {
      return content;
    }

    @Override
    public boolean setSelection(final int start, final int end) {
      if (refuseSelection) return false;
      return super.setSelection(start, end);
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
    // LatinIME.loadSettings() does this on every input session; this harness starts no session.
    // Without it getCurrentLayoutLocale() is null and rotate() throws for a reason that has
    // nothing to do with what is under test.
    set(ime, LatinIME.class, "mLocale", Locale.ENGLISH);
  }

  private static void set(final Object target, final Class<?> owner, final String name,
                          final Object value) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private SettingsValues values() {
    Settings.getInstance().loadSettings(new InputAttributes(hostField, false));
    return Settings.getInstance().getCurrent();
  }

  /** Shift is what drives {@code performRecapitalization}. */
  private void pressShift() {
    ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
        Event.NOT_A_CODE_POINT, Constants.CODE_SHIFT, 0, 0, false));
  }

  private String hostText() {
    return hostConnection.getEditable().toString();
  }

  /**
   * Anti-vacuity guard: the ordinary path really does reach the delete-and-rewrite.
   *
   * <p>If this fails the harness never gets as far as the guard and the test below proves nothing.
   */
  @Test
  public void anordinarySelectionIsStillRecapitalised() {
    ime.mInputLogic.onUpdateSelection(SELECTION_START, SELECTION_END);
    assertTrue("precondition: five characters is far under the recapitalise limit",
        SELECTION_END - SELECTION_START < Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION);
    assertTrue("precondition: the keyboard must believe there is a selection to recapitalise",
        ime.mInputLogic.mConnection.hasSelection());

    pressShift();

    assertEquals("the selected word must be rewritten in place, leaving everything around it",
        "abcDefghij", hostText());
  }

  /**
   * And when the caret cannot be collapsed, the field is left exactly as it was.
   *
   * <p>Without the guard the delete counts five characters back from the <em>selection start</em>,
   * because that is where the editor's caret still is: it takes "abc" — text before the selection,
   * which the user never selected and never saw highlighted — and then commits the recapitalised
   * copy over what is left.
   */
  @Test
  public void arefusedCaretCollapseLeavesTheFieldAlone() {
    ime.mInputLogic.onUpdateSelection(SELECTION_START, SELECTION_END);
    assertEquals("precondition: the host's field must start with the text under test",
        ORIGINAL, hostText());
    assertTrue("precondition: the keyboard must believe there is a selection to recapitalise",
        ime.mInputLogic.mConnection.hasSelection());

    hostConnection.refuseSelection = true;
    pressShift();

    assertEquals("the caret could not be collapsed, so the editor still holds the selection. "
            + "Deleting five characters now takes them from BEFORE the selection - text the user "
            + "never chose - and commits the recapitalised copy at that offset. In this app the "
            + "buffer being rewritten can hold a decrypted message.",
        ORIGINAL, hostText());
  }
}
