package com.amnesica.kryptey.inputmethod.latin.e2ee.adapter;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class ListAdapterContacts extends ArrayAdapter<Object> {

  private ArrayList<Object> mContacts;
  private ListAdapterContactInterface mListener;

  public ListAdapterContacts(
      Context context,
      int resource,
      ArrayList<Object> contacts) {
    super(context, resource, contacts);
    this.mContacts = contacts;
  }

  /**
   * Whether to render address tags at all.
   *
   * <p>Deliberately not "do two names clash": that made the tag depend on the same folding the
   * warning depends on, so one dodge disabled both. The only reason to hide it is that a single
   * contact has nothing to be confused with.
   */
  private boolean shouldShowTags() {
    // From the FIRST contact, not the second.
    //
    // Waiting for a second contact means tags appear on both rows at the moment an impostor is
    // added - precisely when the user needs a remembered value and has none. Worse, it inverts the
    // cue: the genuine long-standing contact is the row that suddenly grew a tag. Showing it from
    // the start costs nothing and gives the user something to have seen before.
    if (mContacts == null) return false;
    for (final Object other : mContacts) {
      if (other instanceof Contact) return true;
    }
    return false;
  }

  /** Clears every field and listener on a recycled row, so it acts on nobody. */
  private View blankRow(final View row) {
    final int[] textViews = {R.id.e2ee_contact_first_name_element,
        R.id.e2ee_contact_last_name_element, R.id.e2ee_contact_address_tag_element};
    for (final int id : textViews) {
      final TextView view = row.findViewById(id);
      if (view != null) {
        view.setText("");
        view.setOnClickListener(null);
      }
    }
    final int[] buttons = {R.id.e2ee_contact_button_delete_contact,
        R.id.e2ee_verify_contact_verified_button, R.id.e2ee_verify_contact_unverified_button};
    for (final int id : buttons) {
      final ImageButton button = row.findViewById(id);
      if (button != null) {
        button.setOnClickListener(null);
        button.setVisibility(View.INVISIBLE);
      }
    }
    return row;
  }

  public View getView(final int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
      convertView = layoutInflater.inflate(R.layout.e2ee_contact_list_element_view, null, false);
    }

    final Contact contact = (Contact) getItem(position);
    if (contact == null) {
      // A row this adapter cannot build a contact for renders empty and inert.
      //
      // Removing the listeners is the part that matters, not the blanking. This View is recycled,
      // so a row left carrying the previous contact's listeners means Delete and Verify still act
      // on that contact while the row shows nothing - a destructive action aimed at a row the user
      // cannot see the name of.
      return blankRow(convertView);
    }

    final TextView firstNameTextView = convertView.findViewById(R.id.e2ee_contact_first_name_element);
    // Sanitised, like every other surface that shows a name. This was the ONE place setting the
    // raw string, and it is the place that matters most: a leading U+202E with the name written
    // backwards renders pixel-identically to another contact's row, while the matching path folds
    // the logical order and sees a different name - so no duplicate warning fires and the two rows
    // are indistinguishable. Every banner already went through this; the row did not.
    firstNameTextView.setText(SignalProtocolMain.sanitizeForBanner(contact.getFirstName()));
    firstNameTextView.setOnClickListener(v -> mListener.selectContact(contact));

    final TextView lastNameTextView = convertView.findViewById(R.id.e2ee_contact_last_name_element);
    // Append the address tag when another contact shares this display name. Two rows both reading
    // "Alice" are otherwise indistinguishable, which lets a messenger bypass the whole pin
    // mechanism: rather than substituting a key for the existing Alice - which is refused and
    // warned about - it invites the user to add a second one at an address it controls, which is a
    // clean first sighting with no warning anywhere.
    // The tag is shown from the FIRST contact, in its own view, not appended to this one.
    //
    // Gating it on the name comparison was the structural mistake: the tag is a pure function of
    // the address, so showing it costs nothing and leaks nothing, while gating it meant any dodge
    // of the name folding removed the tag from BOTH rows as well as suppressing the warning. That
    // turned every gap in the folding - and there will always be gaps, homoglyphs are an infinite
    // regress - from a missing warning into a total blackout. Ungated, a dodge costs the attacker
    // the warning but leaves the rows distinguishable.
    lastNameTextView.setText(SignalProtocolMain.sanitizeForBanner(contact.getLastName()));

    // The tag goes in its OWN view, anchored to the end of the row, so a long name can no longer
    // push it out of existence - the name ellipsises instead. Appending it to the name view meant a
    // name wide enough to fill the row made the tag measure zero width and silently vanish, and an
    // untagged row reads as the plain original one.
    final TextView addressTagTextView =
        convertView.findViewById(R.id.e2ee_contact_address_tag_element);
    if (addressTagTextView != null) {
      addressTagTextView.setText(shouldShowTags() ? contact.getAddressTag() : "");
      addressTagTextView.setOnClickListener(v -> mListener.selectContact(contact));
    }
    lastNameTextView.setOnClickListener(v -> mListener.selectContact(contact));

    final ImageButton deleteContactButton = convertView.findViewById(R.id.e2ee_contact_button_delete_contact);
    deleteContactButton.setOnClickListener(v -> mListener.removeContact(contact));
    // Visibility set explicitly, because blankRow takes it away and this View is recycled.
    //
    // The verified/unverified pair below already do this - both arms of the trust check set both
    // visibilities - and this button did not, because nothing had ever hidden it. blankRow does,
    // so without this line a row that was once blanked renders every later contact bound into it
    // with no Delete button at all. Found by review driving the recycle the other way round: the
    // test written with blankRow only went real -> blanked.
    deleteContactButton.setVisibility(View.VISIBLE);

    final ImageButton verifiedContactButton = convertView.findViewById(R.id.e2ee_verify_contact_verified_button);
    final ImageButton unverifiedContactButton = convertView.findViewById(R.id.e2ee_verify_contact_unverified_button);
    if (com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.isContactKeyTrustworthy(contact)) {
      verifiedContactButton.setOnClickListener(v -> mListener.verifyContact(contact));
      verifiedContactButton.setVisibility(View.VISIBLE);
      unverifiedContactButton.setVisibility(View.INVISIBLE);
    } else {
      unverifiedContactButton.setOnClickListener(v -> mListener.verifyContact(contact));
      unverifiedContactButton.setVisibility(View.VISIBLE);
      verifiedContactButton.setVisibility(View.INVISIBLE);
    }

    return convertView;
  }

  @Override
  public Object getItem(int position) {
    final Object stored = mContacts.get(position);
    if (stored instanceof Contact) return stored;

    // Jackson hands back a LinkedHashMap where a Contact was expected when a stored list is read
    // without its element type, so the fallback rebuilds one. It used to sit INSIDE a
    // {@code catch (ClassCastException)}, which is where the defect was: an exception thrown from
    // within a catch block is not seen by that try's other handlers, so the casts and the two
    // unboxings below escaped {@code getItem} entirely - past the {@code catch (Exception)} written
    // directly beneath them - and landed in {@code getView} during list layout.
    //
    // Now the shape is checked rather than assumed, and a row that cannot be rebuilt answers null.
    // Unreachable today: nothing in this app puts a non-Contact in that list. It is written this
    // way because the alternative is a handler that looks like it covers the code above it and
    // does not.
    if (!(stored instanceof LinkedHashMap)) return null;
    final LinkedHashMap<?, ?> fields = (LinkedHashMap<?, ?>) stored;
    final Object firstName = fields.get("firstName");
    final Object lastName = fields.get("lastName");
    final Object addressName = fields.get("signalProtocolAddressName");
    final Object deviceId = fields.get("deviceId");
    final Object verified = fields.get("verified");
    if (!(firstName instanceof String) || !(lastName instanceof String)
        || !(addressName instanceof String) || !(deviceId instanceof Integer)) {
      // No address or no device id means no address to compare, so every trust question about the
      // row would answer from a value this code invented. An inert row is the honest rendering.
      return null;
    }
    // A missing or malformed verified flag reads as NOT verified, which is the safe direction: the
    // badge is the one indicator this screen carries, and inventing it is worse than omitting it.
    return new Contact((String) firstName, (String) lastName, (String) addressName,
        (Integer) deviceId, verified instanceof Boolean && (Boolean) verified);
  }

  public void setListener(final ListAdapterContactInterface listener) {
    mListener = listener;
  }

  public interface ListAdapterContactInterface {
    void selectContact(Contact contact);

    void removeContact(Contact contact);

    void verifyContact(Contact contact);
  }
}
