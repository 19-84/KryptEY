package com.amnesica.kryptey.inputmethod.latin.e2ee;

import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amnesica.kryptey.inputmethod.BuildConfig;
import com.amnesica.kryptey.inputmethod.R;
import com.amnesica.kryptey.inputmethod.keyboard.MainKeyboardView;
import com.amnesica.kryptey.inputmethod.latin.RichInputConnection;
import com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterContacts;
import com.amnesica.kryptey.inputmethod.latin.e2ee.adapter.ListAdapterMessages;
import com.amnesica.kryptey.inputmethod.latin.e2ee.util.HTMLHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageType;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.Encoder;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.TooManyCharsException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

public class E2EEStripView extends RelativeLayout implements ListAdapterContacts.ListAdapterContactInterface {

  private static final String TAG = E2EEStripView.class.getSimpleName();

  MainKeyboardView mMainKeyboardView;
  E2EEStrip mE2EEStrip;
  Listener mListener;

  private E2EEStripVisibilityGroup mE2EEStripVisibilityGroup;
  private ViewGroup mE2EEMainStrip;

  private RichInputConnection mRichInputConnection;

  // main view
  private LinearLayout mLayoutE2EEMainView;
  private ImageButton mEncryptButton;
  private ImageButton mDecryptButton;
  private ImageButton mRecipientButton;
  private ImageButton mChatLogsButton;
  private ImageButton mShowHelpButton;
  private TextView mInfoTextView;
  private EditText mInputEditText;
  private ImageButton mClearUserInputButton;
  private ImageButton mSelectEncodingFairyTaleButton;
  private ImageButton mSelectEncodingRawButton;

  // add contact view
  private LinearLayout mLayoutE2EEAddContactView;
  private TextView mAddContactInfoTextView;
  private EditText mAddContactFirstNameInputEditText;
  private EditText mAddContactLastNameInputEditText;
  private ImageButton mAddContactCancelButton;
  private ImageButton mAddContactAddButton;

  // contact list view
  private LinearLayout mLayoutE2EEContactListView;
  private TextView mContactListInfoTextView;
  private ListView mContactList;
  private ImageButton mContactListReturnButton;
  private ImageButton mContactListInviteButton; // send pre key response message

  // messages view
  private LinearLayout mLayoutE2EEMessagesListView;
  private TextView mMessagesListInfoTextView;
  private ListView mMessagesList;
  private ImageButton mMessagesListReturnButton;

  // help view
  private LinearLayout mLayoutE2EEHelpView;
  private TextView mHelpInfoTextView;
  private TextView mHelpViewTextView;
  private ImageButton mHelpViewReturnButton;
  private TextView mHelpVersionTextView;

  // verify contact view
  private LinearLayout mLayoutE2EEVerifyContactView;
  private TextView mVerifyContactInfoTextView;
  private TableLayout mVerifyContactTableView;
  private ImageButton mVerifyContactReturnButton;
  private ImageButton mVerifyContactVerifyButton;
  private ImageButton mVerifyContactRejectButton;
  private TextView[] mCodes = new TextView[12];

  private Contact chosenContact;

  /**
   * Changes the recipient, clearing anything staged for the previous one.
   *
   * <p>The input field survived a recipient change it did not cause, and a hostile messenger did
   * not need any cryptographic capability to exploit that. Reposting another contact's OWN earlier
   * invite text - verbatim, no forgery, no keys - sets the chosen contact to them while the field
   * still holds the plaintext just decrypted from somebody else. Driven end to end, one contact's
   * message was re-encrypted and logged to another's.
   *
   * <p>Both routes are messenger-driven and both are invited by the app's own banner: a replayed
   * BUNDLE shows no error at all, and a replayed MESSAGE fails to decrypt and leaves the field
   * untouched. So the fix belongs at the assignment rather than on either path.
   */
  private void setChosenContact(final Contact contact) {
    final boolean changed = chosenContact == null
        ? contact != null
        : !chosenContact.equals(contact);
    chosenContact = contact;
    if (changed && mInputEditText != null && mInputEditText.getText().length() > 0) {
      Log.i(TAG, "Recipient changed; clearing the staged message");
      mInputEditText.setText("");
    }
  }

  private Encoder encodingMethod = Encoder.RAW; // raw is default

  // info texts
  /** Static so {@link #openingMessage} can be decided without an inflated view. */
  static final String INFO_NO_CONTACT_CHOSEN_TEXT = "No contact chosen";

  private final String INFO_NO_CONTACT_CHOSEN = INFO_NO_CONTACT_CHOSEN_TEXT;
  static final String INFO_STORAGE_UNREADABLE =
      "Your saved identity cannot be unlocked on this device. Your contacts and their verified "
          + "keys are still stored but cannot be read. Do NOT re-invite anyone until you have "
          + "checked this - re-inviting replaces every key you have already verified.";
  private final String INFO_PRE_KEY_DETECTED = "Keybundle detected: click on decrypt to save the content";
  private final String INFO_SIGNAL_MESSAGE_DETECTED = "Encrypted message detected: click on decrypt to view message";
  private final String INFO_PRE_KEY_AND_SIGNAL_MESSAGE_DETECTED = "Encrypted update message detected: click on decrypt to view message";
  private final String INFO_ADD_CONTACT = "Add contact to send/receive messages";
  private final String INFO_CONTACT_LIST = "Choose your chat partner to send/receive messages. If you want to chat with someone new, invite them via the add button";
  private final String INFO_HELP = "Q&A";
  private final String INFO_MESSAGES_LIST_DEFAULT = "Choose a contact first to see messages here";
  private final String INFO_NO_SAVED_MESSAGES = "There are no saved messages for this contact";
  private final String INFO_VERIFY_CONTACT = "To verify the security of your end-to-end encryption with %s, compare the numbers above with their device";
  private final String INFO_NO_FINGERPRINT = "No security number is available for this contact yet. Ask them for a key bundle first.";

  // Deliberately does not tell the user to delete the contact. That advice was the app's standard
  // response to any failure here, and an attacker can induce failures at will, so it functioned as
  // a remote instruction to discard a contact's state. Deleting keeps the pinned key either way, so
  // it is no longer a substitution window - but it is still advice that cannot fix anything.
  private final String INFO_SESSION_CREATION_FAILED = "Could not set up a session from that invite. Ask your contact to send a fresh one.";
  private final String INFO_CONTACT_CREATION_FAILED = "Could not create contact. Abort";
  private final String INFO_ADD_FIRSTNAME_ADD_CONTACT = "Enter a first name to create contact";
  private final String INFO_CHOOSE_CONTACT_FIRST = "Please choose a contact first";
  private final String INFO_NO_MESSAGE_TO_ENCRYPT = "No message to encrypt";
  private final String INFO_NO_MESSAGE_TO_DECRYPT = "No message to decrypt";
  private final String INFO_VERIFY_UNAVAILABLE = "Could not verify: no contact is loaded.";
  private final String INFO_PINNED_AFTER_REJECT = "Careful: you previously told the app that %s's number did not match, at this same address. This is a new key for that address - it is NOT automatically the right one. Compare the number by voice before sending anything.";
  private final String INFO_NAME_TOO_LONG = "That name is too long to show next to the contact's address tag. Use a shorter one - the tag is what tells two contacts with similar names apart.";
  private final String INFO_NAME_LOOKS_LIKE_A_TAG = "Names cannot contain '#'. The app shows a tag starting with # beside each contact to tell similar names apart, and a name that imitates one would defeat that.";
  private final String INFO_SAME_ADDRESS_DIFFERENT_NAME = "Not added: this invite is for the identity you already have saved as \"%2$s\", so \"%1$s\" would be a second name for the same person. If you meant to rename them, delete the old contact first. If someone told you this is a different person, they are using an identity you already have to introduce themselves as somebody else.";
  private final String INFO_DUPLICATE_CONTACT_NAME = "You already have a contact called %s, and this is a different one - not a replacement. If they told you they reinstalled, check with them by voice before sending anything: a reinstall really does create a new contact, and so does someone pretending to be them. Both now appear in your list, tagged by address.";

