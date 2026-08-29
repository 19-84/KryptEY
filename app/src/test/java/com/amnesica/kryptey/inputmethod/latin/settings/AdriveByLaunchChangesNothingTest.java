package com.amnesica.kryptey.inputmethod.latin.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.compat.PreferenceManagerCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The app's one exported component must not write preferences on a launch nobody asked for.
 *
 * <p>{@code SettingsActivity} is exported for the launcher and is a {@code PreferenceActivity}, so
 * any app on the device can start it with an {@code EXTRA_SHOW_FRAGMENT} extra naming a fragment
 * from the allowlist and then take the foreground back. {@code ThemeSettingsFragment.onPause} then
 * ran two writes with no human having touched anything: it persisted a theme id and deleted the
 * custom-colour key.
 *
 * <p>Both were inert, and only by accident — the colour key can never have been set, because the
 * control that writes it was permanently disabled, and the theme id written is the one just read.
 * That makes the safety of an exported entry point a property of what two keys currently mean
 * rather than of the entry point.
 *
 * <p>Driven at the fragment's own lifecycle rather than through a cross-app launch: the launch is
 * the platform's business and would need an instrumentation run. What this app controls is whether
 * a pause with no choice behind it writes anything.
 */
@RunWith(RobolectricTestRunner.class)
public class AdriveByLaunchChangesNothingTest {

  private static final String THEME_KEY = "pref_keyboard_theme_20140509";

  private SharedPreferences prefs;

  @Before
  public void setUp() {
    final Context context = RuntimeEnvironment.getApplication();
    prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
    prefs.edit().clear().commit();
  }

  /** A pause with no user choice must leave both keys exactly as they were. */
  @Test
  public void pausingWithoutAchoiceWritesNothing() {
    prefs.edit().putInt(Settings.PREF_KEYBOARD_COLOR, 0x123456).commit();

    new ThemeSettingsFragment().persistIfTheUserChose(prefs);

    assertEquals("a launch nobody asked for must not delete the user's custom colour: any app can "
            + "start the exported activity, take the foreground back, and this callback runs",
        0x123456, prefs.getInt(Settings.PREF_KEYBOARD_COLOR, -1));
    assertFalse("and it must not persist a theme id either", prefs.contains(THEME_KEY));
  }

  /**
   * And a real choice still persists, or the guard has broken the only path that saves a theme.
   *
   * <p>The tempting version of this guard clears its flag in {@code onResume}, which runs again
   * after a dialog is dismissed — so a user's pick would be forgotten before it was saved, and the
   * theme reverting on the next raise reads as a rendering bug rather than as this.
   */
  @Test
  public void achoiceIsStillPersisted() {
    final ThemeSettingsFragment fragment = new ThemeSettingsFragment();
    fragment.markThemePickedForTest(7);
    fragment.persistIfTheUserChose(prefs);

    assertTrue("a theme the user actually picked must reach storage", prefs.contains(THEME_KEY));
    assertEquals("7", prefs.getString(THEME_KEY, null));
  }
}
