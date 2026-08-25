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

import java.lang.reflect.Field;

/**
 * The other half of the buffer the last round bounded.
 *
 * <p>{@code clampToRequest} bounded what the HOST returns into
 * {@code RichInputConnection.mCommittedTextBeforeComposingText}, after measuring that a host
 * answering 1,000,000 characters to a request for 1,024 got all 1,000,000 kept. What it did not
 * bound is what this keyboard appends to the same buffer itself - one call per keystroke, through
 * {@code commitText}, {@code finishComposingText} and {@code sendKeyEvent}.
 *
 * <p>Nothing resets it inside a session. {@code reloadTextCache} runs only when a reported cursor
 * position surprises the keyboard, so a host that reports exactly the position the keyboard
 * predicted - which is what a correct app does on every keystroke - never triggers one. Measured on
 * the real key path, with the host reporting every cursor move: 100,000 keystrokes left 100,000
 * characters in the buffer, in a process Android does not recreate when the user switches apps.
 *
 * <p>That matters here beyond memory. {@code LatinIME.onWindowHidden} clears this buffer so the
 * user's text is not left sitting in the input-method process, and the comment justifying it puts
 * what is at stake at {@link Constants#EDITOR_CONTENTS_CACHE_SIZE} characters "around the cursor".
 * Until the buffer is actually held to that, the amount at stake is the whole session's typing.
 */
@RunWith(RobolectricTestRunner.class)
public class CommittedTextCacheStaysBoundedTest {

  /** Far past the documented cache size, and nothing like a number a bound could reach by luck. */
  private static final int KEYSTROKES = 100000;

  private EditorInfo hostField;
  private BaseInputConnection hostConnection;
  private LatinIME ime;

  @Before
  public void setUp() {
    final Context app = RuntimeEnvironment.getApplication();
    Settings.init(app);
    RichInputMethodManager.init(app);

    hostField = new EditorInfo();
    hostField.inputType = InputType.TYPE_CLASS_TEXT;
    hostField.imeOptions = EditorInfo.IME_ACTION_NONE;
    hostConnection = new BaseInputConnection(new View(app), false);

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

  /** The keyboard's own copy of the text before the cursor - a buffer as old as the service. */
  private int cached() {
    try {
      final Field f =
          RichInputConnection.class.getDeclaredField("mCommittedTextBeforeComposingText");
      f.setAccessible(true);
      return ((StringBuilder) f.get(ime.mInputLogic.mConnection)).length();
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private char charAt(final int i) {
    return (char) ('a' + (i % 26));
  }

  /** One keystroke, through the same path a finger takes. */
  private void type(final char ch) {
    ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
        ch, Constants.CODE_UNSPECIFIED, 0, 0, false /* isKeyRepeat */));
  }

  /** A host that reports every cursor move, exactly where the keyboard expects it. */
  private void typeReporting(final int count) {
    for (int i = 0; i < count; i++) {
      type(charAt(i));
      ime.onUpdateSelection(i, i, i + 1, i + 1, -1, -1);
    }
  }

  /**
   * The premise: typing really is what fills this buffer, and the host reporting its cursor is not
   * quietly emptying it. Without this, a bound could pass because nothing ever got in.
   */
  @Test
  public void typingIsWhatFillsTheCache() {
    typeReporting(200);

    assertEquals("200 keystrokes must leave 200 characters cached - if this is 0 the buffer is "
        + "being reset behind the test and the bound below proves nothing", 200, cached());
  }

  /** The finding: the cache must not grow one character per keystroke without limit. */
  @Test
  public void typingDoesNotGrowTheCacheWithoutBound() {
    typeReporting(KEYSTROKES);

    assertTrue("the keyboard's own commits are unbounded in this buffer: " + KEYSTROKES
            + " keystrokes left " + cached() + " characters, against the "
            + Constants.EDITOR_CONTENTS_CACHE_SIZE + " the class documents",
        cached() <= Constants.EDITOR_CONTENTS_CACHE_SIZE);
  }

  /**
   * And the bound must not have been bought by breaking the cache.
   *
   * <p>Every reader of this buffer wants the text ENDING at the cursor, so the head is what may go
   * and the tail is what must survive - a bound that trimmed the other end would satisfy the test
   * above and leave the keyboard answering with characters typed at the start of the session.
   * After typing well past the bound, the keyboard must still report what was just typed, and
   * still walk backwards through it correctly.
   */
  @Test
  public void theCacheStillAnswersWithWhatWasJustTyped() {
    typeReporting(KEYSTROKES);

    assertEquals("the last character typed must still be the code point before the cursor",
        charAt(KEYSTROKES - 1), (char) ime.mInputLogic.mConnection.getCodePointBeforeCursor());

    for (int i = 0; i < 5; i++) {
      ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
          Event.NOT_A_CODE_POINT, Constants.CODE_DELETE, 0, 0, false /* isKeyRepeat */));
    }

    assertEquals("after five backspaces the cache must have walked five characters back through "
            + "what was typed, not through something left over from the start of the session",
        charAt(KEYSTROKES - 6), (char) ime.mInputLogic.mConnection.getCodePointBeforeCursor());

    final int n = 64;
    final StringBuilder expected = new StringBuilder();
    for (int i = KEYSTROKES - 5 - n; i < KEYSTROKES - 5; i++) expected.append(charAt(i));

    assertEquals("the retained end of the cache must be the end of what was typed", 
        expected.toString(), cachedTail(n));
  }

  /** The last {@code n} characters the cache is holding. */
  private String cachedTail(final int n) {
    try {
      final Field f =
          RichInputConnection.class.getDeclaredField("mCommittedTextBeforeComposingText");
      f.setAccessible(true);
      final StringBuilder b = (StringBuilder) f.get(ime.mInputLogic.mConnection);
      return b.substring(Math.max(0, b.length() - n));
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }
}
