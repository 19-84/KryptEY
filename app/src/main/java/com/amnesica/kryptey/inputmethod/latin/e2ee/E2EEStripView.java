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
      clearStandingWarning();   // saying the number does not match IS the deliberate response
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
        // Only now: the user has compared the number and confirmed it. Not on arriving at the
        // screen, and not on a failed load, which is why this sits after the guard above.
        clearStandingWarning();
        loadContactsIntoContactsListView();
        showOnlyUIView(UIView.CONTACT_LIST_VIEW);
      } catch (UnknownContactException e) {
        Toast.makeText(getContext(), INFO_UPDATE_CONTACT_FAILED, Toast.LENGTH_SHORT).show();
        e.printStackTrace();
      }
    });
  }

  /**
   * Whether anything on screen must not appear in a screenshot or screen recording.
   *
   * <p>Every screen this strip shows holds something the messenger is not supposed to have: the
   * main view holds decrypted plaintext in its input field, the message list holds the entire
   * decrypted conversation, the verify screen holds the safety-number digits a user is about to
   * compare, and the contact list holds who they talk to and the tags that distinguish them.
   *
   * <p>An app whose whole premise is that the messenger cannot read the message should not paint
   * the message into a capturable window. Nothing in this project had ever set {@code FLAG_SECURE}
   * - there was not one occurrence in the source - and the E2EE surface is not an Activity but a
   * view inlined into the IME, so it never inherited one from anywhere either.
   *
   * <p>The flag is applied only while one of these is up, rather than for the keyboard's whole
   * life, so ordinary typing in other apps still screenshots normally. That is a product judgement
   * as much as a security one and it is stated here rather than buried.
   */
  boolean isShowingSensitiveContent() {
    return isUp(mLayoutE2EEMessagesListView)
        || isUp(mLayoutE2EEVerifyContactView)
        || isUp(mLayoutE2EEContactListView)
        || (isUp(mLayoutE2EEMainView) && mInputEditText != null
            && mInputEditText.getText().length() > 0);
  }

  private static boolean isUp(final View view) {
    return view != null && view.getVisibility() == VISIBLE;
  }

  /** Tells the IME whether the window should carry FLAG_SECURE right now. */
  private void notifySensitiveVisibility() {
    if (mListener != null) {
      mListener.onSensitiveContentVisibilityChanged(isShowingSensitiveContent());
    }
  }

  /**
   * Whether the field the user is typing into is a password field.
   *
   * <p>Set by {@code LatinIME} as each input session starts. When it is, encrypt and decrypt are
   * refused: decrypting writes the plaintext into whatever field has focus, and writing a decrypted
   * message into another app's password box hands it to that app's own storage, autofill and
   * whatever it syncs. Encrypting is the mirror - a password typed into the compose box would be
   * encrypted and pasted somewhere as ciphertext nobody wants.
   *
   * <p>Nothing checked this. The strip is inlined into the keyboard, so it appears over every field
   * the keyboard serves, including password fields, and offered both actions there.
   */
  private boolean mHostFieldIsPassword;

  /** Called by the IME as each input session starts. */
  public void setHostFieldIsPassword(final boolean isPassword) {
    mHostFieldIsPassword = isPassword;
    if (isPassword) {
      clearDecryptedContent();
      // Not over a standing warning. LatinIME calls this on EVERY input session with the host
      // field's inputType, and the messenger owns the inputType of every field it presents - so
      // "your session expired, re-enter your PIN" erased the substitution warning, and the flag
      // stayed set, which meant nothing could ever write the banner again. The strip was left
      // reading "encryption is turned off here" while the actions were back on and working.
      setInfoUnlessWarned(INFO_PASSWORD_FIELD);
    }
  }

  /** Whether the E2EE actions may run against the field that currently has focus. */
  boolean actionsAreAvailable() {
    return !mHostFieldIsPassword;
  }

  static final String INFO_PASSWORD_FIELD =
      "This is a password field. Encryption and decryption are turned off here, so a decrypted "
          + "message is never written into another app's password box.";

  /** Screen switches, for tests that drive the real showOnlyUIView rather than the visibilities. */
  void showMessagesListForTest() { showOnlyUIView(UIView.MESSAGES_LIST_VIEW); }

  void showContactListForTest() { showOnlyUIView(UIView.CONTACT_LIST_VIEW); }

  void showMainViewForTest() { showOnlyUIView(UIView.MAIN_VIEW); }

  void showVerifyContactForTest(final Contact contact) { verifyContact(contact); }

  /** Package-visible so a test can drive the real verify screen rather than the pieces. */
  void loadFingerprintInVerifyContactView() {
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

  /**
   * Animators still counting up into the digit views.
   *
   * <p>Each runs for a second after the screen is drawn. Nothing used to stop them, so blanking the
   * screen or opening a different contact left the previous contact's animation to finish writing
   * its digits into the views a moment later - a safety number the user could then read and compare
   * while believing it belonged to whoever is named above it.
   *
   * <p>Untested, deliberately: under Robolectric an un-cancelled animator delivers no further
   * frames once the looper is idled past the view change, so the late repaint does not happen and
   * a test of it passes with or without this cancel. Removing the cancel is therefore invisible
   * here. It is on the device-check list in REVIVAL.md with FLAG_SECURE, for the same reason.
   */
  private final java.util.List<ValueAnimator> mCodeAnimators = new ArrayList<>();

  private void cancelCodeAnimations() {
    for (final ValueAnimator animator : mCodeAnimators) {
      animator.cancel();
    }
    mCodeAnimators.clear();
  }

  /** Blanks the safety-number digits and disables confirmation. */
  private void clearFingerprintViews() {
    cancelCodeAnimations();
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
    // Whatever the previous contact's screen started must not still be running: it would count up
    // into these same views over the top of this contact's number.
    cancelCodeAnimations();
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
    mCodeAnimators.add(valueAnimator);
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
    //
    // Unreachable as the code stands, and kept anyway. This screen is only shown when
    // extractContactFromEnvelope found NO contact at the envelope's address, so by construction
    // there is nothing here to find. It is one flow change away from mattering - anything that
    // opens the add screen for a known address, or lets the list change while it is open - and a
    // guard that costs a list scan is not worth removing to save it. What it is not is a control
    // anyone should count as covering this attack today: the name heuristic above is.
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
      setWarningMessage(
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
      setWarningMessage(warning);
    } else if (duplicateName) {
      final String duplicate =
          String.format(duplicateNameMessage(chosenContact), labelFor(chosenContact));
      Toast.makeText(getContext(), duplicate, Toast.LENGTH_LONG).show();
      // A warning, not an informational line: it is the only control covering the case the pin
      // cannot, and it cost the attacker one extra post to erase.
      setWarningMessage(duplicate);
    }

    if (messageEnvelope.getPreKeyResponse() != null) {
      final boolean successful = mE2EEStrip.createSessionWithContact(chosenContact, messageEnvelope, recipientProtocolAddress);
      if (successful) {
        // Both warnings are already standing, posted above with their toasts. Re-posting them here
        // was not belt-and-braces: it meant each copy masked the other's deletion, so removing
        // either one on its own changed nothing any test could see.
        if (!previouslyRejected && !duplicateName) {
          // Through the guarded writer: an attacker whose substitution was just refused posts one
          // more ordinary invite under a fresh name at a fresh address, the user accepts it -
          // accepting invites is what this app is for - and neither of the two conditions above
          // fires, so "Contact Carol created" used to land straight on top of the warning.
          setInfoUnlessWarned("Contact " + labelFor(chosenContact) + " created. You can send messages now");
        }
      } else if (!mWarningStanding && !warnIfIdentityChanged(chosenContact)) {
        // createSessionWithContact already writes INFO_IDENTITY_CHANGED when a change is pending,
        // and this used to overwrite it with INFO_SESSION_CREATION_FAILED - the same delete-and-
        // re-invite advice - defeating its own guard. The standing check covers the same mistake
        // from the other direction: a failed session must not paint generic advice over the
        // post-rejection or duplicate-name warning, which would leave the flag set over text that
        // is not a warning at all - and nothing passive could then correct it.
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

  /** Package-visible so a test can drive the real validation rather than the predicate alone. */
  boolean providedContactInformationIsValid(CharSequence firstName, CharSequence lastName) {
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
        // Cancelling neither chooses nor un-chooses anyone, so say what is actually chosen - and
        // nothing at all over a standing warning. The flat "No contact chosen" here was wrong
        // whenever a contact WAS chosen, and it erased every warning in the app for the price of
        // one post: declining an unexpected invite is the correct response to a suspicious one,
        // and it was the action that cleared the suspicion from the screen.
        showChosenContactInMainInfoField();
        // The typed name used to survive. The next invite's screen then opened pre-filled with it,
        // so a user who declines one invite and accepts the next without re-reading the field
        // names a new address after the old contact.
        resetAddContactInputTextFields();
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
    setInfoTextViewMessage(mInfoTextView, INFO_NO_CONTACT_CHOSEN);

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
        if (!mayOverwriteInfoBanner()) return;

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
    if (!actionsAreAvailable()) {
      Toast.makeText(getContext(), INFO_PASSWORD_FIELD, Toast.LENGTH_LONG).show();
      return;
    }
    if (chosenContact == null) {
      Toast.makeText(getContext(), INFO_CHOOSE_CONTACT_FIRST, Toast.LENGTH_SHORT).show();
      return;
    }

    if (mInputEditText != null && mInputEditText.getText().length() > 0) {
      // call encrypt method and encrypt text
      final CharSequence encryptedMessage;
      try {
        encryptedMessage = mE2EEStrip.encryptMessage(mInputEditText.getText().toString(), chosenContact.getSignalProtocolAddress(), encodingMethod);

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
    try {
      showOnlyUIViewInternal(uiView);
    } finally {
      notifySensitiveVisibility();
    }
  }

  private void showOnlyUIViewInternal(final UIView uiView) {

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
    // The FLAG_SECURE decision must be revised when this field's content changes.
    //
    // notifySensitiveVisibility() was reachable from exactly one place - the finally of
    // showOnlyUIView - so the only events that revised the flag were screen switches. Putting
    // plaintext into this field is not a screen switch, so the case isShowingSensitiveContent()
    // leads with ("a decrypted message in the field is exactly what must not be captured") never
    // raised the flag at all.
    //
    // It also fixes the mirror on the dismissal path: clearDecryptedContent empties this field
    // BEFORE consulting the predicate, so on the main view the predicate had already gone false by
    // the time it was asked, no screen switch happened, and a flag raised while a draft was on
    // screen was never lowered - leaving it set for the keyboard's whole life, which the design
    // explicitly rejects.
    mInputEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) { }

      @Override
      public void afterTextChanged(Editable s) {
        notifySensitiveVisibility();
      }
    });
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
    if (!actionsAreAvailable()) {
      Toast.makeText(getContext(), INFO_PASSWORD_FIELD, Toast.LENGTH_LONG).show();
      return;
    }
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
    // showChosenContactInMainInfoField refuses over a standing warning, so this is an ordinary
    // call. It used to be guarded here by a flag cleared on the very next line - which protected
    // exactly one frame and left the warning overwritable by the next clipboard event.
    showChosenContactInMainInfoField();
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
      setInfoUnlessWarned("Detected contact: " + labelFor(chosenContact));
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
    }
  }

  private void processPreKeyResponse(MessageEnvelope messageEnvelope, Contact sender) {
    setInfoUnlessWarned(INFO_PRE_KEY_DETECTED);
    if (sender == null) {
      // add contact with preKey message
      showAddContactView(messageEnvelope);
    } else {
      // update contact with preKey information
      setChosenContact(sender);
      setInfoUnlessWarned("Detected contact: " + labelFor(chosenContact));
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
      setInfoUnlessWarned("Detected contact with updated keybundle: " + labelFor(chosenContact));
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
    }
  }

  private void resetChosenContactAndInfoText() {
    setChosenContact(null);
    // Not over a standing warning.
    //
    // This is reached when a paste fails to decode and when the user cancels the add-contact
    // screen, and both are messenger-reachable: the messenger chooses the payload, and cancelling
    // an unexpected invite is the CORRECT response. Writing the neutral banner there erased the
    // identity-change warning and left the strip wedged - the flag still said a warning was on
    // screen, so nothing could replace the text, while the text itself said "No contact chosen",
    // which the watcher reads as a reason to disable both buttons.
    //
    // The javadoc on mWarningStanding says it is never cleared by anything the messenger can
    // cause. These two paths were the exception, and the exception was reachable with one ordinary
    // chat line.
    if (!mWarningStanding) {
      setInfoTextViewMessage(mInfoTextView, INFO_NO_CONTACT_CHOSEN);
    }
  }

  private void showAddContactView(MessageEnvelope messageEnvelope) {
    createAddContactAddClickListener(messageEnvelope);
    showOnlyUIView(UIView.ADD_CONTACT_VIEW);
  }

  /**
   * Set while ANY security warning is on screen, and cleared only by a deliberate user action.
   *
   * <p>The info banner is the app's only persistent warning surface - a toast lasts about three and
   * a half seconds and then the screen looks like an ordinary success. {@code
   * An earlier flag, {@code mIdentityWarningStanding}, was added for exactly that and did not
   * survive: it was cleared at the end of {@code decryptMessageInClipboard} while the warning was
   * still on screen, so by the next clipboard event it was already false and the listener
   * overwrote the banner. It has been removed - two flags for one property, one of them protecting
   * a single frame, is a footgun rather than defence in depth.
   *
   * <p>That reduces the cost of erasing every warning in the app to one extra post. The user copies
   * it as part of the ordinary copy-then-paste workflow, the listener writes "Keybundle detected",
   * and nothing is left: an unverified contact after a refused substitution is byte-identical to
   * one the user simply never compared.
   *
   * <p>So this is cleared by two deliberate user actions and nothing else: going to compare a
   * safety number, and - for warnings other than a pending identity change - choosing the contact
   * the warning is about. Opening the contact list does not clear it; an earlier version of this
   * sentence said it did. Nothing the messenger can cause clears it.
   */
  private boolean mWarningStanding = false;

  /**
   * Posts a warning to the info banner and marks it as standing.
   *
   * <p>Every security warning goes through here rather than {@code setInfoTextViewMessage}, so that
   * "is a warning on screen" is a property of how it was written rather than a string comparison
   * against a set of format strings someone has to remember to extend.
   *
   * <p>That was not true of {@code INFO_SAME_ADDRESS_DIFFERENT_NAME} when this sentence was
   * written, which is the trouble with an invariant stated in prose: it goes on reading as true.
   */
  private void setWarningMessage(final String message) {
    setInfoTextViewMessage(mInfoTextView, message);
    mWarningStanding = true;
  }

  /**
   * Chooses the opening banner, once the storage layer exists to be asked.
   *
   * <p>This used to run in the constructor, where it could never see anything but {@code NONE}.
   * {@code KeyboardSwitcher} evaluates {@code onCreateInputView(...)} - which inflates this view -
   * as the ARGUMENT to {@code setInputView}, so the strip is built before {@code LatinIME} calls
   * {@code initialize}/{@code reloadAccount}, and those are the only callers that create the
   * storage helper. {@code storageState()} returns {@code NONE} when there is no helper.
   *
   * <p>So on a cold keyboard process - which is the normal path, because the strip is built exactly
   * once unless the theme changes - a user whose identity cannot be decrypted saw "No contact
   * chosen". The banner telling them not to re-invite anyone was structurally incapable of ever
   * appearing, and {@code openingMessage} was honest that it covered "WHICH message is chosen, not
   * that the view displays it".
   */
  public void refreshOpeningMessage() {
    if (mInfoTextView == null) return;
    final String opening = openingMessage(SignalProtocolMain.storageState());
    if (INFO_STORAGE_UNREADABLE.equals(opening)) {
      setWarningMessage(opening);
    } else if (!mWarningStanding) {
      setInfoTextViewMessage(mInfoTextView, opening);
    }
  }

  /**
   * Forgets anything decrypted into the input field.
   *
   * <p>The IME view is not recreated when the user switches apps, and nothing in the input
   * lifecycle touched strip state - {@code clearFocusEditTextView} clears focus, not text. So a
   * decrypted message stayed rendered and came back on screen the next time the keyboard rose, in
   * whatever app that happened to be.
   */
  public void clearDecryptedContent() {
    if (mInputEditText != null && mInputEditText.getText().length() > 0) {
      mInputEditText.setText("");
    }

    // The compose field is not the only thing rendering plaintext.
    //
    // The chat-log screen holds the ENTIRE decrypted conversation in an adapter, and clearing the
    // compose box left it both visible and populated. The IME view is not recreated when the user
    // switches apps, so the whole history was still on screen the next time the keyboard rose - in
    // whatever app that happened to be. FLAG_SECURE stops a screenshot of it; it does nothing about
    // the person next to you.
    //
    // Leave the screen AND drop the adapter: going back to the main view alone would keep the
    // plaintext one button-press away with no further decryption.
    if (mMessagesList != null) {
      mMessagesList.setAdapter(null);
    }
    // Every sensitive screen, not just the chat log. The verify screen shows a safety number under
    // a contact's name; the contact list shows the user's whole set of correspondents and their
    // address tags. isShowingSensitiveContent()'s own javadoc says so, and this used to consult one
    // of the three - so two of them rode through the app switch and were on screen when the
    // keyboard next rose, in whatever app that was.
    if (isShowingSensitiveContent()) {
      showOnlyUIView(UIView.MAIN_VIEW);
    }
  }

  /**
   * Forgets who the next message was going to, when the keyboard is dismissed.
   *
   * <p>This resolves a conflict a review round raised and deliberately did not decide: the banner
   * naming the chosen contact ("Chosen contact: Bob #a1b2") survived an app switch, so the keyboard
   * rose in the next app still saying who the user talks to. The obvious fix - blank the banner on
   * hide - collides head-on with {@code mWarningStanding}, whose entire purpose is that a security
   * warning survives everything the messenger can cause, and an app CAN cause this: any app may
   * hide the keyboard whenever it likes. Blanking on hide would have handed the messenger a
   * one-call warning eraser, which is the exact failure this branch has spent several rounds
   * closing.
   *
   * <p>Blanking the TEXT while keeping the recipient is worse than either: encryption would still
   * go to a contact the screen no longer names, which is an invisible recipient - the same shape as
   * the cross-recipient disclosure already fixed here, and a mis-send rather than a disclosure.
   *
   * <p>So the recipient itself is forgotten. It costs the user a tap to re-choose after the
   * keyboard has been dismissed, and it costs nothing else: the compose field is already cleared on
   * the same event, so no draft is lost that was not lost before. A standing warning still owns the
   * banner and is left exactly where it is - a warning that names a contact is a disclosure the
   * user needs more than they need the privacy, and that trade is stated rather than assumed.
   *
   * <p>What this does NOT do is stop someone reading the screen over the user's shoulder while a
   * warning stands. That residue is accepted: the adversary in this threat model is the messenger,
   * which cannot capture the IME window at all.
   */
  public void forgetChosenRecipient() {
    setChosenContact(null);
    // No standing-warning check here: showChosenContactInMainInfoField refuses over one itself, and
    // a second copy of that condition is a mutation nothing can kill - it was written, measured as
    // equivalent, and removed rather than left to read as a live guard.
    showChosenContactInMainInfoField();
  }

  /**
   * Everything this strip must do when the keyboard is dismissed.
   *
   * <p>One entry point rather than a list at the call site, so a test drives the same code the IME
   * does. It was two calls for about an hour, and in that hour the existing test for it was already
   * a stale copy of the old list - which is the failure this codebase keeps naming: a test that
   * re-implements the body proves only that the copy behaves.
   */
  public void onKeyboardHidden() {
    clearDecryptedContent();
    forgetAbandonedInvite();
    forgetChosenRecipient();
  }

  /**
   * Drops a half-finished invite when the keyboard is dismissed.
   *
   * <p>{@code clearDecryptedContent} leaves the current screen only {@code if
   * (isShowingSensitiveContent())}, and that predicate enumerates four screens out of six. The
   * add-contact screen is one it omits, so the one screen the user reaches by acting on the
   * messenger's own payload was the one that survived the app switch - still up, still pre-filled,
   * and with the Add button still bound to the attacker's envelope, in whatever app the keyboard
   * next served.
   *
   * <p>The typed name is dropped for the same reason the Cancel button drops it (6354d93): the next
   * invite's screen would otherwise open pre-filled with the last one's name, so a user who
   * abandons one invite and accepts the next without re-reading names a new address after the old
   * contact. Unlike Cancel, this path is one the messenger can trigger itself - any app may hide the
   * keyboard whenever it likes.
   */
  private void forgetAbandonedInvite() {
    if (mLayoutE2EEAddContactView != null
        && mLayoutE2EEAddContactView.getVisibility() == VISIBLE) {
      showOnlyUIView(UIView.MAIN_VIEW);
    }
    resetAddContactInputTextFields();
  }

  /** The recipient the next message would go to, for tests. */
  Contact chosenContactForTest() {
    return chosenContact;
  }

  /** The real add-contact path, entered as the Add button enters it. */
  void addContactForTest(final MessageEnvelope messageEnvelope) {
    addContact(messageEnvelope);
  }

  /** The reset an undecodable paste and a cancelled add both reach, for tests. */
  void resetChosenContactAndInfoTextForTest() {
    resetChosenContactAndInfoText();
  }

  /** Posts a warning, for tests that drive the strip. */
  void setWarningMessageForTest(final String message) {
    setWarningMessage(message);
  }

  /** Simulates a clipboard event carrying something KryptEY-shaped, for tests. */
  void onClipboardChangedForTest() {
    if (!mayOverwriteInfoBanner()) return;
    setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_DETECTED);
  }

  /**
   * Whether a passive, messenger-driven event may write over the info banner.
   *
   * <p>Extracted so the clipboard listener and its test run the SAME code. The listener is a lambda
   * on a system service that a test cannot invoke, and a test that re-implements its body proves
   * only that the copy behaves - which is the failure this codebase keeps finding elsewhere.
   */
  boolean mayOverwriteInfoBanner() {
    if (storageIsUnreadable() || mWarningStanding) {
      Log.i(TAG, "A security warning is on screen; leaving it in place");
      return false;
    }
    return true;
  }

  /** Clears a standing warning. Only call this from a deliberate user action. */
  private void clearStandingWarning() {
    mWarningStanding = false;
  }

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
  /** Package-visible so a test can drive it; the warning it posts is the app's only lasting one. */
  boolean warnIfIdentityChanged(final Contact sender) {
    if (sender == null) return false;
    if (!com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain
        .hasUnacceptedIdentityChange(sender.getSignalProtocolAddress())) {
      return false;
    }
    final String warning =
        String.format(INFO_IDENTITY_CHANGED_EXISTING, labelFor(sender), labelFor(sender));
    Toast.makeText(getContext(), warning, Toast.LENGTH_LONG).show();
    setWarningMessage(warning);
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

  /**
   * Writes the banner unless a security warning is standing there.
   *
   * <p>For the messenger-supplied side of the decrypt path. The user pressing Decrypt is a
   * deliberate action, but WHAT they press it on is the attacker's choice, so "Detected contact:
   * Bob" over an identity-change warning cost one ordinary post to arrange - the same one-extra-
   * message erasure the standing flag exists to prevent everywhere else.
   */
  private void setInfoUnlessWarned(final String message) {
    if (mWarningStanding) return;
    setInfoTextViewMessage(mInfoTextView, message);
  }

  /** Package-visible so a test can drive the real method rather than a copy of it. */
  void showChosenContactInMainInfoField() {
    // Never over a standing warning. The caller guards this too, with a flag it then clears
    // immediately - so by the time a later event arrives that flag is false and only this check is
    // left. Two guards for one property is deliberate: the outer one covers the frame it was
    // written for, this one covers everything after it.
    if (mWarningStanding) return;
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
    clearStandingWarning();   // the user chose this contact; they have seen whatever was on screen
    setChosenContact(contact);
    // ...but a pending identity change is a state, not a notice that has been read. Choosing the
    // contact from the list is not the user having dealt with it, and clearing the flag here meant
    // "Chosen contact: Bob" replaced the substitution warning for good - the one screen the user
    // goes to when something looks wrong was also the one that made it stop looking wrong.
    // Re-assert it. Only comparing the number, or another deliberate response, may put it down.
    if (!warnIfIdentityChanged(contact)) {
      showChosenContactInMainInfoField();
    }
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
    // NOT cleared here. Opening the screen is not comparing the number: the user can look, back out
    // through the contact list, and arrive at the main view with the change still pending, the
    // warning text still up and the flag down - so the messenger's next post wipes it. The flag
    // comes down where the deliberate act happens, in the Verify and Reject listeners.
    setChosenContact(contact);
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

    /**
     * The strip is now showing, or has stopped showing, content that must not be captured.
     *
     * <p>The strip cannot set window flags itself - it is a view inside the IME's input view, and
     * the window belongs to {@code LatinIME}. This is how the decision reaches it.
     */
    default void onSensitiveContentVisibilityChanged(final boolean sensitive) { }
  }
}
