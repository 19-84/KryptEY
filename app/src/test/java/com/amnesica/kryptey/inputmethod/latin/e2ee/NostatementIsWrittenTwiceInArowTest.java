package com.amnesica.kryptey.inputmethod.latin.e2ee;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * The same statement must not be executed twice in a row.
 *
 * <p>This exists because of a hand-edit that landed in the most-guarded method in the file and was
 * found by a review round rather than by anything automatic:
 *
 * <pre>
 *         warnIfKeyWasRejected(chosenContact);
 *         warnIfThisKeyIsPinnedElsewhere(chosenContact);
 *       warnIfThisKeyIsPinnedElsewhere(chosenContact);
 * </pre>
 *
 * <p>The second call was pasted at the wrong indentation and did nothing except post the same
 * {@code LENGTH_LONG} toast a second time, on the one arm whose own comments reason carefully about
 * how long the toast queue is. The commit message said "wired at five pin sites"; there were six
 * calls.
 *
 * <p><b>Why nothing caught it.</b> Three separate guards run over this file and none of them counts
 * call sites. {@code NoWriteResultIsDiscardedTest} keys its exemptions by {@code "method->callee"}
 * strings, so two calls in one method collapse to a single entry. The warning sweeps match text
 * fragments on the rendered banner, and the banner is identical whether the writer runs once or
 * twice — the duplicate is invisible to every assertion about <em>what the user sees</em>, because
 * it changes only how many times they see it.
 *
 * <p><b>Why adjacency rather than counting per method.</b> Calling the same raiser twice in one
 * method is perfectly ordinary when the calls are in different branches — that is how a warning gets
 * raised on each arm of a dispatch. What is never ordinary is the same statement running, and then
 * immediately running again, with nothing between them. That shape is a paste, not a decision, so it
 * can be rejected outright without a judgement call and without an exemption list that would need
 * the same attention that missed the defect.
 *
 * <p><b>What it cannot do:</b> it sees textually identical adjacent statements. A duplicate
 * separated by an unrelated line, or one written with different whitespace inside the argument list,
 * is not caught. It closes the shape that cost a real defect, not the general question.
 */
public class NostatementIsWrittenTwiceInArowTest {

  private static final String[] FILES = {
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java",
      "com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java",
      "com/amnesica/kryptey/inputmethod/signalprotocol/Account.java",
      "com/amnesica/kryptey/inputmethod/signalprotocol/helper/StorageHelper.java",
  };

  /**
   * Adjacent identical statements that are deliberate, each with the reason.
   *
   * <p>Empty on purpose. An entry here is a claim that running the very same statement twice in
   * succession is <em>correct</em>, which is a real argument someone has to make in writing.
   */
  private static final Set<String> DELIBERATE = new HashSet<>(Arrays.asList());

  /** A whole statement that is a bare call: {@code foo(...);} or {@code this.foo(...);}. */
  private static final Pattern CALL_STATEMENT =
      Pattern.compile("^[\\w.]+\\s*\\(.*\\)\\s*;$");

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  /**
   * Blanks comments and string bodies while keeping every newline.
   *
   * <p>The usual version of this helper collapses a block comment to a single space, which is
   * harmless when the caller only asks "does this text contain X" and wrong the moment it reports a
   * line number: this file's javadoc alone is thirty lines, so the offences it named came out
   * dozens of lines adrift, and in a file the size of the strip the drift runs to thousands. A
   * guard that points at the wrong line sends the reader to innocent code and is worse than one
   * that says nothing.
   */
  private static String withoutCommentsAndStrings(final String source) {
    final StringBuffer blanked = new StringBuffer();
    final Matcher spans = Pattern.compile(
        "(?s)/\\*.*?\\*/|(?m)//[^\n]*|\"(?:[^\"\\\\\n]|\\\\.)*\"").matcher(source);
    while (spans.find()) {
      final StringBuilder replacement = new StringBuilder();
      for (final char c : spans.group().toCharArray()) replacement.append(c == '\n' ? '\n' : ' ');
      spans.appendReplacement(blanked, Matcher.quoteReplacement(replacement.toString()));
    }
    spans.appendTail(blanked);
    return blanked.toString();
  }

