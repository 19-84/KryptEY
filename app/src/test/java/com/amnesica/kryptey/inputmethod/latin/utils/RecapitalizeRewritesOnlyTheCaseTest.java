package com.amnesica.kryptey.inputmethod.latin.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recapitalisation rewrites the user's selected text, so it must change only its case.
 *
 * <p>This is the buffer {@code InputLogic.performRecapitalization} deletes and re-commits, and that
 * method's own comment says it "can hold up to MAX_CHARACTERS_FOR_RECAPITALIZATION characters of
 * whatever the user had selected - including decrypted plaintext". A defect here does not lose a
 * capital letter; it rewrites a message the user is about to send.
 *
 * <p>{@code latin/utils/} had no tests at all. Three sweeps answered the security question about
 * this package - nothing logs typed text - and nothing had asked whether the code that edits typed
 * text edits it correctly.
 *
 * <p><b>Locale.ROOT throughout, and that is a statement about the test rather than about the
 * class.</b> Case mapping is not reversible in general - German {@code ß} upper-cases to
 * {@code SS}, which lower-cases to {@code ss} - so "only the case changed" is false for some
 * strings under any locale, through no fault of this code. The fixtures stay inside the range where
 * the property is well defined, and say so, rather than asserting something Unicode does not
 * promise.
 */
public class RecapitalizeRewritesOnlyTheCaseTest {

  private static final int ROTATIONS = 8;   // comfortably more than one full cycle

  private static RecapitalizeStatus started(final String text) {
    final RecapitalizeStatus status = new RecapitalizeStatus();
    status.start(0, text.length(), text, Locale.ROOT);
    return status;
  }

  /** The premise: rotating actually changes the string, so the properties are not vacuous. */
  @Test
  public void thepremiseThatRotatingChangesSomething() {
    final RecapitalizeStatus status = started("hello there");
    final String first = status.getRecapitalizedString();
    status.rotate();

    assertNotEquals("if rotation never changed the string, every property below would hold on a "
        + "class that does nothing", first, status.getRecapitalizedString());
  }

  /**
   * Every rotation is the same text in a different case - never different text.
   *
   * <p>Compared by lower-casing both sides: that is what "only the case changed" means, and it is
   * the assertion that fails if a rotation ever drops, duplicates or reorders a character.
   */
  @Test
  public void everyRotationPreservesTheTextItself() {
    final String original = "The quick brown fox, 42 times! naïve";
    final RecapitalizeStatus status = started(original);
    final String expected = original.toLowerCase(Locale.ROOT);

    for (int i = 0; i < ROTATIONS; i++) {
      status.rotate();
      assertEquals("rotation " + i + " changed the text, not just its case. This buffer is the "
              + "user's selected text and it is about to be deleted and re-committed",
          expected, status.getRecapitalizedString().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * And the cursor span always matches the string that will be written into it.
   *
   * <p>This is the arithmetic {@code performRecapitalization} acts on: it deletes
   * {@code numCharsSelected} characters and commits the recapitalised string. If the span and the
   * string ever disagree, the delete takes the wrong number of characters from the field - the same
   * class of defect as the refused-collapse guard on that method, which took three characters from
   * before the selection.
   */
  @Test
  public void thecursorSpanAlwaysMatchesTheStringItDescribes() {
    // "straße", because case mapping must CHANGE THE LENGTH here or this case cannot fail.
    //
    // The first version used ASCII, where upper- and lower-casing preserve length - so a mutant
    // computing the span from the old string instead of the new one produced identical numbers and
    // the test passed. Measured: that mutant was applied and this case stayed green. German sharp s
    // upper-cases to SS, six characters becoming seven, which is the only reason the two
    // computations can be told apart.
    //
    // It is deliberately NOT used in everyRotationPreservesTheTextItself above: SS lower-cases to
    // "ss", not back to "ß", so case mapping is not reversible for it and that property is
    // genuinely false for this string through no fault of this class.
    final RecapitalizeStatus status = started("straße Case Text here");

    for (int i = 0; i < ROTATIONS; i++) {
      status.rotate();
      assertEquals("after rotation " + i + " the cursor span and the string disagree, so the "
              + "delete that precedes the commit would take the wrong number of characters",
          status.getRecapitalizedString().length(),
          status.getNewCursorEnd() - status.getNewCursorStart());
    }
  }

  /**
   * Rotation is a cycle: it returns to where it started rather than wandering.
   *
   * <p>Pressed repeatedly, the key must walk a closed set of renderings. A rotation that never
   * came back would leave the user unable to restore what they typed by pressing the same key
   * again, which is the whole affordance.
   */
  @Test
  public void rotationReturnsToWhereItStarted() {
    final RecapitalizeStatus status = started("hello there");
    final Set<String> seen = new HashSet<>();

    for (int i = 0; i < ROTATIONS; i++) {
      status.rotate();
      seen.add(status.getRecapitalizedString());
    }

    assertTrue("rotation must walk a small closed set of renderings, not an open-ended one; it "
        + "produced " + seen.size() + ": " + seen, seen.size() <= 4);
    assertTrue("and the original text must be one of them, or the user cannot get back to what "
        + "they typed: " + seen, seen.contains("hello there"));
  }

  /**
   * {@code trim()} removes whitespace and leaves the span describing what is left.
   *
   * <p>Trimming moves both ends, so it is the operation most able to leave the span and the string
   * disagreeing - and it runs before the rotations above.
   */
  @Test
  public void trimmingLeavesTheSpanDescribingWhatRemains() {
    final RecapitalizeStatus status = started("   padded text   ");
    status.trim();

    assertEquals("the trimmed text must be what is left after the whitespace", "padded text",
        status.getRecapitalizedString());
    assertEquals("and the span must describe exactly that",
        status.getRecapitalizedString().length(),
        status.getNewCursorEnd() - status.getNewCursorStart());

    status.rotate();
    assertEquals("which must still hold once a rotation follows the trim",
        status.getRecapitalizedString().length(),
        status.getNewCursorEnd() - status.getNewCursorStart());
  }
}
