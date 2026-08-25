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
import java.util.Locale;

/**
 * How much text the host hands back is the host's choice, not this keyboard's.
 *
 * <p>Every bound this app states on how much of the host's field it holds is expressed as an
 * argument to a request: {@code getTextBeforeCursor(EDITOR_CONTENTS_CACHE_SIZE, 0)} asks for 1024
 * characters, and {@code performRecapitalization} refuses to start when the selection is longer
 * than {@code MAX_CHARACTERS_FOR_RECAPITALIZATION}. Both numbers are ours. Neither is a bound on
 * what comes back.
 *
 * <p>{@code InputConnection} is implemented by the application on screen. A hostile one answers a
 * request for 1024 characters with a megabyte, and answers {@code getSelectedText} with a megabyte
 * after having declared - through {@code EditorInfo.initialSelStart/End} or
 * {@code onUpdateSelection}, the same two host-written numbers {@link HostReportedSelectionTest}
 * covers - that five characters were selected. Nothing on either path measured the reply.
 *
 * <p>Where the oversized reply lands matters. {@code mCommittedTextBeforeComposingText},
 * {@code mTempObjectForCommitText} and the two strings inside {@code RecapitalizeStatus} all live
 * on objects the service owns for its whole life, in a process Android does not recreate when the
 * user switches apps - which is exactly why {@code LatinIME.onWindowHidden} clears them and why
 * its comment cites "up to 1024 characters" and "up to 100KB" as the amounts at stake. Those two
 * figures were the requests, so the sentence describing the risk was measuring the wrong end of
 * the call.
 *
 * <p>Harness copied from {@link HostReportedSelectionTest}, with the host's replies made hostile
 * instead of its selection numbers.
 */
@RunWith(RobolectricTestRunner.class)
public class HostReturnsMoreThanAskedTest {

  /** How much the hostile host answers with, whatever it was asked for. */
  private static final int OVERSIZED = 200_000;

  /**
   * Stands in for the messenger's own text box. It records what it was asked for and answers with
   * {@link #OVERSIZED} characters regardless.
   */
  private static final class GreedyHost extends BaseInputConnection {
    int lastBeforeRequest = -1;

    GreedyHost(final View dummy) {
      super(dummy, false);
    }

    private static CharSequence blob(final int n) {
      final StringBuilder sb = new StringBuilder(n);
      for (int i = 0; i < n; i++) sb.append('x');
      return sb;
    }

    @Override
    public CharSequence getTextBeforeCursor(final int n, final int flags) {
      lastBeforeRequest = n;
      return blob(OVERSIZED);
    }

    @Override
    public CharSequence getTextAfterCursor(final int n, final int flags) {
      return blob(OVERSIZED);
    }

    @Override
    public CharSequence getSelectedText(final int flags) {
      return blob(OVERSIZED);
    }
  }

  private EditorInfo hostField;
  private GreedyHost hostConnection;
  private LatinIME ime;

  @Before
  public void setUp() {
    final Context app = RuntimeEnvironment.getApplication();
    Settings.init(app);
    RichInputMethodManager.init(app);

    hostField = new EditorInfo();
    hostField.inputType = InputType.TYPE_CLASS_TEXT;
    hostField.imeOptions = EditorInfo.IME_ACTION_NONE;

    hostConnection = new GreedyHost(new View(app));

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
    // Without it getCurrentLayoutLocale() is null and the recapitalise rotate() throws for a
    // reason that has nothing to do with what is under test.
    set(ime, LatinIME.class, "mLocale", Locale.ENGLISH);
  }

  private SettingsValues values() {
    Settings.getInstance().loadSettings(new InputAttributes(hostField, false));
    return Settings.getInstance().getCurrent();
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

  private static CharSequence buffer(final Object target, final Class<?> owner,
                                     final String name) {
    try {
      final Field f = owner.getDeclaredField(name);
      f.setAccessible(true);
      return (CharSequence) f.get(target);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /** The keyboard's copy of the text before the cursor - a buffer as old as the service. */
  private int cachedTextLength() {
    return buffer(ime.mInputLogic.mConnection, RichInputConnection.class,
        "mCommittedTextBeforeComposingText").length();
  }

  /** The verbatim copy of the last commitText argument, also as old as the service. */
  private int lastCommittedLength() {
    return buffer(ime.mInputLogic.mConnection, RichInputConnection.class,
        "mTempObjectForCommitText").length();
  }

  private void pressShift() {
    ime.mInputLogic.onCodeInput(values(), Event.createSoftwareKeypressEvent(
        Event.NOT_A_CODE_POINT, Constants.CODE_SHIFT, 0, 0, false));
  }

  /**
   * The premise: the host really is answering with more than it was asked for, and the request
   * really is the small number this app believes bounds it.
   */
  @Test
  public void thepremise() {
    ime.mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(0, 0);
    assertEquals("the keyboard must ask for exactly the cache size it documents",
        Constants.EDITOR_CONTENTS_CACHE_SIZE, hostConnection.lastBeforeRequest);
    assertTrue("precondition: the host's reply must be larger than the request",
        OVERSIZED > Constants.EDITOR_CONTENTS_CACHE_SIZE);
  }

  /**
   * A cursor move is all it takes. {@code resetCachesUponCursorMoveAndReturnSuccess} runs from
   * {@code onUpdateSelection}, from {@code setSelection} and at the start of every input session.
   */
  @Test
  public void thecursorcachekeepsonlywhatitaskedfor() {
    ime.mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(0, 0);

    assertEquals("the cache asked the host for " + Constants.EDITOR_CONTENTS_CACHE_SIZE
            + " characters and must not keep more than that, whatever the host replied with",
        Constants.EDITOR_CONTENTS_CACHE_SIZE, cachedTextLength());
  }

  /**
   * The selection the host declares and the text the host returns are two different numbers, and
   * the bail-out in {@code performRecapitalization} only ever saw the first.
   */
  @Test
  public void recapitalisingrefusesareplylongerthanthedeclaredselection() {
    // Five characters selected: far below MAX_CHARACTERS_FOR_RECAPITALIZATION, so the declared
    // bail-out does not fire and the real path is taken.
    ime.mInputLogic.onUpdateSelection(0, 5);
    assertTrue("precondition: a five-character selection must be under the declared limit",
        5 < Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION);
    assertTrue("precondition: the keyboard must believe there is a selection to recapitalise",
        ime.mInputLogic.mConnection.hasSelection());
    assertTrue("precondition: the host's reply must be larger than the declared selection",
        OVERSIZED > Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION);

    pressShift();

    assertEquals("recapitalising must not commit a reply the host inflated far past the "
            + "selection it declared", 0, lastCommittedLength());
    assertTrue("and none of it may be left in the service-lifetime text cache. Held: "
            + cachedTextLength(), cachedTextLength() <= Constants.EDITOR_CONTENTS_CACHE_SIZE);
  }
}
