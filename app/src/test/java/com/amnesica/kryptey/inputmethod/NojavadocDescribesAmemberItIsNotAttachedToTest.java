package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A javadoc block must not sit directly on top of another javadoc block.
 *
 * <p>When two of them are adjacent with no declaration between, Java attaches only the last. Every
 * earlier block then sits above — and reads as documentation for — a member it does not describe.
 * The comment is not merely stale at that point, it is attached to the wrong thing, which is worse:
 * a stale comment is checked against its subject and found wrong, and a misattached one is checked
 * against the wrong subject and found irrelevant, so it gets skipped rather than fixed.
 *
 * <p>This has cost twice already, and the sharpest instance found by a review round was three blocks
 * stacked in this class where the top one said a caution is <em>"scoped to that contact, verifying
 * or rejecting ends it"</em> one line above a method whose own javadoc says <em>"the condition
 * cannot be resolved by anything the user does to a contact"</em>. Two adjacent comments, from the
 * same week, contradicting each other about the same method — and the one Java attached was the
 * second.
 *
 * <p>REVIVAL.md's entry on comment drift says the problem has no mechanical test. That is true of
 * drift in general — whether a sentence still describes what the code does needs a reader. It is
 * not true of this class of it, which is purely positional, and this is the check.
 *
 * <h2>Why a ratchet rather than zero</h2>
 *
 * <p>Thirty-one of these remain, and re-homing them is thirty-one chances to attach the wrong block
 * to the wrong member — the reviewer that found them said so, and recommended landing the check
 * first so it lists the sites and they move a commit at a time. So this pins the number and refuses
 * to let it grow. <b>When you fix some, lower {@link #KNOWN}.</b> It may never be raised: a new
 * stacked block is a new misattached comment, and the fix is to put the declaration where it
 * belongs, not to record another one here.
 */
public class NojavadocDescribesAmemberItIsNotAttachedToTest {

  /**
   * How many stacked blocks are known to remain. Lower this as they are fixed; never raise it.
   *
   * <p>Was thirty-six when the check was written.
   */
  private static final int KNOWN = 31;

  private static Path repositoryRoot() {
    Path here = Paths.get("").toAbsolutePath();
    // Probing the same marker the other repo-reading tests use. settings.gradle would work too,
    // but every repo file a test reads has to be a declared task input or the task stays
    // UP-TO-DATE across changes to it, and this one is already declared for them.
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    if (here == null) throw new IllegalStateException("could not find the repository root");
    return here;
  }

  @Test
  public void nojavadocBlockSitsOnTopOfAnother() throws IOException {
    final Path source = repositoryRoot().resolve("app/src/main/java");
    final List<String> sites = new ArrayList<>();

    try (Stream<Path> files = Files.walk(source)) {
      for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))
          ::iterator) {
        final List<String> lines = Files.readAllLines(file);
        for (int i = 1; i < lines.size(); i++) {
          // A javadoc opener whose immediately preceding line closes another block. Whitespace-only
          // difference is deliberate: a blank line between them is the same defect to javac.
          if (lines.get(i).trim().startsWith("/**") && lines.get(i - 1).trim().equals("*/")) {
            sites.add(repositoryRoot().relativize(file) + ":" + (i + 1));
          }
        }
      }
    }

    assertTrue("this test walks app/src/main/java; finding no java files at all means it has "
        + "stopped checking anything", !sites.isEmpty() || KNOWN == 0);
    assertTrue("a javadoc block now sits directly on top of another, so javac attaches only the "
            + "last and the ones above it document members they do not describe. Put the "
            + "declaration where it belongs rather than recording a new one here. Found "
            + sites.size() + ", and " + KNOWN + " are known:\n" + String.join("\n", sites),
        sites.size() <= KNOWN);
  }
}
