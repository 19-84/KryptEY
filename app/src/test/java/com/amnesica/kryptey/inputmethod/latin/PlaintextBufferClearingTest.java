package com.amnesica.kryptey.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.amnesica.kryptey.inputmethod.latin.utils.RecapitalizeStatus;

import org.junit.Test;

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
}