  /**
   * The scan itself, so the guard and its own anti-vacuity case run the SAME code.
   *
   * <p>The first version of this file reimplemented the loop inside the anti-vacuity test. That
   * validates the pattern and nothing else: changing {@code raw.trim()} to {@code raw} in the
   * production loop - which is exactly what makes the original defect invisible again, since its two
   * copies differed only in indentation - left both tests green.
   */
  private static List<String> adjacentDuplicateStatements(final String source, final String label) {
    final List<String> found = new ArrayList<>();
    String previous = null;
    int previousLine = 0;
    int number = 0;
    for (final String raw : withoutCommentsAndStrings(source).split("\n", -1)) {
      number++;
      final String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.equals(previous)
          && CALL_STATEMENT.matcher(line).matches()
          && !DELIBERATE.contains(line)) {
        found.add(label + ":" + previousLine + " and :" + number + "  " + line);
      }
      previous = line;
      previousLine = number;
    }
    return found;
  }

  @Test
  public void nocallStatementRunsTwiceInSuccession() throws IOException {
    final List<String> offences = new ArrayList<>();

    for (final String file : FILES) {
      offences.addAll(adjacentDuplicateStatements(
          new String(Files.readAllBytes(mainSources().resolve(file)), StandardCharsets.UTF_8),
          file));
    }

    assertEquals("a statement that runs and then immediately runs again is a paste, not a "
            + "decision - the second call does the work twice and changes nothing the user can "
            + "read, so no assertion about the banner can see it: " + offences,
        0, offences.size());
  }

  /**
   * The scan can actually see the shape it is looking for.
   *
   * <p>Without this the test above passes on a tree where the pattern never matches anything - which
   * is exactly what it would do if {@code CALL_STATEMENT} were wrong, since a green result and a
   * blind result are the same result. The defect that prompted this file is reconstructed verbatim
   * and must be found.
   */
  @Test
  public void thescanFindsTheDefectItWasWrittenFor() {
    final String reconstructed = "  void arm() {\n"
        + "        warnIfKeyWasRejected(chosenContact);\n"
        + "        warnIfThisKeyIsPinnedElsewhere(chosenContact);\n"
        + "      warnIfThisKeyIsPinnedElsewhere(chosenContact);\n"
        + "  }\n";

    final List<String> found = adjacentDuplicateStatements(reconstructed, "reconstructed");

    assertEquals("the scan must catch the original defect, differing indentation and all - "
        + "otherwise a green run above means only that the pattern matches nothing: " + found,
        1, found.size());
    assertTrue("and must name the two lines it actually sits on, not lines shifted by whatever "
            + "comments happen to precede it: " + found.get(0),
        found.get(0).startsWith("reconstructed:3 and :4"));
  }

  /**
   * A comment above the defect must not move the line numbers the scan reports.
   *
   * <p>The mutant this kills is the ordinary one-line comment stripper, which replaces a block
   * comment with a single space and silently renumbers everything after it.
   */
  @Test
  public void thereportedLinesSurviveAcommentAboveThem() {
    final String withComment = "  void arm() {\n"
        + "    /* a block comment\n"
        + "       spanning three\n"
        + "       lines */\n"
        + "        warnIfKeyWasRejected(chosenContact);\n"
        + "        warnIfThisKeyIsPinnedElsewhere(chosenContact);\n"
        + "      warnIfThisKeyIsPinnedElsewhere(chosenContact);\n"
        + "  }\n";

    final List<String> found = adjacentDuplicateStatements(withComment, "withComment");

    assertEquals("the comment must not hide the defect: " + found, 1, found.size());
    assertTrue("the duplicate is on lines 6 and 7 of that text, and must be reported there - a "
            + "collapsing stripper reports 4 and 5: " + found.get(0),
        found.get(0).startsWith("withComment:6 and :7"));
  }
}
