package com.amnesica.kryptey.inputmethod.latin.e2ee;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;

/**
 * A list that shows whole rows or none of a row, never part of one.
 *
 * <p>{@code ListView} deliberately draws a partial last child when it is measured with a bounded
 * height that is not a whole number of rows. For most lists that is right — a half-visible row is
 * how a person knows to scroll. For the contact list it is not, because of where the address tag
 * sits: it is the bottom-most element of a row, so the part that gets cut is the tag, while the name
 * above it stays complete and perfectly legible.
 *
 * <p>That is the exact state the tag exists to prevent. Two contacts the user has given the same
 * name are told apart by their tags; a row showing a whole name and no tag reads as the plain,
 * unambiguous original. With {@code divider="@null"} there is no rule and no ellipsis either, so the
 * clipping is silent — nothing on screen says the row is cut.
 *
 * <p>Measured before this existed: a 322-pixel budget against 92-pixel rows produced a list 261
 * pixels tall, and the third row's tag ended at 276.
 *
 * <p>Rounding down rather than up: showing fewer rows costs a scroll, showing a partial one costs
 * the property. One row is always kept even if it does not fit, because a list that measures to zero
 * would hide the contact list altogether — a worse failure than a clipped tag, and the direction an
 * over-eager fix would take.
 */
public class WholeRowsListView extends ListView {

  public WholeRowsListView(final Context context) {
    super(context);
  }

  public WholeRowsListView(final Context context, final AttributeSet attrs) {
    super(context, attrs);
  }

  public WholeRowsListView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);

    if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return;
    if (getAdapter() == null || getAdapter().getCount() == 0) return;

    final int rowHeight = measureOneRow(widthMeasureSpec);
    if (rowHeight <= 0) return;

    // Against what the list was actually given, not only against what it wants. The two differ:
    // the screen's budget is shared with the info line and the button strip, so the list's own
    // allowance is smaller, and snapping against the wrong one drops a row that would have fitted.
    final int allowance = Math.min(MeasureSpec.getSize(heightMeasureSpec), getMeasuredHeight());
    final int wholeRows = allowance / rowHeight;

    // Only ever smaller, never larger - and that leaves one state this cannot fix, stated rather
    // than papered over. With room for less than a single row, there is no whole row to show: the
    // choice is a clipped row or nothing, and growing past the allowance would hand the parent a
    // view it has to clip anyway. So below one row this does nothing and the list shows what it was
    // given.
    //
    // A guard that forced a minimum of one row was written here first and was DEAD: it computed a
    // height larger than the measured one and then skipped the assignment, because the assignment
    // is conditional on shrinking. It looked like a floor, tested as a floor, and did nothing. Its
    // removal is why the test beside it asserts what happens rather than what was intended.
    final int snapped = wholeRows * rowHeight;
    if (snapped > 0 && snapped < getMeasuredHeight()) {
      setMeasuredDimension(getMeasuredWidth(), snapped);
    }
  }

  /**
   * The height of one row, asked of the adapter rather than of a laid-out child.
   *
   * <p>At measure time there may be no children yet, and reading {@code getChildAt(0)} then silently
   * returns zero - which would disable the snapping and look like it was working.
   */
  private int measureOneRow(final int widthMeasureSpec) {
    final View row = getAdapter().getView(0, null, this);
    if (row == null) return 0;
    row.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    return row.getMeasuredHeight();
  }
}
