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

    for (int cp = 1; cp <= 0xFFFF; cp++) {
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
}
