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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A disabled test is invisible: the suite still says green, and the count still goes up.
 *
 * <p>This branch reports a test total after every change, and REVIVAL.md quoted "1 permanently
 * skipped" while the runner had been reporting <b>4</b> for some time. The other three turned out to
 * be deliberate and well argued — one vacuous, two superseded — but that is a fact discovered by
 * going to look, not one the suite was telling anyone. An {@code @Ignore} added for a good reason
 * and an {@code @Ignore} added to get a build green look identical from the outside.
 *
 * <p>So every one is listed here with what it is. A new one fails this test until somebody writes it
 * down, which is the same treatment {@code EveryStripFieldIsClassifiedTest} gives strip state and for
 * the same reason: the decision is not the problem, skipping the decision is.
 */
public class IgnoredTestsAreAccountedForTest {

  /**
   * Every deliberately disabled test, and why.
   *
   * <p>Named by method, because that is what the reason attaches to.
   */
  private static final Set<String> ACCOUNTED_FOR = new HashSet<>(Arrays.asList(
      // A generator, not a test: it writes the committed 0.1.5-shaped fixtures on demand. Running
      // it during an ordinary suite would rewrite the very files that prove Jackson can still read
      // a pre-upgrade store.
      "writeProtocolStoreFixture",
      // Vacuous rather than failing: the harness holds the IME as a field and LatinIME.onDestroy
      // never nulls mE2EEStripView, so the strip stays reachable through the test itself. Kept as
      // the record of why the destroy path's reachability claim rests on r1 instead.
      "r1cTheStripDiscardedByOnDestroyMustBecomeUnreachable",
      // Rejected: the residue is real but sits on an object nothing can reach after a rebuild,
      // which is true of every freed object. Justified by r0 below.
      "r2TheDiscardedStripMustNotKeepWhoTheUserTalksTo",
      "r3TheDiscardedStripMustNotKeepNamingTheChosenContact",
      // Measurements, not tests. They produce the raise-cost table in REVIVAL.md's chat-log entry,
      // and they are in the tree so those numbers can be re-derived instead of taken on trust - a
      // review pointed out that nothing in the repo could reproduce them. Not asserted, because a
      // timing threshold tight enough to mean something is flaky and one loose enough to be stable
      // means nothing. The behaviour they describe is guarded structurally by
      // ChatLogLoadsLazilyTest: the log is not read to load an account, and a raise leaves its
      // stored bytes byte-identical.
      "raiseCost",
      "fileLayerCost"));

  /**
   * The test those two rejections rest on.
   *
   * <p>Both {@code @Ignore} reasons say, in words, "r0 shows the discarded strip is unreachable".
   * That is a live dependency written in prose: delete or disable r0 and two security assertions
   * stay rejected on a basis that no longer exists, silently.
   */
  private static final String JUSTIFICATION = "r0TheDiscardedStripMustBecomeUnreachable";

  private static Path testSources() {
    for (final String candidate : new String[] {"src/test/java", "app/src/test/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the test source tree");
  }

  private static List<Path> sources() throws IOException {
    try (Stream<Path> walk = Files.walk(testSources())) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /**
   * Every test the runner will not execute, by whichever mechanism disables it.
   *
   * <p>Two mechanisms, and the first version of this scan only knew about one. {@code @Ignore} is
   * the visible kind; {@code Assume.assumeTrue} is the quiet kind — the test runs, gives up, and is
   * reported as skipped exactly like an annotated one. The fixture generator uses the second, which
   * is why a scan for annotations alone found three of the four and declared the fourth list entry
   * stale.
   *
   * <p>This file is excluded from its own scan: it necessarily contains the word {@code @Ignore} in
   * prose, and counting those made every method here look disabled.
   */
  private static List<String> disabledMethods() throws IOException {
    final Pattern method = Pattern.compile("public\\s+void\\s+(\\w+)\\s*\\(");
    final List<String> found = new ArrayList<>();

    for (final Path source : sources()) {
      if (source.getFileName().toString().equals("IgnoredTestsAreAccountedForTest.java")) continue;
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

      for (final String marker : new String[] {"@Ignore", "assumeTrue(", "assumeFalse("}) {
        int from = 0;
        while (true) {
          final int at = text.indexOf(marker, from);
          if (at < 0) break;
          from = at + 1;

          // Only a real annotation or call, never the word inside a comment or a string literal.
          final int lineStart = text.lastIndexOf('\n', at) + 1;
          final String before = text.substring(lineStart, at).trim();
          if (marker.equals("@Ignore")) {
            if (!before.isEmpty()) continue;
            // The annotation disables the first method declared after it.
            final Matcher next = method.matcher(text);
            if (next.find(at)) found.add(next.group(1));
          } else {
            if (before.startsWith("//") || before.startsWith("*")) continue;
            // An assume disables the method it sits inside: the last one declared above it.
            final Matcher m = method.matcher(text.substring(0, at));
            String enclosing = null;
            while (m.find()) enclosing = m.group(1);
            if (enclosing != null) found.add(enclosing);
          }
        }
      }
    }
    return found;
  }

  @Test
  public void everyDisabledTestIsWrittenDown() throws IOException {
    final List<String> ignored = disabledMethods();

    assertTrue("this test scans for @Ignore and found none - the four known ones are still in the "
        + "tree, so the scan has stopped matching and this is now vacuous", ignored.size() >= 4);

    final List<String> unaccounted = ignored.stream()
        .filter(name -> !ACCOUNTED_FOR.contains(name))
        .collect(Collectors.toList());

    assertEquals("a test has been disabled without being written down here. A disabled test is "
        + "invisible - the suite stays green and the total still counts it - so say what it is and "
        + "why, the way the existing four do:\n" + String.join("\n", unaccounted),
        0, unaccounted.size());
  }

  /** And the list cannot outlive the tests it excuses. */
  @Test
  public void thelistNamesOnlyTestsThatAreStillDisabled() throws IOException {
    final Set<String> ignored = new HashSet<>(disabledMethods());
    final List<String> stale = ACCOUNTED_FOR.stream()
        .filter(name -> !ignored.contains(name))
        .collect(Collectors.toList());

    assertEquals("this list excuses tests that are no longer disabled. Remove them rather than "
        + "leaving them to suggest coverage is missing that is not:\n" + String.join("\n", stale),
        0, stale.size());
  }

  /**
   * The test two of those rejections cite must still exist, and must still run.
   *
   * <p>The dependency is written in prose inside an {@code @Ignore} string, which nothing checks.
   * If r0 is deleted, renamed, or disabled itself, the argument for leaving r2 and r3 rejected
   * evaporates and the only trace is a sentence describing a test that is not there.
   */
  @Test
  public void thejustificationForTheRejectedAssertionsStillRuns() throws IOException {
    boolean declared = false;
    for (final Path source : sources()) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      if (text.contains("public void " + JUSTIFICATION + "(")) declared = true;
    }

    assertTrue("two @Ignore reasons say r0 proves the discarded strip is unreachable, and " 
        + JUSTIFICATION + " is no longer declared anywhere. Those rejections now rest on a test "
        + "that does not exist", declared);
    assertTrue("and it must not itself be disabled, for the same reason",
        !disabledMethods().contains(JUSTIFICATION));
  }
}
