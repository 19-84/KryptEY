package com.amnesica.kryptey.inputmethod.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No shipped layout may introduce a key that reaches outside the keyboard.
 *
 * <p>REVIVAL.md records, as a settled security finding, that the shipped layout files "add no
 * capability" and lists the action codes they use. That claim was written from a manual read and
 * had no guard: adding one XML file with a voice or clipboard key would leave every test green
 * while giving the keyboard a way to send what the user typed somewhere this project has never
 * reasoned about.
 *
 * <p>Pinned as the exact set rather than as a blocklist. A blocklist of "no voice, no clipboard"
 * only refuses the capabilities somebody thought of; requiring the set to be exactly what it is
 * today means any new one - including one nobody has imagined - has to be added here deliberately,
 * next to the sentence explaining why it is safe.
 *
 * <p>Note what this does NOT assert: the count of layout files. REVIVAL says 196 and no directory
 * total matches it, so the denominator is unknown; guessing one would pin a definition nobody
 * chose. What matters for security is the capability set, and that is what is checked.
 */
public class NolayoutAddsAcapabilityTest {

  /**
   * Every {@code !code/} constant the shipped layouts reference today.
   *
   * <p>Twelve, against the eight REVIVAL's prose lists - it omits enter, capslock, shift-enter and
   * the two action keys. All twelve are keyboard-internal: they edit text, move the caret, switch
   * layer or open this app's own settings. None sends anything anywhere.
   */
  private static final Set<String> PERMITTED = new HashSet<>(Arrays.asList(
      "key_action_next", "key_action_previous", "key_capslock", "key_delete", "key_enter",
      "key_language_switch", "key_settings", "key_shift", "key_shift_enter", "key_space",
      "key_switch_alpha_symbol", "key_tab"));

  private static Path resources() {
    for (final String candidate : new String[] {"src/main/res", "app/src/main/res"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the resource tree");
  }

  private static Set<String> codesUsedByEveryLayout() throws IOException {
    final Pattern code = Pattern.compile("!code/([a-zA-Z0-9_]+)");
    final Set<String> found = new TreeSet<>();
    int filesRead = 0;
    try (Stream<Path> files = Files.walk(resources())) {
      for (final Path file : (Iterable<Path>) files
          .filter(p -> p.getParent().getFileName().toString().startsWith("xml"))
          .filter(p -> p.toString().endsWith(".xml"))::iterator) {
        final Matcher matcher = code.matcher(
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        while (matcher.find()) found.add(matcher.group(1));
        filesRead++;
      }
    }
    assertTrue("this scan must actually read the layout tree; it found " + filesRead + " files",
        filesRead >= 300);
    return found;
  }

  @Test
  public void thelayoutsUseExactlyTheKeyboardInternalCodesTheyAlwaysHave() throws IOException {
    final Set<String> used = codesUsedByEveryLayout();

    final Set<String> added = new TreeSet<>(used);
    added.removeAll(PERMITTED);
    assertEquals("a shipped layout now references a key code this project has never reasoned "
            + "about. Every permitted code edits text, moves the caret, switches layer or opens "
            + "this app's own settings - none sends anything anywhere. If the new one is safe, add "
            + "it to PERMITTED with the sentence saying why: " + added,
        "[]", added.toString());

    final Set<String> gone = new TreeSet<>(PERMITTED);
    gone.removeAll(used);
    assertEquals("a code this list permits is no longer used by any layout. Not a defect, but the "
            + "list is meant to describe what ships - stale permissions are how a blocklist rots "
            + "into a formality: " + gone, "[]", gone.toString());
  }

  /**
   * And the property the set is a proxy for, stated directly.
   *
   * <p>If the exactness check above is ever relaxed - and someone will want to relax it, because it
   * fails whenever a layout legitimately gains a key - this one still refuses the specific
   * capabilities that would matter. Belt and braces, on the one claim REVIVAL files as settled.
   */
  @Test
  public void nolayoutReferencesAcodeThatLeavesTheKeyboard() throws IOException {
    final Set<String> outward = new TreeSet<>();
    for (final String code : codesUsedByEveryLayout()) {
      final String lower = code.toLowerCase(java.util.Locale.ROOT);
      if (lower.contains("voice") || lower.contains("clip") || lower.contains("emoji")
          || lower.contains("media") || lower.contains("search") || lower.contains("share")) {
        outward.add(code);
      }
    }
    assertEquals("a layout references a key that reaches outside the keyboard. This app's threat "
        + "model treats the messenger as hostile and the keyboard as the only trusted surface; a "
        + "voice or clipboard key hands what the user typed to something else entirely: " + outward,
        "[]", outward.toString());
  }
}
