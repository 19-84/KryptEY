package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The duplicate-name invariant, asserted by <b>drawing</b> rather than by comparing strings.
 *
 * <p>A string comparison cannot see this class of bug. Two names that differ by an invisible
 * character are different strings but identical pixels, and it is the pixels the user compares. A
 * sweep over rendered strings therefore passed while U+2065, the tag characters and the unassigned
 * Default_Ignorable ranges all rendered a row pixel-identically to another contact's while folding
 * to a different key - so no duplicate warning fired and the two rows were indistinguishable.
 *
 * <p>It also cannot see reordering. A leading U+202E with the name written backwards is a different
 * string in logical order and the same picture in visual order.
 *
 * <p>So: for every code point that does NOT fold to the baseline, draw it and compare the ink. If it
 * paints the same picture, that is a name a user cannot tell apart and the app will not warn about.
 * Only the non-matching candidates are drawn, which keeps this to a few hundred renders rather than
 * a million.
 *
 * <p>Eight review rounds found a gap in a hand-written character list. This asserts the property
 * instead.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RenderedNameAgreementTest {

  private static final String BASELINE = "Alice";
  private static final int W = 420;
  private static final int H = 72;

  private Account account;
  private SignalProtocolAddress elsewhere;
  private Paint paint;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact(BASELINE, "", "peer-uuid", 7, false));
    account.setContactList(list);

    paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTextSize(48f);
    paint.setColor(Color.BLACK);
  }

  /** Draws the name exactly as the contact row would, and returns the pixels. */
  private int[] pixels(final String name) {
    final String shown = SignalProtocolMain.sanitizeForBanner(name);
    final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);
    canvas.drawText(shown, 4f, 52f, paint);

    final int[] out = new int[W * H];
    bitmap.getPixels(out, 0, W, 0, 0, W, H);
    return out;
  }

  private static boolean samePicture(final int[] a, final int[] b) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  /**
   * Whether the test font can actually draw this code point.
   *
   * <p>Without this the sweep reports font-coverage artifacts as security findings. A character the
   * font has no glyph for renders as nothing under Robolectric, but on a device with a fuller font
   * it renders as a glyph, and where no font has it Android draws tofu - visible either way. A
   * leading combining mark is the same story: no glyph here, a dotted circle on a real device.
   *
   * <p>So a "renders as nothing" result is only meaningful for characters the renderer could have
   * drawn. Skipping the rest is a real limitation of measuring in Robolectric rather than on
   * hardware, and it is better stated than silently absorbed - these are exactly the characters an
   * instrumentation run would settle.
   */
  private boolean canRender(final String text) {
    return paint.hasGlyph(text);
  }

  private boolean matchesBaseline(final String name) {
    return SignalProtocolMain.hasContactWithSameDisplayName(name, "", elsewhere);
  }

  @Test
  public void noCodePointPaintsTheBaselineWithoutMatchingIt() {
    final int[] baselinePixels = pixels(BASELINE);
    final List<String> divergences = new ArrayList<>();

    for (int cp = 0; cp <= 0xFFFF; cp++) {
      if (Character.isSurrogate((char) cp)) continue;

      // Three positions, because they are not equivalent. A character appended after the name can
      // only add to the end; one inserted between two letters can also join or separate words; one
      // at the front can reorder everything that follows. A sweep that only appends is blind to the
      // middle and leading cases, and the middle is where a separator has to sit to make
      // "Bob<X>Jones" read as two words.
      // Only meaningful if the renderer could have drawn it - see canRender.
      if (!canRender(String.valueOf((char) cp))) continue;

      for (final String candidate : new String[] {
          BASELINE + ((char) cp),
          BASELINE.substring(0, 2) + ((char) cp) + BASELINE.substring(2),
          String.valueOf((char) cp) + BASELINE,
      }) {
        if (matchesBaseline(candidate)) continue;   // folds together: nothing to check

        if (samePicture(pixels(candidate), baselinePixels)) {
          divergences.add(String.format("U+%04X paints the baseline exactly but does not match it",
              cp));
          break;
        }
      }
    }

    assertTrue("code points that draw the same picture as the baseline while folding to a "
            + "different key - each one is a row the user cannot tell apart and a warning that "
            + "will not fire:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(25, divergences.size())))
            + (divergences.size() > 25
                ? "\n  ... and " + (divergences.size() - 25) + " more" : ""),
        divergences.isEmpty());
  }

  /** The same above the BMP, where the ignorable ranges that were missed actually live. */
  @Test
  public void noSupplementaryCodePointPaintsTheBaselineWithoutMatchingIt() {
    final int[] baselinePixels = pixels(BASELINE);
    final List<String> divergences = new ArrayList<>();

    final int[][] ranges = {{0xE0000, 0xE0FFF}, {0x1D400, 0x1D4FF}, {0x1F100, 0x1F1FF}};
    for (final int[] range : ranges) {
      for (int cp = range[0]; cp <= range[1]; cp++) {
        final String candidate = BASELINE + new String(Character.toChars(cp));
        if (matchesBaseline(candidate)) continue;

        if (samePicture(pixels(candidate), baselinePixels)) {
          divergences.add(String.format("U+%05X paints the baseline exactly but does not match it",
              cp));
        }
      }
    }

    assertTrue("supplementary code points painting the baseline without matching:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(25, divergences.size())))
            + (divergences.size() > 25
                ? "\n  ... and " + (divergences.size() - 25) + " more" : ""),
        divergences.isEmpty());
  }

  /** Reordering: a right-to-left override paints a reversed name as the forward one. */
  @Test
  public void aReversedNameUnderAnOverrideDoesNotPaintTheBaseline() {
    final int[] baselinePixels = pixels(BASELINE);

    final StringBuilder reversed = new StringBuilder(BASELINE).reverse();
    for (final int control : new int[] {0x202E, 0x202D, 0x202B, 0x202A, 0x2067, 0x2066}) {
      final String candidate = ((char) control) + reversed.toString();
      if (matchesBaseline(candidate)) continue;

      assertTrue(String.format("U+%04X reverses the name into the baseline's picture without "
              + "matching it", control),
          !samePicture(pixels(candidate), baselinePixels));
    }
  }

  /**
   * Sequences, not just single characters.
   *
   * <p>A single-character sweep cannot see a combination whose parts are each handled correctly but
   * whose composition is not - two invisibles that cancel a length check, an invisible next to a
   * confusable, a bidi control paired with its terminator. These are the shapes an attacker would
   * actually try after single characters stopped working.
   */
  @Test
  public void noSequencePaintsTheBaselineWithoutMatchingIt() {
    final int[] baselinePixels = pixels(BASELINE);
    final List<String> divergences = new ArrayList<>();

    final char[] invisible = {0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x034F, 0x3164};
    final char[] controls = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2069};

    final List<String> candidates = new ArrayList<>();
    for (final char a : invisible) {
      for (final char b : invisible) {
        candidates.add(BASELINE + a + b);
        candidates.add(String.valueOf(a) + BASELINE + b);
        candidates.add(BASELINE.substring(0, 2) + a + b + BASELINE.substring(2));
      }
      for (final char c : controls) {
        candidates.add(String.valueOf(c) + BASELINE + a);
        candidates.add(String.valueOf(a) + ((char) 0x202E)
            + new StringBuilder(BASELINE).reverse() + c);
      }
    }

    for (final String candidate : candidates) {
      if (matchesBaseline(candidate)) continue;
      if (samePicture(pixels(candidate), baselinePixels)) {
        divergences.add("sequence paints the baseline but does not match: " + escape(candidate));
      }
    }

    assertTrue("character sequences that draw the baseline's picture while folding to a different "
            + "key:\n  "
            + String.join("\n  ", divergences.subList(0, Math.min(15, divergences.size())))
            + (divergences.size() > 15
                ? "\n  ... and " + (divergences.size() - 15) + " more" : ""),
        divergences.isEmpty());
  }

  private static String escape(final String value) {
    final StringBuilder out = new StringBuilder();
    for (final char c : value.toCharArray()) {
      if (c < 0x20 || c > 0x7E) out.append(String.format("U+%04X", (int) c));
      else out.append(c);
    }
    return out.toString();
  }

  // ------------------------------------------------- the property, not the picture

  /**
   * The rule that decides how a character must fold, stated as a measurement.
   *
   * <p>Pixel equality is the wrong predicate for this class. A character that advances 15px where a
   * space advances 12 shifts everything after it by three pixels, so the two pictures differ by a
   * thousand pixels while being humanly identical - and an exact ARGB comparison calls that "not the
   * same" and moves on. That is how 25 C0 controls stayed live through a sweep that was looking
   * directly at them.
   *
   * <p>The decidable rule is: <b>zero ink and a positive advance means it draws a gap, so it must
   * fold to a space; zero ink and no advance means it draws nothing, so it must fold away.</b> Both
   * terms are measurable with the Skia already in this harness, so this asserts the rule for every
   * code point rather than checking a list of characters someone thought of.
   */
  @Test
  public void everyBlankFoldsToASpaceAndEveryInvisibleFoldsAway() {
    final List<String> wrong = new ArrayList<>();
    final float spaceAdvance = paint.measureText(" ");

    for (int cp = 0; cp <= 0xFFFF; cp++) {
      if (Character.isSurrogate((char) cp)) continue;
      final String ch = String.valueOf((char) cp);
      if (!canRender(ch)) continue;

      // Combining marks are excluded, and the reason is a limit of the measurement rather than a
      // gap in the rule. Measured alone they have no base to attach to, so they show zero ink and
      // zero advance - but inside a name they modify the preceding glyph and change its appearance.
      // The property below only decides characters whose rendering is context-free. Marks are
      // handled by the leading-mark fold and by the Indic non-collision test instead.
      final int markType = Character.getType(cp);
      if (markType == Character.NON_SPACING_MARK || markType == Character.COMBINING_SPACING_MARK
          || markType == Character.ENCLOSING_MARK) {
        continue;
      }

      if (inkPixels(ch) != 0) continue;               // draws something: not our business
      final float advance = paint.measureText(ch);

      // How the fold actually treats it, observed rather than assumed: does inserting it between
      // two words leave a word break, or join them?
      final boolean foldsToSpace = SignalProtocolMain.hasContactWithSameDisplayName(
          "Bob" + ch + "Jones", "", elsewhere2("Bob Jones"));
      final boolean foldsAway = SignalProtocolMain.hasContactWithSameDisplayName(
          "Bob" + ch + "Jones", "", elsewhere2("BobJones"));

      if (advance > 0.5f && !foldsToSpace) {
        wrong.add(String.format("U+%04X draws a %.0fpx gap (space is %.0f) but does not fold to a "
            + "space", cp, advance, spaceAdvance));
      } else if (advance <= 0.5f && !foldsAway) {
        wrong.add(String.format("U+%04X draws nothing at all but does not fold away", cp));
      }
    }

    assertTrue("code points whose fold disagrees with what they draw:\n  "
            + String.join("\n  ", wrong.subList(0, Math.min(25, wrong.size())))
            + (wrong.size() > 25 ? "\n  ... and " + (wrong.size() - 25) + " more" : ""),
        wrong.isEmpty());
  }

  /** Re-seeds the baseline contact and returns an address that is not it. */
  private SignalProtocolAddress elsewhere2(final String baseline) {
    final ArrayList<Contact> list = new ArrayList<>();
    list.add(new Contact(baseline, "", "peer-uuid", 7, false));
    account.setContactList(list);
    return elsewhere;
  }

  private int inkPixels(final String text) {
    final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);
    canvas.drawText(text, 4f, 52f, paint);

    final int[] px = new int[W * H];
    bitmap.getPixels(px, 0, W, 0, 0, W, H);
    int ink = 0;
    for (final int p : px) {
      if (p != Color.WHITE) ink++;
    }
    return ink;
  }

  /**
   * The same rule, measured <em>in context</em> rather than on the character alone.
   *
   * <p>Ink and advance are not always properties of a character by itself. A joiner has no ink alone
   * and changes how its neighbours shape; a variation selector is invisible alone and alters the
   * glyph before it; contextual forms differ between isolated and medial position. So a rule decided
   * from isolated measurement can be right about the character and wrong about the name.
   *
   * <p>This measures what the user actually sees: render "Bob<c>Jones" and compare it against the
   * two things it could look like - "Bob Jones" if the character draws a gap, "BobJones" if it draws
   * nothing. Whichever picture it matches, the fold must agree. A character that renders as neither
   * is visible, and not this rule's business.
   */
  @Test
  public void theFoldAgreesWithWhatEachCharacterDrawsInContext() {
    final int[] spaced = pixels("Bob Jones");
    final int[] joined = pixels("BobJones");
    final List<String> wrong = new ArrayList<>();

    for (int cp = 0; cp <= 0xFFFF; cp++) {
      if (Character.isSurrogate((char) cp)) continue;
      final String ch = String.valueOf((char) cp);
      if (!canRender(ch)) continue;

      final String candidate = "Bob" + ch + "Jones";
      final int[] drawn = pixels(candidate);

      final boolean looksSpaced = samePicture(drawn, spaced);
      final boolean looksJoined = samePicture(drawn, joined);
      if (!looksSpaced && !looksJoined) continue;   // visible: not this rule's business

      if (looksSpaced && !SignalProtocolMain.hasContactWithSameDisplayName(
          candidate, "", elsewhere2("Bob Jones"))) {
        wrong.add(String.format("U+%04X draws \"Bob Jones\" but does not fold like it", cp));
      }
      if (looksJoined && !SignalProtocolMain.hasContactWithSameDisplayName(
          candidate, "", elsewhere2("BobJones"))) {
        wrong.add(String.format("U+%04X draws \"BobJones\" but does not fold like it", cp));
      }
    }

    assertTrue("characters whose fold disagrees with what they draw between two words - measured "
            + "in context, so shaping and joining are included:\n  "
            + String.join("\n  ", wrong.subList(0, Math.min(25, wrong.size())))
            + (wrong.size() > 25 ? "\n  ... and " + (wrong.size() - 25) + " more" : ""),
        wrong.isEmpty());
  }



  /**
   * Over-folds that are known, deliberate, or artefacts of the test font - written down rather than
   * hidden behind a widened filter, because a filter that removes them also removes whatever lands
   * in the same range next. The C1 controls sat behind exactly such a filter while they were wrong.
   *
   * <p>This is checked as a subset, not an equality: the second group is a property of the font
   * Robolectric happens to load, so a font change may legitimately empty it. Any over-fold NOT
   * listed here still fails.
   */
  private static final Map<Integer, String> OVER_FOLD_EXCEPTIONS = Map.of(
      // Deliberate. Line separators by definition, folded to a space. In this font they paint a
      // tofu box instead of breaking a line, which is what makes them show up here at all. A
      // renderer that honours them would break the banner into two lines, and a second line of
      // attacker-chosen text below the warning is a worse failure than a cosmetic over-fold.
      0x0085, "NEXT LINE - line separator, folded to a space deliberately",
      0x2028, "LINE SEPARATOR - folded to a space deliberately",
      0x2029, "PARAGRAPH SEPARATOR - folded to a space deliberately",
      // Artefacts. These are Default_Ignorable or interlinear-annotation format characters that
      // conforming fonts render as nothing; deleting them is correct. The Robolectric font simply
      // has no support for them and falls back to tofu, so they measure as ink here and nowhere a
      // user would see. Unlike C1, which is unassigned to any glyph in every font, these have a
      // defined invisible rendering.
      0x180F, "MONGOLIAN FREE VARIATION SELECTOR FOUR - default-ignorable, tofu in the test font",
      0xFFF9, "INTERLINEAR ANNOTATION ANCHOR - format character, tofu in the test font",
      0xFFFA, "INTERLINEAR ANNOTATION SEPARATOR - format character, tofu in the test font",
      0xFFFB, "INTERLINEAR ANNOTATION TERMINATOR - format character, tofu in the test font");

  /**
   * The other direction: a character the user can SEE must not be folded away.
   *
   * <p>Every sweep above asks whether two names that look alike fold alike - the direction that
   * suppresses a warning. This asks the converse: does a name containing a plainly visible character
   * fold together with one that lacks it? That is the cries-wolf direction, and it has its own cost.
   * A duplicate warning that fires on names a reader can tell apart teaches the user to dismiss it,
   * which is the same damage as never firing.
   *
   * <p>It is also the direction that catches a fix applied too broadly. C1 controls render as a
   * visible tofu box - measured ink 349, no glyph - and were briefly folded to a space here on the
   * assumption that they behaved like C0. Nothing caught it, because every other sweep skips
   * characters that draw ink.
   */
  @Test
  public void aVisibleCharacterIsNotFoldedAway() {
    final List<String> overFolded = new ArrayList<>();

    for (int cp = 0; cp <= 0xFFFF; cp++) {
      if (Character.isSurrogate((char) cp)) continue;
      final int type = Character.getType(cp);
      if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
          || type == Character.ENCLOSING_MARK || type == Character.SPACE_SEPARATOR) {
        continue;   // context-dependent, or legitimately a space
      }

      final String ch = String.valueOf((char) cp);
      // Deliberately NOT filtered by canRender. For this direction "does the font have a glyph" is
      // the wrong question: a character with no glyph paints a tofu box, and a tofu box is ink the
      // user can see. Folding it away is an over-fold here, and on a device whose font DOES have
      // the glyph it is an over-fold there too. The filter is exactly what hid the C1 range from
      // this test while I had it wrong - it is not an oversight that it is missing.
      if (inkPixels(ch) == 0) continue;             // invisible: the other sweeps own this

      if (OVER_FOLD_EXCEPTIONS.containsKey(cp)) continue;

      // Visible. A name containing it must not fold onto one without it.
      if (SignalProtocolMain.hasContactWithSameDisplayName(
          "Bob" + ch + "Jones", "", elsewhere2("BobJones"))) {
        overFolded.add(String.format("U+%04X draws ink but folds away entirely", cp));
      }
      // ...and must not fold onto a space either. This is the shape the C1 mistake took: the
      // character stayed, but became indistinguishable from "Bob Jones". Folding away and folding
      // to a space are the same error wearing different clothes, and only one of them is caught by
      // the check above.
      if (SignalProtocolMain.hasContactWithSameDisplayName(
          "Bob" + ch + "Jones", "", elsewhere2("Bob Jones"))) {
        overFolded.add(String.format("U+%04X draws ink but folds onto a space", cp));
      }
    }

    assertTrue("visible characters folded away - each is a duplicate warning that fires on names a "
            + "reader can tell apart, which teaches the user to dismiss it:\n  "
            + String.join("\n  ", overFolded.subList(0, Math.min(25, overFolded.size())))
            + (overFolded.size() > 25
                ? "\n  ... and " + (overFolded.size() - 25) + " more" : ""),
        overFolded.isEmpty());
  }
}
