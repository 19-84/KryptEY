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
    // The tag sits BELOW the names now, not beside them, so the separation is vertical.
    //
    // This asserted "the name container must end before the tag begins" - a horizontal adjacency
    // invariant. That was the right check while the two shared a line and a long name could
    // overprint the tag in the same colour. It is meaningless once they are on separate lines, and
    // keeping it would have blocked the change that actually fixed the thing it was protecting
    // against: at 320dp and fontScale 2.0 the names had been squeezed to nothing at all.
    assertTrue("the names must end before the tag begins at " + where + " - they must not overlap, "
            + "whichever way round the layout puts them",
        container.getBottom() <= tag.getTop());
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

    // The tag, which nothing asserted. Every other test in this file sets the tag text ITSELF and
    // then measures geometry, so replacing the adapter's setText with setText("") left the whole
    // suite green - on the one surface the tag exists for. This file's own javadoc makes exactly
    // this argument for the name ("a test that calls the sanitiser proves the sanitiser works; it
    // says nothing about whether the row uses it") and did not apply it here.
    final TextView tag = row.findViewById(R.id.e2ee_contact_address_tag_element);
    final String shownTag = tag.getText().toString();

    assertTrue("the adapter must put the address tag in the row - it is the only thing that "
            + "distinguishes two contacts whose names a reader cannot tell apart, and it was \""
            + shownTag + "\"",
        shownTag.length() >= 8);
    assertEquals("and it must be the tag for THIS contact, not some other row's",
        com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.displayTagFor(
            ((com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact) contacts.get(0))),
        shownTag);
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
        // Not all ASCII. CJK glyphs are about twice the advance of Latin ones, so they exhaust the
        // column at font scales where Latin names still fit - a pair that never leaves the ASCII
        // range cannot reach the cells where the column actually runs out.
        {"龘龘龘龘龘龘龘龘龘龘", "山田", "龘龘龘龘龘龘龘龘龘龘", "田中"},
        {"אליס", "שמית", "אליס", "כהן"},
        {"Maria del Carmen Fernandez", "", "María del Carmen Fernandez", ""},
    };

    int namesCollided = 0;

    for (final int widthDp : WIDTHS_DP) {
      for (final float fontScale : FONT_SCALES) {
        for (final String[] pair : pairs) {
          // Distinct contacts have distinct addresses, so they have distinct tags. That is the
          // whole reason the tag exists; a sweep that gave both rows the same tag would be
          // testing a situation the app cannot produce.
          // The real property is a DISJUNCTION: the names differ, or the tag is legible.
          //
          // Comparing whole rows with different tags was a tautology - the tag view has no
          // ellipsize, so visibleText returns its whole string by construction, the third component
          // always differed, and assertNotEquals held for all 160 cells whatever the names did.
          //
          // And "the names always differ" is simply false: "Maria del Carmen Fernandez" and
          // "...Fernandes" both truncate to "Maria del Carmen Ferna" at 320dp. That is exactly the
          // case the address tag exists for, so the honest claim is that when the names collide,
          // the thing that separates the rows must be readable.
          final String a = renderedRow(widthDp, fontScale, pair[0], pair[1], "#ab12-cd34");
          final String b = renderedRow(widthDp, fontScale, pair[2], pair[3], "#ef56-7890");

          final String namesA = a.substring(0, a.lastIndexOf('|'));
          final String namesB = b.substring(0, b.lastIndexOf('|'));

          if (namesA.equals(namesB)) {
            namesCollided++;
            assertTagFullyDrawn(widthDp, fontScale, pair[0]);
            assertTagFullyDrawn(widthDp, fontScale, pair[2]);
          }
        }
      }
    }

    assertTrue("no pair in the grid ever collided on names, so the tag half of the disjunction was "
        + "never exercised - this would pass on a layout that never draws the tag at all",
        namesCollided > 0);
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
          assertTagFullyDrawn(widthDp, fontScale, name);
        }
      }
    }
  }

  /**
   * Measured geometrically, because the obvious predicate cannot fail.
   *
   * <p>The first version of this asked whether {@code visibleText(tag)} still equalled the tag. The
   * tag TextView has {@code maxLines="1"} and deliberately no {@code ellipsize}, so
   * {@code getEllipsisCount(0)} is always 0 and {@code visibleText} returns the whole string by
   * construction - the assertion was comparing a string with itself. Pinning the tag view to 18dp,
   * where it needs 65px and is silently clipped to about "#a", left the whole class green.
   *
   * <p>The negative control that appeared to justify it - "tag fixed at 30dp with ellipsize" - only
   * failed because adding {@code ellipsize} changes the view under test. That is the third
   * insufficient predicate in this file: {@code getEllipsize() != null}, then
   * {@code getWidth() > 0}, then visible-text on a view that never ellipsises.
   *
   * <p>What actually distinguishes a clipped tag from a whole one is width: the text needs
   * {@code Layout.getDesiredWidth} and the view has {@code getWidth()}. Clipping is silent - no
   * ellipsis, no marker - so nothing in the drawn text can report it.
   */
  private void assertTagFullyDrawn(final int widthDp, final float fontScale, final String name) {
    final Context context = RuntimeEnvironment.getApplication();
    final android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    final float density = metrics.density;
    metrics.scaledDensity = density * fontScale;

    try {
      final View row = inflateRow(context);
      final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
      final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);
      final TextView tag = row.findViewById(R.id.e2ee_contact_address_tag_element);
      firstName.setText(name);
      lastName.setText(name);
      tag.setText("#ab12-cd34");

      final int widthPx = (int) (widthDp * density);
      row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      row.layout(0, 0, widthPx, row.getMeasuredHeight());

      final android.text.Layout layout = tag.getLayout();
      assertNotNull("the tag was not laid out at all at " + widthDp + "dp, fontScale " + fontScale,
          layout);

      // getDesiredWidth, NOT getLineWidth. Measured at a pinned 18dp: the view is 18px, the text
      // needs 65px, and getLineWidth(0) reports 14.0 - because the Layout is built at the view's
      // width and WRAPS, and maxLines="1" then simply clips the lines after the first. So the line
      // width is a property of the box, not of the text, and comparing it to the box can only ever
      // say yes. getDesiredWidth measures the text itself.
      final float needed = android.text.Layout.getDesiredWidth(tag.getText(), tag.getPaint());
      final int available = tag.getWidth();
      assertTrue("the tag needs " + needed + "px but has " + available + "px at " + widthDp
              + "dp, fontScale " + fontScale + " with name \"" + escape(name) + "\" - it is "
              + "clipped silently, and it is the only thing left to distinguish two rows whose "
              + "names have both been cut",
          needed <= available);
    } finally {
      metrics.scaledDensity = density;
    }
  }

  /**
   * The mandatory field must not be starved by an empty optional one.
   *
   * <p>With both views at {@code 0dp} + {@code weight="1"} each took exactly half the column
   * whatever it held, so an empty last name left half the column blank while the first name - the
   * field that always exists - was cut in half. Measured at 320dp: "Maria del Carmen Fernandez"
   * showed "Mari" in a 57px view with 58px sitting empty beside it.
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

  /**
   * Every name shows something, at every width and font scale in the grid.
   *
   * <p>This was recorded in REVIVAL.md as a deferred defect and could not be asserted before: at
   * 320dp with fontScale 2.0 both name views measured narrower than the ellipsis glyph, so nothing
   * was drawn at all - no characters, no "...". The rows stayed distinguishable by their tags, so
   * the security property held, but a contact list showing no names is unusable, and fontScale 2.0
   * is set by exactly the people who most need to read it.
   *
   * <p>The fix was structural: the tag moved onto its own line, which gives the names the full
   * column instead of whatever the tag left them. Rows are taller - 55px instead of 40 at default
   * scale - which is the trade this buys.
   *
   * <p>Non-ASCII is included deliberately. CJK advances are about twice Latin ones, so an
   * ASCII-only sweep does not reach the cells where a column actually runs out; the earlier claim
   * that every cell showed a character was measured over ASCII and was false for CJK.
   */
  @Test
  public void everyNameShowsAtLeastOneCharacterEverywhere() {
    // Ordinary names, including non-Latin scripts. NOT the pathological case of a ten-character
    // CJK first name beside a last name at 320dp: CJK advances are about twice Latin ones, so the
    // two weighted views run out of column before the last name gets a character, and no
    // attribute fixes that without breaking the empty-last-name case. That limit is recorded in
    // REVIVAL.md rather than asserted away here.
    final String[][] names = {
        {"Alice", "Smith"},
        {"Maria del Carmen Fernandez", "Gonzalez"},
        {"山田", "太郎"},
        {"אליס", "שמית"},
    };

    for (final int widthDp : WIDTHS_DP) {
      for (final float fontScale : FONT_SCALES) {
        for (final String[] name : names) {
          final String shown = renderedRow(widthDp, fontScale, name[0], name[1], "#ab12-cd34");
          final String first = shown.substring(0, shown.indexOf('|'));
          final String last = shown.substring(shown.indexOf('|') + 1, shown.lastIndexOf('|'));

          assertTrue("the first name rendered nothing at " + widthDp + "dp, fontScale " + fontScale
                  + " for \"" + escape(name[0]) + "\" - no characters and no ellipsis, so the row "
                  + "says nothing about who it is", !first.isEmpty());
          assertTrue("the last name rendered nothing at " + widthDp + "dp, fontScale " + fontScale
                  + " for \"" + escape(name[1]) + "\"", !last.isEmpty());
        }
      }
    }
  }

  /**
   * The CJK starvation case, pinned as a limit instead of described as one.
   *
   * <p>REVIVAL.md carries this as deferred: ten CJK characters want about twice the advance of ten
   * Latin ones, so at 320dp the two weighted name views run out of column before the last name gets
   * a single character. A {@code minEms} floor fixes it and breaks the empty-last-name case, because
   * the floor is reserved whether or not the view holds anything, so it trades one defect for
   * another. The real fix renders the two fields as one ellipsised string.
   *
   * <p>{@code everyNameShowsAtLeastOneCharacterEverywhere} deliberately excludes this case rather
   * than asserting it away, which is honest — but it leaves the claim unmeasured and the exclusion
   * unpinned. This measures it. If the layout is fixed this fails, and the deferred entry can be
   * retired on evidence; if it degrades further it fails too. Prose about a defect drifts from the
   * defect, and three of the four entries on that list turned out to say something untrue.
   */
  @Test
  @Config(qualifiers = "w320dp")
  public void thecjkStarvationLimitIsWhereThisSaysItIs() {
    final Context context = RuntimeEnvironment.getApplication();
    final View row = inflateRow(context);
    final TextView firstName = row.findViewById(R.id.e2ee_contact_first_name_element);
    final TextView lastName = row.findViewById(R.id.e2ee_contact_last_name_element);
    assertNotNull(firstName);
    assertNotNull(lastName);

    // Ten CJK characters, which is an ordinary length for a name in those scripts.
    firstName.setText("\u5f35\u5049\u5eb7\u738b\u5c0f\u660e\u674e\u5a1c\u5468\u6770");
    lastName.setText("Jones");

    final float density = context.getResources().getDisplayMetrics().density;
    final int widthPx = (int) (320 * density);
    row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    row.layout(0, 0, widthPx, row.getMeasuredHeight());

    assertTrue("precondition: the first name must actually be taking the column, or this is "
        + "measuring something else entirely", firstName.getWidth() > 0);

    // What is actually true, which is not what the deferred entry said.
    //
    // It claimed the column runs out "before the last name gets a single character". Measured at
    // 320dp with ten CJK characters, the last name gets 39dp - squeezed to roughly three or four
    // Latin characters, so an ellipsised "Jon..." rather than nothing at all. Both halves are pinned
    // because they fail in opposite directions: if a fix lands the width grows and the upper bound
    // fails, and the entry can be retired on evidence; if the layout degrades to the starvation the
    // entry described, the lower bound fails.
    final int lastNameDp = (int) (lastName.getWidth() / density);
    assertTrue("the last name must not be starved to nothing - it got " + lastNameDp + "dp, and if "
            + "that is now zero the layout has degraded to what the deferred entry describes",
        lastNameDp > 0);
    assertTrue("and it must still be squeezed - it got " + lastNameDp + "dp. If this is now a "
            + "comfortable width the layout has been fixed and the entry in REVIVAL.md should be "
            + "retired on this evidence", lastNameDp < 64);
  }
}
