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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A method that WRITES must not sit where Java is free not to call it.
 *
 * <p>This exists because of one defect and the way it hid. {@code warnIfIdentityChanged} returns a
 * boolean and is named like a question, and it is the only thing on its arm that raises the app's
 * persistent warning. It was written as:
 *
 * <pre>if (!mWarningStanding &amp;&amp; !warnIfIdentityChanged(contact))</pre>
 *
 * <p>and read, correctly, as "unless a warning is standing, and unless this is an identity change".
 * Then the left term was widened to cover a second kind of standing state — a change that was right
 * for what the guard was for — and short-circuit evaluation quietly stopped calling the writer. A
 * detected key substitution stopped reaching the one surface that persists. Nothing about the call
 * site looked like a statement, so nothing about the edit looked like deleting one.
 *
 * <p>So this scans for the shape rather than trusting anyone to remember it: any method whose body
 * has a side effect, invoked on the right of {@code &&} or {@code ||}. The writer set is computed
 * from the source, not listed by hand — a hand-written list would have to be kept current by the
 * same attention that missed the defect.
 *
 * <p><b>What it cannot do:</b> it reasons about one file's own methods, and its notion of "writes"
 * is the side effects this class knows how to look for. A writer reached indirectly, or one whose
 * effect is a field assignment this scan does not recognise, is not caught. It closes the specific
 * shape that cost a real defect, not the general question.
 */
public class NoWriterSitsInAshortCircuitTest {

  private static final String[] FILES = {
      "com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java",
      // Not E2EEStrip: it is a thin facade over SignalProtocolMain and writes nothing itself, so
      // requiring writers there failed for a reason about the test rather than about the code.
      "com/amnesica/kryptey/inputmethod/signalprotocol/SignalProtocolMain.java",
  };

  /**
   * Calls that are allowed on the right of a short circuit, each with the reason.
   *
   * <p>Empty on purpose right now. An entry here is a claim that skipping the call is <em>correct</em>
   * in every state that short-circuits, which is a real argument someone has to make.
   */
  private static final Set<String> DELIBERATE = new HashSet<>(Arrays.asList(
      // selectContact: `warnIfIdentityChanged(contact) || warnIfKeyWasRejected(contact)`.
      //
      // Skipping the second is correct here, and the argument is precedence rather than
      // convenience. Both writers concern the SAME contact, and the left one returns true only
      // after it has posted - so when the short circuit fires, a warning about that contact is
      // already standing and addressed to that address. The user is never left with nothing, which
      // is what made the round-16 case a defect rather than an ordering choice.
      //
      // Which of the two should win is a real judgement and it is made deliberately: a pending
      // identity change is a live event - a different key was offered at this address and refused -
      // while the rejection record describes something the user already did. The banner holds one
      // warning; the verify screen is where both are shown together, and it composes them.
      "warnIfKeyWasRejected"));

  /** Evidence, in a method body, that calling it does something rather than answering something. */
  private static final Pattern SIDE_EFFECT = Pattern.compile(
      "setWarningMessage\\s*\\(|setInfoTextViewMessage\\s*\\(|Toast\\.makeText\\s*\\(|"
          + "setInviteRefusalWarning\\s*\\(|setCautionBesideAnyWarning\\s*\\(|"
          + "clearStandingWarning\\s*\\(|storeAllAccountInformationInSharedPreferences\\s*\\(|"
          + "setInfoUnlessWarned\\s*\\(");

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

  /** Every method in the file, by name, mapped to its body. */
  private static Map<String, String> methods(final String text) {
    final Map<String, String> found = new LinkedHashMap<>();
    final Matcher declaration = Pattern.compile(
        "\n  (?:public |private |protected |static |final |synchronized )*"
            + "[\\w<>\\[\\], .?]+ (\\w+)\\s*\\([^)]*\\)\\s*(?:throws [\\w, .]+)?\\{").matcher(text);
    while (declaration.find()) {
      final int end = text.indexOf("\n  }", declaration.end());
      found.put(declaration.group(1),
          text.substring(declaration.end(), end < 0 ? text.length() : end));
    }
    return found;
  }

  @Test
  public void nomethodThatWritesIsCalledWhereJavaMaySkipIt() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int scanned = 0;

    for (final String relative : FILES) {
      final Path path = mainSources().resolve(relative);
      assertTrue("expected to find " + path, Files.exists(path));
      final String text = withoutCommentsAndStrings(
          new String(Files.readAllBytes(path), StandardCharsets.UTF_8));

      final Map<String, String> methods = methods(text);
      assertTrue("no methods were parsed out of " + relative + "; the pattern has stopped matching "
          + "and this test is asserting nothing", methods.size() >= 20);

      final Set<String> writers = new HashSet<>();
      for (final Map.Entry<String, String> method : methods.entrySet()) {
        if (SIDE_EFFECT.matcher(method.getValue()).find()) writers.add(method.getKey());
      }
      assertTrue("no writers were identified in " + relative + ". Both files here post warnings or "
          + "persist the account, so finding none means the side-effect pattern has stopped "
          + "matching and the scan is looking at nothing", writers.size() >= 3);

      // Anything invoked after a && or || - the positions Java is free not to evaluate.
      final Matcher shortCircuit =
          Pattern.compile("(?:&&|\\|\\|)\\s*!?\\s*(\\w+)\\s*\\(").matcher(text);
      while (shortCircuit.find()) {
        scanned++;
        final String called = shortCircuit.group(1);
        if (!writers.contains(called) || DELIBERATE.contains(called)) continue;
        offenders.add(relative + " :: " + called + "() after a short circuit");
      }
    }

    assertTrue("this test found no short-circuiting conditions at all, which means it is not "
        + "reading what it thinks it is", scanned >= 10);

    assertEquals("a method that writes is being called where Java is free to skip it. That is how "
        + "the identity-change warning stopped being raised: the call read like a boolean, so "
        + "widening the term to its left looked like tightening a guard rather than deleting a "
        + "statement. Call it unconditionally into a local and guard the branch instead - or, if "
        + "skipping it really is correct in every state that short-circuits, add it to DELIBERATE "
        + "with that argument written down:\n" + String.join("\n", offenders),
        0, offenders.size());
  }
}
