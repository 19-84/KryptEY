package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    final String source = new String(Files.readAllBytes(repositoryRoot().resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8)
        // Comments stripped, so a site named in prose is not counted as one - which is exactly the
        // confusion this test exists to end.
        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");

    int found = 0;
    int from = 0;
    while (true) {
      final int at = source.indexOf("setShouldUseOtherIC(false)", from);
      if (at < 0) break;
      found++;
      from = at + 1;
    }

    assertEquals("the number of places that hand the user's typing back to the host application "
        + "has changed. That is the security-relevant enumeration on this surface: lowering the "
        + "redirect points the next keystroke at the messenger's own field. Decide what the new "
        + "one is, say so beside the others in the focus listener's comment, and update this "
        + "number - four comments in this file once said 'two', and the site they omitted was "
        + "lowering the redirect with the user's draft still on screen", LOWERING_SITES, found);
  }
}
