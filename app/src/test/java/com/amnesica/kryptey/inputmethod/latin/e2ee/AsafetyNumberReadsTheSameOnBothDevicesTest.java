package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

/**
 * The safety number must render in the same digits whatever the device's locale is.
 *
 * <p>The segments were formatted with {@code Locale.getDefault()}, so the default locale's
 * numbering system chose the glyphs. On a device set to a locale that uses Eastern-Arabic or
 * Devanagari digits the number rendered in those, while the peer - reading the number for the same
 * key - saw Latin ones.
 *
 * <p>Two honest devices holding the identical key would show visibly different numbers. The whole
 * instruction on that screen is "compare the number", so a comparison that fails between two
 * correct devices teaches the user that mismatches are normal, which is precisely the lesson this
 * screen cannot afford to teach: a real mismatch is the only signal it exists to deliver.
 *
 * <p>The premise case asserts the hazard is real on this runtime rather than assuming it - if
 * {@code String.format} ever stopped honouring the numbering system, the fix would still be correct
 * but this test would be measuring nothing, and it should say so rather than pass quietly.
 */
@RunWith(RobolectricTestRunner.class)
public class AsafetyNumberReadsTheSameOnBothDevicesTest {

  private final Locale original = Locale.getDefault();

  @After
  public void restoreLocale() {
    Locale.setDefault(original);
  }

  /** Devanagari digits, requested through the locale's numbering-system extension. */
  private static final Locale DEVANAGARI = Locale.forLanguageTag("hi-IN-u-nu-deva");

  @Test
  public void thepremiseThatAlocaleCanChangeTheDigits() {
    assertNotEquals("this runtime must actually render non-Latin digits for that locale, or the "
            + "case below is asserting something that could never have failed",
        "00042", String.format(DEVANAGARI, "%05d", 42));
  }

  @Test
  public void asegmentRendersInLatinDigitsUnderAnyLocale() {
    Locale.setDefault(DEVANAGARI);

    assertEquals("the safety number must read the same on both devices - the peer comparing it is "
            + "looking at Latin digits, and a mismatch between two honest devices teaches the user "
            + "to ignore mismatches", "00042", E2EEStripView.formatCodeSegment(42));
    assertEquals("and the padding must survive the change too", "00007",
        E2EEStripView.formatCodeSegment(7));
  }

  @Test
  public void thesameSegmentRendersIdenticallyUnderTwoDifferentLocales() {
    Locale.setDefault(Locale.US);
    final String latin = E2EEStripView.formatCodeSegment(31415);
    Locale.setDefault(DEVANAGARI);
    final String other = E2EEStripView.formatCodeSegment(31415);

    assertEquals("two devices in different locales must render one key's number identically, "
        + "which is the property the comparison rests on", latin, other);
  }
}
