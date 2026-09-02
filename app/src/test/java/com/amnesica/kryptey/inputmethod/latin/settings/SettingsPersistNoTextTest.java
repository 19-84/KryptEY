package com.amnesica.kryptey.inputmethod.latin.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The settings screens persist preferences, never anything the user typed.
 *
 * <p>REVIVAL records as settled that "{@code latin/settings/} persists no text — every write is an
 * appearance or key-press value, with no personalisation setting, no gesture data and no dictionary
 * write". That was a manual read of eighteen files and had no guard. It is the kind of claim that
 * stops being true by addition rather than by edit: a later "remember my last message" or a
 * recent-emoji list is a natural-looking settings feature, and it would put user content into
 * {@code SharedPreferences} - which this app's own storage layer goes to considerable lengths to
 * keep encrypted everywhere else.
 *
 * <p>Pinned as the exact set of writes rather than as a search for suspicious ones. There are seven
 * across the package and every one is accounted for below; a blocklist would only refuse the
 * feature somebody thought to forbid, while an exact set means an eighth write has to be added here
 * deliberately, next to the sentence saying what it stores.
 */
public class SettingsPersistNoTextTest {

  /** Every persisting call in the package, as "file:method(key)". */
  private static final List<String> PERMITTED = java.util.Arrays.asList(
      // Key-press feedback: volume, duration, and the delay before a long press.
      "KeyPressSettingsFragment:putInt(key)",
      "KeyPressSettingsFragment:putFloat(key)",
      // Appearance: keyboard height and colours.
      "AppearanceSettingsFragment:putInt(key)",
      "AppearanceSettingsFragment:putFloat(key)",
      // The one string, and the only persisted string in the package: the list of enabled
      // keyboard subtypes, which is locale and layout names this app ships - not user content.
      "Settings:putString(PREF_ENABLED_SUBTYPES)");

  private static final Pattern WRITE = Pattern.compile(
      "\\.(put(?:String|Boolean|Int|Float|Long|StringSet))\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)");

  private static Path settingsPackage() {
    for (final String candidate : new String[] {
        "src/main/java/com/amnesica/kryptey/inputmethod/latin/settings",
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/settings"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate latin/settings");
  }

  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  @Test
  public void everyPersistedValueIsAsettingAndNotSomethingTyped() throws IOException {
    final List<String> found = new ArrayList<>();
    int filesRead = 0;

    try (Stream<Path> files = Files.walk(settingsPackage())) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.toString().endsWith(".java"))::iterator) {
        filesRead++;
        final String name = file.getFileName().toString().replace(".java", "");
        final String body = withoutCommentsAndStrings(
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        final Matcher write = WRITE.matcher(body);
        while (write.find()) {
          // mExtras is a Bundle handed to a fragment, not a preference file - it does not persist.
          // Distinguishing the two is the point: a Bundle carrying a locale is not a stored value,
          // and folding it in would make the permitted list describe something it does not mean.
          final int at = write.start();
          final String receiver = body.substring(Math.max(0, at - 40), at);
          if (receiver.contains("mExtras") || receiver.contains("Bundle")) continue;
          found.add(name + ":" + write.group(1) + "(" + write.group(2) + ")");
        }
      }
    }

    assertTrue("this scan must read the settings package; it saw " + filesRead + " files",
        filesRead >= 15);

    final List<String> unexpected = new ArrayList<>(found);
    unexpected.removeAll(PERMITTED);
    assertEquals("a settings screen now persists something this project has not accounted for. "
            + "Every write here is meant to be an appearance or key-press value; user content in "
            + "SharedPreferences is exactly what the storage layer encrypts everywhere else. If "
            + "the new value is a setting, add it above with what it stores: " + unexpected,
            0, unexpected.size());

    final List<String> gone = new ArrayList<>(PERMITTED);
    gone.removeAll(found);
    assertEquals("a permitted write no longer exists, so this list describes the package less well "
        + "than it reads - stale permissions are how an exact set decays into a formality: " + gone,
        0, gone.size());
  }

  /** And the count REVIVAL quotes for the package is the count on disk. */
  @Test
  public void thepackageIsStillTheEighteenFilesTheClaimWasReadFrom() throws IOException {
    try (Stream<Path> files = Files.walk(settingsPackage())) {
      final long javaFiles = files.filter(p -> p.toString().endsWith(".java")).count();
      assertEquals("REVIVAL says the finding was read from eighteen files. If the package has "
              + "grown, the reading is older than the code and the claim needs re-checking rather "
              + "than re-quoting", 18, javaFiles);
    }
  }
}