  /**
   * The deleted-contact case needs its own words.
   *
   * <p>It used to reuse the message above, which says "You already have a contact called %s" and
   * "Both now appear in your list". After a deletion neither is true, and the user can see that
   * both are false - there is one row and no other contact of that name. Habituation is the
   * documented failure mode of this control, so a warning that is provably wrong where it fires is
   * worse than the gap it closes.
   */
  static final String INFO_RETIRED_CONTACT_NAME = "You deleted a contact called %s, and this new one has a different address - so it is not the same person coming back. If they told you they reinstalled, check the security number with them by voice before sending anything.";
  // Does not tell the user to obtain the invite "out of band": there is no import path for one -
  // exportOwnKeyBundle and importOutOfBandKeyBundle have no production caller, so the clipboard is
  // the only way a bundle can enter the app. Advising a route that does not exist is the same
  // defect as telling them to check a number that is never displayed.
  private final String INFO_KEY_REJECTED = "Forgot the stored key for %s. Nothing can be sent to them until they send a new invite. When one arrives, compare the number with them by voice before sending anything - this app has already been given a wrong key for them once.";
  private final String INFO_VERIFY_PENDING_CHANGE = "Someone offered a different key for %s since you last spoke - it was refused and is not in use. The number below is the key you already have. If it still matches what they read out, confirm it to dismiss the warning.";
  // Deliberately does NOT offer "they reinstalled" as an explanation. A reinstall mints a fresh
  // address (AddressingPremiseTest), so it cannot collide with an existing pin - a changed key at a
  // pinned address is never a reinstall. Naming it as the likely cause handed the attacker their
  // cover story in the app's own voice. Nor does it ask the user to check "their new number": the
  // offered number is not displayed anywhere, so that instruction could not be followed.
  private final String INFO_IDENTITY_CHANGED_EXISTING = "Someone offered a different key for %s. It was refused and is not in use - your messages still go to the key you already had. Open %s in your contact list and compare the number with them by voice.";
  // Same reasoning as INFO_SESSION_CREATION_FAILED: no deletion advice. The commonest cause by far
  // is decrypting the same message twice, for which deleting anything is pure damage.
  private final String INFO_MESSAGE_DECRYPTION_FAILED = "Message could not be decrypted. Most often this means it was already decrypted once - each message can only be opened a single time. If a new message from this contact also fails, ask them to send a fresh invite.";
  private final String INFO_CANNOT_DECRYPT_OWN_MESSAGES = "You can't decrypt your own messages";
  private final String INFO_SIGNAL_MESSAGE_NO_CONTACT_FOUND = "Please add the contact first";
  private final String INFO_MESSAGE_ENCRYPTION_FAILED = "Message could not be encrypted";
  private final String INFO_UPDATE_CONTACT_FAILED = "Could not update contact information";

  private static class E2EEStripVisibilityGroup {
    private final View mE2EEStripView;
    private final View mE2EEStrip;

    public E2EEStripVisibilityGroup(final View e2EEStripView, final ViewGroup e2EEStrip) {
      mE2EEStripView = e2EEStripView;
      mE2EEStrip = e2EEStrip;
      showE2EEStrip();
    }

    public void showE2EEStrip() {
      mE2EEStrip.setVisibility(VISIBLE);
    }
  }

  /**
   * Construct a {@link E2EEStripView} for showing e2ee functionality.
   *
   * @param context Context
   * @param attrs   AttributeSet
   */
  public E2EEStripView(final Context context, final AttributeSet attrs) {
    this(context, attrs, R.attr.e2eeStripViewStyle);
  }

  public E2EEStripView(final Context context, final AttributeSet attrs, final int defStyle) {
    super(context, attrs, defStyle);

    mE2EEStrip = new E2EEStrip(getContext());

    final LayoutInflater inflater = LayoutInflater.from(context);
    inflater.inflate(R.layout.ee2e_main_view, this);

    setupMainView();
    setupAddContactView();
    setupContactListView();
    setupMessagesListView();
    setupHelpView();
    setupVerifyContactView();

    mE2EEStripVisibilityGroup = new E2EEStripVisibilityGroup(this, mE2EEMainStrip);
  }

  private void setupVerifyContactView() {
    mLayoutE2EEVerifyContactView = findViewById(R.id.e2ee_verify_contact_wrapper);
    mVerifyContactInfoTextView = findViewById(R.id.e2ee_verify_contact_info_text);
    mVerifyContactTableView = findViewById(R.id.e2ee_verify_contact_number_table);
    mVerifyContactReturnButton = findViewById(R.id.e2ee_verify_contact_return_button);
    mVerifyContactVerifyButton = findViewById(R.id.e2ee_verify_contact_verify_button);
    mVerifyContactRejectButton = findViewById(R.id.e2ee_verify_contact_reject_button);
    mCodes[0] = findViewById(R.id.code_first);
    mCodes[1] = findViewById(R.id.code_second);
    mCodes[2] = findViewById(R.id.code_third);
    mCodes[3] = findViewById(R.id.code_fourth);
    mCodes[4] = findViewById(R.id.code_fifth);
    mCodes[5] = findViewById(R.id.code_sixth);
    mCodes[6] = findViewById(R.id.code_seventh);
    mCodes[7] = findViewById(R.id.code_eighth);
    mCodes[8] = findViewById(R.id.code_ninth);
    mCodes[9] = findViewById(R.id.code_tenth);
    mCodes[10] = findViewById(R.id.code_eleventh);
    mCodes[11] = findViewById(R.id.code_twelth);

    createVerifyContactReturnButtonClickListener();
    createVerifyContactVerifyButtonClickListener();
    loadFingerprintInVerifyContactView();

    if (chosenContact == null) return;
    setInfoTextViewMessage(mVerifyContactInfoTextView, String.format(INFO_VERIFY_CONTACT, labelFor(chosenContact)));
  }

  /**
   * "The number does not match." The only control that un-pins a key, and the only correct action
   * when trust-on-first-use pinned an impostor - every other control assumes the pin is genuine.
   */
  private void createVerifyContactRejectButtonClickListener() {
    if (mVerifyContactRejectButton == null) return;
    mVerifyContactRejectButton.setContentDescription(
        "The number does not match - forget this contact's key");
    mVerifyContactRejectButton.setOnClickListener(v -> {
      if (chosenContact == null) return;
      // Capture the label before rejecting: the message names the contact whose key was just
      // forgotten, and reading it afterwards would describe post-rejection state.
      final String label = SignalProtocolMain.displayLabelFor(chosenContact);
      mE2EEStrip.rejectContactKey(chosenContact);
      Toast.makeText(getContext(), String.format(INFO_KEY_REJECTED, label), Toast.LENGTH_LONG).show();
      loadContactsIntoContactsListView();
      showOnlyUIView(UIView.CONTACT_LIST_VIEW);
    });
  }

  private void createVerifyContactVerifyButtonClickListener() {
    if (mVerifyContactVerifyButton == null) return;
    mVerifyContactVerifyButton.setOnClickListener(v -> {
      try {
        // false now means only "no contact or no account loaded" - verification itself no longer
        // refuses. Saying "a different security number was offered" here would fabricate a
        // security claim out of a failed load.
        if (!mE2EEStrip.verifyContact(chosenContact)) {
          Toast.makeText(getContext(), INFO_VERIFY_UNAVAILABLE, Toast.LENGTH_LONG).show();
          return;
        }
        loadContactsIntoContactsListView();
        showOnlyUIView(UIView.CONTACT_LIST_VIEW);
      } catch (UnknownContactException e) {
        Toast.makeText(getContext(), INFO_UPDATE_CONTACT_FAILED, Toast.LENGTH_SHORT).show();
        e.printStackTrace();
      }
    });
  }

