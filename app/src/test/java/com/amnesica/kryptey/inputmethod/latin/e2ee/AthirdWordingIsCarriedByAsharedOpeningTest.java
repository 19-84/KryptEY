package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three shared-name wordings, and every reader that tells them apart keys on one opening.
 *
 * <p>{@code standingWarningIsAboutAsharedName} decides whether the banner is currently holding a
 * shared-name warning, and it decides by prefix: {@code literalPrefixOf} truncates each wording at
 * its first placeholder and asks whether the standing text starts with the result. That answer is
 * what lets the warning be <em>lowered</em> when the condition ends, and what stops the reduce
 * branch from taking down an unrelated notice.
 *
 * <p>A third wording was added — the one for two same-named rows that hold one key — and a clause
 * for it was added beside the other two, with a comment saying it "has to be recognised or it is
 * never lowered". The comment was wrong: the new wording opens with the same words as the plain
 * duplicate one, so it was already matched, and the added clause is byte-identical to the one above
 * it. Nothing was broken, and nothing was fixed.
 *
 * <p>That is a fine state to be in and a bad one to leave undeclared. The three wordings are
 * <b>load-bearing on a shared opening</b>: shorten either one so the prefixes differ and the same-key
 * wording silently stops being recognised by every prefix reader at once. The symptom would be a
 * warning that will not go down, several screens from any text anyone edited, which is the shape of
 * defect this file has spent whole rounds on.
 *
 * <p>So the collision is asserted rather than relied on. If someone wants the wordings to be
 * distinguishable, this test is where they learn that the prefix readers have to be taught first —
 * and there are more of them than the two that are easy to find, including tests that match the same
 * opening.
 */
public class AthirdWordingIsCarriedByAsharedOpeningTest {

  private static Path stripSource() {
    for (final String candidate : new String[] {
        "src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java",
        "app/src/main/java/com/amnesica/kryptey/inputmethod/latin/e2ee/E2EEStripView.java"}) {
      final Path path = Paths.get(candidate);
      if (Files.exists(path)) return path;
    }
    throw new IllegalStateException("could not locate E2EEStripView.java");
  }

  /** The declared value of a string constant, joined across its continuation lines. */
  private static String constant(final String source, final String name) {
    final Matcher declaration = Pattern.compile(
        "private final String " + name + "\\s*=\\s*(.*?);", Pattern.DOTALL).matcher(source);
    assertTrue("expected to find " + name, declaration.find());
    final StringBuilder joined = new StringBuilder();
    final Matcher piece = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(declaration.group(1));
    while (piece.find()) joined.append(piece.group(1));
    return joined.toString();
  }

  /** The same truncation the production reader performs. */
  private static String literalPrefixOf(final String template) {
    final int placeholder = template.indexOf('%');
    return placeholder < 0 ? template : template.substring(0, placeholder);
  }

  @Test
  public void thesamekeyWordingIsRecognisedOnlyBecauseItsOpeningMatchesItsSibling()
      throws IOException {
    final String source = new String(Files.readAllBytes(stripSource()), StandardCharsets.UTF_8);

    final String duplicate = literalPrefixOf(constant(source, "INFO_DUPLICATE_CONTACT_NAME"));
    final String sameKey = literalPrefixOf(constant(source, "INFO_DUPLICATE_NAME_SAME_KEY"));

    assertTrue("precondition: the openings must be non-trivial, or this asserts that two empty "
        + "strings are equal", duplicate.length() > 20);
    assertEquals("the same-key wording is recognised as a shared-name warning ONLY because its "
            + "opening is identical to the plain duplicate one - the clause added for it in "
            + "standingWarningIsAboutAsharedName is a byte-for-byte copy of the clause above it. "
            + "If you shorten either opening so these differ, that wording drops out of every "
            + "prefix reader at once and the symptom is a warning that will not go down. Teach the "
            + "readers first: standingWarningIsAboutAsharedName, the reduce branch beside it, and "
            + "the tests that match this opening",
        duplicate, sameKey);
  }
}
