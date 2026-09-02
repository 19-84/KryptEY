package com.amnesica.kryptey.inputmethod.latin.e2ee.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * A contact row the adapter cannot build must render empty and act on nobody.
 *
 * <p>{@code getItem}'s fallback rebuilds a {@code Contact} from a {@code LinkedHashMap}, which is
 * what Jackson hands back when a stored list is read without its element type. It sat inside a
 * {@code catch (ClassCastException)}, and that was the defect: <b>an exception thrown from within a
 * catch block is not seen by that try's other handlers</b>, so the three casts and the two
 * unboxings in the fallback escaped {@code getItem} entirely - straight past the
 * {@code catch (Exception)} written directly beneath them - and landed in {@code getView} during
 * list layout.
 *
 * <p>Unreachable today: nothing in this app puts a non-{@code Contact} in that list. It is worth
 * fixing anyway because the handler <em>looked</em> like it covered the code above it.
 *
 * <p>The assertion that matters is not the blank text - it is the listeners. The row {@code View}
 * is recycled, so a row left carrying the previous contact's listeners means Delete and Verify
 * still act on that contact while the row shows nothing: a destructive action aimed at a contact
 * whose name the user cannot see.
 */
@RunWith(RobolectricTestRunner.class)
public class ArowThatCannotBeBuiltActsOnNobodyTest {

  private ListAdapterContacts adapterOver(final Object... items) {
    final ArrayList<Object> list = new ArrayList<>();
    for (final Object item : items) list.add(item);
    return new ListAdapterContacts(
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.KeyboardTheme_LXX_Pure_Day),
        R.layout.e2ee_contact_list_element_view, list);
  }

  /** The shape Jackson produces, with the device id missing - so no address can be formed. */
  private static LinkedHashMap<String, Object> mapWithoutAdeviceId() {
    final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("firstName", "Bob");
    fields.put("lastName", "Jones");
    fields.put("signalProtocolAddressName", "bob-uuid");
    fields.put("verified", Boolean.TRUE);
    return fields;
  }

  /**
   * The premise: a well-formed map IS rebuilt, so the case below is about the malformed one.
   *
   * <p>Without this, everything here would pass on an adapter whose fallback had simply been
   * deleted - which would break the legitimate Jackson case while looking like a fix.
   */
  @Test
  public void thepremiseThatAwellFormedMapIsStillRebuilt() {
    final LinkedHashMap<String, Object> fields = mapWithoutAdeviceId();
    fields.put("deviceId", 7);

    final Contact rebuilt = (Contact) adapterOver(fields).getItem(0);
    assertNotNull("a complete stored map must still rebuild into a contact", rebuilt);
    assertEquals("Bob", rebuilt.getFirstName());
    assertEquals(7, rebuilt.getDeviceId());
  }

  /** A verified flag of the wrong type must read as NOT verified, never as verified. */
  @Test
  public void amalformedVerifiedFlagReadsAsUnverified() {
    final LinkedHashMap<String, Object> fields = mapWithoutAdeviceId();
    fields.put("deviceId", 7);
    fields.put("verified", "yes please");

    final Contact rebuilt = (Contact) adapterOver(fields).getItem(0);
    assertNotNull(rebuilt);
    assertEquals("the badge is the one indicator this screen carries; inventing it from a value of "
        + "the wrong type is worse than omitting it", false, rebuilt.isVerified());
  }

  @Test
  public void amapMissingItsDeviceIdYieldsNoContactRatherThanEscaping() {
    assertNull("a row with no device id has no address, so every trust question about it would be "
            + "answered from a value this code invented",
        adapterOver(mapWithoutAdeviceId()).getItem(0));
  }

  /**
   * And the row renders, inert, instead of taking the list layout down with it.
   *
   * <p>Driven through a recycled {@code View} carrying a real contact's row, because that is the
   * state where leaving listeners behind does damage - and it is the state a {@code ListView}
   * actually produces.
   */
  @Test
  public void therowRendersEmptyAndCarriesNoListeners() {
    final Contact real = new Contact("Alice", "Smith", "alice-uuid", 3, false);
    final ListAdapterContacts adapter = adapterOver(real, mapWithoutAdeviceId());

    final View recycled = adapter.getView(0, null, null);
    assertEquals("precondition: the first row must render the real contact, or the recycled view "
            + "carries nothing for the second row to keep", "Alice",
        ((TextView) recycled.findViewById(R.id.e2ee_contact_first_name_element))
            .getText().toString());

    final View reused = adapter.getView(1, recycled, null);
    assertNotNull("the adapter must still return a row rather than throwing out of list layout",
        reused);
    assertEquals("the previous contact's name must not remain on an unbuildable row", "",
        ((TextView) reused.findViewById(R.id.e2ee_contact_first_name_element))
            .getText().toString());

    final ImageButton delete = reused.findViewById(R.id.e2ee_contact_button_delete_contact);
    assertNotNull(delete);
    assertEquals("Delete must not still be armed against the previous contact - the row shows no "
            + "name, so the user cannot see who it would remove", false,
        delete.hasOnClickListeners());
  }
}
