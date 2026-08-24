package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;

/**
 * The input cap on {@code decodeMessage}, which had no test at all.
 *
 * <p>Deleting it left the whole suite green, and it is the only thing standing between the IME and
 * an OutOfMemoryError. The output budget added for the compression bomb runs AFTER the payload has
 * been converted to a bit string, and that conversion allocates several characters per input
 * character - so a large enough paste exhausts memory before the inflate is ever reached. Measured
 * before this: a 16M-character clip produced an uncaught OutOfMemoryError out of
 * {@code FairyTaleEncoder.decode}, past {@code decodeMessage}'s {@code catch (RuntimeException)}
 * and past the clipboard listener's {@code catch (Exception)}.
 *
 * <p>That listener runs on every clipboard change, and the adversary in this threat model is the
 * messenger, so no user action is needed to reach it.
 */
@RunWith(RobolectricTestRunner.class)
public class DecodeInputCapTest {

  private E2EEStrip strip;

  @Before
  public void setUp() {
    final Context context = RuntimeEnvironment.getApplication();
    strip = new E2EEStrip(context);
  }

  /** One invisible character, so the paste routes to the FairyTale decoder rather than RAW. */
  private static String oversizedPaste(final int chars) {
    final StringBuilder builder = new StringBuilder(chars);
    builder.append('‌');
    while (builder.length() < chars) builder.append('a');
    return builder.toString();
  }

  @Test
  public void apasteLargerThanTheCapIsRefused() {
    final IOException refused = assertThrows(IOException.class,
        () -> strip.decodeMessage(oversizedPaste(E2EEStrip.MAX_DECODABLE_CHARS + 1)));

    assertTrue("the refusal must name the size and the limit: " + refused.getMessage(),
        refused.getMessage() != null && refused.getMessage().contains("too large"));
  }

  /**
   * And it must be refused BEFORE the payload is expanded, or the cap is decoration. A megabyte
   * paste that took hundreds of milliseconds and tens of megabytes to reject would still block the
   * IME on every clipboard change.
   */
  @Test
  public void ahugePasteIsRefusedWithoutExpandingIt() {
    final String huge = oversizedPaste(4 * 1024 * 1024);

    final long before = System.nanoTime();
    assertThrows(IOException.class, () -> strip.decodeMessage(huge));
    final long millis = (System.nanoTime() - before) / 1_000_000;

    assertTrue("rejecting a 4M-character paste took " + millis + "ms, which means it was converted "
        + "before being rejected - this runs on every clipboard change", millis < 250);
  }

  /** A paste exactly at the cap is still accepted, so the boundary is not off by one. */
  @Test
  public void apasteAtTheCapIsNotRefusedForSize() {
    try {
      strip.decodeMessage(oversizedPaste(E2EEStrip.MAX_DECODABLE_CHARS));
    } catch (IOException e) {
      assertTrue("a paste at exactly the cap must not be refused for size, but was: "
              + e.getMessage(),
          e.getMessage() == null || !e.getMessage().contains("too large"));
    }
  }

  /** Null input is refused rather than dereferenced. */
  @Test
  public void nullIsRefused() {
    assertThrows(IOException.class, () -> strip.decodeMessage(null));
  }
}
