package com.amnesica.kryptey.inputmethod.latin.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;

/**
 * The locale cache is static and lives for the process, so it needs a ceiling.
 *
 * <p>Every caller today passes either a {@code Subtype}'s own locale or a string from this app's
 * own resources, so the key space is closed and small - nobody can drive this from outside. The
 * bound exists because that is a property of the six call sites rather than of the method, which
 * takes any {@code String} and kept every distinct one forever.
 *
 * <p>Clearing rather than evicting one entry is deliberate and this test pins the consequence: the
 * values are pure functions of their keys and cost a {@code split} plus a constructor to rebuild,
 * so the simplest bound that cannot be wrong is the right one. What must NOT change is that the
 * method keeps answering correctly across the clear.
 */
public class AlocaleCacheThatCannotGrowForeverTest {

  @SuppressWarnings("unchecked")
  private static HashMap<String, Locale> cache() throws Exception {
    final Field field = LocaleUtils.class.getDeclaredField("sLocaleCache");
    field.setAccessible(true);
    return (HashMap<String, Locale>) field.get(null);
  }

  private static int bound() throws Exception {
    final Field field = LocaleUtils.class.getDeclaredField("MAX_CACHED_LOCALES");
    field.setAccessible(true);
    return (Integer) field.get(null);
  }

  @Test
  public void thecacheDoesNotGrowWithoutBound() throws Exception {
    cache().clear();
    final int bound = bound();

    for (int i = 0; i < bound * 3; i++) {
      assertNotNull(LocaleUtils.constructLocaleFromString("xx_Y" + i));
    }

    assertTrue("a static cache on a method that accepts any string must have a ceiling; it held "
        + cache().size() + " against a bound of " + bound, cache().size() <= bound);
  }

  /**
   * And the answers stay correct across the clear, which is the only way the bound could hurt.
   *
   * <p>Without this the case above would pass on an implementation that stopped caching, or one
   * that returned something wrong after the reset.
   */
  @Test
  public void theanswersSurviveTheClear() throws Exception {
    cache().clear();

    final Locale first = LocaleUtils.constructLocaleFromString("de_DE");
    assertSame("precondition: a repeat must come from the cache, or nothing here is about a cache",
        first, LocaleUtils.constructLocaleFromString("de_DE"));

    for (int i = 0; i < bound() * 2; i++) LocaleUtils.constructLocaleFromString("zz_Q" + i);

    final Locale afterClear = LocaleUtils.constructLocaleFromString("de_DE");
    assertEquals("the language must still be right after the cache was reset", "de",
        afterClear.getLanguage());
    assertEquals("and the country", "DE", afterClear.getCountry());
  }

  /** The three shapes the parser supports still parse, so the bound changed nothing else. */
  @Test
  public void thethreeLocaleShapesStillParse() {
    assertEquals("fr", LocaleUtils.constructLocaleFromString("fr").getLanguage());

    final Locale withCountry = LocaleUtils.constructLocaleFromString("fr_CA");
    assertEquals("fr", withCountry.getLanguage());
    assertEquals("CA", withCountry.getCountry());

    final Locale withVariant = LocaleUtils.constructLocaleFromString("fr_CA_POSIX");
    assertEquals("POSIX", withVariant.getVariant());
  }
}
