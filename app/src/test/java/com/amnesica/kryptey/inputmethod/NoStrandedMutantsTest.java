package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A tripwire for mutation experiments left behind in the tree.
 *
 * <p>This exists because one was. A mutation sweep run in the foreground was killed by a time cap
 * mid-run; the runner restores each file in a {@code finally}, and a killed process runs no
 * {@code finally}. The stranded mutant swapped the verified and unverified badges on the trusted
 * arm of a contact row — so a contact the user had compared rendered as unverified — and the next
 * commit, made with {@code git add -A}, shipped it. The entire suite passed with it in place,
 * because the render site it hit had no coverage; that gap is closed separately by
 * {@code VerifiedBadgeRenderTest}.
 *
 * <p>What this file adds is cheaper and blunter: it looks for two <em>shapes</em> a mutation leaves
 * that a human almost never writes on purpose.
 *
 * <p><b>It would not have caught the one that shipped.</b> That is worth stating plainly, because
 * the first version of this comment claimed the opposite and was wrong: the stranded mutant's two
 * arms differed in one line — each bound its click listener to a different button — so the arms
 * were never identical and no twin-arm check would have flagged them. What would have caught it,
 * and now does, is a test that renders the row and asserts which icon is showing.
 *
 * <p>What this does catch is the sweep's OTHER operator. The same run also mutated the trust
 * condition to {@code if (true)}; had it been killed a few seconds earlier, that is what would have
 * been left behind, and {@code noconditionHasBeenReplacedByAconstant} fails on it. Both checks were
 * verified by re-introducing a real instance of each and watching them fail.
 *
 * <p>Not a substitute for diffing production files against the last known-good commit before
 * committing. That is the practice that actually found the incident; this is a backstop for one
 * narrow slice of it, and claiming more for it would repeat the mistake it exists to record.
 */
public class NoStrandedMutantsTest {

  /**
   * The one legitimate twin-armed branch in the tree.
   *
   * <p>Inherited AOSP, untouched since the initial commit: {@code TYPE_TEXT_VARIATION_FILTER} and
   * the default both resolve to {@code MODE_TEXT}. Allowlisted by file and line content rather than
   * by line number, so it does not need editing every time the file moves.
   */
  private static final List<String> ALLOWED = Collections.singletonList(
      "keyboard/KeyboardLayoutSet.java:return KeyboardId.MODE_TEXT;");

  private static Path sourceRoot() {
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

  /** Statements of a block, comments and blank lines removed, order-insensitive. */
  private static List<String> statements(final String body) {
    final List<String> out = new ArrayList<>();
    for (String line : body.split("\n")) {
      line = line.replaceAll("//.*", "").trim();
      if (!line.isEmpty()) out.add(line);
    }
    Collections.sort(out);
    return out;
  }

  /** The index of the brace matching the one at {@code from}, walking backwards. */
  private static int openingBraceBefore(final String text, final int from) {
    int depth = 0;
    for (int i = from; i >= 0; i--) {
      final char c = text.charAt(i);
      if (c == '}') depth++;
      else if (c == '{' && --depth == 0) return i;
    }
    return -1;
  }

  /** The index of the brace matching the one at {@code from}, walking forwards. */
  private static int closingBraceAfter(final String text, final int from) {
    int depth = 0;
    for (int i = from; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return i;
    }
    return -1;
  }

  /**
   * No branch does the same thing in both arms.
   *
   * <p>A branch whose arms agree is either dead code worth deleting or a decision that has stopped
   * being made, and there is no third case worth keeping. This is a code-smell check rather than a
   * mutation check: the mutant that shipped here did NOT take this shape, and the tree contains
   * exactly one instance, inherited and benign.
   */
  @Test
  public void nobranchDoesTheSameThingInBothArms() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int examined = 0;

    for (final Path source : javaSources()) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      int at = 0;
      while ((at = text.indexOf("} else {", at)) >= 0) {
        final int open = openingBraceBefore(text, at);
        final int close = closingBraceAfter(text, at + "} else {".length() - 1);
        at += "} else {".length();
        if (open < 0 || close < 0) continue;

        examined++;
        final List<String> then = statements(text.substring(open + 1, text.indexOf("} else {",
            open) < 0 ? open + 1 : text.lastIndexOf("}", at)));
        final List<String> otherwise =
            statements(text.substring(text.indexOf('{', at - 1) + 1, close));
        if (then.isEmpty() || otherwise.isEmpty() || !then.equals(otherwise)) continue;

        final String where = source.toString() + ":" + text.substring(0, open).split("\n").length;
        final boolean allowed = ALLOWED.stream().anyMatch(entry -> {
          final String[] parts = entry.split(":", 2);
          return source.toString().endsWith(parts[0]) && then.contains(parts[1]);
        });
        if (!allowed) offenders.add(where + "  -> " + then);
      }
    }

    assertTrue("this test scans source; examining none means it has stopped testing anything",
        examined >= 20);
    assertEquals("a branch does the same thing in both arms - either dead code, or a decision "
        + "that has stopped being made:\n" + String.join("\n", offenders),
        0, offenders.size());
  }

  /**
   * No condition has been replaced by a constant.
   *
   * <p>{@code if (true)} and {@code if (false)} are the other mutation operator used in this
   * project. Neither appears in the tree today, and neither has a reason to.
   */
  @Test
  public void noconditionHasBeenReplacedByAconstant() throws IOException {
    final Pattern constant = Pattern.compile("\\b(if|while)\\s*\\(\\s*(true|false)\\s*\\)");
    final List<String> offenders = new ArrayList<>();

    for (final Path source : javaSources()) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      final Matcher matcher = constant.matcher(text);
      while (matcher.find()) {
        // while (true) is an ordinary loop idiom; if (true)/if (false) is not.
        if ("while".equals(matcher.group(1))) continue;
        offenders.add(source + ": " + matcher.group());
      }
    }

    assertEquals("a condition has been replaced by a constant - the signature of a mutation "
        + "experiment that was never restored:\n" + String.join("\n", offenders),
        0, offenders.size());
  }

  /** The allowlist must stay honest: an entry that no longer matches anything is stale. */
  @Test
  public void theallowlistIsNotStale() throws IOException {
    for (final String entry : ALLOWED) {
      final String[] parts = entry.split(":", 2);
      final Path path = sourceRoot().resolve("com/amnesica/kryptey/inputmethod/" + parts[0]);
      assertTrue("allowlisted file no longer exists: " + parts[0], Files.exists(path));
      final String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertTrue("allowlisted branch no longer present in " + parts[0] + " - remove the entry "
          + "rather than leaving it to excuse something else later", text.contains(parts[1]));
    }
    assertEquals("the allowlist should hold exactly the one inherited AOSP case", 1,
        new ArrayList<>(Arrays.asList(ALLOWED.toArray())).size());
  }
}