  private void loadFingerprintInVerifyContactView() {
    if (chosenContact == null) return;

    createVerifyContactReturnButtonClickListener();
    createVerifyContactRejectButtonClickListener();
    setInfoTextViewMessage(mVerifyContactInfoTextView, String.format(INFO_VERIFY_CONTACT, labelFor(chosenContact)));

    final Fingerprint fingerprint = mE2EEStrip.getFingerprint(chosenContact);
    if (fingerprint == null) {
      // The digit views are members that persist across contact selections. Returning without
      // clearing them left the PREVIOUS contact's twelve numbers on screen under text naming this
      // one - so a user could "compare" and confirm a number belonging to somebody else, for a
      // contact with no key at all.
      clearFingerprintViews();
      setInfoTextViewMessage(mVerifyContactInfoTextView, INFO_NO_FINGERPRINT);
      return;
    }
    if (mVerifyContactVerifyButton != null) mVerifyContactVerifyButton.setEnabled(true);
    if (mVerifyContactRejectButton != null) mVerifyContactRejectButton.setEnabled(true);
    // Tell the user a key was offered BEFORE they compare, so they compare attentively. The digits
    // shown are the pinned key's, which is what makes confirming them a dismissal of the offered
    // key rather than an acceptance of it.
    if (com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain
        .hasUnacceptedIdentityChange(chosenContact.getSignalProtocolAddress())) {
      // labelFor, not the bare first name: this branch is reached whenever a change is pending,
      // which any messenger can arrange with one forged bundle - so it is precisely the state in
      // which the user most needs to know WHICH contact they are looking at.
      setInfoTextViewMessage(mVerifyContactInfoTextView,
          String.format(INFO_VERIFY_PENDING_CHANGE, labelFor(chosenContact)));
    }
    setFingerprintViews(fingerprint, true);
  }

  /** Blanks the safety-number digits and disables confirmation. */
  private void clearFingerprintViews() {
    for (final TextView code : mCodes) {
      if (code != null) code.setText("");
    }
    if (mVerifyContactVerifyButton != null) mVerifyContactVerifyButton.setEnabled(false);
    if (mVerifyContactRejectButton != null) mVerifyContactRejectButton.setEnabled(false);
  }

  private String[] getSegments(Fingerprint fingerprint, int segmentCount) {
    String[] segments = new String[segmentCount];
    String digits = fingerprint.getDisplayableFingerprint().getDisplayText();
    int partSize = digits.length() / segmentCount;

    for (int i = 0; i < segmentCount; i++) {
      segments[i] = digits.substring(i * partSize, (i * partSize) + partSize);
    }

    return segments;
  }

  private void setFingerprintViews(Fingerprint fingerprint, boolean animate) {
    String[] segments = getSegments(fingerprint, mCodes.length);

    for (int i = 0; i < mCodes.length; i++) {
      if (animate) setCodeSegment(mCodes[i], segments[i]);
      else mCodes[i].setText(segments[i]);
    }
  }

  private void setCodeSegment(final TextView codeView, String segment) {
    ValueAnimator valueAnimator = new ValueAnimator();
    valueAnimator.setObjectValues(0, Integer.parseInt(segment));

    valueAnimator.addUpdateListener(animation -> {
      int value = (int) animation.getAnimatedValue();
      codeView.setText(String.format(Locale.getDefault(), "%05d", value));
    });

    valueAnimator.setEvaluator((TypeEvaluator<Integer>) (fraction, startValue, endValue)
        -> Math.round(startValue + (endValue - startValue) * fraction));

    valueAnimator.setDuration(1000);
    valueAnimator.start();
  }

