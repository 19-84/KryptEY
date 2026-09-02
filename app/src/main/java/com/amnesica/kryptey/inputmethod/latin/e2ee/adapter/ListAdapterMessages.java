package com.amnesica.kryptey.inputmethod.latin.e2ee.adapter;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public class ListAdapterMessages extends ArrayAdapter<Object> {

  private final ArrayList<Object> mListStorageMessages;
  private final String accountName;
  private static final String PATTERN_FORMAT = "dd.MM.yyyy HH:mm:ss";

  public ListAdapterMessages(
      Context context,
      int resource,
      ArrayList<Object> listStorageMessages,
      String accountName) {
    super(context, resource, listStorageMessages);
    this.mListStorageMessages = listStorageMessages;
    this.accountName = accountName;
  }

  public View getView(final int position, View convertView, ViewGroup parent) {

    final StorageMessage message = (StorageMessage) getItem(position);

    if (convertView == null) {
      LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
      convertView = layoutInflater.inflate(R.layout.e2ee_messages_element_view, null, false);
    }

    final TextView ownMessageTextView = convertView.findViewById(R.id.e2ee_own_messages_text_view_element);
    final TextView ownMessageTimestampTextView = convertView.findViewById(R.id.e2ee_own_messages_timestamp_text_view_element);

    final TextView othersMessageTextView = convertView.findViewById(R.id.e2ee_others_messages_text_view_element);
    final TextView othersMessageTimestampTextView = convertView.findViewById(R.id.e2ee_others_messages_timestamp_text_view_element);

    if (message != null && accountName != null && accountName.equals(message.getSenderUUID())) {
      ownMessageTextView.setText(message.getUnencryptedMessage());
      ownMessageTextView.setVisibility(View.VISIBLE);

      ownMessageTimestampTextView.setText(formatInstant(message.getTimestamp()));
      ownMessageTimestampTextView.setVisibility(View.VISIBLE);

      othersMessageTimestampTextView.setVisibility(View.GONE);
      othersMessageTextView.setVisibility(View.GONE);
    } else if (message != null && accountName != null && accountName.equals(message.getRecipientUUID())) {
      othersMessageTextView.setText(message.getUnencryptedMessage());
      othersMessageTextView.setVisibility(View.VISIBLE);

      othersMessageTimestampTextView.setText(formatInstant(message.getTimestamp()));
      othersMessageTimestampTextView.setVisibility(View.VISIBLE);

      ownMessageTimestampTextView.setVisibility(View.GONE);
      ownMessageTextView.setVisibility(View.GONE);
    } else {
      // A row this adapter cannot attribute renders as nothing, rather than as whatever the
      // recycled view was showing before.
      //
      // Both arms above set text and visibility on the side they claim and hide the other, so a
      // message matching NEITHER left every field exactly as the previous row had it - and
      // ListView hands the same View back for a different position, so the row would repaint the
      // previous message, on the previous side, under this message's position. The text is one the
      // user was shown before, so nothing new is disclosed; what is wrong is that it is presented
      // as a different message than it is, on the one screen whose job is to say who said what.
      //
      // Not reachable today: every stored message carries the account name in getSenderUUID or
      // getRecipientUUID, so one of the two arms always claims it. This is what the third arm of a
      // two-arm decision is for - the shape of the data changing without this file being read.
      // Clearing the text as well as hiding it, so a later bind that makes a side visible cannot
      // find a stale sentence waiting in it.
      ownMessageTextView.setText("");
      ownMessageTimestampTextView.setText("");
      othersMessageTextView.setText("");
      othersMessageTimestampTextView.setText("");
      ownMessageTextView.setVisibility(View.GONE);
      ownMessageTimestampTextView.setVisibility(View.GONE);
      othersMessageTextView.setVisibility(View.GONE);
      othersMessageTimestampTextView.setVisibility(View.GONE);
    }
    return convertView;
  }

  @Override
  public Object getItem(int position) {
    StorageMessage message = (StorageMessage) mListStorageMessages.get(position);
    return message;
  }

  private String formatInstant(Instant timestamp) {
    return DateTimeFormatter.ofPattern(PATTERN_FORMAT)
        .withZone(ZoneId.systemDefault()).format(timestamp);
  }
}
