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

  /** True when some other contact in the list renders under the same first and last name. */
  private boolean hasDuplicateDisplayName(final Contact contact) {
    if (mContacts == null) return false;
    int seen = 0;
    for (final Object other : mContacts) {
      if (!(other instanceof Contact)) continue;
      final Contact c = (Contact) other;
      if (sameDisplayName(c, contact) && ++seen > 1) return true;
    }
    return false;
  }

  private static boolean sameDisplayName(final Contact a, final Contact b) {
    // Same normalisation as the add-time warning, or the two disagree and a name that dodges one
    // dodges the other.
    return com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.displayNamesMatch(
        a.getFirstName(), a.getLastName(), b.getFirstName(), b.getLastName());
  }

  public View getView(final int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
      convertView = layoutInflater.inflate(R.layout.e2ee_contact_list_element_view, null, false);
    }

    final Contact contact = (Contact) getItem(position);

    final TextView firstNameTextView = convertView.findViewById(R.id.e2ee_contact_first_name_element);
    firstNameTextView.setText(contact.getFirstName());
    firstNameTextView.setOnClickListener(v -> mListener.selectContact(contact));

    final TextView lastNameTextView = convertView.findViewById(R.id.e2ee_contact_last_name_element);
    // Append the address tag when another contact shares this display name. Two rows both reading
    // "Alice" are otherwise indistinguishable, which lets a messenger bypass the whole pin
    // mechanism: rather than substituting a key for the existing Alice - which is refused and
    // warned about - it invites the user to add a second one at an address it controls, which is a
    // clean first sighting with no warning anywhere.
    lastNameTextView.setText(hasDuplicateDisplayName(contact)
        ? contact.getLastName() + "  " + contact.getAddressTag()
        : contact.getLastName());
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
