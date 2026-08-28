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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A notice nothing reaches is a sentence the app has quietly stopped saying.
 *
 * <p>It has happened here. `INFO_NO_SAVED_MESSAGES` — "there are no saved messages for this
 * contact" — became unreachable when both arms of the chat-log screen were pointed at the
 * unreadable-log sentence instead. Nothing failed: the constant sat in the file looking like a
 * feature, the arm that should have used it said something false, and it took a review round to
 * notice. The same shape is one rewired branch away at any time, because these notices are the
 * whole trust surface and the branches under them are edited constantly.
 *
 * <p>So every `INFO_` constant on the strip must be referenced somewhere other than its own
 * declaration. This does not check that it is reachable at runtime — a reference inside dead code
 * would satisfy it — and it does not check that the sentence is true where it is used, which is
 * what the tests around it are for. It checks the one thing that is mechanical: that the sentence
 * still has a caller at all.
 *
 * <p>Deliberately unused constants go in {@link #DELIBERATELY_UNREFERENCED} with the argument, which
 * is the same shape every other classification list in this project uses: the point is that the
 * decision gets written down, not that the list stays empty.
 */
public class NoNoticeGoesUnsaidTest {

  /** Constants kept without a caller, and why. */
  private static final Set<String> DELIBERATELY_UNREFERENCED = new LinkedHashSet<>();

  static {
    // Nothing yet. If a notice is retired, delete it rather than parking it here; this list is for
    // a constant that must exist without a caller, and no such case has come up.
  }

  private static String source() throws IOException {
    Path here = Paths.get("").toAbsolutePath();
    while (here != null && !Files.exists(here.resolve("gradle/verification-metadata.xml"))) {
      here = here.getParent();
    }
    assertTrue("the repository root must be findable, or this test checks nothing", here != null);
    return new String(Files.readAllBytes(here.resolve(
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java")),
        StandardCharsets.UTF_8);
  }

  @Test
  public void everyNoticeOnTheStripHasAcaller() throws IOException {
    final String all = source();

    final Set<String> declared = new TreeSet<>();
    final Matcher declaration = Pattern.compile("\\b(INFO_[A-Z0-9_]+)\\s*=").matcher(all);
    while (declaration.find()) declared.add(declaration.group(1));

    assertTrue("almost no notices were found; the naming convention this scan relies on has "
        + "changed and it is checking nothing: " + declared.size(), declared.size() >= 20);

    // Everything except the declarations themselves.
    final String uses = all.replaceAll("\\bINFO_[A-Z0-9_]+\\s*=", " ");

    final List<String> unsaid = new ArrayList<>();
    for (final String notice : declared) {
      if (DELIBERATELY_UNREFERENCED.contains(notice)) continue;
      if (!Pattern.compile("\\b" + notice + "\\b").matcher(uses).find()) unsaid.add(notice);
    }

    assertEquals("a notice has no caller. That is not dead code being tidy - it means a sentence "
        + "the app used to say has quietly stopped being said, and the branch that should say it is "
        + "now saying something else or nothing. INFO_NO_SAVED_MESSAGES became unreachable exactly "
        + "this way and the arm that lost it started telling users their deleted history was still "
        + "on the device:\n" + String.join("\n", unsaid), 0, unsaid.size());
  }

  /** And the exemption list cannot name notices that no longer exist. */
  @Test
  public void theexemptionListNamesOnlyRealNotices() throws IOException {
    final String all = source();
    final List<String> stale = new ArrayList<>();
    for (final String notice : DELIBERATELY_UNREFERENCED) {
      if (!Pattern.compile("\\b" + notice + "\\s*=").matcher(all).find()) stale.add(notice);
    }
    assertEquals("the exemption list names a notice that no longer exists; remove it rather than "
        + "leave it suggesting a decision with no subject:\n" + String.join("\n", stale),
        0, stale.size());
  }
}
