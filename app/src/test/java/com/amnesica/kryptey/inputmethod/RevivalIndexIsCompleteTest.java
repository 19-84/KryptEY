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

/**
 * REVIVAL.md's index must name every section, and only sections that exist.
 *
 * <p>The document is written in the order things were found rather than by subject. A line count
 * used to sit in this sentence and it has now been wrong three times - 1800, then ~2550, against
 * 2926 today - because prose grows and nobody updates a number in a javadoc. The section count is
 * left to the document's own header, where {@code RevivalIndexIsCompleteTest} at least checks the
 * index against the headings; a line count is checked by nothing and so is worth nothing. Written
 * rather than
 * by subject, so its sweeps are scattered and its deferred list sits between two of them. An index
 * is the fix for that — and a hand-maintained index of a document that drifts is itself a thing that
 * drifts. This branch has now corrected a stale count in that file's header twice, a coverage claim
 * that was true when written, a comment naming a method that never existed, and a "settled" decision
 * resting on a premise nobody measured.
 *
 * <p>So the index gets the same treatment as every other list here: a new section fails the build
 * until it is placed, and an entry naming a section that no longer exists fails too. Which matters
 * more than tidiness — the audits that found those four errors were possible only because someone
 * read every section, and a section missing from the index is a section nobody navigates to.
 */
public class RevivalIndexIsCompleteTest {

  private static Path document() {
    for (final String candidate : new String[] {"REVIVAL.md", "../REVIVAL.md"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) return path;
    }
    throw new IllegalStateException("could not locate REVIVAL.md from "
        + Paths.get("").toAbsolutePath());
  }

  private static String text() throws IOException {
    return new String(Files.readAllBytes(document()), StandardCharsets.UTF_8);
  }

  /** Every "## " heading, excluding the index's own. */
  private static Set<String> headings(final String doc) {
    final Set<String> found = new LinkedHashSet<>();
    for (final String line : doc.split("\n")) {
      if (line.startsWith("## ")) {
        final String title = line.substring(3).trim();
        if (!title.equals("How to read this")) found.add(title);
      }
    }
    return found;
  }

  /** Every title linked from the index block. */
  private static Set<String> indexed(final String doc) {
    final int from = doc.indexOf("## How to read this");
    assertTrue("REVIVAL.md must carry an index section", from > 0);
    final int to = doc.indexOf("\n## ", from + 1);
    final String block = to > from ? doc.substring(from, to) : doc.substring(from);

    final Set<String> found = new LinkedHashSet<>();
    for (final String line : block.split("\n")) {
      final String trimmed = line.trim();
      if (!trimmed.startsWith("- [")) continue;
      final int close = trimmed.indexOf("](");
      if (close > 3) found.add(trimmed.substring(3, close));
    }
    return found;
  }

  @Test
  public void everySectionIsInTheIndex() throws IOException {
    final String doc = text();
    final Set<String> headings = headings(doc);
    final Set<String> indexed = indexed(doc);

    assertTrue("this test reads REVIVAL.md's headings and found almost none; it has stopped "
        + "testing anything", headings.size() >= 20);

    final List<String> unlisted = new ArrayList<>();
    for (final String heading : headings) {
      if (!indexed.contains(heading)) unlisted.add(heading);
    }

    assertEquals("these sections are not in the index. The document is written in discovery order, "
        + "so an unlisted section is one a reader has no route to - and every error found in this "
        + "file was found by someone reading the section it was in:\n"
        + String.join("\n", unlisted), 0, unlisted.size());
  }

  @Test
  public void theindexNamesOnlySectionsThatExist() throws IOException {
    final String doc = text();
    final Set<String> headings = headings(doc);

    final List<String> stale = new ArrayList<>();
    for (final String entry : indexed(doc)) {
      if (!headings.contains(entry)) stale.add(entry);
    }

    assertEquals("the index links to sections that are not in the document. A renamed heading "
        + "breaks its own anchor silently, so this is the half that rots first:\n"
        + String.join("\n", stale), 0, stale.size());
  }
}
