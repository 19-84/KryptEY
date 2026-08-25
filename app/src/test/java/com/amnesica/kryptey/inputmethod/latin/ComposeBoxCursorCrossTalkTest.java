package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.amnesica.kryptey.inputmethod.event.Event;
import com.amnesica.kryptey.inputmethod.latin.common.Constants;
import com.amnesica.kryptey.inputmethod.latin.settings.Settings;
import com.amnesica.kryptey.inputmethod.latin.settings.SettingsValues;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Whose cursor the keyboard is editing around while the user composes inside the strip.
 *
 * <p>{@code shouldUseOtherIC} switches the <em>destination</em> of an edit to the strip's compose
 * box. It does not switch the bookkeeping that decides what the edit is:
 * {@code mExpectedSelStart} / {@code mExpectedSelEnd} live on the same long-lived
 * {@code RichInputConnection} and are set from one place only -
 * {@code LatinIME.onUpdateSelection}, which is the <b>host application</b> telling the keyboard
 * where the cursor is in <b>its own</b> field. The strip's compose box is an ordinary view inside
 * the IME and never reports anything.
 *
 * <p>So while the user types a message the messenger is not meant to read, the messenger owns the
 * selection the keyboard believes it is editing. {@code InputLogic.handleBackspaceEvent} branches
 * on {@code mConnection.hasSelection()}, and on the selection branch deletes
 * {@code end - start} characters. Those characters come out of the user's draft.
 */
@RunWith(RobolectricTestRunner.class)
public class ComposeBoxCursorCrossTalkTest {

  private static final String DRAFT = "meet me at nine";

  private EditorInfo hostField;
  private LatinIME ime;
  private EditText compose;

  @Before
  public void setUp() {
    final Context app = RuntimeEnvironment.getApplication();
    Settings.init(app);
    RichInputMethodManager.init(app);

    hostField = new EditorInfo();
    hostField.inputType = InputType.TYPE_CLASS_TEXT;
    hostField.imeOptions = EditorInfo.IME_ACTION_NONE;

    ime = new LatinIME() {
      @Override
      public EditorInfo getCurrentInputEditorInfo() {
        return hostField;
      }
    };

    compose = new EditText(app);
    ime.mInputLogic.mConnection.setOtherIC(compose);
    ime.mInputLogic.mConnection.setShouldUseOtherIC(true);

    // The user types the draft into the keyboard's own box.
    ime.mInputLogic.mConnection.commitText(DRAFT, 1);
    assertEquals(DRAFT, compose.getText().toString());
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
   * Anti-vacuity guard: with a plain caret reported, one backspace removes one character.
   *
   * <p>If this fails the harness is not driving the real backspace path and the test below proves
   * nothing.
   */
  @Test
  public void onebackspaceRemovesOneCharacter() {
    ime.mInputLogic.onUpdateSelection(DRAFT.length(), DRAFT.length());

    pressBackspace();

    assertEquals("meet me at nin", compose.getText().toString());
  }

  /**
   * A selection the host application reports for its own field must not delete the user's draft.
   *
   * <p>{@code onUpdateSelection} is delivered to the keyboard by the framework on the host's behalf
   * whenever the input view is shown, and an app can produce whatever numbers it likes for its own
   * EditText. Here it claims its whole field is selected; the user, who is typing into the
   * keyboard, presses backspace once and loses the message instead of a character.
   */
  @Test
  public void ahostReportedSelectionMustNotDeleteTheUsersDraft() {
    ime.mInputLogic.onUpdateSelection(0, DRAFT.length());

    pressBackspace();

    assertEquals("one backspace must take one character off the draft, whatever the app "
        + "underneath says about its own cursor", "meet me at nin", compose.getText().toString());
  }
}
