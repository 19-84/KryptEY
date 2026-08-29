package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A test that deletes a contact and throws away the answer is usually not testing what it says.
 *
 * <p>{@code removeContactFromContactListAndProtocol} returns whether the deletion reached disk, and
 * a deletion whose write fails is <em>rolled back wholesale</em> — the contact list, the message
 * list and the session are all restored. So in a fixture with no landing store the contact is still
 * there afterwards, and every assertion about "after the deletion" is reading the state from
 * before it.
 *
 * <p>That is not a hypothetical. A review round applied mutants to production and ran all 1412
 * tests: four separate tests whose subject is what a deletion does stayed green under mutants that
 * broke exactly that, because their deletions never happened. One of them —
 * "deleting the impostor must not erase the genuine contact's history" — survived widening the
 * message sweep to a total wipe, which is the single thing it exists to catch. Three other tests
 * caught that mutant; the one named for it did not.
 *
 * <p>The call is not always wrong. Several tests here are <em>about</em> the failure arm — a
 * deletion that does not land, an interrupted log move — and for those, discarding the result is
 * correct because the result is known to be false. So this cannot be a ban, and is a ratchet: the
 * number may not grow, and <b>when you make one of these assert its result, lower {@link #KNOWN}.</b>
 *
 * <p>What a new one should do instead: install {@code TestStores.writesLand()} and assert the return
 * value as a precondition. If the test is genuinely about the failure arm, say so in a comment where
 * the call is — the reader cannot otherwise tell a deliberate failure from a fixture that forgot.
 */
public class AdeletionsResultMustNotBeSilentlyDiscardedTest {

  /**
   * How many discarded deletion results are known to remain. Lower this; never raise it.
   *
   * <p>Twenty-nine when the check was written, less the one fixed alongside it.
   */
  private static final int KNOWN = 21;

  private static final Pattern DISCARDED =
      Pattern.compile("^\\s*SignalProtocolMain\\.removeContactFromContactListAndProtocol\\(");

  private static Path repositoryRoot() {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    if (here == null) throw new IllegalStateException("could not find the repository root");
    return here;
  }

  @Test
  public void nonewTestThrowsAwayWhetherItsDeletionHappened() throws IOException {
    final Path tests = repositoryRoot().resolve("app/src/test/java");
    final List<String> sites = new ArrayList<>();

    try (Stream<Path> files = Files.walk(tests)) {
      for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))
          ::iterator) {
        final List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          // A statement, not an argument. A call wrapped in an assertion appears on its own
          // continuation line and starts the same way, so the start of the line is not enough to
          // tell them apart; what does is the parentheses. A standalone statement closes exactly
          // what it opens, and an argument to assertTrue closes the assertion too.
          final String line = lines.get(i);
          final long opens = line.chars().filter(c -> c == '(').count();
          final long closes = line.chars().filter(c -> c == ')').count();
          if (DISCARDED.matcher(line).find() && opens == closes) {
            sites.add(repositoryRoot().relativize(file) + ":" + (i + 1));
          }
        }
      }
    }

    assertTrue("this test walks app/src/test/java; finding no deletions at all means the pattern "
        + "has stopped matching and it is asserting nothing", !sites.isEmpty() || KNOWN == 0);
    assertTrue("a test now deletes a contact and discards whether the deletion reached disk. A "
            + "deletion whose write fails is rolled back wholesale, so every assertion after it "
            + "reads the state from before it. Install TestStores.writesLand() and assert the "
            + "return value, or - if the test is about the failure arm - say so at the call. Found "
            + sites.size() + ", and " + KNOWN + " are known:\n" + String.join("\n", sites),
        sites.size() <= KNOWN);
  }
}
