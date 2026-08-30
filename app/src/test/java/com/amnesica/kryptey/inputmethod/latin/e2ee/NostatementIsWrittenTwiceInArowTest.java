package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;

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

  private static String withoutCommentsAndStrings(final String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
  }

  @Test
  public void nocallStatementRunsTwiceInSuccession() throws IOException {
    final List<String> offences = new ArrayList<>();

    for (final String file : FILES) {
      final Path path = mainSources().resolve(file);
      final String text = withoutCommentsAndStrings(
          new String(Files.readAllBytes(path), StandardCharsets.UTF_8));

      String previous = null;
      int previousLine = 0;
      int number = 0;
      for (final String raw : text.split("\n", -1)) {
        number++;
        final String line = raw.trim();
        if (line.isEmpty()) continue;

        if (line.equals(previous)
            && CALL_STATEMENT.matcher(line).matches()
            && !DELIBERATE.contains(line)) {
          offences.add(file + ":" + previousLine + " and :" + number + "  " + line);
        }
        previous = line;
        previousLine = number;
      }
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
    final String reconstructed = "        warnIfKeyWasRejected(chosenContact);\n"
        + "        warnIfThisKeyIsPinnedElsewhere(chosenContact);\n"
        + "      warnIfThisKeyIsPinnedElsewhere(chosenContact);\n";

    final List<String> offences = new ArrayList<>();
    String previous = null;
    for (final String raw : withoutCommentsAndStrings(reconstructed).split("\n", -1)) {
      final String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.equals(previous) && CALL_STATEMENT.matcher(line).matches()) offences.add(line);
      previous = line;
    }

    assertEquals("the scan must catch the original defect, differing indentation and all - "
        + "otherwise a green run above means only that the pattern matches nothing", 1,
        offences.size());
  }
}
