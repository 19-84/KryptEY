package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.amnesica.kryptey.inputmethod.latin.utils.RecapitalizeStatus;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Decrypted text must not sit in the keyboard's own buffers after the keyboard is dismissed.
 *
 * <p>The visible compose field was cleared; three other copies were not. This covers the one that
 * can be driven without an IME service: {@code RecapitalizeStatus} holds up to 100KB of whatever
 * was last being recapitalised, on an object that lives as long as the service, and {@code stop()}
 * cleared only the started flag.
 *
 * <p>That matters here more than in an ordinary keyboard. The IME process is not recreated when the
 * user switches apps, so a decrypted message left in these buffers is still in memory behind
 * whatever they open next - which in this threat model may be the messenger itself.
 */
@RunWith(RobolectricTestRunner.class)
public class PlaintextBufferClearingTest {

  private static Object field(final Object target, final String name) throws Exception {
    final Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  @Test
  public void stoppingRecapitalisationDropsTheText() throws Exception {
    final RecapitalizeStatus status = new RecapitalizeStatus();
    status.enable();
    status.start(0, 33, "meet me at the safe house at nine", Locale.ENGLISH);

    assertEquals("precondition: the text must be held while active",
        "meet me at the safe house at nine", field(status, "mStringBefore"));

    status.stop();

    assertNull("a decrypted message must not outlive the keyboard in this buffer",
        field(status, "mStringBefore"));
    assertNull(field(status, "mStringAfter"));
  }

  /** Stopping when nothing was started must not throw - it is called on every dismissal. */
  @Test
  public void stoppingWhenNothingWasStartedIsSafe() throws Exception {
    final RecapitalizeStatus status = new RecapitalizeStatus();
    status.stop();
    assertNull(field(status, "mStringBefore"));
  }

  /**
   * And a field change must empty it, not merely disable it.
   *
   * <p>The test above pins that {@code stop()} clears. It does not pin that anything calls
   * {@code stop()} when the user moves to another field — and nothing did. {@code startInput()}
   * called {@code disable()}, which sets one boolean, while the two strings held up to
   * {@code MAX_CHARACTERS_FOR_RECAPITALIZATION} (100KB) of the field just left.
   * {@code forgetCachedText()}, which does call {@code stop()}, is reached only from
   * {@code onWindowHidden}, and a focus move does not hide the window.
   *
   * <p>Same callback and same argument as the verify screen's digits, which this branch already had
   * to fix once: {@code onStartInputViewInternal} runs on any {@code restartInput} or focus move,
   * and the window need not hide. The residue is not readable from outside the IME process — the
   * state machine cannot be re-entered across the field change — but it is the previous field's
   * text sitting in memory behind whatever the user opened next, which in this threat model may be
   * the messenger.
   */
  @Test
  public void afieldChangeEmptiesTheRecapitalisationBuffer() throws Exception {
    final org.robolectric.android.controller.ServiceController<
        com.amnesica.kryptey.inputmethod.latin.LatinIME> controller =
        org.robolectric.Robolectric.buildService(
            com.amnesica.kryptey.inputmethod.latin.LatinIME.class);
    final com.amnesica.kryptey.inputmethod.latin.LatinIME ime = controller.create().get();

    final Object logic = field(ime, "mInputLogic");
    final RecapitalizeStatus status =
        (RecapitalizeStatus) field(logic, "mRecapitalizeStatus");
    status.enable();
    status.start(0, 33, "meet me at the safe house at nine", Locale.ENGLISH);
    assertEquals("precondition: the text must be held", "meet me at the safe house at nine",
        field(status, "mStringBefore"));

    // What onStartInputViewInternal calls on every restartInput and every focus move.
    logic.getClass().getMethod("startInput").invoke(logic);

    assertNull("the previous field's text must not survive a move to another field: this callback "
            + "runs without the window hiding, and the only thing that emptied this buffer was the "
            + "window hiding", field(status, "mStringBefore"));
    assertNull(field(status, "mStringAfter"));
  }
}
