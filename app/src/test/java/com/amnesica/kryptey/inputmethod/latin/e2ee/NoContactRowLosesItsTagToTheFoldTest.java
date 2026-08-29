package com.amnesica.kryptey.inputmethod.latin.e2ee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * A contact row shows its tag or it shows nothing. It must never show a name without one.
 *
 * <p>Two contacts the user has given the same name are told apart by their address tags — that is
 * the whole mechanism, and the pin cannot cover the case it covers. The tag is the bottom-most
 * element of a row, and {@code ListView} deliberately draws a partial last child when its height is
 * bounded and not a whole number of rows. So the cut falls on the tag while the name above it stays
 * complete and legible: the row reads as the plain, unambiguous original. With
 * {@code divider="@null"} there is no rule and no ellipsis, so nothing on screen says it was cut.
 *
 * <p>Measured before the fix: a 322-pixel budget against 92-pixel rows gave a list 261 pixels tall,
 * with the third row's tag ending at 276.
 *
 * <p>This drives the real layout and the real adapter, and asserts on the laid-out children, because
 * the property is geometric and nothing about the row's own XML can express it.
 */
@RunWith(RobolectricTestRunner.class)
public class NoContactRowLosesItsTagToTheFoldTest {

  private ContextThemeWrapper themed;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    themed = new ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), R.style.KeyboardTheme_LXX_Pure_Day);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private ListView listOf(final int contacts, final double rowsThatFit) {
    final ArrayList<Object> rows = new ArrayList<>();
    final ArrayList<Contact> typed = new ArrayList<>();
    for (int i = 0; i < contacts; i++) {
      final Contact contact = new Contact("Bob", "Jones", "addr-" + i, i + 1, false);
      rows.add(contact);
      typed.add(contact);
    }
    SignalProtocolMain.getInstance().getAccount().setContactList(typed);

    final View screen = LayoutInflater.from(themed)
        .inflate(R.layout.e2ee_contact_list_view, null);
    final ListView list = screen.findViewById(R.id.e2ee_contact_list);
    assertNotNull("the contact list must inflate", list);
    list.setAdapter(new ListAdapterContacts(themed, R.layout.e2ee_contact_list_element_view, rows));

    final View row = list.getAdapter().getView(0, null, list);
    row.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    final int budget = (int) (row.getMeasuredHeight() * rowsThatFit);

    screen.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(budget, View.MeasureSpec.AT_MOST));
    screen.layout(0, 0, 720, screen.getMeasuredHeight());
    return list;
  }

  @Test
  public void noRowIsDrawnPastTheBottomOfTheList() {
    final ListView list = listOf(8, 3.5);

    assertTrue("the list must be showing something", list.getChildCount() > 0);
    for (int i = 0; i < list.getChildCount(); i++) {
      final View child = list.getChildAt(i);
      assertTrue("row " + i + " is drawn past the bottom of the list, so its address tag - the "
              + "bottom-most thing in the row - is cut while the name above it stays legible. That "
              + "is the row reading as the plain original, which is the state the tag exists to "
              + "prevent: child bottom " + child.getBottom() + " against list height "
              + list.getHeight(),
          child.getBottom() <= list.getHeight());
    }
  }

  /**
   * And it shows every whole row its own height allows.
   *
   * <p>The floor, and it caught a flaw in the first version of the fix: that one snapped against the
   * height the list <em>wanted</em> rather than the height it was <em>given</em>, and the two differ
   * because the screen's budget is shared with the info line and the button strip. Refusing partial
   * rows by quietly showing fewer whole ones trades a clipped tag for a list that hides contacts,
   * which is the direction an over-eager fix takes.
   */
  @Test
  public void everyWholeRowTheHeightAllowsIsShown() {
    final ListView list = listOf(8, 3.5);
    final int rowHeight = list.getChildAt(0).getHeight();

    assertTrue("rows must have a height, or this measures nothing", rowHeight > 0);
    assertEquals("the list must be a whole number of rows tall", 0, list.getHeight() % rowHeight);
    assertEquals("and must show every row that fits in that height",
        list.getHeight() / rowHeight, list.getChildCount());
  }

  /**
   * With room for less than one row, the list does not grow to make one.
   *
   * <p>The limit of this fix, asserted so it is not mistaken for the property. Below a single row
   * there is no whole row to show — the choice is a clipped row or nothing — and growing past the
   * allowance only hands the parent a view it must clip anyway. So the list keeps what it was given.
   *
   * <p>A minimum-of-one-row guard was written here first and was dead code: it computed a larger
   * height and then skipped the assignment, because the assignment only ever shrinks. It looked
   * like a floor and tested like one. This test exists because that is worth remembering.
   */
  @Test
  public void withRoomForLessThanArowTheListDoesNotGrow() {
    final ArrayList<Object> rows = new ArrayList<>();
    final ArrayList<Contact> typed = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      final Contact contact = new Contact("Bob", "Jones", "addr-" + i, i + 1, false);
      rows.add(contact);
      typed.add(contact);
    }
    SignalProtocolMain.getInstance().getAccount().setContactList(typed);

    final WholeRowsListView list = new WholeRowsListView(themed);
    list.setAdapter(new ListAdapterContacts(themed, R.layout.e2ee_contact_list_element_view, rows));

    final View row = list.getAdapter().getView(0, null, list);
    row.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    final int rowHeight = row.getMeasuredHeight();
    assertTrue("fixture: a row must have a height", rowHeight > 0);

    final int allowance = rowHeight / 2;
    list.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(allowance, View.MeasureSpec.AT_MOST));

    assertTrue("the list must not grow past what it was given: " + list.getMeasuredHeight()
        + " against " + allowance, list.getMeasuredHeight() <= allowance);
  }
}
