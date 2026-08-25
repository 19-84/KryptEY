package com.amnesica.kryptey.inputmethod.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The inherited keyboard's debug switches must stay off, because several of them are keyloggers.
 *
 * <p>{@code KeyboardState.onPressKey} and {@code PointerTracker.onCodeInput} log the code of every
 * key the user touches, and {@code PointerTracker}'s version resolves {@code CODE_OUTPUT_TEXT} to
 * the actual output string. {@code RichInputConnection.DEBUG_PREVIOUS_TEXT} dumps the text around
 * the cursor. In an ordinary keyboard that is a developer convenience. In this one the text being
 * typed is the plaintext of an end-to-end encrypted message, and logcat is readable over adb and
 * swept whole into bug reports - so flipping any one of these ships a keylogger for exactly the
 * content the app exists to protect.
 *
 * <p>They are all {@code static final false} today, which means javac folds the branches away and
 * the {@code Log} calls are not in the APK at all. That is the right design and nothing enforces
 * it: one character, in a file nobody in this revival has had reason to open, turns it off. The
 * ~7,300 lines of inherited AOSP keyboard have never been examined by anyone, and this is the part
 * of them that touches the security premise directly.
 *
 * <p>Read from source rather than by reflection, deliberately. A {@code static final boolean} is
 * inlined into every use site at compile time, so reflecting on the field would report what the
 * constant pool holds - which is the same value the branch was folded against, but says nothing
 * about a flag that was flipped and then re-derived from something else. The source is the thing a
 * reviewer would read, and it is what a diff would show.
 */
public class DebugLoggingStaysOffTest {

  /** Matches a compile-time boolean switch: {@code static final boolean DEBUG_FOO = <value>;} */
  private static final Pattern SWITCH = Pattern.compile(
      "static\\s+final\\s+boolean\\s+((?:DEBUG|TRACE|LOG)[A-Z_0-9]*)\\s*=\\s*([^;]+);");

  private static Path sourceRoot() {
    // The test runs from the module directory; both layouts are tolerated so a change to the
    // Gradle working directory does not silently turn this into a test of an empty file list.
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree from "
        + Paths.get("").toAbsolutePath());
  }

  private static List<Path> javaSources() throws IOException {
    try (Stream<Path> walk = Files.walk(sourceRoot())) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /**
   * Every debug switch in the app is off.
   *
   * <p>Stated over all of them rather than a hand-listed few: the ones that log keystrokes today
   * are not necessarily the ones that will, and a list would need maintaining by whoever adds the
   * next flag - which is exactly the person who would not think to.
   */
  @Test
  public void everyDebugSwitchIsCompiledOut() throws IOException {
    final List<String> on = new ArrayList<>();
    int found = 0;

    for (final Path source : javaSources()) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      final Matcher matcher = SWITCH.matcher(text);
      while (matcher.find()) {
        found++;
        final String name = matcher.group(1);
        final String value = matcher.group(2).trim();
        // A switch may be defined in terms of other switches - PointerTracker.DEBUG_MODE is
        // "DebugFlags.DEBUG_ENABLED || DEBUG_EVENT". That is fine as long as every term is itself
        // one of these constants, which the whole-file assertion below covers.
        final boolean off = value.equals("false")
            || value.chars().noneMatch(Character::isLowerCase)
            || value.matches("[A-Za-z.\\s_0-9|&]+");
        if (!off || value.contains("true")) {
          on.add(source + ": " + name + " = " + value);
        }
      }
    }

    assertTrue("this test scans source files; finding none means it has stopped testing anything",
        found >= 15);
    assertEquals("a debug switch is on. Several of these log every key the user presses, and in "
        + "this keyboard those keys are the plaintext of an encrypted message:\n"
        + String.join("\n", on), 0, on.size());
  }

  /**
   * The two that log key codes are named explicitly as well.
   *
   * <p>The sweep above is the durable guard; this one fails with a message that says what was just
   * turned on and why it matters, rather than leaving the next person to work out which of nineteen
   * switches was the dangerous one.
   */
  @Test
  public void theswitchesThatLogKeystrokesAreNamedAndOff() throws IOException {
    assertSwitchIsFalse("keyboard/internal/KeyboardState.java", "DEBUG_EVENT",
        "logs the code of every key pressed and released");
    assertSwitchIsFalse("keyboard/PointerTracker.java", "DEBUG_LISTENER",
        "logs every code input, resolving CODE_OUTPUT_TEXT to the literal output string");
    assertSwitchIsFalse("latin/RichInputConnection.java", "DEBUG_PREVIOUS_TEXT",
        "dumps the text around the cursor, which is the decrypted message");
    assertSwitchIsFalse("latin/define/DebugFlags.java", "DEBUG_ENABLED",
        "turns on the debug switches that are derived from it");
  }

  private void assertSwitchIsFalse(final String relativePath, final String name, final String what)
      throws IOException {
    final Path path = sourceRoot().resolve("com/amnesica/kryptey/inputmethod/" + relativePath);
    assertTrue("expected to find " + path + " - if the file moved, this test needs updating "
        + "rather than deleting", Files.exists(path));

    final String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    final Matcher matcher = Pattern.compile(
        "static\\s+final\\s+boolean\\s+" + Pattern.quote(name) + "\\s*=\\s*([^;]+);").matcher(text);
    assertTrue("could not find " + name + " in " + relativePath, matcher.find());
    assertEquals(name + " in " + relativePath + " must stay false: it " + what,
        "false", matcher.group(1).trim());
  }

  /**
   * And the constant really is compile-time, so the branch is folded away rather than merely not
   * taken.
   *
   * <p>If one of these became non-final, or moved to a settings-backed value, the {@code Log} calls
   * would be in the APK and reachable at runtime - a different and much worse situation than a
   * constant that happens to be false, and one no source scan above would notice.
   */
  @Test
  public void thekeystrokeSwitchesAreCompileTimeConstants() throws Exception {
    assertIsConstant("com.amnesica.kryptey.inputmethod.latin.define.DebugFlags", "DEBUG_ENABLED");
    assertIsConstant("com.amnesica.kryptey.inputmethod.keyboard.internal.KeyboardState",
        "DEBUG_EVENT");
    assertIsConstant("com.amnesica.kryptey.inputmethod.keyboard.PointerTracker", "DEBUG_LISTENER");

    // DebugFlags has no Android dependencies, so its value can be read as well as its modifiers -
    // and it is the one the others derive from.
    final Field enabled = com.amnesica.kryptey.inputmethod.latin.define.DebugFlags.class
        .getDeclaredField("DEBUG_ENABLED");
    enabled.setAccessible(true);
    assertFalse("DEBUG_ENABLED must be false at runtime, not merely in source",
        enabled.getBoolean(null));
  }

  private void assertIsConstant(final String className, final String fieldName) throws Exception {
    // initialize=false: KeyboardState and PointerTracker pull in the Android framework the moment
    // their static initialisers run, and this test has no need of it. Modifiers are readable
    // without initialising; the VALUE is not, which is why only DebugFlags is asked for one below.
    final Field field = Class.forName(className, false, getClass().getClassLoader())
        .getDeclaredField(fieldName);
    field.setAccessible(true);
    assertTrue(fieldName + " must be static final so javac can fold the branch away",
        Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
  }
}
