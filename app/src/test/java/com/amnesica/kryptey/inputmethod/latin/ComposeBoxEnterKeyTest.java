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
 * What the Enter key does inside the keyboard's own compose box.
 *
 * <p>While the user is composing a message to encrypt, the characters go to the strip's compose
 * box - {@code RichInputConnection.shouldUseOtherIC} is true and the input connection is the
 * strip's own. The <em>decision</em> about what a key means is not switched over with it.
 * {@code InputLogic.handleNonFunctionalEvent} asks {@code getCurrentInputEditorInfo()}, which is
 * the EditorInfo of the <b>host application's</b> field, and dispatches Enter accordingly:
 *
 * <pre>
 *   IME_ACTION_NONE  -&gt; insert a newline
 *   anything else    -&gt; performEditorAction(thatAction) on the compose box
 * </pre>
 *
 * <p>The messenger owns that value - it is one attribute on its own EditText - and every chat app
 * sets one. So the messenger, not the keyboard, decides what happens when the user presses Enter
 * while typing a message it is not supposed to be able to read. {@code TextView.onEditorAction}
 * gives those actions real effects on the compose box: {@code IME_ACTION_NEXT} moves focus off it,
 * and focus is the only thing keeping the user's typing inside the keyboard
 * (see {@code TypingDestinationTest}); {@code IME_ACTION_DONE} hides the keyboard, which runs
 * {@code clearDecryptedContent}. The most common declaration, {@code IME_ACTION_SEND}, is a silent
 * no-op, so the compose box simply has no working Enter key.
 */
@RunWith(RobolectricTestRunner.class)
public class ComposeBoxEnterKeyTest {

  /** The messenger's own text field. It chooses these values; the keyboard only reads them. */
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

    ime = new LatinIME() {
      @Override
      public EditorInfo getCurrentInputEditorInfo() {
        return hostField;
      }
    };

    // The user is composing inside the keyboard, exactly as the strip's focus listener arranges it.
    compose = new EditText(app);
    ime.mInputLogic.mConnection.setOtherIC(compose);
    ime.mInputLogic.mConnection.setShouldUseOtherIC(true);
  }

  private SettingsValues values() {
    Settings.getInstance().loadSettings(new InputAttributes(hostField, false));
    return Settings.getInstance().getCurrent();
  }

  private void pressEnter() {
    ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
        Constants.CODE_ENTER, Constants.CODE_ENTER, 0, 0, false));
  }

  /**
   * Anti-vacuity guard: with the host declaring no action, Enter really does reach the compose box.
   *
   * <p>If this fails, the harness is not driving the real key path and the test below proves
   * nothing.
   */
  @Test
  public void withNoActionDeclaredByTheHostEnterInsertsAnewline() {
    hostField.imeOptions = EditorInfo.IME_ACTION_NONE;

    pressEnter();

    assertEquals("\n", compose.getText().toString());
  }

  /**
   * The host application must not decide what Enter does inside the keyboard's own compose box.
   *
   * <p>{@code IME_ACTION_SEND} is what a chat app declares on its message field. Nothing about the
   * user's situation has changed - they are typing a second line of a message the messenger is not
   * meant to see - but the keystroke is now dispatched as that app's editor action instead.
   */
  @Test
  public void themessengersImeOptionsMustNotChangeWhatEnterDoesInsideTheComposeBox() {
    hostField.imeOptions = EditorInfo.IME_ACTION_SEND;

    pressEnter();

    assertEquals("a newline typed into the keyboard's own box must be a newline, whatever the "
        + "app underneath declares", "\n", compose.getText().toString());
  }

  /**
   * The sharpest instance: {@code IME_ACTION_NEXT} takes focus off the compose box.
   *
   * <p>Focus is the only thing routing the user's typing into the keyboard rather than into the
   * messenger, so an attribute the messenger sets on its own field turns the user's next keystroke
   * into cleartext in the messenger's EditText.
   */
  @Test
  public void anactionTheMessengerDeclaresMustNotTakeFocusOffTheComposeBox() {
    hostField.imeOptions = EditorInfo.IME_ACTION_NEXT;

    pressEnter();

    assertEquals("a newline typed into the keyboard's own box must be a newline, whatever the "
        + "app underneath declares", "\n", compose.getText().toString());
  }
}