  private void createVerifyContactReturnButtonClickListener() {
    if (mVerifyContactReturnButton == null) return;
    mVerifyContactReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.CONTACT_LIST_VIEW));
  }

  private void setupHelpView() {
    mLayoutE2EEHelpView = findViewById(R.id.e2ee_help_view_wrapper);
    mHelpInfoTextView = findViewById(R.id.e2ee_help_info_text);
    mHelpViewTextView = findViewById(R.id.e2ee_help_view_text);
    mHelpViewReturnButton = findViewById(R.id.e2ee_help_list_return_button);
    mHelpVersionTextView = findViewById(R.id.e2ee_help_view_version_text);

    mHelpViewTextView.setText(Html.fromHtml(getResources().getString(R.string.e2ee_help_view_text), Html.FROM_HTML_SEPARATOR_LINE_BREAK_HEADING));
    mHelpViewTextView.setMovementMethod(new ScrollingMovementMethod());
    setInfoTextViewMessage(mHelpInfoTextView, INFO_HELP);

    mHelpVersionTextView.setText(String.format("%s%s", "v", BuildConfig.VERSION_NAME));

    createHelpReturnButtonClickListener();
  }

  private void createHelpReturnButtonClickListener() {
    if (mHelpViewReturnButton == null) return;
    mHelpViewReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.MAIN_VIEW));
  }

  private void setupMessagesListView() {
    mLayoutE2EEMessagesListView = findViewById(R.id.e2ee_messages_list_wrapper);
    mMessagesListInfoTextView = findViewById(R.id.e2ee_messages_list_info_text);
    mMessagesList = findViewById(R.id.e2ee_messages_list);
    mMessagesListReturnButton = findViewById(R.id.e2ee_messages_list_return_button);

    refreshContactInMessageInfoField();
    createMessagesListReturnButtonClickListener();
    loadMessagesIntoMessagesListView();
  }

  private void refreshContactInMessageInfoField() {
    if (mMessagesListInfoTextView == null) return;
    if (chosenContact != null) {
      setInfoTextViewMessage(mMessagesListInfoTextView, "Message log with: " + labelFor(chosenContact));
    } else {
      setInfoTextViewMessage(mMessagesListInfoTextView, INFO_MESSAGES_LIST_DEFAULT);
    }
  }

  private void loadMessagesIntoMessagesListView() {
    List<StorageMessage> messages = null;
    String accountName = null;

    if (chosenContact != null) {
      try {
        messages = mE2EEStrip.getUnencryptedMessages(chosenContact);
        accountName = mE2EEStrip.getAccountName();
      } catch (UnknownContactException e) {
        Toast.makeText(getContext(), INFO_NO_SAVED_MESSAGES, Toast.LENGTH_SHORT).show();
        Log.d(TAG, INFO_NO_SAVED_MESSAGES);
        e.printStackTrace();
      }
    }

    if (messages == null) {
      messages = new ArrayList<>();
    } else {
      // o1 first, then o2
      messages.sort(Comparator.comparing(StorageMessage::getTimestamp));
    }

    final ArrayList<Object> messagesAsObjectsList = new ArrayList<>(messages);
    final ListAdapterMessages listAdapterMessages = new ListAdapterMessages(this.getContext(), R.layout.e2ee_messages_element_view, messagesAsObjectsList, accountName);
    mMessagesList.setAdapter(listAdapterMessages);

    changeHeightOfMessageListView(messages);
  }

  private void changeHeightOfMessageListView(List<StorageMessage> messages) {
    if (messages == null) return;
    Log.d(TAG, "Setting layout params...");
    LinearLayout.LayoutParams params = null;
    if (messages.size() == 0) {
      params = (LinearLayout.LayoutParams) mMessagesList.getLayoutParams();
      params.height = 0;
      mMessagesList.setLayoutParams(params);
    } else {
      params = (LinearLayout.LayoutParams) mMessagesList.getLayoutParams();
      params.height = 700;
      mMessagesList.setLayoutParams(params);
    }
  }

  private void setupContactListView() {
    mLayoutE2EEContactListView = findViewById(R.id.e2ee_contact_list_wrapper);
    mContactListInfoTextView = findViewById(R.id.e2ee_contact_list_info_text);
    mContactList = findViewById(R.id.e2ee_contact_list);
    mContactListReturnButton = findViewById(R.id.e2ee_contact_list_return_button);
    mContactListInviteButton = findViewById(R.id.e2ee_contact_list_invite_new_contact_button);

    createContactListReturnButtonClickListener();
    createContactListInviteButtonClickListener();

    setInfoTextViewMessage(mContactListInfoTextView, INFO_CONTACT_LIST);

    loadContactsIntoContactsListView();
  }

  private void createMessagesListReturnButtonClickListener() {
    if (mMessagesListReturnButton == null) return;
    mMessagesListReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.MAIN_VIEW));
  }

  private void createContactListReturnButtonClickListener() {
    if (mContactListReturnButton == null) return;
    mContactListReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.MAIN_VIEW));
  }

  private void createContactListInviteButtonClickListener() {
    if (mContactListInviteButton == null) return;
    mContactListInviteButton.setOnClickListener(v -> {
      showOnlyUIView(UIView.MAIN_VIEW);
      sendPreKeyResponseMessageToApplication();
    });
  }

  private void loadContactsIntoContactsListView() {
    ArrayList<Contact> contacts = mE2EEStrip.getContacts();
    if (contacts == null) return;
    final ArrayList<Object> contactsAsObjectsList = new ArrayList<>(contacts);
    final ListAdapterContacts listAdapterContacts = new ListAdapterContacts(this.getContext(), R.layout.e2ee_contact_list_element_view, contactsAsObjectsList);
    listAdapterContacts.setListener(this); // to remove and select contacts on click
    mContactList.setAdapter(listAdapterContacts);
  }

  private void setupAddContactView() {
    mLayoutE2EEAddContactView = findViewById(R.id.e2ee_add_contact_wrapper);
    mAddContactInfoTextView = findViewById(R.id.e2ee_add_contact_info_text);
    mAddContactFirstNameInputEditText = findViewById(R.id.e2ee_add_contact_first_name_input_field);
    mAddContactLastNameInputEditText = findViewById(R.id.e2ee_add_contact_last_name_input_field);
    mAddContactCancelButton = findViewById(R.id.e2ee_add_contact_cancel_button);
    mAddContactAddButton = findViewById(R.id.e2ee_add_contact_button);

    setupFirstNameInputEditTextField();
    setupLastNameInputEditTextField();

    mAddContactInfoTextView.setText(INFO_ADD_CONTACT);

    createAddContactCancelClickListener();
  }

  private void createAddContactAddClickListener(final MessageEnvelope messageEnvelope) {
    if (mAddContactAddButton == null) return;
    mAddContactAddButton.setOnClickListener(v -> addContact(messageEnvelope));
  }

  private void addContact(final MessageEnvelope messageEnvelope) {
    final CharSequence firstName = mAddContactFirstNameInputEditText.getText();
    final CharSequence lastName = mAddContactLastNameInputEditText.getText();

    final String signalProtocolAddressName = messageEnvelope.getSignalProtocolAddressName();
    final int deviceId = messageEnvelope.getDeviceId();
    // Same hazard as above: this device id is peer-supplied, so it must be folded into range.
    final SignalProtocolAddress recipientProtocolAddress = ProtocolAddresses.of(signalProtocolAddressName, deviceId);

    if (!providedContactInformationIsValid(firstName, lastName)) return;

    // The one moment the app can notice the cheapest bypass of the whole trust model. Substituting
    // a key for an existing contact is refused, recorded and warned about; adding a SECOND contact
    // with the same name at an address the messenger controls is a clean first sighting that fires
    // nothing. The story that gets a user there - "phone died, had to reinstall, here's my new
    // invite" - is also true behaviour for a genuine reinstall, so this cannot refuse. It says what
    // happened and lets the user decide.
    final boolean duplicateName = mE2EEStrip.hasContactWithSameDisplayName(
        String.valueOf(firstName), String.valueOf(lastName), recipientProtocolAddress);

    // Exact and unspoofable, unlike the name heuristic above: one address is one identity, so a
    // second contact row at an address already taken is never legitimate. Catches the variant that
    // needs no name trickery - an attacker already present as one contact re-introducing that same
    // address under a new name.
    final Contact sameAddress = mE2EEStrip.existingContactAtSameAddress(
        recipientProtocolAddress, String.valueOf(firstName), String.valueOf(lastName));
    if (sameAddress != null) {
      // Actually refuse, rather than warn and proceed. One address is one identity: creating the
      // row anyway leaves two entries the app cannot tell apart, and because
      // updateContactInContactList matches by address and replaces the FIRST match, verifying the
      // new one silently overwrites the old - erasing the very row the warning pointed at.
      //
      // Three places used to claim this was refused while the code created the contact regardless.
      // Refusing is also the honest reading: a user who genuinely wants to rename someone should
      // delete and re-add, which is unambiguous.
      Toast.makeText(getContext(),
          String.format(INFO_SAME_ADDRESS_DIFFERENT_NAME,
              SignalProtocolMain.sanitizeForBanner(firstName),
              SignalProtocolMain.displayLabelFor(sameAddress)),
          Toast.LENGTH_LONG).show();
      setInfoTextViewMessage(mInfoTextView,
          String.format(INFO_SAME_ADDRESS_DIFFERENT_NAME,
              SignalProtocolMain.sanitizeForBanner(firstName),
              SignalProtocolMain.displayLabelFor(sameAddress)));
      abortContactAdding();
      return;
    }

    // Store the FOLDED device id, not the raw one. Keeping the raw value here left
    // Contact.deviceId and Contact.signalProtocolAddress.getDeviceId() disagreeing for any legacy
    // peer - and the contact list keys off the former while the identity store keys off the latter.
    setChosenContact(mE2EEStrip.createAndAddContactToContacts(firstName, lastName,
        recipientProtocolAddress.getName(), recipientProtocolAddress.getDeviceId()));

    if (chosenContact == null) {
      abortContactAdding();
      return;
    } else {
      Log.d(TAG, "chosenContact = " + chosenContact);
    }

    resetAddContactInputTextFields();
    showOnlyUIView(UIView.MAIN_VIEW);

    // A key arriving where the user previously reported a mismatch is not a first sighting, even
    // though the store looks empty. Without this the forged bundle that provoked the rejection can
    // simply be re-delivered and pinned silently.
    final boolean previouslyRejected = mE2EEStrip.wasKeyRejected(recipientProtocolAddress);

    if (previouslyRejected) {
      final String warning =
          String.format(INFO_PINNED_AFTER_REJECT, labelFor(chosenContact));
      Toast.makeText(getContext(), warning, Toast.LENGTH_LONG).show();
      setInfoTextViewMessage(mInfoTextView, warning);
      mIdentityWarningStanding = true;
    } else if (duplicateName) {
      Toast.makeText(getContext(),
          String.format(duplicateNameMessage(chosenContact), labelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
    }

    if (messageEnvelope.getPreKeyResponse() != null) {
      final boolean successful = mE2EEStrip.createSessionWithContact(chosenContact, messageEnvelope, recipientProtocolAddress);
      if (successful && previouslyRejected) {
        setInfoTextViewMessage(mInfoTextView,
            String.format(INFO_PINNED_AFTER_REJECT, labelFor(chosenContact)));
      } else if (successful && duplicateName) {
        setInfoTextViewMessage(mInfoTextView,
            String.format(duplicateNameMessage(chosenContact), labelFor(chosenContact)));
      } else if (successful) {
        setInfoTextViewMessage(mInfoTextView, "Contact " + labelFor(chosenContact) + " created. You can send messages now");
      } else if (!warnIfIdentityChanged(chosenContact)) {
        // createSessionWithContact already writes INFO_IDENTITY_CHANGED when a change is pending,
        // and this used to overwrite it with INFO_SESSION_CREATION_FAILED - the same delete-and-
        // re-invite advice - defeating its own guard.
        setInfoTextViewMessage(mInfoTextView, INFO_SESSION_CREATION_FAILED);
      }
    }

    if (messageEnvelope.getCiphertextMessage() != null) {
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
      changeImageButtonState(mDecryptButton, ButtonState.DISABLED);
    }
  }

  private void abortContactAdding() {
    Toast.makeText(getContext(), INFO_CONTACT_CREATION_FAILED, Toast.LENGTH_SHORT).show();
    Log.d(TAG, INFO_CONTACT_CREATION_FAILED);
    showOnlyUIView(UIView.MAIN_VIEW);
    resetChosenContactAndInfoText();
  }

  private void resetAddContactInputTextFields() {
    mAddContactFirstNameInputEditText.setText("");
    mAddContactLastNameInputEditText.setText("");
  }

  /**
   * Longest display name accepted.
   *
   * <p>No longer load-bearing, and deliberately generous. It was 32, chosen to stop a long name
   * crowding the address tag off its row — which was the wrong lever twice over: the cap counts
   * characters while the layout consumes width, so eleven EM SPACEs beat any character cap on any
   * device; and it rejected ordinary names outright, "Maria del Carmen Fernández" among them. The
   * tag now has its own anchored view and the name ellipsises instead, so this only exists to keep
   * a pathological paste out of the banners.
   */
  private static final int MAX_DISPLAY_NAME_CHARS = 96;

  private boolean providedContactInformationIsValid(CharSequence firstName, CharSequence lastName) {
    if (firstName == null || firstName.length() == 0) {
      Toast.makeText(getContext(), INFO_ADD_FIRSTNAME_ADD_CONTACT, Toast.LENGTH_SHORT).show();
      return false;
    }

    // The name shares a text field with the address tag, so unbounded and unsanitised names let an
    // attacker attack the tag through the name.
    //
    // One concrete move this stops: a name containing '#' can imitate a tag outright, because the
    // invite text is written by the attacker and the app has been teaching the user that tags
    // identify contacts. It does NOT stop a long name crowding the tag - that is the layout's job
    // now, and an earlier version of this comment claimed the cap handled it while describing the
    // pre-fix layout.
    final int total = firstName.length() + (lastName == null ? 0 : lastName.length());
    if (total > MAX_DISPLAY_NAME_CHARS) {
      Toast.makeText(getContext(), INFO_NAME_TOO_LONG, Toast.LENGTH_LONG).show();
      return false;
    }
    if (SignalProtocolMain.displayNameImitatesATag(firstName)
        || SignalProtocolMain.displayNameImitatesATag(lastName)) {
      Toast.makeText(getContext(), INFO_NAME_LOOKS_LIKE_A_TAG, Toast.LENGTH_LONG).show();
      return false;
    }
    return true;
  }



  private void createAddContactCancelClickListener() {
    if (mAddContactCancelButton != null) {
      mAddContactCancelButton.setOnClickListener(v -> {
        showOnlyUIView(UIView.MAIN_VIEW);
        setInfoTextViewMessage(mInfoTextView, INFO_NO_CONTACT_CHOSEN);
        mE2EEStrip.clearClipboard();
      });
    }
  }

  private void changeImageButtonState(ImageButton imageButton, ButtonState state) {
    if (state.equals(ButtonState.ENABLED)) {
      imageButton.setEnabled(true);
    } else if (state.equals(ButtonState.DISABLED)) {
      imageButton.setEnabled(false);
    }
  }

  private void setupMainView() {
    mLayoutE2EEMainView = findViewById(R.id.e2ee_main_wrapper);
    mE2EEMainStrip = findViewById(R.id.e2ee_main_button_strip);
    mEncryptButton = findViewById(R.id.e2ee_button_encrypt);
    mDecryptButton = findViewById(R.id.e2ee_button_decrypt);
    mRecipientButton = findViewById(R.id.e2ee_button_select_recipient);
    mChatLogsButton = findViewById(R.id.e2ee_button_chat_logs);
    mShowHelpButton = findViewById(R.id.e2ee_button_show_help);
    mInfoTextView = findViewById(R.id.e2ee_info_text);
    mInputEditText = findViewById(R.id.e2ee_input_field);
    mClearUserInputButton = findViewById(R.id.e2ee_button_clear_text);
    mSelectEncodingFairyTaleButton = findViewById(R.id.e2ee_button_select_encoding_fairytale);
    mSelectEncodingRawButton = findViewById(R.id.e2ee_button_select_encoding_raw);

    setMainInfoTextTextChangeListener();
    setMainInfoTextClearChosenContactListener();
    setInfoTextViewMessage(mInfoTextView, openingMessage(SignalProtocolMain.storageState()));

    createButtonEncryptClickListener();
    createButtonDecryptClickListener();
    createButtonClearUserInputClickListener();
    createButtonRecipientClickListener();
    createButtonSelectEncryptionMethodClickListener();
    createButtonChatLogsClickListener();
    createButtonShowHelpClickListener();

    setupMessageInputEditTextField();

    initClipboardListenerToChangeStateOfDecryptButton();
  }

  private void setMainInfoTextClearChosenContactListener() {
    if (mInfoTextView == null) return;
    mInfoTextView.setOnClickListener(v -> resetChosenContactAndInfoText());
  }

  private void initClipboardListenerToChangeStateOfDecryptButton() {
    final ClipboardManager clipboardManager = (ClipboardManager) this.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    clipboardManager.addPrimaryClipChangedListener(() -> {
      try {
        String item = null;
        boolean isHTML = false;
        // hint: listener for HTML text needed for using app with telegram
        if (clipboardManager.getPrimaryClipDescription() != null &&
            (clipboardManager.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                clipboardManager.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
          isHTML = clipboardManager.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
          // getPrimaryClip() can be null even when getPrimaryClipDescription() is not - since
          // Android 10 it returns null whenever this IME is not the active input method - and a
          // clip can legitimately hold zero items.
          final android.content.ClipData clip = clipboardManager.getPrimaryClip();
          if (clip == null || clip.getItemCount() == 0) return;
          item = String.valueOf(clip.getItemAt(0).getText());
        }

        if (item == null || item.isEmpty()) return;
        if (isHTML) {
          item = HTMLHelper.replaceHtmlCharacters(item);
        }

        final String decodedItem = mE2EEStrip.decodeMessage(item);
        if (decodedItem == null) return;

        // An unreadable store must keep its warning.
        //
        // decodeMessage and getMessageType need no account, so they run happily in that state -
        // and the first KryptEY-shaped thing the user copies used to overwrite the info text,
        // which re-enabled both buttons through the TextWatcher and wiped the "do NOT re-invite
        // anyone" line. That line is the part that actually protects their pins, and the copy that
        // erased it is the attacker's invite: the whole workflow is copy-then-paste, so the
        // warning survived exactly until the moment it mattered.
        if (storageIsUnreadable()) {
          Log.i(TAG, "Storage cannot be decrypted; leaving the warning in place");
          return;
        }

        // Parse once. This ran on every clipboard change and used to deserialize up to three
        // times, and getMessageType returns null for anything unrecognised - which then NPE'd on
        // .equals() inside a system clipboard callback.
        final MessageType clipboardType =
            mE2EEStrip.getMessageType(EnvelopeCodec.fromWire(decodedItem));
        if (clipboardType == MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE) {
          changeImageButtonState(mDecryptButton, ButtonState.ENABLED);
          setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_AND_SIGNAL_MESSAGE_DETECTED);
        } else if (clipboardType == MessageType.PRE_KEY_RESPONSE_MESSAGE) {
          changeImageButtonState(mDecryptButton, ButtonState.ENABLED);
          setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_DETECTED);
        } else if (clipboardType == MessageType.SIGNAL_MESSAGE) {
          // Was mEncryptButton, which is a copy-paste slip - an inbound message enables decrypt.
          // Inert either way: setInfoTextViewMessage below fires a TextWatcher that enables both
          // buttons for any info text other than INFO_NO_CONTACT_CHOSEN, so all three of these
          // calls are dead. Corrected rather than deleted so the branch reads as what it means.
          changeImageButtonState(mDecryptButton, ButtonState.ENABLED);
          setInfoTextViewMessage(mInfoTextView, INFO_SIGNAL_MESSAGE_DETECTED);
        }
      } catch (IOException e) {
        // Expected constantly: the clipboard usually holds ordinary text.
        Log.d(TAG, "Clipboard content is not a KryptEY message");
      } catch (Exception e) {
        // Nothing may escape a system clipboard callback: an unchecked exception here takes the
        // whole input-method process down, in every app, on an ordinary copy.
        Log.e(TAG, "Unexpected failure inspecting the clipboard", e);
      }
    });
  }

  /**
   * Whether an info message means encrypt and decrypt cannot work.
   *
   * <p>Separated so it can be tested: the watcher itself needs an inflated IME, and this is the
   * decision, not the wiring.
   */
  static boolean disablesActionButtons(final String message) {
    return INFO_NO_CONTACT_CHOSEN_TEXT.equals(message) || INFO_STORAGE_UNREADABLE.equals(message);
  }

  private void setMainInfoTextTextChangeListener() {
    if (mInfoTextView == null) return;
    mInfoTextView.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        // The buttons are driven by the info TEXT, which means every new message added here is a
        // decision about whether encrypt and decrypt are usable - and the default for an
        // unrecognised message is ENABLED.
        //
        // Adding INFO_STORAGE_UNREADABLE re-enabled both buttons on an install whose account
        // cannot be decrypted, because it simply was not INFO_NO_CONTACT_CHOSEN. There is no
        // account to encrypt with in that state, so the buttons offered an action that cannot
        // work. Coupling behaviour to a string comparison makes that the failure mode for the next
        // message too; naming the disabled states is the least that keeps it visible.
        if (disablesActionButtons(s.toString())) {
          changeImageButtonState(mDecryptButton, ButtonState.DISABLED);
          changeImageButtonState(mEncryptButton, ButtonState.DISABLED);
        } else {
          changeImageButtonState(mDecryptButton, ButtonState.ENABLED);
          changeImageButtonState(mEncryptButton, ButtonState.ENABLED);
        }
      }
    });
  }

  /**
   * Whether the account on disk cannot be decrypted.
   *
   * <p>Read live rather than cached at construction: the strip is built once and the state can only
   * be discovered when storage is next touched.
   */
  private boolean storageIsUnreadable() {
    return SignalProtocolMain.storageState() == StorageHelper.StorageState.UNREADABLE;
  }

  /**
   * What the strip says when it first opens.
   *
   * <p>A user whose storage cannot be decrypted used to see "No contact chosen" - byte-identical to
   * a healthy install that simply has no contacts yet. That is the worst possible reading of the
   * situation: their identity key, sessions and verified contacts are all still on disk under a key
   * that no longer exists, and the obvious response to an apparently empty app is to re-invite
   * everyone. Re-inviting replaces every pin they had already compared, so a key loss silently
   * becomes a fresh trust-on-first-use window for every contact at once.
   *
   * <p>Separated from the view so the decision can be tested without an inflated IME. The wiring
   * above is the one call that uses it.
   */
  static String openingMessage(final StorageHelper.StorageState state) {
    return state == StorageHelper.StorageState.UNREADABLE
        ? INFO_STORAGE_UNREADABLE : INFO_NO_CONTACT_CHOSEN_TEXT;
  }

  /**
   * Which duplicate-name wording applies: a live contact of that name, or a deleted one.
   *
   * <p>Separated so the choice can be tested without an inflated IME.
   */
  private String duplicateNameMessage(final Contact contact) {
    final boolean live = SignalProtocolMain.hasContactWithSameDisplayName(
        contact.getFirstName(), contact.getLastName(), contact.getSignalProtocolAddress())
        && !SignalProtocolMain.hasRetiredDisplayName(contact.getFirstName(),
            contact.getLastName(), contact.getSignalProtocolAddress());
    return live ? INFO_DUPLICATE_CONTACT_NAME : INFO_RETIRED_CONTACT_NAME;
  }

  private void setInfoTextViewMessage(final TextView textView, final String message) {
    if (textView == null) return;
    textView.setText(message);
  }

  private void createButtonEncryptClickListener() {
    if (mEncryptButton == null) return;
    mEncryptButton.setOnClickListener(v -> encryptAndSendInputFieldContent());
  }

  private void encryptAndSendInputFieldContent() {
    if (chosenContact == null) {
      Toast.makeText(getContext(), INFO_CHOOSE_CONTACT_FIRST, Toast.LENGTH_SHORT).show();
      return;
    }

    if (mInputEditText != null && mInputEditText.getText().length() > 0) {
      // call encrypt method and encrypt text
      final CharSequence encryptedMessage;
      try {
        encryptedMessage = mE2EEStrip.encryptMessage(mInputEditText.getText().toString(), chosenContact.getSignalProtocolAddress(), encodingMethod);
        Log.d(TAG, String.valueOf(encryptedMessage));

        if (encryptedMessage != null) {
          mInputEditText.setText(encryptedMessage);
          sendEncryptedMessageToApplication(encryptedMessage);
        } else {
          Toast.makeText(getContext(), INFO_MESSAGE_ENCRYPTION_FAILED, Toast.LENGTH_SHORT).show();
          Log.e(TAG, "Error: Encrypted message is null!");
        }
      } catch (TooManyCharsException e) {
        Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        Log.e(TAG, e.getMessage());
        e.printStackTrace();
      } catch (IOException e) {
        Toast.makeText(getContext(), INFO_MESSAGE_ENCRYPTION_FAILED, Toast.LENGTH_SHORT).show();
        Log.e(TAG, "Error: Encrypted message is null!");
        e.printStackTrace();
      }
    } else {
      Toast.makeText(getContext(), INFO_NO_MESSAGE_TO_ENCRYPT, Toast.LENGTH_SHORT).show();
    }
    showChosenContactInMainInfoField();
  }

  private void createButtonDecryptClickListener() {
    if (mDecryptButton == null) return;
    mDecryptButton.setOnClickListener(v -> decryptMessageInClipboard());
  }

  private void createButtonRecipientClickListener() {
    if (mRecipientButton != null) {
      mRecipientButton.setOnClickListener(v -> {
        loadContactsIntoContactsListView();
        showOnlyUIView(UIView.CONTACT_LIST_VIEW);
      });
    }
  }

  private void showOnlyUIView(final UIView uiView) {
    if (mLayoutE2EEMainView == null || mLayoutE2EEAddContactView == null ||
        mLayoutE2EEContactListView == null || mLayoutE2EEMessagesListView == null)
      return;

    if (uiView.equals(UIView.MAIN_VIEW)) {
      mLayoutE2EEMainView.setVisibility(VISIBLE);
      mLayoutE2EEAddContactView.setVisibility(GONE);
      mLayoutE2EEContactListView.setVisibility(GONE);
      mLayoutE2EEMessagesListView.setVisibility(GONE);
      mLayoutE2EEHelpView.setVisibility(GONE);
      mLayoutE2EEVerifyContactView.setVisibility(GONE);
    } else if (uiView.equals(UIView.ADD_CONTACT_VIEW)) {
      mLayoutE2EEMainView.setVisibility(GONE);
      mLayoutE2EEAddContactView.setVisibility(VISIBLE);
      mLayoutE2EEContactListView.setVisibility(GONE);
      mLayoutE2EEMessagesListView.setVisibility(GONE);
      mLayoutE2EEHelpView.setVisibility(GONE);
      mLayoutE2EEVerifyContactView.setVisibility(GONE);
    } else if (uiView.equals(UIView.CONTACT_LIST_VIEW)) {
      mLayoutE2EEMainView.setVisibility(GONE);
      mLayoutE2EEAddContactView.setVisibility(GONE);
      mLayoutE2EEContactListView.setVisibility(VISIBLE);
      mLayoutE2EEMessagesListView.setVisibility(GONE);
      mLayoutE2EEHelpView.setVisibility(GONE);
      mLayoutE2EEVerifyContactView.setVisibility(GONE);
    } else if (uiView.equals(UIView.MESSAGES_LIST_VIEW)) {
      mLayoutE2EEMainView.setVisibility(GONE);
      mLayoutE2EEAddContactView.setVisibility(GONE);
      mLayoutE2EEContactListView.setVisibility(GONE);
      mLayoutE2EEMessagesListView.setVisibility(VISIBLE);
      mLayoutE2EEHelpView.setVisibility(GONE);
      mLayoutE2EEVerifyContactView.setVisibility(GONE);
    } else if (uiView.equals(UIView.HELP_VIEW)) {
      mLayoutE2EEMainView.setVisibility(GONE);
      mLayoutE2EEAddContactView.setVisibility(GONE);
      mLayoutE2EEContactListView.setVisibility(GONE);
      mLayoutE2EEMessagesListView.setVisibility(GONE);
      mLayoutE2EEHelpView.setVisibility(VISIBLE);
      mLayoutE2EEVerifyContactView.setVisibility(GONE);
    } else if (uiView.equals(UIView.VERIFY_CONTACT_VIEW)) {
      mLayoutE2EEMainView.setVisibility(GONE);
      mLayoutE2EEAddContactView.setVisibility(GONE);
      mLayoutE2EEContactListView.setVisibility(GONE);
      mLayoutE2EEMessagesListView.setVisibility(GONE);
      mLayoutE2EEHelpView.setVisibility(GONE);
      mLayoutE2EEVerifyContactView.setVisibility(VISIBLE);
    }
  }

  private void createButtonClearUserInputClickListener() {
    if (mClearUserInputButton == null) return;
    mClearUserInputButton.setOnClickListener(v -> clearUserInputString());
  }

  private void createButtonSelectEncryptionMethodClickListener() {
    if (mSelectEncodingFairyTaleButton == null || mSelectEncodingRawButton == null) return;

    mSelectEncodingFairyTaleButton.setOnClickListener(v -> {
      mSelectEncodingFairyTaleButton.setVisibility(GONE);
      mSelectEncodingRawButton.setVisibility(VISIBLE);
      encodingMethod = Encoder.RAW;
    });

    mSelectEncodingRawButton.setOnClickListener(v -> {
      mSelectEncodingFairyTaleButton.setVisibility(VISIBLE);
      mSelectEncodingRawButton.setVisibility(GONE);
      encodingMethod = Encoder.FAIRYTALE;
    });
  }

  private void createButtonShowHelpClickListener() {
    if (mShowHelpButton == null) return;
    mShowHelpButton.setOnClickListener(v -> {
      showOnlyUIView(UIView.HELP_VIEW);
    });
  }

  private void createButtonChatLogsClickListener() {
    if (mChatLogsButton == null) return;
    mChatLogsButton.setOnClickListener(v -> {
      refreshContactInMessageInfoField();
      loadMessagesIntoMessagesListView();
      showOnlyUIView(UIView.MESSAGES_LIST_VIEW);
    });
  }

  private void setupMessageInputEditTextField() {
    mInputEditText.setMovementMethod(new ScrollingMovementMethod());
    mInputEditText.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) mRichInputConnection.setOtherIC(mInputEditText);
      mRichInputConnection.setShouldUseOtherIC(hasFocus);
      changeVisibilityInputFieldButtons(hasFocus);
    });

    mClearUserInputButton.setVisibility(GONE);
    mSelectEncodingFairyTaleButton.setVisibility(GONE);
  }

  private void setupFirstNameInputEditTextField() {
    mAddContactFirstNameInputEditText.setMovementMethod(new ScrollingMovementMethod());
    mAddContactFirstNameInputEditText.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) mRichInputConnection.setOtherIC(mAddContactFirstNameInputEditText);
      mRichInputConnection.setShouldUseOtherIC(hasFocus);
    });
  }

  private void setupLastNameInputEditTextField() {
    mAddContactLastNameInputEditText.setMovementMethod(new ScrollingMovementMethod());
    mAddContactLastNameInputEditText.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) mRichInputConnection.setOtherIC(mAddContactLastNameInputEditText);
      mRichInputConnection.setShouldUseOtherIC(hasFocus);
    });
  }

  private void sendPreKeyResponseMessageToApplication() {
    final String encoded;
    try {
      // Serialization can now fail (the binary codec validates what it is given), so it belongs
      // inside the try rather than ahead of it.
      final String message = mE2EEStrip.getPreKeyResponseMessage();
      if (message == null) throw new IOException("Could not build a key bundle");
      mE2EEStrip.checkMessageLengthForEncodingMethod(message, encodingMethod, true);
      encoded = mE2EEStrip.encode(message, encodingMethod);
    } catch (TooManyCharsException e) {
      Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
      Log.e(TAG, e.getMessage());
      e.printStackTrace();
      return;
    } catch (IOException e) {
      Toast.makeText(getContext(), "Generating pre key message failed!", Toast.LENGTH_SHORT).show();
      Log.e(TAG, "Generating pre key message failed!");
      e.printStackTrace();
      return;
    }
    mInputEditText.setText(encoded);
    sendEncryptedMessageToApplication(encoded);
  }

  private void decryptMessageInClipboard() {
    final CharSequence mEncryptedMessageFromClipboard = mE2EEStrip.getEncryptedMessageFromClipboard();
    if (mEncryptedMessageFromClipboard == null || mEncryptedMessageFromClipboard.length() == 0) {
      Toast.makeText(getContext(), INFO_NO_MESSAGE_TO_DECRYPT, Toast.LENGTH_SHORT).show();
      return;
    }

    try {
      final String encodedMessage = mE2EEStrip.decodeMessage(mEncryptedMessageFromClipboard.toString());

      final MessageEnvelope messageEnvelope = EnvelopeCodec.fromWire(encodedMessage);
      if (messageEnvelope == null) throw new IOException("Message is null. Abort!");

      final MessageType messageType = mE2EEStrip.getMessageType(messageEnvelope);
      if (messageType == null) throw new IOException("Message type is null. Abort!");

      final Contact extractedSender = (Contact) mE2EEStrip.getContactFromEnvelope(messageEnvelope);
      if (messageEnvelope.getSignalProtocolAddressName().equals(mE2EEStrip.getAccountName())) {
        Toast.makeText(getContext(), INFO_CANNOT_DECRYPT_OWN_MESSAGES, Toast.LENGTH_SHORT).show();
        mE2EEStrip.clearClipboard();
        showChosenContactInMainInfoField();
        return;
      }

      if (messageType.equals(MessageType.PRE_KEY_RESPONSE_MESSAGE)) {
        processPreKeyResponse(messageEnvelope, extractedSender);
      } else if (messageType.equals(MessageType.SIGNAL_MESSAGE)) {
        processSignalMessage(messageEnvelope, extractedSender);
      } else if (messageType.equals(MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE)) {
        processUpdatedPreKeyResponse(messageEnvelope, extractedSender);
      }
    } catch (IOException e) {
      e.printStackTrace();
      resetChosenContactAndInfoText();
    }
    // Only reset the info field if nothing more important is standing in it. This unconditionally
    // overwrote the identity-change warning one frame after it was set, on the clipboard path -
    // which is the only path an attacker's envelope takes - leaving a ~3.5s toast as the entire
    // signal, over a screen that otherwise looked like an ordinary success.
    if (!mIdentityWarningStanding) showChosenContactInMainInfoField();
    mIdentityWarningStanding = false;
    mE2EEStrip.clearClipboard();
    changeImageButtonState(mDecryptButton, ButtonState.DISABLED);
  }

  private void processSignalMessage(MessageEnvelope messageEnvelope, Contact sender) {
    if (sender == null) {
      // if no contact found, show add contact view
      Toast.makeText(getContext(), INFO_SIGNAL_MESSAGE_NO_CONTACT_FOUND, Toast.LENGTH_SHORT).show();
      showAddContactView(messageEnvelope);
    } else {
      setChosenContact(sender);
      setInfoTextViewMessage(mInfoTextView, "Detected contact: " + labelFor(chosenContact));
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
    }
  }

  private void processPreKeyResponse(MessageEnvelope messageEnvelope, Contact sender) {
    setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_DETECTED);
    if (sender == null) {
      // add contact with preKey message
      showAddContactView(messageEnvelope);
    } else {
      // update contact with preKey information
      setChosenContact(sender);
      setInfoTextViewMessage(mInfoTextView, "Detected contact: " + labelFor(chosenContact));
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, true);
    }
  }

  private void processUpdatedPreKeyResponse(MessageEnvelope messageEnvelope, Contact sender) {
    // debug only Toast.makeText(getContext(), "Updated signed pre key detected!", Toast.LENGTH_SHORT).show();
    if (sender == null) {
      // contact was not added before -> proceed as normal preKeyMessage
      processPreKeyResponse(messageEnvelope, sender);
    } else {
      // update contact with preKey information
      setChosenContact(sender);
      setInfoTextViewMessage(mInfoTextView, "Detected contact with updated keybundle: " + labelFor(chosenContact));
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
    }
  }

  private void resetChosenContactAndInfoText() {
    setChosenContact(null);
    setInfoTextViewMessage(mInfoTextView, INFO_NO_CONTACT_CHOSEN);
  }

  private void showAddContactView(MessageEnvelope messageEnvelope) {
    createAddContactAddClickListener(messageEnvelope);
    showOnlyUIView(UIView.ADD_CONTACT_VIEW);
  }

  /** Set while an identity-change warning is on screen, so the info field is not reset over it. */
  private boolean mIdentityWarningStanding = false;

  /** How a contact is named on screen. See {@code SignalProtocolMain.displayLabelFor}. */
  private String labelFor(final Contact contact) {
    return SignalProtocolMain.displayLabelFor(contact);
  }




  /**
   * Shows the identity-change warning if one is pending for this sender.
   *
   * @return true if a warning was shown, in which case the caller must not also show the generic
   *     failure advice - that advice tells the user to delete and re-invite, which is the wrong
   *     move for an impersonation attempt, which at a pinned address is the only possibility.
   */
  private boolean warnIfIdentityChanged(final Contact sender) {
    if (sender == null) return false;
    if (!com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain
        .hasUnacceptedIdentityChange(sender.getSignalProtocolAddress())) {
      return false;
    }
    final String warning =
        String.format(INFO_IDENTITY_CHANGED_EXISTING, labelFor(sender), labelFor(sender));
    Toast.makeText(getContext(), warning, Toast.LENGTH_LONG).show();
    setInfoTextViewMessage(mInfoTextView, warning);
    mIdentityWarningStanding = true;
    return true;
  }

  private void decryptMessageAndShowMessageInMainInputField(final MessageEnvelope messageEnvelope, final Contact sender, boolean isSessionCreation) {
    final CharSequence decryptedMessage = mE2EEStrip.decryptMessage(messageEnvelope, sender);

    // Check before branching: a substitution recorded during this decrypt attempt must be reported
    // even on the paths that otherwise look like success (a bundle-only re-invite advances the UI
    // exactly as a good one does), and must displace the generic advice on the failure path.
    final boolean identityChanged = warnIfIdentityChanged(sender);

    if (!isSessionCreation && decryptedMessage != null) {
      mInputEditText.setText(decryptedMessage);
      changeVisibilityInputFieldButtons(true);
    } else if (isSessionCreation) {
      changeVisibilityInputFieldButtons(true);
    } else if (!identityChanged) {
      Toast.makeText(getContext(), INFO_MESSAGE_DECRYPTION_FAILED, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Error: Decrypted message is null");
    }
    mE2EEStrip.clearClipboard();
  }

  private void sendEncryptedMessageToApplication(CharSequence encryptedMessage) {
    if (encryptedMessage == null) return;

    mRichInputConnection.setShouldUseOtherIC(false);
    mListener.onTextInput((String) encryptedMessage);
    mInputEditText.clearFocus();
    clearUserInputString();
    mE2EEStrip.clearClipboard();
  }

  private void clearUserInputString() {
    if (mInputEditText != null) mInputEditText.setText("");
  }

  private void changeVisibilityInputFieldButtons(boolean shouldBeVisible) {
    if (mClearUserInputButton != null && mSelectEncodingFairyTaleButton != null && mSelectEncodingRawButton != null) {
      if (shouldBeVisible) {
        mClearUserInputButton.setVisibility(VISIBLE);
        if (encodingMethod.equals(Encoder.FAIRYTALE)) {
          mSelectEncodingFairyTaleButton.setVisibility(VISIBLE);
        } else {
          mSelectEncodingRawButton.setVisibility(VISIBLE);
        }
      } else {
        mClearUserInputButton.setVisibility(GONE);
        mSelectEncodingFairyTaleButton.setVisibility(GONE);
        mSelectEncodingRawButton.setVisibility(GONE);
      }
    }
  }

  private void showChosenContactInMainInfoField() {
    if (chosenContact != null) {
      setInfoTextViewMessage(mInfoTextView, "Chosen contact: " + labelFor(chosenContact));
    } else {
      setInfoTextViewMessage(mInfoTextView, INFO_NO_CONTACT_CHOSEN);
    }
  }

  private enum ButtonState {ENABLED, DISABLED}

  private enum UIView {MAIN_VIEW, ADD_CONTACT_VIEW, CONTACT_LIST_VIEW, MESSAGES_LIST_VIEW, HELP_VIEW, VERIFY_CONTACT_VIEW}

  @Override
  public void selectContact(Contact contact) {
    setChosenContact(contact);
    showChosenContactInMainInfoField();
    Log.d(TAG, chosenContact.toString());
    showOnlyUIView(UIView.MAIN_VIEW);
  }

  @Override
  public void removeContact(Contact contact) {
    mE2EEStrip.removeContact(contact);
    loadContactsIntoContactsListView();
    resetChosenContactAndInfoText();
  }

  @Override
  public void verifyContact(Contact contact) {
    setChosenContact(contact);
    Log.d(TAG, chosenContact.toString());
    loadFingerprintInVerifyContactView();
    showOnlyUIView(UIView.VERIFY_CONTACT_VIEW);
  }

  public void setRichInputConnection(RichInputConnection richInputConnection) {
    mRichInputConnection = richInputConnection;
  }

  public void clearFocusEditTextView() {
    if (mInputEditText != null) mInputEditText.clearFocus();
  }

  /**
   * A connection back to the input method.
   *
   * @param listener Listener
   */
  public void setListener(final Listener listener, final View inputView) {
    mListener = listener;
    mMainKeyboardView = inputView.findViewById(R.id.keyboard_view);
  }

  public void clear() {
    mE2EEMainStrip.removeAllViews();
    mE2EEStripVisibilityGroup.showE2EEStrip();
  }

  public interface Listener {
    void onTextInput(final String rawText);
  }
}
