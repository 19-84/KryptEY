package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The allow-list standing between an exported activity and an arbitrary fragment.
 *
 * <p>{@code SettingsActivity} is {@code android:exported="true"} in the manifest, so any app on the
 * device can start it. {@code PreferenceActivity} takes {@code EXTRA_SHOW_FRAGMENT} from that
 * intent and instantiates whatever class it names - the fragment-injection weakness Android added
 * {@code isValidFragment} to close. This seven-entry set is the whole of that closure, and it had
 * no test: a review read it and cleared it, which is not the same as something failing if it
 * changes.
 *
 * <p>Two ways it stops protecting anything, and both look harmless in a diff. {@code isValidFragment}
 * returning true - the "temporarily, to debug something" edit - opens every class on the classpath.
 * And an entry added for a fragment that reads an intent extra widens what the caller controls
 * without touching this file's shape at all.
 *
 * <p>The set is pinned exactly rather than asserted non-empty, so an eighth fragment is a
 * deliberate act. The names are read from the class objects, not written as strings, so a rename
 * moves the expectation with the code instead of silently emptying the allow-list - a set of stale
 * string literals would refuse every real fragment and pass this test.
 */
public class OnlyThisAppsOwnFragmentsCanBeLaunchedTest {

  private static final Class<?>[] PERMITTED = {
      com.amnesica.kryptey.inputmethod.latin.settings.PreferencesSettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.KeyPressSettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.AppearanceSettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.ThemeSettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.SettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.LanguagesSettingsFragment.class,
      com.amnesica.kryptey.inputmethod.latin.settings.SingleLanguageSettingsFragment.class,
  };

  @Test
  public void everyFragmentThisAppShipsIsAccepted() {
    for (final Class<?> fragment : PERMITTED) {
      assertTrue("this app's own settings fragment must still launch: " + fragment.getName(),
          FragmentUtils.isValidFragment(fragment.getName()));
    }
  }

  /**
   * And nothing else is, including the classes an attacker would actually name.
   *
   * <p>Not a bare "some string is refused": the interesting inputs are real classes on this app's
   * classpath, because those are what {@code EXTRA_SHOW_FRAGMENT} can reach. A guard that refuses
   * {@code "not a fragment"} while accepting a real one would pass a lazier version of this test.
   */
  @Test
  public void nothingElseIsAcceptedIncludingRealClassesOnTheClasspath() {
    final String[] refused = {
        // A real Fragment in this app that is NOT a settings screen.
        "com.amnesica.kryptey.inputmethod.latin.settings.SettingsActivity",
        // Real classes, reachable by name, that must never be instantiated from an intent.
        "com.amnesica.kryptey.inputmethod.latin.LatinIME",
        "com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain",
        "androidx.preference.PreferenceFragmentCompat",
        "android.app.Fragment",
        "",
    };
    for (final String name : refused) {
      assertFalse("a class this app does not ship as a settings screen must not be launchable "
              + "through an exported activity: " + name,
          FragmentUtils.isValidFragment(name));
    }
    assertFalse("and null must be refused rather than thrown on - the extra is attacker-supplied "
        + "and may be absent", FragmentUtils.isValidFragment(null));
  }

  /**
   * The allow-list is exactly these seven, checked against the source rather than against itself.
   *
   * <p>Counting the {@code add} calls in {@code FragmentUtils} keeps this test from being satisfied
   * by its own list: if someone adds an eighth fragment there, the count moves and this fails,
   * where a test that only asked "are my seven accepted" would stay green.
   */
  @Test
  public void theallowListIsExactlyTheSevenFragmentsThisAppShips() throws IOException {
    Path source = null;
    for (final String candidate : new String[] {
        "src/main/java/com/amnesica/kryptey/inputmethod/latin/utils/FragmentUtils.java",
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/utils/FragmentUtils.java"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) source = path;
    }
    org.junit.Assert.assertNotNull("could not locate FragmentUtils", source);

    final String body = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\n]*", " ");

    final List<String> added = new ArrayList<>();
    final java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("sLatinImeFragments\\.add\\(\\s*([A-Za-z0-9_.]+)\\.class").matcher(body);
    while (matcher.find()) added.add(matcher.group(1));

    assertTrue("the allow-list is populated by addAll, which this count cannot read - it counts "
        + "add(X.class) calls. Either list the fragments individually or teach this test the new "
        + "shape; a count that silently sees none would pass with the list wide open",
        !body.contains("sLatinImeFragments.addAll"));

    assertEquals("the allow-list has changed size. Every entry is a class any app on the device "
            + "can ask this exported activity to instantiate, so an addition is a decision: "
            + added, PERMITTED.length, added.size());
  }
}
