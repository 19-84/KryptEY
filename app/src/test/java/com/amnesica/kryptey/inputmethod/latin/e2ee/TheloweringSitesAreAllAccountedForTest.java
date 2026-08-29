package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every place that hands the user's typing back to the host application must be written down.
 *
 * <p>The redirect is what makes typing into the compose box private. Lowering it points the next
 * keystroke at the messenger's own field, so the set of places that do it is the security-relevant
 * enumeration on this surface — and four separate comments stated that set as "two" or "three" when
 * it was four. The missing one was the add-contact screen, whose lowering ran with the user's draft
 * still rendered and only two small buttons going dark to show for it. Nobody asked what it did,
 * because three comments said it did not exist.
 *
 * <p>So the count is pinned. A fifth lowering fails this until somebody decides what it is and says
 * so beside the others — the same instrument {@code IgnoredTestsAreAccountedForTest} uses, for the
 * same reason: "remember to update the comment" has already failed here.
 *
 * <p>What this cannot do is check that the enumeration is <em>right</em>. It counts. The prose it
 * points at is what has to be true, and a reader comparing the two is the point.
 */
public class TheloweringSitesAreAllAccountedForTest {

  /** The four, by what raises the question rather than by line number. */
  private static final int LOWERING_SITES = 4;

  private static Path repositoryRoot() {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable", here != null);
    return here;
  }

  @Test
  public void theloweringSitesAreAllAccountedFor() throws IOException {
    // Every production source, not just the strip.
    //
    // This read one file, and the enumeration it pins is a property of
    // RichInputConnection.shouldUseOtherIC rather than of E2EEStripView. The mutant is one line:
    // add mInputLogic.mConnection.setShouldUseOtherIC(false) to LatinIME - onFinishInput is where
    // a fifth lowering would most plausibly be written - and this stayed green while the four
    // comments in the strip still said "four". E2EEStripView is the only caller today, so that was
    // a hollowness in what the test could see rather than a live gap; a test that can only see one
    // file cannot report that fact, it can only assume it.
    int found = 0;
    final List<String> callers = new ArrayList<>();
    final java.nio.file.Path root = repositoryRoot().resolve("app/src/main/java");
    try (Stream<java.nio.file.Path> files = Files.walk(root)) {
      for (final java.nio.file.Path file
          : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))
              ::iterator) {
        final String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            // Comments stripped, so a site named in prose is not counted as one - which is exactly
            // the confusion this test exists to end.
            .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        int from = 0;
        while (true) {
          final int at = source.indexOf("setShouldUseOtherIC(false)", from);
          if (at < 0) break;
          found++;
          if (!callers.contains(file.getFileName().toString())) {
            callers.add(file.getFileName().toString());
          }
          from = at + 1;
        }
      }
    }

    // The declaration itself never matches: it is setShouldUseOtherIC(final boolean ...), so the
    // literal (false) picks up calls only. What this still cannot see is a lowering written as
    // setShouldUseOtherIC(someVariable), and that limit is stated rather than papered over.
    assertEquals("the file that lowers the redirect has changed. It has always been E2EEStripView "
        + "alone, and a second one is a decision rather than a refactor: whoever hands the user's "
        + "typing back to the host is making the security-relevant choice this test enumerates. "
        + "Found in: " + callers, "[E2EEStripView.java]", callers.toString());

    assertEquals("the number of places that hand the user's typing back to the host application "
        + "has changed. That is the security-relevant enumeration on this surface: lowering the "
        + "redirect points the next keystroke at the messenger's own field. Decide what the new "
        + "one is, say so beside the others in the focus listener's comment, and update this "
        + "number - four comments in this file once said 'two', and the site they omitted was "
        + "lowering the redirect with the user's draft still on screen", LOWERING_SITES, found);
  }
}
