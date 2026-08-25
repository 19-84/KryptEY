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

  public View getView(final int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
      convertView = layoutInflater.inflate(R.layout.e2ee_contact_list_element_view, null, false);
    }

    final Contact contact = (Contact) getItem(position);

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
    Contact contact = null;
    try {
      contact = (Contact) mContacts.get(position);
    } catch (ClassCastException e) {
      LinkedHashMap linkedHashMap = (LinkedHashMap) mContacts.get(position);
      return new Contact((String) linkedHashMap.get("firstName"),
          (String) linkedHashMap.get("lastName"),
          (String) linkedHashMap.get("signalProtocolAddressName"),
          (Integer) linkedHashMap.get("deviceId"),
          (Boolean) linkedHashMap.get("verified"));
    } catch (Exception e) {
      e.printStackTrace();
    }
    return contact;
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
