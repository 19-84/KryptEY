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
}
