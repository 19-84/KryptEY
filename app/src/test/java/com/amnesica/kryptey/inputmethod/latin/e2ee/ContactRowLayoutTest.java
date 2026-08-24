package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The contact row must never let a name push the address tag out of view.
 *
 * <p>This has been wrong twice, and both times it was caught by someone measuring the rendered
 * layout by hand — the suite could not see it. First the tag was appended to the last-name view,
 * which is {@code wrap_content} with only a start constraint, so a long name made it measure zero
 * width and vanish. Then the fix constrained the last-name view and left the <em>first</em> name —
 * the mandatory field — unconstrained, so it spanned across the tag and overprinted it in the same
 * colour. A character cap cannot prevent either: the cap counts characters and the layout consumes
 * width, so eleven EM SPACEs beat any cap on any device.
 *
 * <p>The invariant is structural, so it can be asserted structurally: the name container must end
 * before the tag begins, at every width, for every name. That is what these check.
 */
/*
 * NATIVE graphics is load-bearing, not a detail. Under Robolectric's default (LEGACY) mode text is
 * not really measured, so every TextView reports a stub width and the geometric assertion below
 * passes no matter what the layout does - the first version of this test survived removing the very
 * constraint it exists to protect. Real Skia measurement is what makes it discriminate.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContactRowLayoutTest {

  private static final String[] HOSTILE_NAMES = {
      "Alice",
      "Maria del Carmen Fernandez",
      "███████████████",
      "Alice           ",
      "龘龘龘龘龘龘龘龘龘龘",
      "אליס שמית",
      "AliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAliceAlice",
  };

  private View inflateRow(final Context context) {
    final LayoutInflater inflater = LayoutInflater.from(context);
    return inflater.inflate(R.layout.e2ee_contact_list_element_view, null, false);
  }

  private void assertTagSurvives(final int widthDp, final String first, final String last) {
    final Context context = RuntimeEnvironment.getApplication();
    final View row = inflateRow(context);
    assertNotNull("the row must inflate", row);

    final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
    final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);
    final View tag = row.findViewById(R.id.e2ee_contact_address_tag_element);
    final View container = row.findViewById(R.id.e2ee_contact_name_container);

    assertNotNull("the tag must have its own view, not be appended to a name", tag);
    assertNotNull("both name views must live in a bounded container", container);

    firstName.setText(first);
    lastName.setText(last);
    ((TextView) tag).setText("#ab12-cd34");

    final float density = context.getResources().getDisplayMetrics().density;
    final int widthPx = (int) (widthDp * density);
    row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    row.layout(0, 0, widthPx, row.getMeasuredHeight());

    final String where = widthDp + "dp, name=\"" + first.substring(0, Math.min(12, first.length()))
        + "\"";
    assertTrue("the tag must have non-zero width at " + where, tag.getWidth() > 0);
    assertTrue("the name container must end before the tag begins at " + where,
        container.getRight() <= tag.getLeft());
    assertTrue("the tag must fit inside the row at " + where, tag.getRight() <= widthPx);
  }

  @Test
  public void theTagSurvivesEveryNameAtEveryWidth() {
    for (final int widthDp : new int[] {320, 360, 411, 480}) {
      for (final String name : HOSTILE_NAMES) {
        assertTagSurvives(widthDp, name, "");
        assertTagSurvives(widthDp, name, name);
      }
    }
  }

  /** Large font scales are where the name column previously computed negative. */
  @Test
  @Config(fontScale = 2.0f)
  public void theTagSurvivesAtDoubleFontScale() {
    for (final String name : HOSTILE_NAMES) {
      assertTagSurvives(320, name, "");
    }
  }

  /** Both name views must be ellipsised, or a truncated name is invisibly truncated. */
  @Test
  public void bothNameViewsEllipsise() {
    final View row = inflateRow(RuntimeEnvironment.getApplication());
    final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
    final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);

    assertNotNull("the first name must ellipsise - it is the mandatory field and was the one left "
        + "unconstrained", firstName.getEllipsize());
    assertNotNull(lastName.getEllipsize());
    assertEquals(1, firstName.getMaxLines());
    assertEquals(1, lastName.getMaxLines());
  }

  // ------------------------------------------- what the adapter actually puts in the row

  /**
   * The row must show a SANITISED name, not the raw one.
   *
   * <p>Every banner went through the sanitiser; the contact row did not, and the row is where two
   * contacts are compared. A leading U+202E with the name written backwards renders
   * pixel-identically to another contact's row, while the matching path folds the logical order and
   * sees a different name - so no duplicate warning fires and the rows are indistinguishable.
   *
   * <p>Asserted through {@code ListAdapterContacts.getView}, not by calling the sanitiser directly.
   * A test that calls the sanitiser proves the sanitiser works; it says nothing about whether the
   * row uses it, and reverting the row to the raw name survived exactly such a test.
   */
  @Test
  public void theAdapterPutsASanitisedNameInTheRow() {
    final Context context = RuntimeEnvironment.getApplication();
    com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.testIsRunning = true;
    com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.initialize(null);

    final String hostile = ((char) 0x202E) + "ecilA";
    final java.util.ArrayList<Object> contacts = new java.util.ArrayList<>();
    contacts.add(new com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact(
        hostile, "Smith" + ((char) 0x000A) + "Jones", "peer-uuid", 7, false));

    final com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts adapter =
        new com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts(
            context, com.amnesica.kryptey.inputmethod.R.layout.e2ee_contact_list_element_view,
            contacts);
    adapter.setListener(
        new com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts
            .ListAdapterContactInterface() {
          @Override public void selectContact(
              com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact c) { }
          @Override public void removeContact(
              com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact c) { }
          @Override public void verifyContact(
              com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact c) { }
        });

    final View row = adapter.getView(0, null, new android.widget.FrameLayout(context));
    final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
    final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);

    final String shownFirst = firstName.getText().toString();
    final String shownLast = lastName.getText().toString();

    assertTrue("a bidi override must not reach the row: " + escape(shownFirst),
        shownFirst.indexOf((char) 0x202E) < 0);
    assertTrue("nor a line separator: " + escape(shownLast),
        shownLast.indexOf((char) 0x000A) < 0);
    assertTrue("and the name itself must survive", shownFirst.contains("ecilA"));
  }

  private static String escape(final String value) {
    final StringBuilder out = new StringBuilder();
    for (final char c : value.toCharArray()) {
      if (c < 0x20 || c > 0x7E) out.append(String.format("U+%04X", (int) c));
      else out.append(c);
    }
    return out.toString();
  }

  /**
   * Neither name view may be starved to zero width.
   *
   * <p>They were both {@code wrap_content} in a {@code wrap_content} container, so the first child
   * was measured against the whole bounded width and a long first name left the last-name view at
   * zero - with no ellipsis and nothing on screen to say a name had been cut. Two genuinely
   * different contacts then rendered byte-identically, in plain ASCII: "Maria del Carmen
   * Fernandez"/"Smith" and the same first name with "Jones".
   *
   * <p>Asserting that a view {@code getEllipsize() != null} does not catch this - that is a property
   * of the view, and a view of zero width ellipsises nothing. The width is what matters.
   */
  @Test
  public void neitherNameViewIsStarvedToZeroWidth() {
    for (final int widthDp : new int[] {320, 360, 411, 480}) {
      final Context context = RuntimeEnvironment.getApplication();
      final View row = inflateRow(context);
      final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
      final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);

      firstName.setText("Maria del Carmen Fernandez");
      lastName.setText("Smith");
      ((TextView) row.findViewById(R.id.e2ee_contact_address_tag_element)).setText("#ab12-cd34");

      final float density = context.getResources().getDisplayMetrics().density;
      final int widthPx = (int) (widthDp * density);
      row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      row.layout(0, 0, widthPx, row.getMeasuredHeight());

      assertTrue("a long first name starved the last name to nothing at " + widthDp + "dp",
          lastName.getWidth() > 0);
      assertTrue("and the first name must still have room at " + widthDp + "dp",
          firstName.getWidth() > 0);
    }
  }

  /** Two contacts differing only in the last name must not render as the same text. */
  @Test
  public void contactsDifferingOnlyInTheLastNameRenderDifferently() {
    final Context context = RuntimeEnvironment.getApplication();
    final float density = context.getResources().getDisplayMetrics().density;
    final int widthPx = (int) (360 * density);

    final String[] shown = new String[2];
    final String[] lasts = {"Smith", "Jones"};
    for (int i = 0; i < 2; i++) {
      final View row = inflateRow(context);
      final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
      final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);
      firstName.setText("Maria del Carmen Fernandez");
      lastName.setText(lasts[i]);

      row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      row.layout(0, 0, widthPx, row.getMeasuredHeight());

      shown[i] = visibleText(firstName) + "|" + visibleText(lastName);
    }

    org.junit.Assert.assertNotEquals("two contacts differing in the last name rendered identically",
        shown[0], shown[1]);
  }

  private static final int[] WIDTHS_DP = {320, 360, 411, 480};

  /** 1.3 is the stock "Largest" slider; 2.0 is reachable in accessibility settings. */
  private static final float[] FONT_SCALES = {1.0f, 1.15f, 1.3f, 1.5f, 2.0f};

  /** Everything the user can actually read in one row, at one configuration. */
  private String renderedRow(final int widthDp, final float fontScale,
                             final String first, final String last, final String tagText) {
    final Context context = RuntimeEnvironment.getApplication();
    final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    final float density = metrics.density;
    // Font scale is what shrinks the name column, so it has to be real: sp dimensions resolve
    // through scaledDensity, and leaving it at the default measures a screen nobody has.
    metrics.scaledDensity = density * fontScale;

    final View row = inflateRow(context);
    final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
    final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);
    final TextView tag = row.findViewById(R.id.e2ee_contact_address_tag_element);
    firstName.setText(first);
    lastName.setText(last);
    tag.setText(tagText);

    final int widthPx = (int) (widthDp * density);
    row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    row.layout(0, 0, widthPx, row.getMeasuredHeight());

    try {
      return visibleText(firstName) + "|" + visibleText(lastName) + "|" + visibleText(tag);
    } finally {
      metrics.scaledDensity = density;
    }
  }

  /**
   * Two distinct contacts must never render as the same row - at any width, at any font scale.
   *
   * <p>The predicate here has been wrong three rounds running: {@code getEllipsize() != null},
   * then {@code getWidth() > 0}, then visible-text-differs at one width and one font scale. Each
   * was true of a layout that still rendered two different contacts identically. A 33px name view
   * satisfies {@code width > 0} and is narrower than the ellipsis glyph, so it draws nothing at all
   * - no characters, no "...", no indication a name was cut.
   *
   * <p>Note what is NOT claimed: that the NAMES always differ. Under truncation they cannot - two
   * contacts sharing a long first name will always truncate alike, and no layout fixes that. The
   * address tag is the designed answer to exactly this, so the property is that the ROW differs. It
   * follows that the tag has to be legible whenever the names are not, which the next test pins.
   */
  @Test
  public void twoDistinctContactsNeverRenderAsTheSameRow() {
    final String[][] pairs = {
        {"Alice", "Smith", "Alicia", "Smythe"},
        {"Maria del Carmen Fernandez", "Smith", "Maria del Carmen Fernandez", "Jones"},
        {"Maria del Carmen Fernandez", "", "Maria del Carmen Fernandes", ""},
        {"Alice", "", "Alice", "Smith"},
        {"AliceAliceAliceAliceAlice", "X", "AliceAliceAliceAliceAlice", "Y"},
    };

    for (final int widthDp : WIDTHS_DP) {
      for (final float fontScale : FONT_SCALES) {
        for (final String[] pair : pairs) {
          // Distinct contacts have distinct addresses, so they have distinct tags. That is the
          // whole reason the tag exists; a sweep that gave both rows the same tag would be
          // testing a situation the app cannot produce.
          final String a = renderedRow(widthDp, fontScale, pair[0], pair[1], "#ab12-cd34");
          final String b = renderedRow(widthDp, fontScale, pair[2], pair[3], "#ef56-7890");

          org.junit.Assert.assertNotEquals(
              "two distinct contacts rendered identically at " + widthDp + "dp, fontScale "
                  + fontScale + " - \"" + pair[0] + "\"/\"" + pair[1] + "\" vs \"" + pair[2]
                  + "\"/\"" + pair[3] + "\" both show [" + escape(a) + "]",
              a, b);
        }
      }
    }
  }

  /**
   * The tag is the fallback discriminator, so it must be fully legible everywhere - not merely
   * non-zero width, which is what every previous version of this checked. A tag truncated to
   * "#ab1" collides with every other tag sharing that prefix, and a tag truncated to nothing
   * leaves two identically-truncated names with nothing to tell them apart at all.
   */
  @Test
  public void theTagIsNeverTruncatedAtAnyWidthOrFontScale() {
    for (final int widthDp : WIDTHS_DP) {
      for (final float fontScale : FONT_SCALES) {
        for (final String name : HOSTILE_NAMES) {
          final String shown = renderedRow(widthDp, fontScale, name, name, "#ab12-cd34");
          final String tagShown = shown.substring(shown.lastIndexOf('|') + 1);

          assertEquals("the tag was truncated at " + widthDp + "dp, fontScale " + fontScale
                  + " with name \"" + escape(name) + "\" - it is the only thing left to "
                  + "distinguish two rows whose names have both been cut",
              "#ab12-cd34", tagShown);
        }
      }
    }
  }

  /**
   * The mandatory field must not be starved by an empty optional one.
   *
   * <p>With both views at {@code 0dp} + {@code weight="1"} each took exactly half the column
   * whatever it held, so an empty last name left half the column blank while the first name - the
   * field that always exists - was cut in half. Measured at 320dp: "Maria del Carmen Fernandez"
   * showed "Mari" with 61px sitting empty beside it.
   */
  @Test
  public void anEmptyLastNameGivesItsRoomToTheFirst() {
    final String full = "Maria del Carmen Fernandez";
    int configurationsWhereItMattered = 0;

    for (final int widthDp : WIDTHS_DP) {
      for (final float fontScale : FONT_SCALES) {
        final String withEmpty = renderedRow(widthDp, fontScale, full, "", "#ab12-cd34");
        final String withLast = renderedRow(widthDp, fontScale, full, "Smith", "#ab12-cd34");

        final String firstOfEmpty = withEmpty.substring(0, withEmpty.indexOf('|'));
        final String firstOfLast = withLast.substring(0, withLast.indexOf('|'));

        assertTrue("an empty last name showed LESS of the first name than a present one at "
                + widthDp + "dp, fontScale " + fontScale + ": \"" + firstOfEmpty + "\" vs \""
                + firstOfLast + "\"",
            firstOfEmpty.length() >= firstOfLast.length());

        // Where the first name is cut at all, dropping the optional field must buy back room.
        // Asserting only ">=" would pass on a layout that never gave the room back, so the
        // strict case is required wherever it is reachable.
        if (firstOfLast.length() < full.length()) {
          assertTrue("the last name was blank and the first name was still truncated to \""
                  + firstOfEmpty + "\" at " + widthDp + "dp, fontScale " + fontScale
                  + " - its half of the column sat empty beside it",
              firstOfEmpty.length() > firstOfLast.length());
          configurationsWhereItMattered++;
        }
      }
    }

    assertTrue("no configuration in the grid truncated the first name, so this proved nothing",
        configurationsWhereItMattered > 0);
  }

  /** What the user can actually read: the text minus whatever the ellipsis cut off. */
  private static String visibleText(final TextView view) {
    final android.text.Layout layout = view.getLayout();
    if (layout == null || layout.getLineCount() == 0) return "";
    final int cut = layout.getEllipsisCount(0);
    final CharSequence text = view.getText();
    final int keep = Math.max(0, text.length() - cut);
    return text.subSequence(0, keep).toString();
  }
}
