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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A file a test reads that Gradle does not track makes the test replay its last result.
 *
 * <p>This has produced a confidently wrong answer on this branch <b>six times</b>. The task stays
 * UP-TO-DATE, the runner reports the previous run, and a control against the changed file comes back
 * "survived" — which is the direction that invents findings, because a survivor is the interesting
 * outcome. Twice it did worse than corrupt a control: the test itself was useless in ordinary use,
 * since anyone editing the file would get a green replay.
 *
 * <p>Each occurrence was fixed by declaring one more input. This closes the pattern instead: a test
 * that reads a repo path Gradle has no reason to know about fails until someone declares it.
 *
 * <p>Sources under {@code src/main/java}, {@code src/test/java} and {@code src/main/res} are already
 * inputs by virtue of being compiled or processed, so they are exempt — and that exemption is the
 * reason the trap is subtle, since the scanner tests that read Java sources have always worked
 * correctly and gave no hint that the markdown ones would not.
 */
public class EveryFileATestReadsIsATaskInputTest {

  /** Paths Gradle already tracks because it compiles or processes them. */
  private static final String[] ALREADY_TRACKED = {
      "src/main/java", "src/test/java", "src/main/res", "src/test/resources",
      "app/src/main/java", "app/src/test/java", "app/src/main/res", "app/src/test/resources",
  };

  private static Path testSources() {
    for (final String candidate : new String[] {"src/test/java", "app/src/test/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the test source tree");
  }

  private static String buildScript() throws IOException {
    for (final String candidate : new String[] {"build.gradle", "app/build.gradle"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("could not locate the module build script");
  }

  /**
   * Every repo path a test names as a string literal.
   *
   * <p>Matched on the literal rather than on {@code Paths.get("...")}, because this codebase almost
   * never writes that: the idiom here is a candidate array — {@code {"REVIVAL.md", "../REVIVAL.md"}}
   * — tried in turn, because the working directory is the module rather than the repository root.
   * The first version of this scan looked for the call form, found two literals in the whole tree,
   * and was caught by its own vacuity assertion.
   */
  private static Set<String> pathsTestsRead() throws IOException {
    final Pattern literal = Pattern.compile(
        "\"((?:\\.\\./)?(?:app/)?(?:src/[a-zA-Z]+/[a-zA-Z/]+|tools/[A-Za-z0-9_.-]+"
            + "|[A-Za-z0-9_./-]+"
            + "\\.(?:md|gradle|txt|xml|yml|yaml|pro|cfg|properties)))\"");
    final Set<String> found = new LinkedHashSet<>();

    try (Stream<Path> walk = Files.walk(testSources())) {
      for (final Path source : walk.filter(p -> p.toString().endsWith(".java"))
          .collect(Collectors.toList())) {
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final Matcher matcher = literal.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
      }
    }
    return found;
  }

  private static boolean alreadyTracked(final String path) {
    final String normalised = path.startsWith("../") ? path.substring(3) : path;
    for (final String tracked : ALREADY_TRACKED) {
      if (normalised.equals(tracked) || normalised.startsWith(tracked + "/")) return true;
    }
    return false;
  }

  @Test
  public void everyRepoFileATestReadsIsDeclaredAsATaskInput() throws IOException {
    final Set<String> read = pathsTestsRead();
    final String script = buildScript();

    assertTrue("this test scans for Paths.get literals and found almost none; it has stopped "
        + "testing anything", read.size() >= 5);

    final List<String> undeclared = new ArrayList<>();
    for (final String path : read) {
      if (alreadyTracked(path)) continue;
      // Only real repo paths. A bare name like "registration-id.txt" is a CLASSPATH fixture loaded
      // through getResourceAsStream, not a file on disk - test resources are processed into the
      // runtime and tracked already. Filtering on existence rather than on the shape of the string
      // means this stays right when either set changes.
      if (!Files.exists(Paths.get(path)) && !Files.exists(Paths.get("../" + path))) continue;
      // A test usually tries both the module-relative and repo-relative spelling, because the
      // working directory is the module. Either spelling being declared is enough.
      final String bare = path.startsWith("../") ? path.substring(3) : path;
      final String withApp = bare.startsWith("app/") ? bare.substring(4) : bare;
      final boolean declared = script.contains("inputs.file('" + path + "')")
          || script.contains("inputs.dir('" + path + "')")
          || script.contains("inputs.file('../" + bare + "')")
          || script.contains("inputs.dir('../" + bare + "')")
          || script.contains("inputs.file('" + withApp + "')")
          || script.contains("inputs.dir('" + withApp + "')");
      if (!declared) undeclared.add(path);
    }

    assertEquals("a test reads this at run time and Gradle does not track it, so the task will stay "
        + "UP-TO-DATE when it changes and replay the previous result - the test passes without "
        + "looking, and a control against it reports \"survived\". Declare it beside the others in "
        + "the testDebugUnitTest block:\n" + String.join("\n", undeclared), 0, undeclared.size());
  }

  /** And a declared input must still be something a test reads. */
  @Test
  public void thedeclaredInputsAreAllStillRead() throws IOException {
    final Pattern declared = Pattern.compile("inputs\\.(?:file|dir)\\('([^']+)'\\)");
    final Matcher matcher = declared.matcher(buildScript());
    final Set<String> read = pathsTestsRead();

    final List<String> unused = new ArrayList<>();
    while (matcher.find()) {
      final String path = matcher.group(1);
      final String bare = path.startsWith("../") ? path.substring(3) : path;
      boolean used = false;
      for (final String candidate : read) {
        final String candidateBare = candidate.startsWith("../") ? candidate.substring(3) : candidate;
        final String candidateNoApp =
            candidateBare.startsWith("app/") ? candidateBare.substring(4) : candidateBare;
        if (candidateBare.equals(bare) || candidateNoApp.equals(bare)) used = true;
      }
      if (!used) unused.add(path);
    }

    assertEquals("these are declared as task inputs but no test reads them. An input nobody needs "
        + "costs an up-to-date check on every build and suggests coverage that is not there:\n"
        + String.join("\n", unused), 0, unused.size());
  }
}
