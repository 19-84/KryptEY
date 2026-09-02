package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import com.amnesica.kryptey.inputmethod.latin.Subtype;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * A locale this app says it supports must actually produce a working keyboard.
 *
 * <p>{@code sSupportedLocales} is a hand-maintained array, and the subtypes it promises are
 * assembled from separate resource arrays and a set of layout-name constants. Nothing ties the
 * three together: add a locale to the list without its resources, or rename a layout constant
 * without touching the list, and the build succeeds, every existing test passes, and a user who
 * picks that language gets no subtype - which is a keyboard that does not come up.
 *
 * <p>This is a whole-set consistency check rather than a test of a function. It is the shape that
 * catches an omission in data, and data is where this particular defect would live: the fork ships
 * 196 layout files and a long locale list, and no single call site sees both.
 */
@RunWith(RobolectricTestRunner.class)
public class EverySupportedLocaleReallyWorksTest {

  private Resources resources;

  @Before
  public void setUp() {
    // LocaleResourceUtils.init, because Subtype.getName() resolves display names through it and
    // NPEs if it has not run. Production does this from RichInputMethodManager's constructor, so
    // it is set up long before any subtype is asked for its name; a test that skips it fails with
    // a null static rather than with anything about the data under test.
    LocaleResourceUtils.init(RuntimeEnvironment.getApplication());
    resources = RuntimeEnvironment.getApplication().getResources();
  }

  /** The premise: there is a substantial list to check, so the sweeps below are not empty. */
  @Test
  public void thepremiseThatManyLocalesAreClaimed() {
    final List<String> locales = SubtypeLocaleUtils.getSupportedLocales();
    assertTrue("the supported-locale list must be substantial, or every sweep below holds "
        + "vacuously: " + locales.size(), locales.size() >= 20);
  }

  /**
   * Every claimed locale yields a default subtype.
   *
   * <p>The one that fails if the locale list and the resources ever disagree. A null here is a
   * language the settings screen offers and the keyboard cannot honour.
   */
  @Test
  public void everyClaimedLocaleHasAdefaultSubtype() {
    final List<String> missing = new ArrayList<>();
    for (final String locale : SubtypeLocaleUtils.getSupportedLocales()) {
      if (SubtypeLocaleUtils.getDefaultSubtype(locale, resources) == null) {
        missing.add(locale);
      }
    }
    assertEquals("these locales are offered and produce no subtype, so choosing one leaves the "
        + "user with no keyboard: " + missing, 0, missing.size());
  }

  /**
   * And every subtype it produces is complete enough to render and to build a layout from.
   *
   * <p>An empty name is a blank row in the language picker; an empty layout set is a keyboard that
   * cannot be inflated. Both are states the types permit and the data must not contain.
   */
  @Test
  public void everySubtypeIsCompleteEnoughToUse() {
    final List<String> broken = new ArrayList<>();
    for (final String locale : SubtypeLocaleUtils.getSupportedLocales()) {
      for (final Subtype subtype : SubtypeLocaleUtils.getSubtypes(locale, resources)) {
        if (subtype.getName() == null || subtype.getName().isEmpty()) {
          broken.add(locale + ": empty name");
        }
        if (subtype.getKeyboardLayoutSet() == null || subtype.getKeyboardLayoutSet().isEmpty()) {
          broken.add(locale + ": empty layout set");
        }
        if (!locale.equals(subtype.getLocale())) {
          broken.add(locale + ": subtype reports locale " + subtype.getLocale());
        }
      }
    }
    assertEquals("a subtype with no name is a blank row in the language picker and one with no "
        + "layout set is a keyboard that cannot be inflated: " + broken, 0, broken.size());
  }

  /**
   * A locale this app does not support answers null rather than throwing.
   *
   * <p>The lookup is reached from settings with whatever the system reports, so an unknown value is
   * an ordinary input rather than a programming error.
   */
  @Test
  public void anunsupportedLocaleAnswersNull() {
    assertNull("an unknown locale must be refused by answering null",
        SubtypeLocaleUtils.getDefaultSubtype("zz_ZZ", resources));
    assertTrue("and produce no subtypes rather than throwing",
        SubtypeLocaleUtils.getSubtypes("zz_ZZ", resources).isEmpty());
  }

  /**
   * Asking for a layout a locale does not have answers null, rather than substituting one.
   *
   * <p>Substituting silently would give the user a keyboard whose keys do not match the language
   * they chose, which is worse than refusing: the refusal is visible and the substitution is not.
   */
  @Test
  public void alayoutAlocaleDoesNotHaveIsNotSubstituted() {
    final String someLocale = SubtypeLocaleUtils.getSupportedLocales().get(0);
    assertNotNull("precondition: the locale under test must itself be supported",
        SubtypeLocaleUtils.getDefaultSubtype(someLocale, resources));

    assertNull("a layout set that does not exist must not be quietly replaced by one that does",
        SubtypeLocaleUtils.getSubtype(someLocale, "no_such_layout_set", resources));
  }
}
