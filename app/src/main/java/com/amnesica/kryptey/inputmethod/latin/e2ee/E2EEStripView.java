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
import com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException;
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
import java.util.HashMap;
import java.util.Map;
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
    // Explicitly, because the usual trigger is a banner CHANGE and this event need not produce one.
    //
    // refreshActionButtons runs from the banner's TextWatcher, so it fires when the text differs.
    // Re-selecting a contact while a standing item holds the banner repaints the same string, the
    // watcher sees no change, and the buttons keep whatever state they had for somebody else -
    // which is precisely the moment the answer is most likely to have changed, since the refusal is
    // per contact. Ordered after the assignment so the refresh reads the new recipient.
    if (changed) refreshActionButtons();
    if (changed && mInputEditText != null && mInputEditText.getText().length() > 0) {
      Log.i(TAG, "Recipient changed; clearing the staged message");
      clearComposeFieldAndCaches();
    }
    // Repaint the banner here, not at the call sites, whenever a warning is holding it.
    //
    // The "Sending to: X" line was written in two places and invalidated in none. Every other path
    // that moves the recipient is deliberately banner-silent while a warning stands - a decrypt
    // that identifies a sender, an invite being processed, the recipient being forgotten - so the
    // line kept naming whoever was chosen when the warning went up. That is worse than the
    // invisible recipient it was added to fix: an invisible recipient says nothing, and this said
    // something false. Reposting another contact's own earlier invite is enough to move the
    // recipient without a word on screen, and this file documents that replay a few hundred lines
    // up.
    //
    // Doing it in the setter means it cannot be forgotten by the next path that moves the
    // recipient, and it covers null - forgetChosenRecipient exists to stop the banner carrying a
    // name into the next app, and the recipient line would have ridden along with it.
    // A caution carries the same "Sending to: X" line a warning does, so it needs the same repaint.
    // Without it the banner went on naming Carol while the chosen contact had moved to Bob - and
    // Encrypt encrypts to the chosen contact. That is this method's own stated reason for existing:
    // "an invisible recipient says nothing, and this said something false".
    if (changed && aStandingItemHoldsTheBanner()) {
      setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
    }
    // The window's FLAG_SECURE decision depends on whether a recipient is chosen, and nothing told
    // it when that changed here. notifySensitiveVisibility had three callers - a screen switch, the
    // compose-box watcher and adoptState - and this method has nine, of which only two are followed
    // by a screen switch.
    //
    // Fails open on the route a user most often takes: accepting an invite with the Decrypt button
    // runs setChosenContact from processPreKeyResponse with no screen change, and the session-
    // creation arm writes no text either, so the watcher never fires. The strip then sits on the
    // main view with the banner naming the recipient and the flag DOWN - which is the disclosure
    // the chosen-contact term was added to cover.
    //
    // And fails closed on the way out: hiding the keyboard notifies true while the contact is still
    // set, then forgetChosenRecipient nulls it silently, so the last thing the window heard was
    // "sensitive" and the flag stays on for the rest of the keyboard's life.
    if (changed) notifySensitiveVisibility();
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

  /**
   * The log exists and could not be opened, which is not the same as it being empty.
   *
   * <p>Both places that meet this state said "There are no saved messages for this contact" - a
   * statement about the history, made in the one situation where the app cannot see the history at
   * all. On the delete route it also asserts the opposite of the reason the deletion was refused:
   * the refusal exists because the log probably still holds that contact's plaintext. And a user who
   * believes their history is gone is exactly the user who stops trying to clean it up.
   */
  private final String INFO_SAVED_MESSAGES_UNREADABLE = "Your saved messages with this contact cannot be opened right now, so they cannot be shown or deleted. They are still on this device - this is not an empty history. Try again after unlocking the device; if it keeps happening, do not assume anything here has been removed.";
  private final String INFO_VERIFY_CONTACT = "To check your encryption with %s, read the numbers above out to them by voice - in person or on a call - and have them read theirs back. Do not send the numbers through the messenger you are chatting in: anything that could change your keys could change those numbers to match.";
  /**
   * A re-invite that was refused, said out loud on the arm that used to say nothing.
   *
   * <p>The bundle-only success path advanced the UI identically whether the invite was accepted or
   * refused, because "no decrypted message" is what BOTH look like. A relay that strips the
   * one-time pre-key from every invite could therefore hold a contact permanently unusable while
   * the app said "Detected contact" each time - and the add-contact arm's own advice ("ask for a
   * fresh one") routes the user straight onto this arm, where the next attempt is silent again.
   */
  private final String INFO_INVITE_REFUSED = "That invite from %s could not be used - it does not verify, which means it was changed on the way here. Nothing has been set up. Ask them to send another, and if it keeps failing, send it a different way.";

  /**
   * The same event where a session already exists, which the sentence above describes wrongly.
   *
   * <p>The refusal now fires on paths where {@code isSessionCreation} is false — the routine one
   * being a signed-pre-key rotation, where an honest peer attaches a full bundle to an ordinary
   * message. There the message decrypts under the existing session and the contact keeps working,
   * so "Nothing has been set up" is false while the user is reading their reply in the compose box.
   * It is false in the other direction too on the add-contact arm, where the attached ciphertext
   * pins a key by trust-on-first-use even though the bundle was refused.
   */
  /**
   * The third state: nothing existed before, and the refused bundle's own ciphertext pinned a key.
   *
   * <p>Neither other sentence is true here. "Nothing has been set up" is false — a key is pinned
   * and the contact is usable — and "what you already had with them is unchanged" is worse, because
   * there was nothing before and the thing that just happened is the one this app most needs to
   * report: trust-on-first-use accepting a key the messenger supplied. The attached
   * {@code PreKeySignalMessage} carries its own identity key, so refusing the bundle does not stop
   * it, and on this arm the contact-creation caution does not fire either.
   */
  /**
   * A contact created in memory only, which the next raise will undo.
   *
   * <p>The last member of the write family that could not say this. The banner otherwise reads
   * "Contact X created … compare the security number by voice before sending anything private" and
   * sends the user to verify a contact that will not be there after the next keyboard raise — and
   * the host app decides when that happens.
   */
  /**
   * Leads with the problem, and says nothing about when the contact disappears that is not true.
   *
   * <p>It once said "the next time the keyboard opens". That event does not reload the account:
   * {@code reloadAccount} runs only from {@code LatinIME.setInputView}, whose only in-app caller
   * fires on a theme or ui-mode change. Lowering and raising the keyboard changes nothing, so the
   * contact stays present and usable in memory for as long as the process lives - the opposite of
   * what the sentence promised, on the surface it had just been moved onto.
   *
   * <p>The fixed opening was briefly load-bearing: {@code disablesActionButtons} matched it with
   * {@code startsWith} to keep Encrypt dark. That did not work and could not be made to - the banner
   * is composed warning-first, so any warning sharing it pushed this notice off the start of the
   * string and the match failed exactly when a security warning was already on screen. The refusal
   * is recorded per contact now, in {@code mContactsNotOnDisk}, and this phrase is only wording
   * again.
   */
  private final String INFO_CONTACT_NOT_SAVED = "Not saved: contact %s was set up here, but it could not be saved - the app could not write to its own storage. They will be gone once this keyboard restarts. Do not send them anything until you have added them again successfully.";


  /**
   * The contact went; their stored messages did not.
   *
   * <p>Two files, two commits: the log is written first and the account second, and only the second
   * decided whether the deletion was reported as done. A log commit that fails while the account
   * commit succeeds leaves that contact's plaintext in the log file owned by no row - unreachable
   * by any screen, and beyond the one erasure action the user has. Said out loud because it is the
   * outcome the app's own refusal elsewhere calls the worse of the two, and because the help text
   * now promises the opposite.
   */
  private final String INFO_DELETED_BUT_MESSAGES_REMAIN = "%s was removed, but their saved messages could not be deleted - the app could not write to its own storage. Those messages are still on this device and no screen can reach them now. Try deleting another contact once the device has free space, which rewrites the same file.";

  /**
   * The session state was not saved. A different failure from a contact row that was not saved.
   *
   * <p>One string was doing four jobs and was true of one of them. On this path the contact exists
   * and is on disk; what was lost is the session - the advanced ratchet, or a key just pinned. So
   * "was set up here" and "they will be gone once this keyboard restarts" were both false, and "add
   * them again successfully" is the delete-and-re-invite advice this file elsewhere identifies as a
   * key-substitution window, given for a storage fault.
   *
   * <p>Worse after the last commit, which put this path on every incoming message: a lost write
   * during ordinary use told the user their established, possibly verified contact had just been
   * set up here and was about to disappear.
   */
  private final String INFO_SESSION_NOT_SAVED = "The keys for %s changed and the app could not save the change - it could not write to its own storage. Messages you send now may not be readable after this keyboard restarts, and nothing here needs deleting or re-inviting. Wait until the device has free space and ask them to send another message.";

  /** A deletion that did not reach disk, which the next raise will undo. */
  private final String INFO_DELETE_NOT_SAVED = "That contact was not removed - the app could not write to its own storage. They, their key and their saved messages are all still here, exactly as they were. Try again once the device has free space, and do not rely on this having deleted anything.";

  /**
   * The send-side counterpart, because the receive-side wording is not true here.
   *
   * <p>"This message was read" is about an incoming message; a sent one has already gone to the
   * messenger by the time the log write fails. Both leave the history missing a message, and both
   * must avoid the generic failure advice - deleting the contact and re-inviting is a
   * key-substitution window, and nothing about a storage failure calls for it.
   */
  private final String INFO_SENT_MESSAGE_NOT_SAVED = "That message was sent, but it could not be added to your saved history, because the app could not write to its own storage. The message went out normally - only the record of it is missing.";

  /**
   * The message was read; only the record of it failed.
   *
   * <p>Deliberately says nothing about re-sending, re-inviting or deleting. The chat log being
   * unreadable used to surface as {@code INFO_MESSAGE_DECRYPTION_FAILED}, and this codebase's own
   * analysis is that the generic decryption-failure advice drives users to delete the contact and
   * ask for a new invite - which is a key-substitution window opened by advice about the wrong
   * problem. One flipped byte in the sealed log makes that permanent.
   *
   * <p>And deliberately says nothing about WHICH storage failure it was, because the flag behind it
   * covers two. {@code mLastChatLogWriteFailed} is raised both when the log cannot be read and when
   * it reads fine but the write does not land, and the sentence used to name only the first: "the
   * stored history cannot be opened". On the write arm that is false, and falsifiable on screen in
   * one tap - the chat-log button opens the very history the toast says cannot be opened, showing
   * the message it says was not saved, which will then vanish at the next raise. Naming a cause the
   * user can immediately disprove is worse than naming none: it teaches them the notices are
   * unreliable, and the notices are all this app has.
   */
  private final String INFO_MESSAGE_NOT_SAVED = "This message was read, but it could not be added to your saved history - the app could not store it. The message itself is fine and nothing needs to be sent again - only the record of it is missing.";

  private final String INFO_INVITE_REFUSED_BUT_KEY_PINNED = "The key update from %s could not be used - it does not verify, which means it was changed on the way here. The message it arrived with has set up a key for them anyway, and this app cannot tell whose it is - compare the security number by voice before sending anything private.";

  private final String INFO_INVITE_REFUSED_SESSION_KEPT = "A key update from %s could not be used - it does not verify, which means it was changed on the way here. It was ignored, and what you already had with them is unchanged. Ask them to send another, and if it keeps failing, send it a different way.";

  private final String INFO_NO_FINGERPRINT = "No security number is available for this contact yet. Ask them for a key bundle first.";

  /**
   * The same cell, once the user has rejected a key there.
   *
   * <p>Not {@code INFO_VERIFY_AFTER_REJECTION}, which was written for the screen where a key IS
   * pinned: it says "the number below is the key in use now", and in this cell the digits are
   * blank. Reusing it here would have replaced "nothing has ever happened at this address" with
   * "compare the number that is not on screen" — a different false statement, which is what the
   * first attempt at this fix did and what its own test caught.
   */
  private final String INFO_NO_FINGERPRINT_AFTER_REJECTION = "You told this app not to trust keys arriving for %s. There is no security number to compare until they send a new invite.";

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
  /**
   * What a false return from {@code verifyContact} means now.
   *
   * <p>It used to mean only "no contact or no account is loaded", and this string said so.
   * Verification also refuses when there is no pinned key to verify - {@code verifyContact}'s own
   * javadoc says a caller rendering false as "nothing is loaded" is now sometimes wrong - and this
   * is its only caller. Saying "could not be recorded" covers both without claiming which, and
   * without inventing a security claim out of a failed load.
   */
  private final String INFO_VERIFY_UNAVAILABLE = "Could not record that check. Nothing has been marked as verified - try again from the contact list.";
  private final String INFO_PINNED_AFTER_REJECT = "Careful: you told this app not to trust keys arriving for %s, at this same address. This is a new key for that address - it is NOT automatically the right one. Compare the number by voice before sending anything.";
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
  static final String INFO_RETIRED_CONTACT_NAME = "You deleted a contact called %s, and this app cannot confirm that this is the same person coming back. If they told you they reinstalled, check the security number with them by voice before sending anything.";
  // Does not tell the user to obtain the invite "out of band": there is no import path for one -
  // exportOwnKeyBundle and importOutOfBandKeyBundle have no production caller, so the clipboard is
  // the only way a bundle can enter the app. Advising a route that does not exist is the same
  // defect as telling them to check a number that is never displayed.
  /**
   * Said when Reject is pressed and there was no stored key to forget.
   *
   * <p>Reject is deliberately available with no pin — it is the one deliberate response left when a
   * warning stands and the verify screen has no number to compare, and without it that state is a
   * dead end. But {@code INFO_KEY_REJECTED} opens "Forgot the stored key for %s", and in that state
   * nothing was stored.
   *
   * <p>Its closing sentence had to go too, which the commit that added this string got wrong by
   * assuming only the opening one was about the pin. "This app has already been given a wrong key
   * for them once" is true where a key was pinned and the user reported a mismatch; it is not
   * implied by anything in this branch. The branch is reachable only with no pin and a standing
   * warning, and post-round-five those are the duplicate-name, same-address and storage warnings —
   * in none of which was a key ever offered at that address, let alone a wrong one. Telling the
   * user the app was handed a wrong key for a contact is a factual claim about a security event,
   * and asserting one that did not happen is the same defect as staying silent about one that did.
   */
  private final String INFO_NOTHING_TO_REJECT = "There was no stored key for %s to forget - none had been stored yet. Nothing can be sent to them until they send an invite. When one arrives, compare the number with them by voice before sending anything private.";

  /**
   * The third state, which the previous two both described wrongly.
   *
   * <p>"No pin" is not one situation. It is "nothing was ever stored here" AND "you already
   * rejected the key that was stored", and the two need opposite sentences. The first version of
   * this branch kept {@code INFO_KEY_REJECTED}'s closing claim that the app had been given a wrong
   * key, which is false in the never-stored state. The fix replaced it with "none had been stored
   * yet", which is false in this one - a key WAS stored, and the user is the person who reported it
   * as wrong. Swapping one false claim for its opposite is not a fix, and the distinguishing fact
   * was three lines away the whole time: {@code wasKeyRejected}.
   */
  /**
   * The rejection did not reach storage, so it will not survive the next raise.
   *
   * <p>Its own message because the alternative is printing "Forgot the stored key for %s" over a
   * key that is still pinned on disk. Reads keep succeeding from the in-memory map, so nothing looks
   * wrong until {@code reloadAccount} runs on the next {@code setInputView} - and then the key the
   * user rejected is back, with no record that they rejected it.
   */
  private final String INFO_REJECTION_NOT_SAVED = "This could not be saved - the app could not write to its own storage, so it will not be remembered the next time the keyboard opens. Do not send anything to %s until you have compared the security number with them by voice.";

  private final String INFO_ALREADY_REJECTED = "You had already told this app not to trust keys arriving for %s, so there was nothing left to forget. Nothing can be sent to them until they send a new invite. When one arrives, compare the number with them by voice before sending anything private.";

  private final String INFO_KEY_REJECTED = "Forgot the stored key for %s. Nothing can be sent to them until they send a new invite. When one arrives, compare the number with them by voice before sending anything - this app has already been given a wrong key for them once.";
  /**
   * Shown on the verify screen when a key for this contact was rejected earlier.
   *
   * <p>The verify screen said nothing about a standing rejection, and pressing Verify there is what
   * clears one - {@code rejectedAddresses} is documented as retired only by a fresh comparison. So
   * the one screen where that irreversible decision is made was the one screen that did not mention
   * the app had already been handed a wrong key for this contact. The reason the pending-change
   * notice exists ("tell the user a key was offered BEFORE they compare, so they compare
   * attentively") applies here at least as strongly: {@code isContactKeyTrustworthy} ranks a
   * standing rejection ABOVE a verified badge.
   */
  private final String INFO_VERIFY_AFTER_REJECTION = "You told this app not to trust keys arriving for %s. The number below is the key in use now. Only confirm it if they read these exact numbers back to you by voice - confirming clears that refusal.";

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
  // Says which state this is, not just that something failed. The commonest cause is the one the
  // storage banner describes, and "try again" would be wrong advice for it.
  static final String INFO_INVITE_UNAVAILABLE = "No invite could be built. This usually means your saved identity cannot be unlocked on this device - check that before re-inviting anyone, because re-inviting replaces every key you have already verified.";
  /**
   * An invite whose private halves did not reach disk.
   *
   * <p>The invite carries public keys whose private halves this app must keep. It is handed to a
   * messenger by hand, so the keyboard always restarts before the reply arrives - and if the write
   * was lost, that reply cannot be decrypted. The app would then report a generic decryption
   * failure, whose standard advice is to delete the contact and ask for a new invite: the
   * key-substitution window, reached out of a storage fault nobody mentioned.
   */
  private final String INFO_INVITE_NOT_SAVED = "That invite was created, but the app could not write to its own storage - so it cannot keep the private half of the keys in it. Do not send this invite: replies to it will not be readable. Free up space or unlock the device, then make a new one.";

  /** A message whose ratchet or rotated keys did not reach disk. */
  private final String INFO_SEND_STATE_NOT_SAVED = "That message was sent, but the app could not save the key state that went with it - it could not write to its own storage. Later messages from %s may not be readable until this is fixed. Nothing here needs deleting or re-inviting; free up space or unlock the device.";

  /** The should-never-happen half; see the unchecked catch in sendPreKeyResponseMessageToApplication. */
  private final String INFO_INVITE_FAILED = "Could not build an invite.";

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
      // Scoped, for the reason removeContact is scoped: a deliberate response about THIS contact
      // must not put down a warning about a different one. Rejecting Bob used to clear a standing
      // warning about Alice, and the duplicate-name warning is never re-asserted, so it was gone
      // for good. Null-addressed warnings (storage, same-address) still clear here - they have no
      // other exit, and scoping them strictly would recreate the dead end round five removed.
      // Ask BEFORE rejecting: afterwards there is never a pin, so the answer would always be no.
      final boolean hadAkeyToForget =
          mE2EEStrip.hasPinnedKey(chosenContact.getSignalProtocolAddress());
      // Three states, not two. Without this, a second Reject on a contact the user has already
      // rejected says no key was ever stored for them - which is exactly backwards, and reachable
      // by opening that contact's verify screen while any warning stands.
      final boolean alreadyRejected =
          mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress());
      mE2EEStrip.rejectContactKey(chosenContact);
      // The selection is written out here rather than lifted into a local so that the constants
      // remain visible at the call site: NoToastCarriesMessageContentTest reads these arguments to
      // check that a toast - which is drawn outside FLAG_SECURE and so is visible to a recording -
      // can only ever interpolate a named constant. Hiding the choice behind a variable would pass
      // that check by concealment rather than by being true.
      Toast.makeText(getContext(), String.format(
          !mE2EEStrip.lastRejectionReachedDisk() ? INFO_REJECTION_NOT_SAVED
              : hadAkeyToForget ? INFO_KEY_REJECTED
                  : (alreadyRejected ? INFO_ALREADY_REJECTED : INFO_NOTHING_TO_REJECT), label),
          Toast.LENGTH_LONG).show();

      // Only once the rejection is on disk, which is the last member of this family to learn it.
      //
      // Scoped, for the reason removeContact is scoped: a deliberate response about THIS contact
      // must not put down a warning about a different one. Rejecting Bob used to clear a standing
      // warning about Alice, and the duplicate-name warning is never re-asserted, so it was gone
      // for good. Null-addressed warnings (storage, same-address) still clear here - they have no
      // other exit, and scoping them strictly would recreate the dead end round five removed.
      //
      // The clears used to run BEFORE the write, unconditionally. When the account write failed,
      // the toast said so for three and a half seconds while the persistent surface had already
      // been wiped and nothing put it back - and on the next reloadAccount the rejected key is
      // pinned again with rejectedAddresses empty. That is the silent trust-on-first-use that
      // markKeyRejected exists to prevent, with the app's only lasting warning about it gone.
      // verifyContact learned this first and rolls back; removeContact learned it next; this was
      // the one left.
      if (mE2EEStrip.lastRejectionReachedDisk()) {
        clearStandingWarningIfAbout(chosenContact);
        clearCautionIfAbout(chosenContact);
      }
      loadContactsIntoContactsListView();
      showOnlyUIView(UIView.CONTACT_LIST_VIEW);
    });
  }

  private void createVerifyContactVerifyButtonClickListener() {
    if (mVerifyContactVerifyButton == null) return;
    mVerifyContactVerifyButton.setOnClickListener(v -> {
      try {
        // false means "this could not be recorded" - no contact, no account, or nothing pinned to
        // verify. The comment here used to say verification itself never refuses, which stopped
        // being true when verifyContact gained its no-pin refusal. Saying "a different security
        // number was offered" would fabricate a security claim out of a failed load, which is why
        // the message names neither cause.
        if (!mE2EEStrip.verifyContact(chosenContact)) {
          Toast.makeText(getContext(), INFO_VERIFY_UNAVAILABLE, Toast.LENGTH_LONG).show();
          return;
        }
        // Only now: the user has compared the number and confirmed it. Not on arriving at the
        // screen, and not on a failed load, which is why this sits after the guard above.
        clearStandingWarningIfAbout(chosenContact);
        clearCautionIfAbout(chosenContact);
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
        // The main view holds two different things worth covering, and this used to name one.
        //
        // The compose box is the obvious one. The other is the banner: with a recipient chosen it
        // reads "Chosen contact: Bob  #a1b2-...", which is who the user talks to AND the tag that
        // tells them apart from a second contact of the same name - the very pair this method's own
        // javadoc gives as the reason the contact list is on the list. forgetChosenRecipient spends
        // a paragraph on the same fact, calling that banner surviving an app switch a disclosure
        // worth costing the user a tap to prevent. So two sections of this file already agreed it
        // is sensitive and the predicate between them did not, and the gap is not a moment: it is
        // the whole interval between choosing a recipient and typing anything, plus every standing
        // warning, all of which name the contact and none of which put a character in the box.
        //
        // Asked of the MODEL rather than of the banner text - but of both halves of it, which is
        // where the first version went wrong. It asked only chosenContact, on the premise that
        // "every main-view banner that names a contact is written on a path where it is set". That
        // premise is false in two states the host app can force, and in both of them the banner
        // still names the contact while the recipient is gone:
        //
        //   - hiding the keyboard. onKeyboardHidden calls forgetChosenRecipient, which is a
        //     recipient change to null; the warning text is deliberately untouched, because the
        //     whole point of a standing warning is that the messenger cannot clear it.
        //   - a configuration change. adoptState restores the warning and caution and deliberately
        //     does NOT restore chosenContact.
        //
        // In both, FLAG_SECURE came down while the window displayed "Someone offered a different
        // key for Bob #a1b2" - who the user talks to, plus the tag that tells them from a second
        // contact of the same name, which is the exact pair this javadoc gives as its reason for
        // existing. And nothing raises it again until the user picks a recipient.
        //
        // Reading the rendered string would still be the wrong fix, for the reason
        // refreshActionButtons was taken off it. A standing item is a fact in the model, so it is
        // asked as one.
        || (isUp(mLayoutE2EEMainView)
            && (chosenContact != null
                // A standing WARNING, not any standing item. This is a decision with a cost on
                // both sides, and the line is drawn on what the state MEANS rather than on how
                // sensitive the text is.
                //
                // A caution is the app's report of ordinary successful use: one goes up after every
                // contact added, and comes down only when the user verifies, rejects or deletes
                // that contact. Counting those would put the flag up whenever the keyboard is, from
                // the first contact onward - and a FLAG_SECURE window blanks the whole system
                // screenshot, so it would silently break screenshots in every app during ordinary
                // typing, for as long as the app is used normally. That is not a state the user is
                // being asked to leave.
                //
                // A warning means something is wrong and the user is being asked to act; the flag
                // is up while that is true and comes down when they act. The accepted cost, stated
                // rather than hidden: a relay can raise one unilaterally - stripping the one-time
                // pre-key from an invite is one unsigned byte - so it can force screenshots off
                // until the user responds. That is a nuisance the user can end, and it is inflicted
                // by an app that is already telling them something about their keys is wrong;
                // dismissing the keyboard also restores screenshots meanwhile. Weighed against it:
                // the messenger this app treats as the adversary cannot capture the screen at all,
                // so the disclosure being defended here is against a screen recorder, and what it
                // would capture is a security warning naming a contact.
                //
                // The residue is real and recorded: a caution naming a contact is capturable once
                // the recipient has been forgotten.
                || mWarningStanding
                || (mInputEditText != null && mInputEditText.getText().length() > 0)));
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
    final boolean wasPassword = mHostFieldIsPassword;
    mHostFieldIsPassword = isPassword;
    if (isPassword) {
      clearDecryptedContent();
      // And stop collecting what the user types here.
      //
      // The guard's own javadoc gives both halves: decrypting "would hand a decrypted message to
      // that app's storage", and "Encrypting is the mirror - a password typed into the compose box
      // would be encrypted and pasted somewhere as ciphertext nobody wants". Only the first half
      // was enforced. The second was answered by darkening the Encrypt button, which stops the
      // press and not the capture: the redirect was left UP, so every character of the user's
      // password was committed into the strip's compose box instead of into the password field -
      // the field itself received nothing - and it stayed there, on screen, in the IME's caches,
      // and in the box that Encrypt sends the moment the user moves to an ordinary field and the
      // button comes back on. Measured: hostReceived=[], composeAfterTyping=[the password].
      //
      // A third deliberate lowering, alongside a send and the keyboard being dismissed. It is
      // deliberate in the same sense: the app has just announced that it will not encrypt anything
      // typed here, so it must not be collecting it either. The mid-compose case - the messenger
      // flipping inputType while a draft is being typed - loses only what is typed AFTER the flip,
      // because clearDecryptedContent above already destroys the draft itself, and the user is
      // shown an emptied box, a changed banner and two dark buttons rather than nothing.
      stopComposingInsideTheKeyboard();
      // Not over a standing warning. LatinIME calls this on EVERY input session with the host
      // field's inputType, and the messenger owns the inputType of every field it presents - so
      // "your session expired, re-enter your PIN" erased the substitution warning, and the flag
      // stayed set, which meant nothing could ever write the banner again. The strip was left
      // reading "encryption is turned off here" while the actions were back on and working.
      setInfoUnlessWarned(INFO_PASSWORD_FIELD);
    } else if (wasPassword) {
      // What was typed into the password field must not outlive it either.
      //
      // The caches are cleared when the guard is ARMED and nothing cleared them when it is
      // lowered, so everything typed while it was up accumulated in buffers that live as long as
      // the service. resetCachesUponCursorMoveAndReturnSuccess runs on the new input session and
      // does not close this: it reloads mCommittedTextBeforeComposingText from the new field and
      // empties mComposingText, but leaves mTempObjectForCommitText - "the third buffer on this
      // object, and the one that is easy to forget", holding a verbatim copy of the last
      // commitText argument, which over a password field is the password.
      //
      // Through the same helper as every other end of a message's life, for the reason that
      // helper exists: a rule remembered at each call site is not a rule. The compose box is
      // already empty here - the redirect was down for the whole of the password field - so this
      // is the cache half in practice.
      clearComposeFieldAndCaches();
      // The notice must not outlive the guard it describes.
      //
      // Lowering the flag wrote nothing, so "Encryption and decryption are turned off here" stayed
      // on the banner - the app's only lasting surface - for the rest of the session, on an
      // ordinary field where both actions were back on and working. That is the pairing the comment
      // four lines above already records as a defect, reached from the other direction: there the
      // notice erased a warning, here it survives the field it was written for. The messenger needs
      // one throwaway password box to arrange it, because it declares the inputType of every field
      // it presents.
      //
      // What goes back is what the MODEL says, not what the banner said before - there is no record
      // of that and re-rendering one would be the same mistake in a third place.
      // showChosenContactInMainInfoField refuses over a standing warning itself.
      //
      // ONLY when coming off a password field, which is why wasPassword is read. Unconditionally
      // this runs on every input session, and the messenger starts one whenever it likes - so
      // "Keybundle detected: click on decrypt" would be wiped by the user tapping the chat box
      // immediately after copying, which is the next gesture in the app's own workflow.
      showChosenContactInMainInfoField();
    }
    // The guard can change without the banner changing, and then nothing else repaints the
    // buttons. See refreshActionButtons.
    refreshActionButtons();
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
      // Takes the buttons down with the digits: clearFingerprintViews disables both. A review
      // reported them as staying live here and that turned out to be wrong - checked by removing
      // the disabling from clearFingerprintViews, which fails
      // VerifyScreenNamesAstandingRejectionTest. Left as one call rather than repeated here.
      clearFingerprintViews();

      // ...except Reject, when a warning is standing.
      //
      // The invariant this restores: a standing warning must always leave the user one deliberate
      // response. Both buttons live on this screen, both are disabled when there is no fingerprint,
      // and there is no fingerprint precisely when no key is pinned - so any warning that can stand
      // in that state is a dead end. The user is told something is wrong, sent here by the
      // warning's own text, and finds nothing to press; the banner then suppresses every routine
      // message for the life of the install.
      //
      // Verify stays down because there is nothing to compare: confirming a number that is not on
      // screen would be a lie. Reject is meaningful without a pin - it says "I do not trust
      // whatever arrives at this address", which is already what the rejection record means, and
      // its listener clears the warning. Two rounds of review found dead ends in this cell by two
      // different routes; this stops the next one being a dead end as well.
      // Scoped the same way the clear is. The escape hatch exists so a standing warning always has
      // a deliberate response available; offering it on a contact the warning is NOT about is a
      // false affordance with a permanent side effect, because rejectContactKey marks the address
      // whether or not anything was pinned. Open Alice's screen while a warning about Bob stands,
      // press the only enabled button, and Alice is flagged for good while Bob's warning survives
      // untouched. Address-less warnings still enable it anywhere: they have no other exit.
      //
      // A standing CAUTION counts too, and leaving it out was the same dead end by a third route.
      // A caution holds the banner exactly as a warning does - it suppresses every routine message
      // through mayOverwriteInfoBanner - and one can stand with nothing pinned: a bundle whose
      // signature fails pins nothing, and if the contact write also fails the lost-write caution
      // goes up about a contact with no key. Verify is dark because there is no number, Reject was
      // dark because no warning stood, and the remaining exit - deleting the contact - is refused
      // when its own write fails, which is the same storage trouble that raised the caution. The
      // banner then held a sentence nothing could clear, for the life of the process.
      //
      // A standing CAUTION does not qualify, and the round that added it was wrong. The escape
      // hatch exists so a standing item always leaves a deliberate response - but Reject is not a
      // deliberate response to a STORAGE failure, and offering it there is a false affordance with
      // a permanent destructive side effect: rejectContactKey marks the address whether or not
      // anything was pinned, and that record is deliberately permanent. Pressing it on a
      // lost-write caution either brands an address the user never had a complaint about - so the
      // contact's next genuine invite raises a key warning that is simply untrue - or, if the
      // write fails, does that in memory and does not even clear the caution, because the clear is
      // gated on the rejection landing. Both outcomes are worse than the dead end it was meant to
      // open, and the dead end has since been closed where it actually was: a failed deletion now
      // restores the row, so the contact and its verify screen stay reachable.
      if (mWarningStanding && mVerifyContactRejectButton != null
          && (mStandingWarningAddress == null || mStandingWarningAddress
              .equals(String.valueOf(chosenContact.getSignalProtocolAddress())))) {
        mVerifyContactRejectButton.setEnabled(true);
      }
      // "yet ... ask them for a key bundle first" describes an address nothing has happened at.
      // After a rejection something very much has, and the screen that says otherwise is the one
      // where the user decides whether to reject again.
      setInfoTextViewMessage(mVerifyContactInfoTextView,
          mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress())
              ? String.format(INFO_NO_FINGERPRINT_AFTER_REJECTION, labelFor(chosenContact))
              : INFO_NO_FINGERPRINT);
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
      // ...and if a rejection ALSO stands, say so in the same breath.
      //
      // An else-if here was wrong, and one extra post reaches the state that proves it: reject,
      // let the attacker's bundle be re-pinned, then let them post again to record a pending
      // change. Both hold. Confirming calls clearRejection AND dismissIdentityChange, so the
      // pending-change text - "confirm it to dismiss the warning" - was describing one of the two
      // things it does, which is the omission this notice was added to close.
      // Composed once and written once. Writing the pending-change text and then overwriting it
      // in the both-hold case left a dead first write, which reads as though two notices are being
      // shown in sequence.
      final String pending = String.format(INFO_VERIFY_PENDING_CHANGE, labelFor(chosenContact));
      setInfoTextViewMessage(mVerifyContactInfoTextView,
          mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress())
              ? pending + "\n\n" + String.format(INFO_VERIFY_AFTER_REJECTION,
                  labelFor(chosenContact))
              : pending);
    } else if (mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress())) {
      // A rejection with no pending change.
      setInfoTextViewMessage(mVerifyContactInfoTextView,
          String.format(INFO_VERIFY_AFTER_REJECTION, labelFor(chosenContact)));
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
        // An empty history, said plainly - and NOT the unreadable-log sentence, however much the
        // exception's name invites it. Reaching here means getUnencryptedMessages RETURNED: the log
        // was opened and read, and nothing in it belongs to this contact. An unreadable log throws
        // ChatLogUnavailableException out of that same call and lands in the arm below; a null
        // account or contact returns null and throws nothing at all. So this arm has exactly one
        // meaning, and it is the ordinary one.
        //
        // Written this way because the round before this got it backwards, on the reasoning that
        // the type is called UnknownContact so it must be a lookup that failed. The cost of that
        // was not cosmetic: every newly added contact is in this state before the first message,
        // and so is every user who has just successfully cleared their history - and both were told
        // their messages "are still on this device", with "do not assume anything here has been
        // removed". A deletion that worked, reported as a deletion to distrust, is the one direction
        // this sentence was written to avoid.
        Toast.makeText(getContext(), INFO_NO_SAVED_MESSAGES, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "no stored messages for this contact");
        e.printStackTrace();
      } catch (ChatLogUnavailableException e) {
        // The stored log exists and could not be read. Show no history rather than no keyboard:
        // this runs from a click listener, and an uncaught exception here kills the input method
        // in whatever app the user is typing in. The condition is persistent, so it would be
        // crash-on-tap until reinstall. Nothing is written - the account stays deferred - so the
        // unreadable log is still on disk for a later version, or a later unlock, to recover.
        //
        // And the message says that. This screen renders an empty list under "Message log with: X",
        // so the sentence beside it was the only thing distinguishing "you have no history" from
        // "your history cannot be opened" - and it said the first while this comment says the
        // second.
        Toast.makeText(getContext(), INFO_SAVED_MESSAGES_UNREADABLE, Toast.LENGTH_LONG).show();
        Log.e(TAG, "the chat log could not be read; showing none", e);
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

    refreshContactListInfoField();

    loadContactsIntoContactsListView();
  }

  private void createMessagesListReturnButtonClickListener() {
    if (mMessagesListReturnButton == null) return;
    mMessagesListReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.MAIN_VIEW));
  }

  /**
   * What the contact list says about itself, which is not always the ordinary line.
   *
   * <p>An unreadable store reaches exactly one surface - the main banner, and the button state
   * derived from its text. The contact list is one button away, that button is not disabled, and
   * there is no account to load rows from, so it rendered an EMPTY list under "if you want to chat
   * with someone new, invite them via the add button". That is the fresh-install reading
   * INFO_STORAGE_UNREADABLE exists to prevent, on the screen the banner is not on, next to the one
   * action it tells the user not to take.
   */
  private void refreshContactListInfoField() {
    if (mContactListInfoTextView == null) return;
    setInfoTextViewMessage(mContactListInfoTextView,
        storageIsUnreadable() ? INFO_STORAGE_UNREADABLE : INFO_CONTACT_LIST);
  }

  private void createContactListReturnButtonClickListener() {
    if (mContactListReturnButton == null) return;
    mContactListReturnButton.setOnClickListener(v -> showOnlyUIView(UIView.MAIN_VIEW));
  }

  private void createContactListInviteButtonClickListener() {
    if (mContactListInviteButton == null) return;
    mContactListInviteButton.setOnClickListener(v -> {
      // No storage check here, deliberately. A second copy of the condition would mask the guard in
      // createPreKeyResponseMessage - the one place that can actually see whether an account
      // loaded - so removing either would change nothing observable and both mutations would live.
      // That pattern has already cost this file two undetectable deletions.
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

    // Here, because here is where the row was created - not down in the arm that also happens to
    // establish a session.
    //
    // The check lived inside "getPreKeyResponse() != null && successful", which is one of THREE
    // ways out of this method, and the flag is set by createAndAddContactToContacts eight lines
    // above regardless of which one is taken. The two it missed:
    //
    //   - a bundle that was refused (successful == false). The row is in memory, the banner gives
    //     the ask-for-a-fresh-invite advice, and the lost write is not mentioned.
    //   - a ciphertext-only envelope, handled further down - the arm whose own comment records that
    //     it pins a key by trust-on-first-use. There the contact is created, a key is pinned, the
    //     message is decrypted and shown, and nothing at all is said: the account write can fail
    //     while the LOG write succeeds, so INFO_MESSAGE_NOT_SAVED does not cover it either. The
    //     user ends on the main view having read a message from a contact that will not survive the
    //     next raise, told nothing.
    //
    // That second one is the same shape as the defect this notice was added for, in the arm it did
    // not reach. Fired before the security warnings below on purpose: toasts queue, so the last one
    // posted is the one left on screen, and a key warning must not be buried under a storage one.
    // Recorded as a FACT about this contact, not inferred later from what the banner happens to say.
    //
    // Reading it back off the banner failed in the state that matters most. The banner is composed
    // warning-first, so the moment any warning shares it - a post-rejection pin, a duplicate name,
    // both reachable in this same method - the notice is no longer at the START of the string and a
    // startsWith gate simply misses it. The gate was defeated exactly when a security warning was
    // already on screen.
    // Before either arm can pin anything. Both of them run in this method, and the second reads a
    // world the first may have changed, so a per-arm snapshot would be taken too late on the arm
    // that matters.
    final boolean keyPinnedBeforeThisAdd =
        mE2EEStrip.hasPinnedKey(recipientProtocolAddress);
    // Whether anything on this pass has already put a sentence on the banner. The repaint at the
    // end is a fallback for the arms that write nothing, and a fallback that fires anyway is just
    // an overwrite.
    boolean bannerWasWritten = false;
    // Tracked separately from bannerWasWritten: that says "something is on the banner", this says
    // which sentence, and only this one has to survive being replaced by the storage caution.
    boolean sessionCreationFailed = false;

    final boolean rowReachedDisk = mE2EEStrip.lastContactWriteReachedDisk();
    if (!rowReachedDisk) {
      Toast.makeText(getContext(),
          String.format(INFO_CONTACT_NOT_SAVED, labelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
    }

    // A key arriving where the user previously reported a mismatch is not a first sighting, even
    // though the store looks empty. Without this the forged bundle that provoked the rejection can
    // simply be re-delivered and pinned silently.
    // The post-rejection warning is NOT posted here any more. It was a hand-rolled second copy of
    // warnIfKeyWasRejected - not a caller of it - so when that helper learned to require an actual
    // pinned key, and its callers moved to after the pin attempt, this path kept the old behaviour:
    // a bundle whose signature fails pins nothing and was still announced as "a new key for that
    // address". Worse here than elsewhere, because the standing false warning then suppressed
    // INFO_SESSION_CREATION_FAILED below, so the refused bundle produced only the false line.
    //
    // It now fires from the one place that knows the pin landed, further down. The duplicate-name
    // warning stays here: it is about two contact rows, not about a key, so it does not depend on
    // anything having been pinned.
    if (duplicateName) {
      final String duplicate =
          String.format(duplicateNameMessage(chosenContact), labelFor(chosenContact));
      Toast.makeText(getContext(), duplicate, Toast.LENGTH_LONG).show();
      // A warning, not an informational line: it is the only control covering the case the pin
      // cannot, and it cost the attacker one extra post to erase.
      //
      // Addressed, so deleting this row puts it down. That matters here more than anywhere: this
      // warning's whole subject is two rows the user cannot tell apart, and deleting one of them is
      // the resolution. Without the address it was the one warning the deletion route could not
      // clear - which is the opposite of what it needs.
      setWarningMessage(duplicate, String.valueOf(recipientProtocolAddress));
    }

    if (messageEnvelope.getPreKeyResponse() != null) {
      final boolean successful = mE2EEStrip.createSessionWithContact(chosenContact, messageEnvelope, recipientProtocolAddress);
      if (successful) {
        // Here, because here is where the key actually got pinned. Through the shared helper, so
        // there is one definition of when this warning is true rather than two that drift.
        warnIfKeyWasRejected(chosenContact);
        {
          // Through the guarded writer: an attacker whose substitution was just refused posts one
          // more ordinary invite under a fresh name at a fresh address, the user accepts it -
          // accepting invites is what this app is for - and neither of the two conditions above
          // fires, so "Contact Carol created" used to land straight on top of the warning.
          // "You can send messages now" was the only one of the four outcomes here that did
          // not send the user to the security number, and it is the one where nothing else
          // is protecting them: the other three fire BECAUSE the app noticed something. This
          // one fires because it noticed nothing, which is exactly what pinning a key the
          // messenger chose looks like when the substitution succeeded.
          // Shown BESIDE any standing warning rather than instead of it, because both of the
          // obvious options are defects this file already records.
          //
          // Suppressed (the plain guarded line it used to be): a relay raises a standing warning
          // about Bob - it can do that unilaterally, with the refused-invite warning or with the
          // identity-change one, which this file elsewhere says "any messenger can arrange with one
          // forged bundle" - then offers an invite as "Carol". The user pins an attacker-chosen key
          // and never sees the one caution that fires BECAUSE nothing was noticed.
          //
          // Replacing (posting it as a warning of its own): that is "Contact Carol created" landing
          // on top of a security warning, which StripWarningErasureTest exists to forbid.
          //
          // So: both. The warning keeps standing and keeps its text; the caution appears under it.
          cautionThatAkeyWasPinned(keyPinnedBeforeThisAdd);
        }
      } else {
        // warnIfIdentityChanged is a WRITER, not a predicate, and it was sitting on the right of an
        // && - so widening the left term to cover the caution stopped it being CALLED at all.
        //
        // That mattered at the worst possible moment. On this arm it is the only thing that raises
        // the identity-change warning: createSessionWithContact shows a Toast and nothing more, and
        // the ciphertext path that would otherwise post it is not taken by a bundle-only invite. So
        // with a caution standing - the common case, straight after adding any contact - the app
        // could detect a key substitution at a pinned address and put nothing on the one surface
        // that persists. The banner is built to hold a warning and a caution together; suppressing
        // the generic advice over a standing item is right, suppressing the warning is not.
        final boolean warned = warnIfIdentityChanged(chosenContact);
        // Recorded whether or not the line below is painted, and that separation is the fix.
        //
        // It used to be set inside the guard, so the fact was lost exactly when the guard fired -
        // and the guard fires when a standing item holds the banner, which is the common case right
        // after adding anyone. The lost-write caution further down then composed itself without the
        // refusal, while OVERWRITING the standing item the guard was protecting anyway. So the
        // suppression protected nothing and cost the sentence: the user was told to "add them again
        // successfully" with an invite that will never work, and never told it had been refused.
        sessionCreationFailed = true;
        if (!warned && !aStandingItemHoldsTheBanner()) {
        // createSessionWithContact already writes INFO_IDENTITY_CHANGED when a change is pending,
        // and this used to overwrite it with INFO_SESSION_CREATION_FAILED - the same delete-and-
        // re-invite advice - defeating its own guard. The standing check covers the same mistake
        // from the other direction: a failed session must not paint generic advice over the
        // post-rejection or duplicate-name warning, which would leave the flag set over text that
        // is not a warning at all - and nothing passive could then correct it.
          setInfoTextViewMessage(mInfoTextView, INFO_SESSION_CREATION_FAILED);
          // Counted, because this line is not stored anywhere. It is a plain banner write rather
          // than a standing item, so anything that repaints afterwards - including the fallback at
          // the end of this method - destroys it, and the one sentence telling the user to ask for
          // a fresh invite is gone with no trace.
          bannerWasWritten = true;
        }
      }
    }

    if (messageEnvelope.getCiphertextMessage() != null) {
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
      changeImageButtonState(mDecryptButton, ButtonState.DISABLED);
      // This arm pins too, and that is not obvious: SignalProtocolMain.decrypt takes its PREKEY
      // arm on the ciphertext TYPE alone, and isTrustedIdentity returns true whenever nothing is
      // pinned - a rejection record does not block trust-on-first-use. So a bundle-less PreKey
      // message relayed at an address the user explicitly rejected lands a new key here, with no
      // getPreKeyResponse() and therefore none of the warnings above.
      //
      // Before the previous commit an unconditional check at the top of this method covered this
      // arm. Moving it to "where the key actually got pinned" moved it to only ONE of the two
      // places that pin, which is the same mistake in the opposite direction. Both arms call the
      // shared helper now; it is a no-op unless a key is really pinned at a rejected address.
      warnIfKeyWasRejected(chosenContact);

      // And the caution, which this arm pinned a key without.
      //
      // This is the INVITER'S side of every conversation this app sets up. The help describes it:
      // "Your chat partner has to add you to their contact list and then send you an encrypted
      // message... The contact is now automatically selected." So whoever sent the invite meets
      // this arm, once, at the moment their peer's key is pinned - and the pin here is
      // trust-on-first-use over a key that arrived through the messenger, which is the party this
      // app treats as the adversary. Choosing this arm costs a relay one omitted field: the type is
      // decided by field presence alone, and decrypt takes its PreKey branch on the ciphertext type
      // where isTrustedIdentity returns true whenever nothing is pinned.
      //
      // The bundle arm has said this for several rounds, and its comment there argues it is the
      // most important of the four outcomes on that arm precisely BECAUSE it fires when nothing was
      // noticed - which is what a successful substitution looks like from inside the app. The same
      // sentence was owed here and was not being said.
      //
      // Beside any standing warning rather than instead of one, for the reason the sibling records:
      // replacing would be a success line landing on top of a security warning, and suppressing
      // would let a relay raise a warning about anyone in order to silence the one notice that
      // fires because nothing was noticed.
      //
      // It also, not incidentally, repaints the banner. Nothing else on this path does, so the
      // banner was left reading "No contact chosen" while a contact WAS chosen and Encrypt aimed at
      // them - and since disablesActionButtons matches that exact sentence, Encrypt and Decrypt
      // stayed dark. The user was handed a decrypted message and no way to answer it, on the flow
      // the help calls "automatically selected".
      cautionThatAkeyWasPinned(keyPinnedBeforeThisAdd);
      // And the banner is repainted whether or not anything was pinned.
      //
      // It used to be repainted only as a side effect of posting that caution, so gating the
      // caution took the repaint with it: on a decrypt that pins nothing the banner was left
      // reading "No contact chosen" while a contact WAS chosen, which is false about the thing that
      // matters most on this screen and, because disablesActionButtons matches that sentence, left
      // both action buttons dark.
      showChosenContactInMainInfoField();
      bannerWasWritten = true;
    }

    // The same repaint the ciphertext arm gets, for every arm that has said nothing, because the
    // reason is not about ciphertext.
    //
    // Fixing this on one arm last round was the third time in this file a correction reached one of
    // two places that needed it. The bundle-only arm has no other banner write once the pin caution
    // is gated: reach it by DELETING a contact and accepting their fresh invite - the app's own
    // recovery advice - and every repaint is skipped. The duplicate-name warning does not fire,
    // because a re-add at the same address is deliberately suppressed while the pin survives; the
    // pin caution does not fire, because the pin survived the deletion; the ciphertext arm does not
    // run, because an invite carries no message. So the banner keeps whatever
    // decryptMessageInClipboard left there on its way to the add screen, which is
    // "No contact chosen" - and since disablesActionButtons matches that exact sentence, Encrypt
    // and Decrypt are both dark, on a contact that was just set up successfully.
    //
    // Only when nothing else has written, so it cannot destroy a message an arm just posted. The
    // first version of this line was unconditional and did exactly that to the refused-invite
    // sentence.
    if (!bannerWasWritten) showChosenContactInMainInfoField();

    // Last, so it is the caution left standing.
    //
    // Both arms above may post the compare-the-number caution, and there is one caution slot. The
    // two sentences are about different things - one about a key, one about the contact row - but
    // when both are true the row is the one to say, because its advice contains the other's: "do
    // not send them anything until you have added them again successfully" already forbids sending
    // anything private to a key nobody has compared, and it also explains why the contact is about
    // to vanish, which the compare-the-number sentence does not mention.
    //
    // Posted here rather than where the fact is learned, for exactly that ordering: written earlier,
    // it was overwritten a few lines later by the arm that pinned, and the screen went back to
    // reassuring the user about a contact that will not survive the next restart.
    //
    // Independent of whether a key was pinned, which folding it into the pin caution had made it:
    // a refused invite whose write also failed says nothing about keys, and used to say nothing
    // about the lost row either, past a toast.
    // The row's write and the SESSION's write are separate, and either can be the one that failed.
    //
    // A row that landed while the session and the freshly pinned key did not is the worse of the
    // two and used to be entirely silent: "Session with X created", a prompt to compare a security
    // number, and a session that exists in memory only until the next reloadAccount. Same sentence
    // for both, because it is true of both and the user's move is the same - add them again and
    // check it worked before sending anything.
    if (!rowReachedDisk || !mE2EEStrip.lastSessionWriteReachedDisk()) {
      // Carrying the refused-invite instruction with it when that also applies.
      //
      // The two are independent - a bundle can be refused whether or not the write landed - and the
      // refusal line is a plain banner write stored nowhere, so this repaint destroyed it. That
      // left the user with a storage notice and nothing telling them the invite itself had failed
      // or to ask for a fresh one. Appended rather than allowed to overwrite, because "add them
      // again successfully" is not actionable when the invite they have will never work.
      rememberContactIsNotOnDisk(chosenContact);
      final String notSaved = String.format(INFO_CONTACT_NOT_SAVED, labelFor(chosenContact));
      setCautionBesideAnyWarning(
          sessionCreationFailed ? notSaved + " " + INFO_SESSION_CREATION_FAILED : notSaved,
          chosenContact, true);
    }
  }

  /**
   * The caution both arms that pin a key owe the user, in one place.
   *
   * <p>Written once because it was written twice. The two arms of {@code addContact} that pin -
   * a bundle that established a session, and a bundle-less message that pinned by
   * trust-on-first-use - must say the same thing, and a second copy of a sentence is a sentence
   * that will drift: this file already carries a test forbidding one claim from being shared
   * between two messages for the opposite reason, and the moment one copy is reworded the two arms
   * start describing the same event differently.
   */
  /**
   * The caution owed when a key was pinned by THIS paste.
   *
   * <p>Gated on the event actually having happened, and "happened" means <em>changed</em>, not
   * "is true now". {@code hasPinnedKey} alone is satisfied by a pin that survived a deletion —
   * {@code removeContact} keeps the identity on purpose — so after deleting a contact and re-adding
   * them, any envelope at that address made the app announce that a key had just reached the user
   * through the messenger when nothing of the sort occurred. It also fires on the honest re-add,
   * where the message decrypts precisely BECAUSE it matched the key already trusted, which is the
   * opposite of an unattributable new key.
   *
   * <p>The caller passes whether anything was pinned before it acted; this fires only on the
   * transition. That is exactly the {@code keyPinnedByThisPaste} shape the sibling on the decrypt
   * path already uses, and the before-value has to be a parameter because by the time this runs the
   * pin has happened and the old answer is gone.
   *
   * <p>A pin that merely CHANGES cannot occur here and is not the omission it looks like: with a key
   * already pinned, a different one is refused rather than substituted, which is the whole point of
   * pinning.
   */
  private void cautionThatAkeyWasPinned(final boolean pinnedBefore) {
    if (chosenContact == null || pinnedBefore) return;
    if (!mE2EEStrip.hasPinnedKey(chosenContact.getSignalProtocolAddress())) return;

    setCautionBesideAnyWarning("Contact " + labelFor(chosenContact)
        + " created. This key reached you through the messenger and the app cannot tell whose it is"
        + " - compare the security number by voice before sending anything private.", chosenContact);
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
    // No expiry call here. One was placed outside this lambda, where it ran once at construction
    // with an empty map and never on a tap - a statement whose comment described what it would do
    // if it were one line further in. The expiry that matters runs where the act happens, in
    // encryptAndSendInputFieldContent.
    mInfoTextView.setOnClickListener(v -> resetChosenContactAndInfoText());
  }

  /** Held so the view can be unregistered when it is discarded. See releaseClipboardListener. */
  private ClipboardManager.OnPrimaryClipChangedListener mClipboardListener;

  private void initClipboardListenerToChangeStateOfDecryptButton() {
    final ClipboardManager clipboardManager = (ClipboardManager) this.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    mClipboardListener = () -> {
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
        // Parse once. This ran on every clipboard change and used to deserialize up to three
        // times, and getMessageType returns null for anything unrecognised - which then NPE'd on
        // .equals() inside a system clipboard callback.
        final MessageType clipboardType =
            mE2EEStrip.getMessageType(EnvelopeCodec.fromWire(decodedItem));

        onKryptEyItemOnClipboard(clipboardType);
      } catch (IOException e) {
        // Expected constantly: the clipboard usually holds ordinary text.
        Log.d(TAG, "Clipboard content is not a KryptEY message");
      } catch (Exception e) {
        // Nothing may escape a system clipboard callback: an unchecked exception here takes the
        // whole input-method process down, in every app, on an ordinary copy.
        Log.e(TAG, "Unexpected failure inspecting the clipboard", e);
      }
    };
    clipboardManager.addPrimaryClipChangedListener(mClipboardListener);
  }

  /**
   * Whether an info message means encrypt and decrypt cannot work.
   *
   * <p>Separated so it can be tested: the watcher itself needs an inflated IME, and this is the
   * decision, not the wiring.
   */
  /**
   * What the strip does when the clipboard holds something of ours: re-arm, then maybe say so.
   *
   * <p>One implementation, called by the real listener and by the test seam, because the ORDER is
   * the thing worth pinning and a seam that re-implements it pins only its own copy. That is the
   * mistake {@code mayOverwriteInfoBanner} was extracted to avoid — "so the listener and its test
   * run the SAME code" — and duplicating the ordering beside it put it straight back: the whole
   * re-arm could be deleted from the listener with the suite still green.
   *
   * <p>The re-arm comes first, through {@code refreshActionButtons} so the password-field and
   * unreadable-storage answers still win. The guard after it protects the BANNER only. It used to
   * protect the buttons too, by accident, because the only thing that re-enabled them was the
   * {@code TextWatcher} firing on a banner write — so once a warning stood and stopped banner
   * writes, one decrypt left Decrypt dark with nothing able to bring it back.
   */
  private void onKryptEyItemOnClipboard(final MessageType clipboardType) {
    if (clipboardType == MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE
        || clipboardType == MessageType.PRE_KEY_RESPONSE_MESSAGE
        || clipboardType == MessageType.SIGNAL_MESSAGE) {
      refreshActionButtons();
    }

    if (!mayOverwriteInfoBanner()) return;

    if (clipboardType == MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE) {
      setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_AND_SIGNAL_MESSAGE_DETECTED);
    } else if (clipboardType == MessageType.PRE_KEY_RESPONSE_MESSAGE) {
      setInfoTextViewMessage(mInfoTextView, INFO_PRE_KEY_DETECTED);
    } else if (clipboardType == MessageType.SIGNAL_MESSAGE) {
      setInfoTextViewMessage(mInfoTextView, INFO_SIGNAL_MESSAGE_DETECTED);
    }
  }

  static boolean disablesActionButtons(final String message) {
    if (message == null) return false;
    // startsWith, not equals. The banner may now carry a "Sending to: X" line under a standing
    // warning, and matching on equality meant appending that line silently re-enabled Encrypt and
    // Decrypt on an install whose store cannot be decrypted. Caught by the test that asserts the
    // storage warning keeps its buttons down - which is the whole reason that assertion is in it.
    return message.startsWith(INFO_NO_CONTACT_CHOSEN_TEXT)
        || message.startsWith(INFO_STORAGE_UNREADABLE);
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
        // A banner change is one of the two events that can change the answer. The other is the
        // password-field guard being armed or lowered, which does not always write a banner - see
        // refreshActionButtons.
        refreshActionButtons();
      }
    });
  }

  /**
   * Decides whether Encrypt and Decrypt are usable, and paints that.
   *
   * <p>The banner text is still consulted, and that half is unchanged: it is how "no contact
   * chosen" and "the store cannot be decrypted" reach the buttons. What it is NOT allowed to be any
   * more is the WHOLE answer. Deriving the button state from rendered prose means every message
   * anyone adds to this file is a silent decision about whether both actions are offered, with
   * ENABLED as the default for anything unrecognised - and the password-field notice was exactly
   * that: {@code setHostFieldIsPassword(true)} wrote "Encryption and decryption are turned off
   * here" onto the banner, the watcher read a string that was not one of the two named ones, and
   * both buttons came ON. Focusing another app's password box was the only event in this app that
   * turned the action buttons on by announcing that they were off.
   *
   * <p>{@code actionsAreAvailable()} is the fact behind that sentence, so it is asked directly. The
   * two click paths already refuse on it, which is why the defect was a lie about state rather than
   * a way to run either action - but the buttons are the control surface and the prose is beside
   * it, and an app whose only lasting surface says one thing while its buttons say the other has
   * spent the credibility it needs for the warnings that matter.
   *
   * <p>Called from the text watcher AND from {@code setHostFieldIsPassword}, because the guard can
   * change without the banner changing: {@code setInfoUnlessWarned} correctly refuses to write the
   * notice over a standing security warning, and with no banner change there was no watcher, so
   * over a password box with a substitution warning on screen both buttons stayed lit - the one
   * state where both of the app's reasons to refuse are live at once.
   */
  /**
   * Whether the chosen contact's row is known to have reached disk.
   *
   * <p>A fact, kept because the alternative was reading it back out of the banner - and the banner
   * is composed warning-first, so any warning sharing it moved the notice off the start of the
   * string and a prefix match missed it precisely when a security warning was already on screen.
   */
  /**
   * Contacts whose row is known not to have reached disk, and the write count at that moment.
   *
   * <p>Keyed by address and kept apart from the banner, because both of the places this lived
   * before could be taken away by something that had nothing to do with the fact.
   *
   * <p>It began as a flag about "the chosen contact", reset on every recipient change - which the
   * messenger forces at will by hiding the keyboard. Moving it onto the standing caution fixed that
   * and introduced the mirror image: there is one caution slot, so adding any OTHER contact
   * successfully overwrote the caution and cancelled the refusal with it. Neither of those is a
   * fact about whether Bob's row is on disk, and this is.
   *
   * <p>The stored value is the write count when the failure was seen. The account batch writes the
   * whole account, contact list included, so any later successful write puts the row on disk and
   * makes the refusal wrong - refusing to send to somebody who IS saved is the same class of defect
   * as the reverse, and it is the one that teaches users to ignore the notice.
   */
  private final Map<String, Long> mContactsNotOnDisk = new HashMap<>();

  /**
   * A notice about the store rather than about a contact, kept apart from the caution slot.
   *
   * <p>There is one thing that goes here: a deletion whose account write landed and whose message
   * log write did not, leaving that contact's plaintext in the log owned by no row. It is the
   * user's only chance to learn of a condition no screen can otherwise show and their one erasure
   * action cannot reach.
   *
   * <p>It cannot live in {@code mStandingCaution}. There is one caution slot, so the next ordinary
   * contact add overwrote it; and it names no contact, which {@code clearCautionIfAbout} reads as
   * "about anyone", so verifying or deleting anybody cleared it as well. Both are trivially
   * reachable straight after the deletion that raises it.
   */
  /**
   * Whether the standing caution is one of the storage notices, rather than a security one.
   *
   * <p>Set beside the caution so the retirement above can ask a fact instead of searching the
   * sentence for a phrase. Searching worked until a second storage notice was written that did not
   * contain the phrase, and then failed silently in the direction that leaves a false sentence on
   * the durable surface.
   */
  private boolean mStandingCautionIsAstorageNotice;

  private String mStandingStoreNotice;

  /** The message-log write count when that notice went up; see {@link #clearAstoreNoticeThatHasBeenResolved}. */
  private long mLogWritesLandedWhenNoticeRaised = -1;

  private void refreshActionButtons() {
    // The same guard setMainInfoTextTextChangeListener carries, and no more: the buttons come from
    // the same inflate and the watcher already dereferenced them unguarded, so a null check on them
    // here would be a line no test could ever reach.
    if (mInfoTextView == null) return;

    // Both retirements run here, and both had lost their call site.
    //
    // Each of these clears something that a later successful write has made untrue - a send-refusal
    // for a contact now on disk, and a notice about plaintext the log has since dropped. Each was
    // wired here once and then lost when a file was restored from a snapshot taken before it, and
    // nothing failed either time: a private method with no callers compiles, and a notice that is
    // never retired simply stays on screen looking like a notice. The store one holds the banner,
    // so its absence suppressed every informational line for the life of the process.
    //
    // Both repaint, and both are idempotent: the repaint re-enters this method through the banner
    // watcher, by which point the entry or the notice is already gone and the second pass returns
    // at once.
    expireRefusalsSettledByAlaterWrite();
    clearAstoreNoticeThatHasBeenResolved();
    // storageIsUnreadable() asked directly, as well as through the banner.
    //
    // The banner half is how "no contact chosen" reaches the buttons and it stays. But it is prose,
    // and any warning that overwrites INFO_STORAGE_UNREADABLE took the button state with it - which
    // mattered little while the clipboard listener returned early on a standing warning, and
    // mattered immediately once the re-arm moved ahead of that guard. Asking the store is not
    // subject to whatever happens to be written on screen.
    final ButtonState state =
        !actionsAreAvailable() || storageIsUnreadable()
            || disablesActionButtons(mInfoTextView.getText().toString())
            ? ButtonState.DISABLED : ButtonState.ENABLED;
    changeImageButtonState(mDecryptButton, state);

    // Encrypt alone carries the lost-write refusal, and Decrypt deliberately does not.
    //
    // Disabling both was a trap with no exit. The notice says "do not send them anything until you
    // have added them again successfully"; adding them again means pasting their invite, and
    // pasting needs Decrypt. Deleting the contact first does not help - a deletion whose write also
    // fails is not treated as done, so the caution stays up while the row leaves the list, taking
    // that contact's verify screen and therefore the only unconditional clear with it. The banner
    // then held a caution nothing could clear, with both buttons dark FOR EVERY CONTACT, including
    // ones whose keys are fine on disk, until the input-method process was killed. A rotation did
    // not help: the caution is carried across a rebuild on purpose.
    //
    // Refusing to SEND is the whole of what the sentence asks for. Reading is how the user gets out.
    changeImageButtonState(mEncryptButton,
        state == ButtonState.ENABLED && sendingIsRefusedForTheChosenContact()
            ? ButtonState.DISABLED : state);
  }

  /**
   * Whether the account on disk cannot be decrypted.
   *
   * <p>Read live rather than cached at construction: the strip is built once and the state can only
   * be discovered when storage is next touched.
   */
  /**
   * Whether the standing caution forbids sending to whoever is chosen right now.
   *
   * <p>Scoped to the address, like every other deliberate response on this screen: a contact whose
   * row failed to save must not disable Encrypt for a different contact whose row is on disk. And
   * asked live rather than cached, so re-selecting the contact the caution is about brings the
   * refusal back with them - which is the whole point, since the messenger can move the recipient
   * off that contact whenever it likes.
   */
  /**
   * Whether the app is refusing to send to whoever is chosen. A question, and only a question.
   *
   * <p>It briefly cleared the expired caution itself, and the short-circuit guard caught it: this is
   * called on the right of an {@code &&} in {@code refreshActionButtons}, so Java is free to skip
   * it, and a writer that may or may not run is how the identity-change warning stopped being
   * raised. Expiring is a separate statement now, run unconditionally before the question is asked.
   */
  private boolean sendingIsRefusedForTheChosenContact() {
    if (chosenContact == null) return false;
    return mContactsNotOnDisk.containsKey(String.valueOf(chosenContact.getSignalProtocolAddress()));
  }

  /**
   * Drops refusals that a later successful write has settled, and the caution that justified them.
   *
   * <p>Both halves together, because separating them was the defect: the refusal expired on a later
   * write while the sentence justifying it was cleared only by verify, reject or a landed delete, so
   * the app returned to offering exactly what it was still telling the user not to do - permanently
   * rather than for one repaint, on the surface it calls durable. Two halves of one fact must not
   * have two lifetimes, which is the mistake this refusal has now made in three different shapes.
   *
   * <p>The account batch writes the whole contact list, so one landed write anywhere puts every
   * in-memory row on disk. The one write that does NOT count is the write-back inside
   * {@code reloadAccount}, which stores what it has just read and therefore cannot contain the row
   * an earlier failure lost; it is excluded at the counter rather than here.
   */
  private void expireRefusalsSettledByAlaterWrite() {
    if (mContactsNotOnDisk.isEmpty()) return;
    // Entries whose contact no longer exists are dropped here rather than left to accumulate.
    //
    // The only other removals run for the contact currently chosen, or on a landed deletion. When
    // the failure was the row's own write the row was never on disk, so a later reload drops it
    // from memory too - the contact is then unselectable and undeletable, and the entry would be
    // copied into every CarriedState for the life of the process. It cannot produce a false refusal
    // (a genuinely new contact at that address finds the counter far above the stale value), so
    // this is a leak rather than a hazard, and it is cheaper to close than to reason about again.
    final ArrayList<Contact> known = mE2EEStrip.getContacts();
    if (known != null) {
      final java.util.Set<String> live = new java.util.HashSet<>();
      for (final Contact contact : known) {
        live.add(String.valueOf(contact.getSignalProtocolAddress()));
      }
      mContactsNotOnDisk.keySet().retainAll(live);
      if (mContactsNotOnDisk.isEmpty()) return;
    }
    final long landed = mE2EEStrip.accountWritesLanded();
    final List<String> settled = new ArrayList<>();
    for (final Map.Entry<String, Long> entry : mContactsNotOnDisk.entrySet()) {
      if (landed > entry.getValue()) settled.add(entry.getKey());
    }
    for (final String address : settled) {
      mContactsNotOnDisk.remove(address);
      // Matched on a FLAG rather than on a phrase inside the sentence.
      //
      // It matched the substring "could not be saved", which INFO_CONTACT_NOT_SAVED contains and
      // INFO_SESSION_NOT_SAVED - added later, and posted by the same code path that records the
      // refusal - does not. So after a lost session write a later landed write removed the refusal
      // while the sentence justifying it stayed on the banner, clearable only by verifying,
      // rejecting or deleting that contact: a security action taken for a storage reason, which
      // this file calls a false affordance. That is verbatim the defect this method exists to
      // prevent, reintroduced by adding a second string that the phrase did not match.
      if (address.equals(mStandingCautionAddress) && mStandingCautionIsAstorageNotice) {
        mStandingCaution = null;
        mStandingCautionAddress = null;
        mStandingCautionIsAstorageNotice = false;
        setInfoTextViewMessage(mInfoTextView, mWarningStanding ? warningWithRecipient()
            : chosenContact != null ? "Chosen contact: " + labelFor(chosenContact)
                : INFO_NO_CONTACT_CHOSEN);
      }
    }
  }

  /** Records that this contact's row did not reach disk. */
  private void rememberContactIsNotOnDisk(final Contact contact) {
    if (contact == null) return;
    mContactsNotOnDisk.put(String.valueOf(contact.getSignalProtocolAddress()),
        mE2EEStrip.accountWritesLanded());
  }

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
    // Asked of the LIVE list alone. This used to be "the name is known AND it is not retired", so a
    // retirement suppressed the live wording even when a live row of that name was sitting in the
    // list - and the live wording is the only one that ends "Both now appear in your list, tagged
    // by address", which is the sentence pointing at the tag the user is meant to read. The case
    // where both hold is the attacker's second attempt at a name this user already deleted once,
    // i.e. the one furthest along.
    final boolean live = SignalProtocolMain.hasLiveContactWithSameDisplayName(
        contact.getFirstName(), contact.getLastName(), contact.getSignalProtocolAddress());
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

    // The refusal, asked HERE rather than only rendered on the button.
    //
    // A dark button is a hint, not a control. Button state is recomputed from the banner's
    // TextWatcher, so it moves when the banner TEXT changes - and the events that matter most here
    // need not change it: re-selecting a contact under a standing item repaints the same string,
    // and a later successful write elsewhere changes nothing on screen at all. Either way the
    // buttons keep whatever state they had, which is stale in both directions: offering a send the
    // app forbids, or refusing one it no longer has reason to refuse.
    //
    // So the question is asked at the moment of the act, with the expiry run first. This is also
    // the only guard the send path has ever had against a contact that exists in memory only -
    // encryptAndSend had none, and the whole refusal rested on a button being dark.
    expireRefusalsSettledByAlaterWrite();
    if (sendingIsRefusedForTheChosenContact()) {
      Toast.makeText(getContext(),
          String.format(INFO_CONTACT_NOT_SAVED, labelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
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
          // The send half of "delivered but not recorded". The receive half reported this and the
          // send half did not, so a sent message could vanish from the history with nothing said -
          // arguably more visible to the user than the incoming case, and equally unexplained.
          if (mE2EEStrip.lastChatLogWriteFailed()) {
            Toast.makeText(getContext(), INFO_SENT_MESSAGE_NOT_SAVED, Toast.LENGTH_LONG).show();
          }
          // And the KEY state, which is a different fact from the history entry and was reported
          // by nothing.
          //
          // A send advances the ratchet and may rotate the signed and Kyber pre-keys, attaching the
          // new public halves to the outgoing message. The log lives in its own file and can commit
          // while the account does not - and in exactly that case the chat-log flag above stays
          // false, so the app said nothing at all while the peer walked away holding material whose
          // private half never reached this device.
          if (!mE2EEStrip.lastSessionWriteReachedDisk()) {
            Toast.makeText(getContext(),
                String.format(INFO_SEND_STATE_NOT_SAVED, labelFor(chosenContact)),
                Toast.LENGTH_LONG).show();
            setCautionBesideAnyWarning(
                String.format(INFO_SEND_STATE_NOT_SAVED, labelFor(chosenContact)),
                chosenContact, true);
          }
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
      } catch (ChatLogUnavailableException e) {
        // Recording the plaintext is part of sending, and it could not be done - the log exists
        // and will not read. Refusing the send is the honest outcome: completing it would put a
        // message in the peer's hands that this device has no record of, and the user would have
        // no way to tell. Crashing is not an option here for the reason in the catch above.
        Toast.makeText(getContext(), INFO_MESSAGE_ENCRYPTION_FAILED, Toast.LENGTH_SHORT).show();
        Log.e(TAG, "the chat log could not be read, so the send was not recorded; refusing", e);
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
      // Read live, every time the screen is shown. The state can only be discovered when storage is
      // next touched, and this view is built once for the life of the strip.
      refreshContactListInfoField();
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
      if (hasFocus) composeInsideTheKeyboard();
      // Losing focus does NOT send typing back to the host app.
      //
      // It used to, and that was the app's central promise broken by one unprivileged call: any
      // application can call InputMethodManager.showSoftInput, which reaches
      // LatinIME.onShowInputRequested, whose first statement clears focus on this box. Focus goes,
      // this listener lowered the flag, and every subsequent keystroke was committed into the
      // messenger's own EditText in cleartext - with the compose box still on screen holding the
      // draft, the only visible change being two small buttons disappearing. It also fired with no
      // adversary at all: opening any other strip screen sets this layout GONE, which clears focus.
      //
      // Focus loss is not the user saying "type into the host now". The two things that are - a
      // send, and the keyboard being dismissed - lower it explicitly.
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
    // The same first statement its two siblings carry, on the path that writes the LONGEST string
    // this app produces into the host field.
    //
    // encryptAndSendInputFieldContent and decryptMessageInClipboard both open by asking whether the
    // actions may run against the field that has focus; this one asked nothing, and it ends in the
    // same mListener.onTextInput they do. So over another app's password box the strip said
    // "Encryption and decryption are turned off here" and then committed a whole encoded key bundle
    // into it - handed to that app's storage, autofill and whatever it syncs, which is the sentence
    // mHostFieldIsPassword's own javadoc exists for. Two taps, on the one screen button the app
    // deliberately never disables.
    if (!actionsAreAvailable()) {
      Toast.makeText(getContext(), INFO_PASSWORD_FIELD, Toast.LENGTH_LONG).show();
      return;
    }
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
      // Names the state rather than reporting a failure. The commonest way to get here is the one
      // INFO_STORAGE_UNREADABLE describes - no account loaded, so no bundle can be built - and
      // "generating failed" reads as something to try again, which is the wrong move for it.
      Toast.makeText(getContext(), INFO_INVITE_UNAVAILABLE, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Generating pre key message failed!");
      e.printStackTrace();
      return;
    } catch (RuntimeException e) {
      // Nothing unchecked may escape a click listener. Out of one there is nothing left to catch it
      // and the input-method process dies, in whatever app the user is in - the same reason the
      // clipboard listener and E2EEStrip.decryptMessage each carry an unchecked catch.
      //
      // Not hypothetical: getPreKeyBundle dereferenced the account on its first statement, and
      // there is no account whenever the store cannot be decrypted, so pressing Invite on that
      // install threw NullPointerException straight through View.performClick. That cause is closed
      // where it lives, in createPreKeyResponseMessage; this stops the class.
      //
      // Deliberately a DIFFERENT message from the branch above, and not for the user's benefit -
      // it is what makes the two layers separable. With one message, deleting either the guard or
      // this catch left the screen identical and no test could tell them apart. Reaching here means
      // something threw that nothing here anticipated, which is not the storage story.
      Toast.makeText(getContext(), INFO_INVITE_FAILED, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Building a key bundle raised an unchecked exception");
      return;
    }
    // Refused rather than handed over, because an invite whose private halves are not on disk is
    // worse than no invite: the reply to it cannot be read, and the advice the app then gives for a
    // failed decrypt is the one that swaps keys.
    if (!mE2EEStrip.lastBundleExportReachedDisk()) {
      Toast.makeText(getContext(), INFO_INVITE_NOT_SAVED, Toast.LENGTH_LONG).show();
      setCautionBesideAnyWarning(INFO_INVITE_NOT_SAVED, chosenContact, true);
      Log.e(TAG, "the invite's private halves did not reach disk; refusing to hand it over");
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
      // This arm pins too, and the sender decides which arm the envelope takes.
      //
      // The javadoc below used to say the plain signal-message arm carries no bundle "so nothing is
      // pinned there". SignalProtocolMain.decrypt's own comment says the opposite, and it is the
      // one that is right: "a PreKeySignalMessage carries its own identity key and needs no
      // attached bundle". decrypt takes its PREKEY_TYPE arm on the envelope's ciphertextType alone
      // and calls sessionCipher.decrypt(preKeySignalMessage), which trust-on-first-use pins that
      // key whenever the address holds none - and holding none is precisely what a completed
      // rejection leaves behind: rejectContactKey removes the identity AND the session, keeping the
      // contact row.
      //
      // So an attacker who omits the bundle reached the same pin, at the same rejected address,
      // through the one arm of the three that never asked. getMessageType reads field presence and
      // nothing else, so choosing the arm costs them nothing.
      //
      // Asked of the ciphertext type rather than unconditionally: a WHISPER_TYPE message pins
      // nothing, and after a rejection it cannot decrypt either, because the session went with the
      // key. A sender who declares PREKEY_TYPE over other bytes used to get a warning on a message
      // that then fails to decrypt, and the comment here called that "the safe direction". It is
      // not: nothing pins, so the warning claims a key that does not exist and the verify screen it
      // points at has no fingerprint to compare. Asked after the attempt instead.
      // Decrypt FIRST, and only adopt the sender as the chosen contact if something came out.
      //
      // Gating the "Detected contact" line alone was not enough, and the reason is worth keeping:
      // decryptMessageInClipboard calls showChosenContactInMainInfoField four statements after this
      // returns, which paints "Chosen contact: Bob" on the same banner from the same unsigned
      // header. Suppressing one verb while the other one runs unconditionally changed nothing a
      // user would see - and the test that "proved" the gate drove processSignalMessage through a
      // seam, so it never reached the line that undid it.
      //
      // The address name and device id are an unsigned header the relay copies out of any envelope
      // that contact ever sent. A successful decrypt is the only thing on this route that ties the
      // message to the key pinned at that address, so it is what the strip waits for before naming
      // anyone.
      final boolean delivered =
          decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
      if (messageEnvelope.getCiphertextType()
          == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
        warnIfKeyWasRejected(sender);
      }
      // The third arm gets the same reader, because the same write can be lost here.
      //
      // This arm carries no bundle, but decrypt writes at the end of every successful decryption -
      // the advanced ratchet, and on a PREKEY message the key it just pinned by trust-on-first-use.
      // If that write is lost the message is delivered and the session state is not, so the next
      // message from that peer fails to decrypt, and this app's standard advice for a failed
      // decrypt is delete-and-re-invite: the key-substitution window. Reported here for the same
      // reason it is reported on the other two, and the fact that only two of three arms had it was
      // found by comparing the arms rather than by another review round.
      // Called unconditionally into a local, not on the right of an &&.
      //
      // It writes - a caution and a refusal - and a writer Java may skip is how the identity-change
      // warning stopped being raised. Safe to call when nothing decrypted: decrypt sets the flag to
      // true at entry and only a write can make it false, so a decrypt that failed before writing
      // reports nothing.
      final boolean rotationWasNotSaved = reportIfTheRotationWasNotSaved();
      if (delivered && rotationWasNotSaved) return;
      if (delivered) {
        setInfoUnlessWarned("Detected contact: " + labelFor(chosenContact));
      } else {
        // Nothing decrypted, so give the recipient back.
        //
        // The contact has to be adopted BEFORE the decrypt - setChosenContact clears the staged
        // message when the recipient changes, so adopting afterwards wipes the plaintext that was
        // just put on screen. Undoing it here is what makes the adoption conditional in effect.
        //
        // Suppressing the "Detected contact" line alone was not enough and is worth recording:
        // decryptMessageInClipboard calls showChosenContactInMainInfoField four statements after
        // this returns, which paints "Chosen contact: Bob" on the same banner from the same
        // unsigned header. Only the verb changed. It also left Encrypt aimed at a recipient an
        // envelope that decrypted to nothing had chosen.
        forgetChosenRecipient();
      }
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
      // A bundle arriving where the user reported a mismatch is not a first sighting, whatever the
      // empty store suggests - and this is the arm it arrives on. Rejecting KEEPS the contact row,
      // so the very next paste finds a sender here rather than opening the add-contact screen,
      // which is the only other place this warning is written. The record exists precisely so the
      // re-delivered forged bundle is a warned pin instead of a silent one; without this it was
      // consulted by nothing on the route an attacker actually uses.
      // After the attempt, not before: a bundle whose signature fails is refused and pins nothing.
      final boolean usable =
          decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, true);
      warnIfKeyWasRejected(sender);
      if (reportIfTheRotationWasNotSaved()) return;
      // Only if there is actually a session. Otherwise the refusal notice written above stands,
      // instead of being painted over by a line saying the contact was detected.
      if (usable) setInfoUnlessWarned("Detected contact: " + labelFor(chosenContact));
    }
  }

  /**
   * Posts the post-rejection warning when a key is about to be pinned at an address the user told
   * the app was wrong. Returns whether it fired.
   *
   * <p>Called from all three arms. The plain signal-message arm asks it only for an envelope
   * declaring {@code PREKEY_TYPE}, because that is the one that pins - see the comment at that call
   * site. An earlier version of this sentence said that arm carried no bundle "so nothing is pinned
   * there", which is the premise {@code SignalProtocolMain.decrypt} contradicts in its own comment,
   * and it was the premise an attacker only had to omit a field to use.
   */
  private boolean warnIfKeyWasRejected(final Contact sender) {
    if (sender == null) return false;
    if (!mE2EEStrip.wasKeyRejected(sender.getSignalProtocolAddress())) return false;
    // A key must actually be there. INFO_PINNED_AFTER_REJECT states as fact that "this IS a new
    // key for that address", and there are two ways for that to be false: nothing arrived at all
    // (the selection path), or something arrived and was REFUSED.
    //
    // The refusal case is the one a previous round missed. buildSession catches
    // InvalidKeyException when the signed pre-key's signature does not verify, logs, and returns
    // false without saving an identity - and decrypt discards that return value. So one flipped
    // byte in a relayed invite produces a warning claiming a new key arrived, at an address that
    // holds none, and the verify screen it sends the user to has no fingerprint. An earlier comment
    // here said the pin "can land after the check", which is true only when the bundle is good;
    // every caller now runs after the attempt, so this can simply ask.
    if (!mE2EEStrip.hasPinnedKey(sender.getSignalProtocolAddress())) return false;
    final String warning = String.format(INFO_PINNED_AFTER_REJECT, labelFor(sender));
    Toast.makeText(getContext(), warning, Toast.LENGTH_LONG).show();
    setWarningMessage(warning, String.valueOf(sender.getSignalProtocolAddress()));
    return true;
  }

  /**
   * Reports a key rotation whose write did not land, and whether it did.
   *
   * <p>Shared, because the reader existed on one of the three arms an envelope can take and the
   * messenger chooses the arm. {@code getMessageType} dispatches on field presence alone, so
   * appending any bytes as a ciphertext to a bundle moves it from the bundle-only arm to the
   * combined one - one field, and the notice disappeared. The combined arm is also the ORDINARY
   * shape for a signed-pre-key rotation, so the arm with no reader was the common one.
   *
   * <p>What is at stake: the new key and the session built from it exist in memory only. The user is
   * told the contact was detected; the next reload restores the old session while the peer has moved
   * on, every message after that fails to decrypt, and this app's standard advice for a failed
   * decrypt is delete-and-re-invite - the key-substitution window this file exists to keep shut.
   */
  private boolean reportIfTheRotationWasNotSaved() {
    if (mE2EEStrip.lastSessionWriteReachedDisk()) return false;
    rememberContactIsNotOnDisk(chosenContact);
    Toast.makeText(getContext(),
        String.format(INFO_SESSION_NOT_SAVED, labelFor(chosenContact)),
        Toast.LENGTH_LONG).show();
    setCautionBesideAnyWarning(
        String.format(INFO_SESSION_NOT_SAVED, labelFor(chosenContact)), chosenContact, true);
    return true;
  }

  private void processUpdatedPreKeyResponse(MessageEnvelope messageEnvelope, Contact sender) {
    // debug only Toast.makeText(getContext(), "Updated signed pre key detected!", Toast.LENGTH_SHORT).show();
    if (sender == null) {
      // contact was not added before -> proceed as normal preKeyMessage
      processPreKeyResponse(messageEnvelope, sender);
    } else {
      // update contact with preKey information
      setChosenContact(sender);
      // Same reason as the sibling arm above: this envelope carries a bundle too, and the warning
      // runs after the attempt so a refused bundle cannot be reported as a new key.
      // The rotation path, and the one that is NOT exotic: an honest peer attaches a full bundle
      // to an ordinary message whenever its signed pre-key rotates. Strip the one-time key from
      // that and the bundle is refused, the ciphertext still decrypts under the existing session,
      // and this line used to assert an update that never happened - unconditionally, because
      // isSessionCreation is false here so nothing else on this arm ever asked.
      decryptMessageAndShowMessageInMainInputField(messageEnvelope, chosenContact, false);
      warnIfKeyWasRejected(sender);
      // The same reader the sibling arm has. One appended field moves an envelope here, and this
      // arm is the ordinary shape for a rotation, so it was the common one with no reader at all.
      if (reportIfTheRotationWasNotSaved()) return;
      // Asked of the BUNDLE, not of the message.
      //
      // This used to read the method's return value, which stopped meaning "the bundle was
      // accepted" when it gained a second reason to be false: a message that did not decrypt. So a
      // genuine signed-pre-key rotation whose accompanying message was replayed or arrived out of
      // order - ordinary things - landed a new key, cleared any refusal warning, and said nothing
      // at all, leaving only a transient decryption-failure toast. The variable was named for a
      // condition it no longer computed.
      if (!mE2EEStrip.lastAttachedBundleWasRefused()) {
        setInfoUnlessWarned("Detected contact with updated keybundle: " + labelFor(chosenContact));
      }
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
    // The store notice counts here for the same reason the caution does: this method runs from
    // deleting ANY contact, which is precisely the moment the notice is raised.
    if (mStandingCaution != null || mStandingStoreNotice != null) {
      // The other unconditional banner writer, and the one the caution work did not reach. It runs
      // from deleting ANY contact - so deleting Alice painted "No contact chosen" over a caution
      // about Carol, which is precisely the cross-contact erase clearCautionIfAbout was scoped to
      // prevent, arriving one line later. It also runs when the user taps the banner, a natural
      // response to a notice they have just read.
      //
      // The flag stays up through all of it, so the invariant sweep could not see it either; that
      // is why the sweep now asserts the WORDS survive, not only that something is standing.
      setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
    } else if (!mWarningStanding) {
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
   * <p>So this is cleared by deliberate responses to the warning and nothing else: comparing a
   * safety number, or saying that it does not match. Opening the contact list does not clear it,
   * and neither does choosing a contact from it - an earlier version of this sentence allowed that
   * second case, and it was the hole. Tapping a row is not a response to a warning; for the
   * duplicate-name warning it is the ambiguous act the warning exists to flag, and for a warning
   * about another contact, or about storage, it is unrelated to it. Nothing the messenger can cause
   * clears it.
   */
  private boolean mWarningStanding = false;

  /**
   * The standing warning's own text, kept so the chosen recipient can be shown beside it.
   *
   * <p>The banner is the only place the main view names who a message would go to. Once a warning
   * held it permanently, choosing a contact stopped updating it - so tapping Alice while a warning
   * about Bob stood left the banner reading "Bob" with Alice as the recipient, and Encrypt would
   * send to Alice. This file rules that out in as many words elsewhere: "encryption would still go
   * to a contact the screen no longer names, which is an invisible recipient ... a mis-send". The
   * fix is to show both rather than to choose between them.
   */
  private String mStandingWarningText = null;

  /**
   * The address the standing warning is about, or null when it is not about one contact.
   *
   * <p>Only used to let deleting that contact put the warning down. Removing a contact IS a
   * deliberate response to a warning about them - arguably the strongest one available - and
   * without this the warning outlives the row it names, unreachable: its verify screen is gone, so
   * the only way to clear it is to press Verify or Reject on some unrelated contact, which asserts
   * a comparison the user never made or destroys a key they never doubted.
   *
   * <p>Deliberately NOT used to let <em>selecting</em> a contact clear it. That was the hole this
   * whole area was fixed for: tapping a row is not a response to a warning, and for the
   * duplicate-name warning it is the ambiguous act the warning exists to flag.
   */
  private String mStandingWarningAddress = null;

  /**
   * Whether the standing warning is the refused-invite one.
   *
   * <p>Used for exactly one thing: retracting that warning when a later invite from the same
   * contact is accepted, which is the remedy its own text asks the user to perform. It does NOT
   * make the warning weaker — an earlier version used it to let ordinary notices through, and that
   * handed the messenger an erase it could trigger by focusing a password field.
   *
   * <p>Carried across a rebuild so the retraction still works afterwards; without it the warning
   * would become permanent at the first theme change, and the messenger's host app chooses when
   * those happen.
   */
  private boolean mStandingWarningIsInviteRefusal = false;

  /**
   * A caution shown alongside a standing warning, kept so a repaint does not drop it.
   *
   * <p>It used to be written straight into the {@code TextView} and stored nowhere. Every repaint
   * rebuilds the banner from {@code mStandingWarningText} alone, so the caution was erased by
   * hiding the keyboard, by tapping the contact row - the very gesture its own text invites - and
   * by any rotation. The warning survived all three, which is why the invariant sweep could not
   * see it: that sweep watches the flag, and this is an erase of text with the flag left standing.
   */
  private String mStandingCaution = null;

  /**
   * The contact that caution is about, so a response about someone else cannot take it down.
   *
   * <p>The first version scoped the caution to the standing WARNING and cleared it in
   * {@code setWarningMessage}, reasoning that a caution belongs to the warning it was shown beside.
   * That was wrong on a fact: {@code setWarningMessage} does not distinguish replacing a warning
   * from re-posting the identical one, and {@code warnIfIdentityChanged} re-posts on EVERY decrypt.
   * So one more relayed message from the contact the warning was about destroyed a caution about a
   * different contact - the attacker's - with the warning still standing the whole time. That is an
   * erase of text with the flag up, which is precisely what the invariant sweep cannot see.
   */
  private String mStandingCautionAddress = null;

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
  /**
   * The warning, with the chosen recipient named under it when there is one.
   *
   * <p>Composed rather than stored, so it follows the recipient. {@code setChosenContact} repaints
   * through this whenever a warning is standing.
   */
  private String warningWithRecipient() {
    if (mStandingWarningText == null && mStandingCaution == null && mStandingStoreNotice == null) {
      return null;
    }
    final StringBuilder body = new StringBuilder();
    if (mStandingWarningText != null) body.append(mStandingWarningText);
    if (mStandingCaution != null) {
      if (body.length() > 0) body.append("\n\n");
      body.append(mStandingCaution);
    }
    // Last, and in a slot of its own. It is about the store rather than about a contact, so it has
    // no address - and everything else here is addressed. Sharing the caution slot meant the next
    // ordinary contact add overwrote it, and clearCautionIfAbout treats a null address as "about
    // anyone", so verifying or deleting anybody destroyed it too. It reports a condition that is
    // permanent until the log can be rewritten and is never re-asserted, so losing it once loses it
    // for good.
    if (mStandingStoreNotice != null) {
      if (body.length() > 0) body.append("\n\n");
      body.append(mStandingStoreNotice);
    }
    if (chosenContact == null) return body.toString();
    return body + "\n\nSending to: " + labelFor(chosenContact);
  }

  /**
   * Posts a warning to the info banner and marks it as standing.
   *
   * <p>Every security warning goes through here rather than {@code setInfoTextViewMessage}, so that
   * "is a warning on screen" is a property of how it was written rather than a string comparison
   * against a set of format strings someone has to remember to extend. This overload is for
   * warnings that are not about one contact; anything about a contact should carry its address, or
   * deleting that contact cannot put it down.
   */
  private void setWarningMessage(final String message) {
    setWarningMessage(message, null);
  }

  /** @param aboutAddress the contact this warning concerns, or null when it concerns no one. */
  private void setWarningMessage(final String message, final String aboutAddress) {
    mStandingWarningText = message;
    mStandingWarningAddress = aboutAddress;
    // Any new warning is, by default, not the invite refusal - so a later accepted invite cannot
    // retract something else that happens to be standing.
    //
    // The caution is NOT cleared here. It is about a contact of its own and outlives whatever
    // warning happens to share the banner with it; clearing it here meant one more relayed message
    // from an unrelated contact erased it, because warnIfIdentityChanged re-posts its warning on
    // every decrypt.
    mStandingWarningIsInviteRefusal = false;
    mWarningStanding = true;
    setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
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
      clearComposeFieldAndCaches();
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
    // The safety-number digits are decrypted content too, and their animators are still running.
    //
    // Leaving the verify screen hides the digits; it does not blank them and does not stop the
    // count-up, which goes on painting the previous contact's number into views for another second
    // and - because a running ValueAnimator is held by the process-wide AnimationHandler through a
    // listener capturing a digit view - keeps this whole strip alive while it does. Found by
    // asserting the cancel rather than the repaint: the repaint cannot be reproduced under
    // Robolectric, the cancel can, and only surrenderState was doing it.
    clearFingerprintViews();

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
    // The user is done with this keyboard session, so the next one types into the host until they
    // choose the compose box again. This and the send path are the only two deliberate lowerings;
    // see the focus listener for why focus loss is not one.
    if (mRichInputConnection != null) mRichInputConnection.setShouldUseOtherIC(false);
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

  /** Drives the plain message path, for tests. */
  void processSignalMessageForTest(final MessageEnvelope envelope, final Contact sender) {
    processSignalMessage(envelope, sender);
  }

  /** Drives the combined bundle-and-message path, for tests. */
  void processUpdatedPreKeyResponseForTest(final MessageEnvelope envelope, final Contact sender) {
    processUpdatedPreKeyResponse(envelope, sender);
  }

  /** Drives the known-contact bundle path, for tests. */
  void processPreKeyResponseForTest(final MessageEnvelope envelope, final Contact sender) {
    processPreKeyResponse(envelope, sender);
  }

  /** How many contacts are recorded as not on disk, for tests. */
  int refusalCountForTest() {
    expireRefusalsSettledByAlaterWrite();
    return mContactsNotOnDisk.size();
  }

  /** Whether sending is refused for the chosen contact, for tests. */
  boolean sendingIsRefusedForTest() {
    // Expire first, exactly as the send path does. Asking the bare predicate would be asking a
    // question the app never asks, and would pass or fail on a stale answer.
    expireRefusalsSettledByAlaterWrite();
    return sendingIsRefusedForTheChosenContact();
  }

  /** Posts the store notice, for tests that need the third standing item up. */
  void setStoreNoticeForTest(final String notice) {
    mStandingStoreNotice = notice;
    mLogWritesLandedWhenNoticeRaised = mE2EEStrip.messageLogWritesLanded();
    setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
  }

  /** Posts a caution, for tests that need one standing without a warning beside it. */
  void setCautionForTest(final String caution, final Contact about) {
    setCautionBesideAnyWarning(caution, about);
  }

  /** Posts a warning, for tests that drive the strip. */
  void setWarningMessageForTest(final String message) {
    setWarningMessage(message);
  }

  /**
   * Drives the plain signal-message arm, for tests.
   *
   * <p>Calls the production method rather than re-implementing it: the ordering inside that arm -
   * the pin attempt before the post-rejection warning - is the property under test, and a seam that
   * re-created it would pin only its own copy. That mistake has already been made once in this file.
   */
  void processIncomingEnvelopeForTest(final MessageEnvelope envelope) {
    processSignalMessage(envelope, contactFor(envelope));
  }

  /** The contact an envelope claims to come from, or null. */
  /**
   * The contact an envelope claims to come from, asked of production rather than re-derived.
   *
   * <p>This used to compare the contact's device id - already folded into libsignal's range by
   * {@code Contact} - against the envelope's raw, attacker-chosen one. Production folds both through
   * {@code ProtocolAddresses.of} and compares full addresses. A legacy id (the committed 0.1.5
   * fixture carries 7296) therefore resolved to null here and to the real contact in the app: the
   * seam and the code it stands in for would take opposite arms, and a test written through it would
   * be pinning the wrong one.
   *
   * <p>The javadoc two methods up already says this: a seam that re-creates the thing under test
   * pins only its own copy, and "that mistake has already been made once in this file". This was the
   * second time.
   */
  private Contact contactFor(final MessageEnvelope envelope) {
    final Object found = SignalProtocolMain.extractContactFromMessageEnvelope(envelope);
    return found instanceof Contact ? (Contact) found : null;
  }

  /** Posts a warning about a particular contact, for tests that drive the strip. */
  void setWarningMessageAboutForTest(final String message, final Contact about) {
    setWarningMessage(message,
        about == null ? null : String.valueOf(about.getSignalProtocolAddress()));
  }

  /** Leaves Decrypt down, as a completed decryption does. */
  void setDecryptButtonDisabledForTest() {
    changeImageButtonState(mDecryptButton, ButtonState.DISABLED);
  }

  /**
   * Simulates a clipboard event carrying something KryptEY-shaped, for tests.
   *
   * <p>Mirrors the listener's ORDER, which is the part that matters: the buttons are re-armed
   * before the banner guard, so a standing warning stops the banner being overwritten without also
   * stopping Decrypt from coming back. Re-arming after the guard is what left the keyboard unable
   * to decrypt anything once a warning stood.
   */
  void onClipboardHoldsDecryptableItemForTest() {
    onKryptEyItemOnClipboard(MessageType.PRE_KEY_RESPONSE_MESSAGE);
  }

  void onClipboardChangedForTest() {
    onClipboardHoldsDecryptableItemForTest();
  }

  /**
   * Whether a passive, messenger-driven event may write over the info banner.
   *
   * <p>Extracted so the clipboard listener and its test run the SAME code. The listener is a lambda
   * on a system service that a test cannot invoke, and a test that re-implements its body proves
   * only that the copy behaves - which is the failure this codebase keeps finding elsewhere.
   */
  /**
   * Whether a security WARNING is standing, as a plain state read.
   *
   * <p>Not a copy of {@code mayOverwriteInfoBanner} with a term dropped. The first version of this
   * seam was exactly that, and it had already drifted when it was written: the real predicate also
   * refuses over a password field, so a precondition asserted through the copy read "nothing is
   * holding the banner" in a state where the app refuses to write. It also sat between
   * {@code mayOverwriteInfoBanner} and its javadoc — the javadoc that says re-implementing a body
   * in a test proves only that the copy behaves.
   */
  boolean warningIsStandingForTest() {
    return mWarningStanding;
  }

  boolean mayOverwriteInfoBanner() {
    if (storageIsUnreadable() || aStandingItemHoldsTheBanner()) {
      // Through the shared predicate, so a fourth kind of standing item cannot be forgotten here.
      //
      // The caution counts: it is the notice shown when a key was pinned and the app noticed
      // nothing about it, which is what a successful substitution looks like - so letting an
      // ordinary clipboard event paint over it is the erase, whether or not a warning is up too.
      // The store notice counts for the same reason and had to be added to four separate checks
      // written as "warning or caution", which is why they now ask one question.
      Log.i(TAG, "A security warning is on screen; leaving it in place");
      return false;
    }
    // Nor over the password-field notice, which is a live refusal rather than a stale line.
    //
    // The two conditions above are the two the strip already refused to paint over. This is the
    // third and it was missed, in the direction opposite to the one the button gate closed: there,
    // the notice turned the buttons ON by announcing they were off; here, a clipboard event takes
    // the banner from "Encryption and decryption are turned off here" to "Keybundle detected: click
    // on decrypt to save the content" while decrypt is still refused and the button correctly dark.
    //
    // Both halves belong to the messenger and neither is exotic: it declares the inputType of every
    // field it presents, and as the foreground app it owns the clipboard. Copy-then-paste is this
    // app's own workflow, so the copy is the next gesture rather than an unusual one.
    //
    // Nothing is wedged by this: the notice is taken down by setHostFieldIsPassword(false) when the
    // user moves to an ordinary field, which repaints from the model, and this goes back to true
    // with it.
    if (mHostFieldIsPassword) {
      Log.i(TAG, "The password-field notice is on screen; leaving it in place");
      return false;
    }
    return true;
  }

  /** Clears a standing warning. Only call this from a deliberate user action. */
  /**
   * Clears a standing warning only when this contact is who it was about.
   *
   * <p>A warning with no address still clears: {@code INFO_STORAGE_UNREADABLE} and
   * {@code INFO_SAME_ADDRESS_DIFFERENT_NAME} are posted without one, and a deliberate response is
   * their only exit.
   */
  /**
   * Shows a caution that must not be dropped, without taking down a warning that must not be lost.
   *
   * <p>The banner is the only surface either has, so when both exist they share it. The standing
   * warning's flag, text and address are untouched — only what is painted changes — so every
   * deliberate response that clears it still works, and {@code warningWithRecipient} still rebuilds
   * from the warning alone rather than from what is on screen.
   */
  private void setCautionBesideAnyWarning(final String caution, final Contact about) {
    setCautionBesideAnyWarning(caution, about, false);
  }

  /**
   * @param isAstorageNotice whether this caution reports a storage failure rather than a key event.
   *                         Passed in rather than inferred from the text, because inferring it from
   *                         the text is what broke: the phrase searched for was in one storage
   *                         notice and not in the one added later.
   */
  private void setCautionBesideAnyWarning(final String caution, final Contact about,
      final boolean isAstorageNotice) {
    mStandingCautionIsAstorageNotice = isAstorageNotice;

    // Stored in BOTH cases, then painted through the same builder every repaint uses.
    //
    // The no-warning branch used to write straight to the view and store nothing, and that is the
    // COMMON case - the one the call site argues matters most, because it fires when the app
    // noticed nothing, which is what a successful substitution looks like. Every repaint therefore
    // erased it: one clipboard post, hiding the keyboard, tapping the contact row, or a rotation.
    mStandingCaution = caution;
    mStandingCautionAddress = about == null ? null
        : String.valueOf(about.getSignalProtocolAddress());
    setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
  }

  /**
   * Takes down a caution once the user has dealt with the contact it is about.
   *
   * <p>Scoped to that contact, the same way the warning's clear is: verifying, rejecting or
   * deleting somebody else says nothing about whether this key was ever compared.
   */
  /**
   * Whether something is standing on the banner that a transient message must not paint over.
   *
   * <p>One predicate, because the copies drifted. The rule "do not overwrite a standing item" was
   * written out at every banner writer as {@code if (mWarningStanding)}, and when the caution became
   * a second standing item three consecutive review rounds each found another writer that had not
   * been told - the reset path, the recipient repaint, the session-failure line. Each fix taught one
   * more copy. This is the thing they should all have been asking.
   */
  /**
   * Takes the store notice down once a later message-log write has landed.
   *
   * <p>The orphaned entries were already removed from the in-memory log by the deletion; only the
   * write failed. So the next message-log write that does land persists the pruned log, and the
   * plaintext the notice is about is gone. That is a real resolution rather than a timeout, and it
   * is the only one - the condition cannot be resolved by anything the user does to a contact,
   * which is why sharing the contact-addressed caution slot was wrong in the first place.
   */
  private void clearAstoreNoticeThatHasBeenResolved() {
    if (mStandingStoreNotice == null) return;
    if (mE2EEStrip.messageLogWritesLanded() > mLogWritesLandedWhenNoticeRaised) {
      mStandingStoreNotice = null;
      mLogWritesLandedWhenNoticeRaised = -1;
      setInfoTextViewMessage(mInfoTextView,
          aStandingItemHoldsTheBanner() ? warningWithRecipient() : INFO_NO_CONTACT_CHOSEN);
    }
  }

  private boolean aStandingItemHoldsTheBanner() {
    // The store notice counts: it holds the banner exactly as the other two do, and leaving it out
    // meant every routine informational line painted straight over it.
    return mWarningStanding || mStandingCaution != null || mStandingStoreNotice != null;
  }

  private void clearCautionIfAbout(final Contact contact) {
    if (mStandingCaution == null) return;
    if (mStandingCautionAddress == null || (contact != null && mStandingCautionAddress
        .equals(String.valueOf(contact.getSignalProtocolAddress())))) {
      mStandingCaution = null;
      mStandingCautionAddress = null;
    }
  }

  /** Posts the refused-invite warning, tagged so a later good invite can retract it. */
  private void setInviteRefusalWarning(final String message, final String aboutAddress) {
    setWarningMessage(message, aboutAddress);
    mStandingWarningIsInviteRefusal = true;
  }

  /**
   * Retracts a standing refused-invite warning about this contact.
   *
   * <p>Scoped twice over: only the refusal warning, and only for the contact it names. An accepted
   * invite says nothing about an identity change or a rejection recorded for the same address, so
   * those must survive it.
   */
  private void clearInviteRefusalIfAbout(final Contact contact) {
    if (!mWarningStanding || !mStandingWarningIsInviteRefusal) return;
    clearStandingWarningIfAbout(contact);
  }

  private void clearStandingWarningIfAbout(final Contact contact) {
    if (!mWarningStanding) return;
    if (mStandingWarningAddress == null || (contact != null && mStandingWarningAddress
        .equals(String.valueOf(contact.getSignalProtocolAddress())))) {
      clearStandingWarning();
    }
  }

  private void clearStandingWarning() {
    mStandingWarningIsInviteRefusal = false;
    mStandingWarningText = null;
    mStandingWarningAddress = null;
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
    // Addressed. This warning's own text ends "Open %s in your contact list and compare the number
    // with them by voice" - so if the user resolves it by deleting that row instead, the
    // instruction points at something gone and the warning had no way down at all.
    setWarningMessage(warning, String.valueOf(sender.getSignalProtocolAddress()));
    return true;
  }

  /**
   * @return whether the strip may now claim anything about this contact: false when an attached
   *     bundle was refused, and false when a message was expected and none came out. The second
   *     half was missing, in the code as well as in this sentence - it said "false only on the
   *     session-creation arm, when no session exists", and the method never asked whether a session
   *     existed. It returned false exactly when a bundle was refused, so "usable" at the call sites
   *     meant "the bundle was not refused".
   */
  private boolean decryptMessageAndShowMessageInMainInputField(final MessageEnvelope messageEnvelope, final Contact sender, boolean isSessionCreation) {
    // BEFORE the decrypt, because the decrypt itself can create one. decryptMessage's PREKEY_TYPE
    // arm pins by trust-on-first-use whenever the address holds no key, and a refused attached
    // bundle does not stop it - the PreKeySignalMessage carries its own identity key. Asking
    // afterwards answers "is there a session now", which is true because of THIS paste, and the
    // strip then reassured the user that nothing had changed at the exact moment a
    // messenger-supplied key was pinned with nothing else noticing.
    final boolean sessionExistedBefore = sender != null
        && mE2EEStrip.hasSessionWith(sender.getSignalProtocolAddress());
    // The KEY, separately from the session. They come apart on a path the app's own advice
    // produces: deleteContact removes the session and deliberately keeps the pinned identity, so
    // after a delete-and-re-invite the peer's next message creates a session against a key that
    // was already there. Asking only about the session, the strip then told the user a key had
    // just been set up "and this app cannot tell whose it is" - when the message decrypted
    // precisely BECAUSE it matched the pin the app already trusted, which the same app treats
    // elsewhere as proof of identity.
    final boolean keyPinnedBefore = sender != null
        && mE2EEStrip.hasPinnedKey(sender.getSignalProtocolAddress());

    final CharSequence decryptedMessage = mE2EEStrip.decryptMessage(messageEnvelope, sender);

    // The fact, not an inference from it. Two inferences have already been wrong here: "no message
    // came out" is what a refused bundle AND a good bundle-only re-invite both look like, and "is
    // there a session" is true for every contact the user already talks to - which is precisely the
    // case the attack aims at, since a relay stripping a re-invite from a SUBSTITUTED contact was
    // reported as success while the identity-change warning never fired.
    final boolean bundleRefused = messageEnvelope.getPreKeyResponse() != null
        && mE2EEStrip.lastAttachedBundleWasRefused();

    // Check before branching: a substitution recorded during this decrypt attempt must be reported
    // even on the paths that otherwise look like success (a bundle-only re-invite advances the UI
    // exactly as a good one does), and must displace the generic advice on the failure path.
    final boolean identityChanged = warnIfIdentityChanged(sender);

    // The caution for a key pinned by THIS paste, on the path where every arm's pin happens.
    //
    // Both callers of cautionThatAkeyWasPinned were inside addContact, so it announced pins made
    // while adding a contact and nothing else. But a contact ROW can exist with no pinned key -
    // a refused bundle leaves the row and pins nothing, which this file already records - and from
    // then on the address is a known contact, so a later PreKeySignalMessage from it routes to the
    // message arms instead. isTrustedIdentity returns true whenever nothing is pinned, and decrypt
    // takes its PREKEY arm on the ciphertext type alone, so the key lands by trust-on-first-use
    // with no bundle in sight and nothing said.
    //
    // Two steps for the attacker, neither of which needs a forged signature: strip the one-time
    // pre-key from an invite so the bundle is refused and the row is left keyless, then send a
    // bundle-less PreKey message from that same address. The user sees "Detected contact: Alice"
    // and a decrypted message. Omitting one optional field is what moves the envelope from an arm
    // that cautions to one that said nothing.
    //
    // Placed here rather than on each arm because this is the one place all of them pass through,
    // and because keyPinnedBefore is already computed above for the refused-bundle wording - the
    // transition was being measured and used for one sentence only.
    if (!keyPinnedBefore && sender != null
        && mE2EEStrip.hasPinnedKey(sender.getSignalProtocolAddress())) {
      cautionThatAkeyWasPinned(false);
    }

    if (bundleRefused) {
      // A warning rather than a plain line: the banner is repainted straight after this by
      // showChosenContactInMainInfoField, which is guarded only by a standing warning, so an
      // ordinary message would be overwritten with "Chosen contact: Bob" and the refusal would be
      // as silent as before. Soft, because it must not be able to suppress a message that says
      // more than it does - see setInfoUnlessWarned.
      if (!identityChanged && !mWarningStanding) {
        // Three states, and each needs its own sentence. Two of them were collapsed into one and
        // the collapse pointed the wrong way: the reassuring wording landed on the first-pin case.
        // A key pinned by this paste is the only state INFO_INVITE_REFUSED_BUT_KEY_PINNED
        // describes; a session built against a key that was already trusted is not.
        final boolean keyPinnedByThisPaste = !keyPinnedBefore
            && mE2EEStrip.hasPinnedKey(sender.getSignalProtocolAddress());
        final boolean somethingSurvived = sessionExistedBefore || keyPinnedBefore;
        final String outcome = keyPinnedByThisPaste ? INFO_INVITE_REFUSED_BUT_KEY_PINNED
            : (somethingSurvived ? INFO_INVITE_REFUSED_SESSION_KEPT : INFO_INVITE_REFUSED);
        setInviteRefusalWarning(String.format(outcome, labelFor(sender)),
            String.valueOf(sender.getSignalProtocolAddress()));
      }
      // The message itself, if one came with the refused bundle, is still the user's to read: the
      // ciphertext is authenticated by the existing session and has nothing to do with the bundle
      // that was stapled to it.
      if (!isSessionCreation && decryptedMessage != null) {
        mInputEditText.setText(decryptedMessage);
        changeVisibilityInputFieldButtons(true);
        // The same question the ordinary branch asks. Without it a relay suppresses this notice at
        // will: staple a bundle to every relayed message and strip its one-time pre-key - one
        // unsigned byte - and every message takes this branch, so a delivered message whose log
        // write failed is filed nowhere and reported nowhere. The banner the user does get is
        // reassuring about the key and silent about the lost record.
        if (mE2EEStrip.lastChatLogWriteFailed()) {
          Toast.makeText(getContext(), INFO_MESSAGE_NOT_SAVED, Toast.LENGTH_LONG).show();
        }
      }
      mE2EEStrip.clearClipboard();
      return false;
    }

    // A bundle that WAS accepted retracts a refusal standing about the same address. Without this,
    // following the app's own advice - "ask them to send another" - leaves the warning in place
    // over a contact that now works, and a user acting on that text may reject a good key.
    if (messageEnvelope.getPreKeyResponse() != null) {
      clearInviteRefusalIfAbout(sender);
    }

    if (!isSessionCreation && decryptedMessage != null) {
      mInputEditText.setText(decryptedMessage);
      changeVisibilityInputFieldButtons(true);
      // The message arrived; only filing it failed. Said out loud, and said as itself.
      if (mE2EEStrip.lastChatLogWriteFailed()) {
        Toast.makeText(getContext(), INFO_MESSAGE_NOT_SAVED, Toast.LENGTH_LONG).show();
      }
    } else if (isSessionCreation) {
      changeVisibilityInputFieldButtons(true);
    } else if (!identityChanged) {
      Toast.makeText(getContext(), INFO_MESSAGE_DECRYPTION_FAILED, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Error: Decrypted message is null");
    }
    mE2EEStrip.clearClipboard();
    // Nothing came out, so the caller may not say a contact was detected. A successful decrypt is
    // the only thing on this route that ties the message to the key pinned at that address; the
    // address itself is an unsigned header the relay copies out of any envelope that contact ever
    // sent. Without this, an envelope with a genuine address and arbitrary ciphertext bytes left
    // the persistent banner naming the contact, with only a transient toast saying the decrypt
    // failed - and no forgery is needed to build one, because the adversary ENCODES rather than
    // edits, so the canonicality check is satisfied by construction.
    return isSessionCreation || decryptedMessage != null;
  }

  /** The real send path, for tests. */
  void sendEncryptedMessageToApplicationForTest(final CharSequence encryptedMessage) {
    sendEncryptedMessageToApplication(encryptedMessage);
  }

  private void sendEncryptedMessageToApplication(CharSequence encryptedMessage) {
    if (encryptedMessage == null) return;

    mRichInputConnection.setShouldUseOtherIC(false);
    // The keyboard's own copy of the draft goes with the redirect.
    //
    // While typing is redirected those caches fill with the plaintext - that is what they are for.
    // Lowering the flag stops new text going there but leaves what is already in them, and the
    // keyboard stays up in the messenger's app after a send, in buffers that live as long as the
    // service. Measured before this line existed: the cache held "the meeting is at nine" followed
    // by the ciphertext that replaced it on screen.
    //
    // Same class as the buffers cleared when the keyboard is dismissed, at the moment nobody had
    // looked at. Dismissal is the obvious end of a message's life; pressing send is the common one.
    mRichInputConnection.forgetCachedText();
    mListener.onTextInput((String) encryptedMessage);
    mInputEditText.clearFocus();
    clearUserInputString();
    mE2EEStrip.clearClipboard();
  }

  /**
   * Empties the compose box AND the keyboard's own copy of what was in it.
   *
   * <p>One helper because the same defect has now been found three times on three different
   * buttons. While typing is redirected into the strip, the IME's caches fill with the draft - that
   * is what they are for - and every path that clears the visible box had to remember to clear them
   * too. Dismissal remembered; send did not, then clear did not, and a recipient change and the
   * password-field guard did not either. A rule that has to be remembered at five call sites is not
   * a rule, so the two operations are one operation.
   */
  private void clearComposeFieldAndCaches() {
    if (mInputEditText != null) mInputEditText.setText("");
    if (mRichInputConnection != null) mRichInputConnection.forgetCachedText();
  }

  /**
   * State that must outlive this view, because the view does not outlive a configuration change.
   *
   * <p>The strip is rebuilt whenever the system hands the IME a new input view - a theme change, a
   * night-mode switch at sunset, a rotation. Everything on it goes with it, and two of those things
   * are not the view's to lose. A standing security warning is one: its own javadoc says nothing
   * the messenger can cause clears it, and a rebuild reached every defect in that family without
   * touching any of the paths those fixes guard. A half-typed message is the other: the redirect
   * still pointed at the DISCARDED compose box, so the user's next keystrokes landed in an object
   * no clearing path could reach and survived the keyboard being dismissed.
   *
   * <p>Carried rather than cleared. Clearing was tried first and is worse: lowering the redirect
   * sends the next keystroke into the messenger's own field in plaintext, which is the disclosure
   * the redirect exists to prevent - the rebuild would have swapped a residue defect for a
   * disclosure one.
   */
  public static final class CarriedState {
    private final CharSequence draft;
    private final boolean wasComposing;
    private final CharSequence banner;
    private final boolean warningStanding;
    /**
     * The warning's own text, without the recipient line the banner may carry.
     *
     * <p>Carried separately from {@code banner} because the two are not the same string: the banner
     * is the warning plus "Sending to: X". Rebuilding from the banner would append the recipient a
     * second time on the next selection, and rebuilding from nothing would leave a strip that
     * believes a warning stands with no text to paint - which sets the banner to null.
     */
    private final String standingWarningText;
    /** The address that warning is about, so deleting that contact still clears it after a rebuild. */
    private final String standingWarningAddress;
    /**
     * Whether that warning yields to a message with more to say.
     *
     * <p>Carried, because dropping it makes the refusal UNRETRACTABLE: the later good invite that
     * should take it down would no longer recognise it, and the warning would outlive the problem
     * it describes. A configuration change is something an app can force.
     */
    private final boolean standingWarningIsInviteRefusal;
    /** The caution shown beside that warning, so a rebuild does not drop it. */
    private final String standingCaution;
    /** The contact that caution is about, so a rebuild does not widen or narrow its scope. */
    private final String standingCautionAddress;
    /**
     * The contacts whose rows are known not to have reached disk, and when that was seen.
     *
     * <p>Carried because it is the only thing standing between the user and sending to a contact
     * the app has told them not to send to. A rebuild that dropped it would put "do not send them
     * anything" back on screen with Encrypt live.
     */
    private final Map<String, Long> contactsNotOnDisk;
    /**
     * The store notice and the log-write count when it went up.
     *
     * <p>Carried because the condition it reports survives a rebuild and nothing re-asserts it: the
     * user's one chance to learn that a deletion left their plaintext behind would be spent on
     * whichever rotation happened first.
     */
    /** Whether the carried caution is a storage notice; travels with it, for the same reason. */
    private final boolean standingCautionIsAstorageNotice;
    private final String standingStoreNotice;
    private final long logWritesLandedWhenNoticeRaised;
    private final boolean hostFieldIsPassword;
    private final Encoder encoding;

    private CarriedState(final CharSequence draft, final boolean wasComposing,
        final CharSequence banner, final boolean warningStanding,
        final String standingWarningText, final String standingWarningAddress,
        final boolean standingWarningIsInviteRefusal, final String standingCaution,
        final String standingCautionAddress, final Map<String, Long> contactsNotOnDisk,
        final boolean standingCautionIsAstorageNotice,
        final String standingStoreNotice, final long logWritesLandedWhenNoticeRaised,
        final boolean hostFieldIsPassword, final Encoder encoding) {
      this.draft = draft;
      this.wasComposing = wasComposing;
      this.banner = banner;
      this.warningStanding = warningStanding;
      this.standingWarningText = standingWarningText;
      this.standingWarningAddress = standingWarningAddress;
      this.standingWarningIsInviteRefusal = standingWarningIsInviteRefusal;
      this.standingCaution = standingCaution;
      this.standingCautionAddress = standingCautionAddress;
      this.contactsNotOnDisk = contactsNotOnDisk;
      this.standingCautionIsAstorageNotice = standingCautionIsAstorageNotice;
      this.standingStoreNotice = standingStoreNotice;
      this.logWritesLandedWhenNoticeRaised = logWritesLandedWhenNoticeRaised;
      this.hostFieldIsPassword = hostFieldIsPassword;
      this.encoding = encoding;
    }
  }

  /**
   * Unregisters this view from the process-wide clipboard.
   *
   * <p>The listener is added in the constructor and nothing ever removed it, so every rebuild left
   * another one attached to the same {@code ClipboardManager}. That is what turned "the discarded
   * strip is garbage" into "the discarded strip is retained for the life of the process, and still
   * runs {@code EnvelopeCodec.fromWire} on messenger-chosen bytes every time the clipboard
   * changes" - once per rebuild, and the messenger picks how many rebuilds happen.
   */
  private void releaseClipboardListener() {
    if (mClipboardListener == null) return;
    final ClipboardManager clipboardManager =
        (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboardManager != null) {
      clipboardManager.removePrimaryClipChangedListener(mClipboardListener);
    }
    mClipboardListener = null;
  }

  /**
   * Takes what must survive, and leaves nothing behind on this view.
   *
   * <p>An earlier version of this method emptied the compose box and stopped, with a javadoc saying
   * it left nothing behind. That was false in a way that mattered: the chat-log adapter still held
   * the entire decrypted conversation and the verify screen still held a safety number - on a view
   * that no clearing path can reach any more, because every one of them runs on the LIVE strip.
   * {@code clearDecryptedContent}'s own javadoc argues that leaving the chat-log screen is not
   * enough because the plaintext would be "one button-press away"; on an orphaned view it is not
   * even that, which is worse rather than better.
   *
   * <p>So the outgoing view gets the same treatment the keyboard being dismissed gives it, plus the
   * two things that keep it alive at all: the clipboard registration, and the input connection's
   * reference to its compose box.
   *
   * <p><b>What this does NOT drop, deliberately: the contact-list adapter.</b> This javadoc used to
   * claim it did, listing "the contact list still held who the user talks to" among the things fixed
   * here, and that was simply untrue - {@code StripCarriedStateRound5Test} drove an assertion for it
   * that fails on this method to this day. The assertion was rejected rather than the code changed,
   * and the reasoning is worth keeping where the method is rather than only where the test is:
   * after a rebuild nothing reachable points at the discarded view, so its remaining content is
   * ordinary garbage, which is true of every freed object in the process. That claim is not taken on
   * faith either - {@code r0TheDiscardedStripMustBecomeUnreachable} asserts it, and
   * {@code IgnoredTestsAreAccountedForTest} fails if that test is ever renamed or disabled, because
   * two rejections rest on it.
   *
   * <p>Which raises the obvious question about the two things above that <em>are</em> cleared, and
   * the honest answer is that it is not a principle: they come free, because this method reuses the
   * dismissal path's last rites, and that path runs on a live view where the same residue really
   * would be one button-press away.
   */
  public CarriedState surrenderState() {
    final CharSequence draft = mInputEditText == null ? "" : mInputEditText.getText().toString();
    final boolean wasComposing = mRichInputConnection != null
        && mRichInputConnection.isUsingOtherIC();
    final CharSequence banner =
        mInfoTextView == null ? "" : mInfoTextView.getText().toString();

    // The same last rites the dismissal path performs, on a view that is about to become
    // unreachable rather than merely hidden.
    clearDecryptedContent();
    resetAddContactInputTextFields();
    clearFingerprintViews();
    releaseClipboardListener();
    if (mInputEditText != null) mInputEditText.setText("");
    // And stop the service-lifetime connection pointing at this view's compose box. adoptState
    // re-points it when the redirect was up; when it was down nothing did, so the connection went
    // on holding the discarded box - and through its parent chain, the whole discarded strip.
    if (mRichInputConnection != null) mRichInputConnection.setOtherIC(null);

    return new CarriedState(draft, wasComposing, banner, mWarningStanding, mStandingWarningText,
        mStandingWarningAddress, mStandingWarningIsInviteRefusal, mStandingCaution,
        mStandingCautionAddress, new HashMap<>(mContactsNotOnDisk),
        mStandingCautionIsAstorageNotice, mStandingStoreNotice, mLogWritesLandedWhenNoticeRaised,
        mHostFieldIsPassword, encodingMethod);
  }

  /** Restores what the outgoing view surrendered. */
  public void adoptState(final CarriedState carried) {
    if (carried == null) return;

    // Before anything renders from it. adoptState re-raises the redirect with requestFocus(), whose
    // listener calls changeVisibilityInputFieldButtons, which reads encodingMethod to decide which
    // encoding indicator to show - so applying the carried value afterwards left the strip showing
    // RAW while it would in fact encode FairyTale. No plaintext exposure; the app understating its
    // own steganography to the user who chose it.
    if (carried.encoding != null) encodingMethod = carried.encoding;

    if (mInputEditText != null && carried.draft.length() > 0) {
      mInputEditText.setText(carried.draft);
    }
    // Re-pointed whether or not there was a draft, which is a distinction that cost a test run:
    // the connection's mOtherIC still referenced the DISCARDED EditText, so with the redirect up
    // and the box empty, everything typed after the rebuild went into an object that is no longer
    // on screen and that no clearing path can reach. Requesting focus re-points it through the same
    // listener a user's tap goes through, so there is one path that raises the redirect rather than
    // two that have to agree.
    if (carried.wasComposing && mInputEditText != null) {
      mInputEditText.requestFocus();
    }

    // A WARNING is carried. An ordinary banner is not, and the difference is the whole of two
    // defects this branch introduced by treating them alike.
    //
    // Writing back any non-empty banner meant a stale ordinary line was painted over a warning the
    // rebuild had just raised - refreshOpeningMessage can raise exactly one, INFO_STORAGE_UNREADABLE,
    // whose entire purpose is not to look like an ordinary empty app, because the obvious response
    // to an apparently empty app is to re-invite everyone and replace every pin already compared.
    // The user read "No contact chosen" instead, with mWarningStanding left true over it: the wedge
    // resetChosenContactAndInfoText already documents.
    //
    // It also resurrected warnings the user had resolved. Pressing Verify is the deliberate response
    // the flag waits for; afterwards the app's own predicate says there is nothing to warn about,
    // and the stale text used to be self-correcting because the flag was down. Re-posting it told a
    // user who had just compared a safety number to go and compare it again.
    //
    // An ordinary banner is worth nothing across a rebuild anyway: whatever the new strip computes
    // for itself is at least current.
    // Not over a warning this rebuild has already raised.
    //
    // refreshOpeningMessage runs first and can raise INFO_STORAGE_UNREADABLE - through
    // setWarningMessage, so it sets the flag too. Overwriting it with a carried identity warning
    // wiped "do NOT re-invite anyone until you have checked this", and because the button state is
    // derived from the banner TEXT, it also flipped encrypt and decrypt back to enabled on an
    // install whose account cannot be decrypted. mayOverwriteInfoBanner then refuses forever,
    // because storage really is unreadable, so the storage line could never come back.
    //
    // The previous round fixed exactly this collision for ordinary banners and introduced it again
    // in the branch it added for warnings.
    if (carried.warningStanding && !mWarningStanding) {
      // The warning's own text, not the banner: the banner may already carry a "Sending to: X"
      // line, and re-warning with that would append the recipient twice on the next selection.
      // Falling back to the banner keeps a strip carried from before this field existed readable.
      setWarningMessage(carried.standingWarningText != null
          ? carried.standingWarningText : String.valueOf(carried.banner),
          carried.standingWarningAddress);
      // Restored after setWarningMessage, which resets it: without this the refusal comes back
      // unretractable, so the good invite that should take it down leaves it standing forever - and
      // a rebuild is something the messenger's host app can force at will.
      mStandingWarningIsInviteRefusal = carried.standingWarningIsInviteRefusal;
    }

    // OUTSIDE the block above, because a caution can stand with no warning at all - and that is the
    // common case, the one shown when the app noticed nothing about a key it just pinned. Restoring
    // it only alongside a warning would drop exactly the copies that matter most.
    if (carried.standingCaution != null && mStandingCaution == null) {
      mStandingCaution = carried.standingCaution;
      mStandingCautionAddress = carried.standingCautionAddress;
    }
    // Inside nothing, because it belongs to the caution: restored wherever that is, and a rebuild
    // that dropped it while keeping the caution would put "do not send them anything" back on
    // screen with Encrypt live.
    // Carried, and restored whatever else happened: a rebuild that dropped this would put "do not
    // send them anything" back on screen with Encrypt live, and a configuration change is something
    // the host app can force.
    mContactsNotOnDisk.clear();
    if (carried.contactsNotOnDisk != null) mContactsNotOnDisk.putAll(carried.contactsNotOnDisk);
    mStandingCautionIsAstorageNotice = carried.standingCautionIsAstorageNotice;
    mStandingStoreNotice = carried.standingStoreNotice;
    mLogWritesLandedWhenNoticeRaised = carried.logWritesLandedWhenNoticeRaised;
    // Repainted through the shared builder so the restored banner shows both, rather than the
    // warning alone - which is the erase this carry exists to stop.
    if (aStandingItemHoldsTheBanner()) {
      setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
    }

    // Not the view's to lose either. The password-field guard is re-armed only by
    // onStartInputViewInternal, which then calls updateKeyboardTheme - and a theme change rebuilds
    // the strip synchronously, eight lines later, so the guard was armed on a view discarded
    // microseconds afterwards. Carrying the banner while dropping the guard was the worse half:
    // the strip went on saying encryption was off over a password box while the actions were back
    // on, which is the exact pairing setHostFieldIsPassword's own comment records as a defect.
    setHostFieldIsPassword(carried.hostFieldIsPassword);
    // Choosing FairyTale is the user saying "do not let this look like ciphertext in the
    // transcript". A rebuild put it back to RAW and the messenger picks when rebuilds happen.


    // The window's FLAG_SECURE decision belongs to whatever is on screen NOW. Nothing else tells
    // it: notifySensitiveVisibility is reachable only from a screen switch, and a rebuild is not
    // one, so the window kept whatever the discarded view last said - stuck on for the rest of the
    // keyboard's life, which contradicts a property this class states outright.
    notifySensitiveVisibility();
  }

  /** The clear button's real path, for tests. */
  void clearUserInputStringForTest() {
    clearUserInputString();
  }

  private void clearUserInputString() {
    clearComposeFieldAndCaches();
  }

  /**
   * Hands typing back to the host's field.
   *
   * <p>The counterpart of {@link #composeInsideTheKeyboard()}, and used by exactly one caller: the
   * password-field guard. The other two lowerings - a send, and the keyboard being dismissed - do
   * it inline where they have other work to do in a particular order; see the compose box's focus
   * listener for why focus loss is not one of them.
   *
   * <p>{@code clearFocus()} alone is not enough and its failure is silent: the box may not have
   * focus to lose - any app can take it away with {@code InputMethodManager.showSoftInput} - while
   * the redirect is up regardless, which is the whole point of that asymmetry.
   */
  private void stopComposingInsideTheKeyboard() {
    if (mRichInputConnection != null) mRichInputConnection.setShouldUseOtherIC(false);
    if (mInputEditText != null) mInputEditText.clearFocus();
    // Explicitly, rather than relying on the blur: with no focus to lose no listener fires, and the
    // affordances would go on claiming the user composes here while typing goes to the host.
    changeVisibilityInputFieldButtons(false);
  }

  /**
   * Points the user's typing at the compose box.
   *
   * <p>One implementation, because raising the redirect from two places is how the two would come
   * to disagree - and they did: see {@link #changeVisibilityInputFieldButtons}.
   *
   * <p>Through focus first, so the caret is where the characters are going and a user's tap and
   * this arrive by the same route. Then the redirect itself, because {@code requestFocus()} returns
   * false silently whenever the view cannot take focus at that moment - a {@code GONE} ancestor, a
   * window not yet focusable - and a silent failure here is precisely the disclosure this exists to
   * prevent.
   */
  private void composeInsideTheKeyboard() {
    if (mInputEditText == null || mRichInputConnection == null) return;
    // Guarded, because requestFocus() on an already-focused view is a no-op but the listener it
    // would fire re-enters changeVisibilityInputFieldButtons, which calls this again.
    if (!mInputEditText.hasFocus()) mInputEditText.requestFocus();
    mRichInputConnection.setOtherIC(mInputEditText);
    mRichInputConnection.setShouldUseOtherIC(true);
  }

  /**
   * Shows or hides the compose box's own buttons - and, when it shows them, makes what they claim
   * true.
   *
   * <p>These buttons ARE the app's statement that the user is composing inside the keyboard: the
   * clear button and the encoding selector appear on focus and vanish on blur, and nothing else on
   * the strip distinguishes "typing goes here" from "typing goes to the messenger". They were shown
   * from two places. One was the focus listener, where the redirect really had just been raised.
   * The other was the decrypt path, which raised nothing.
   *
   * <p>So after decrypting a message the strip rendered the peer's plaintext in the compose box and
   * lit the compose affordances beside it, while {@code shouldUseOtherIC} was still false - and the
   * reply the user typed next was committed straight into the messenger's own field in cleartext,
   * one character at a time. No adversary and no unusual gesture: receive, decrypt, reply is the
   * app's documented workflow, and the redirect is raised by exactly one thing, the compose box's
   * focus listener, which that workflow never fires. {@code TypingDestinationTest} asserts that
   * losing focus must not LOWER the redirect; that showing decrypted content must RAISE it is the
   * same property from the receive side, and nothing asserted it.
   *
   * <p>Made true here rather than at the two call sites, for the reason
   * {@code clearComposeFieldAndCaches} gives one screen away: a rule that has to be remembered at
   * every call site is not a rule.
   *
   * <p>Hiding them does NOT lower the redirect. That asymmetry is deliberate and is the property
   * the focus listener's comment argues for at length - only a send and the keyboard being
   * dismissed lower it.
   */
  private void changeVisibilityInputFieldButtons(boolean shouldBeVisible) {
    if (shouldBeVisible) composeInsideTheKeyboard();
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
    // Back to refusing over ANY standing warning, because the "soft" exception was worse than the
    // problem it solved. Six callers reach this method, and one of them is the password-field
    // notice - which LatinIME raises on EVERY input session from the host field's inputType, and
    // the messenger owns the inputType of every field it presents. So a soft warning could be
    // erased with no user action at all: focus a password field, the notice lands, the warning is
    // cleared, focus an ordinary field again and the strip reads "Chosen contact: Bob". That is
    // the exact path this file already records as a fixed defect twenty lines above the notice,
    // and it contradicts mWarningStanding's own javadoc: "Nothing the messenger can cause clears
    // it." INFO_PRE_KEY_DETECTED had the same shape - it fires at the head of processPreKeyResponse
    // and would clear a warning about Bob while handling an invite for Alice.
    //
    // The message that genuinely must not be suppressed - the caution for a newly created contact -
    // is shown BESIDE a standing warning instead, by setCautionBesideAnyWarning. Posting it as a
    // warning of its own was tried and is forbidden by StripWarningErasureTest: that is "Contact
    // Carol created" landing on top of a security warning.
    if (aStandingItemHoldsTheBanner()) return;
    setInfoTextViewMessage(mInfoTextView, message);
  }

  /** Package-visible so a test can drive the real method rather than a copy of it. */
  void showChosenContactInMainInfoField() {
    // Never over a standing warning. The caller guards this too, with a flag it then clears
    // immediately - so by the time a later event arrives that flag is false and only this check is
    // left. Two guards for one property is deliberate: the outer one covers the frame it was
    // written for, this one covers everything after it.
    if (mWarningStanding) return;
    // A standing caution holds the banner too, and it carries the recipient line itself - so this
    // repaints through the shared builder rather than returning, which would leave "Sending to: X"
    // naming whoever was chosen before.
    if (mStandingCaution != null || mStandingStoreNotice != null) {
      setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
      return;
    }
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
    // Choosing a contact does NOT clear a standing warning, and this line used to.
    //
    // It cleared whatever was on the banner, about whichever contact, and then re-asserted exactly
    // two of the app's warning states and only for the contact that was tapped. Three ways that
    // went wrong, all of them one tap:
    //
    //   - the duplicate-name warning, which this file elsewhere calls the only control covering the
    //     case the pin cannot. Its own text ends "Both now appear in your list, tagged by address",
    //     so the user opens the list to look - and the act of tapping one of the two identical rows
    //     is what erased the warning about them being identical. Half the time that is the
    //     impostor, and what replaced the warning is byte-identical to a healthy contact;
    //   - a warning about Bob, erased by tapping Alice, because the re-assertion asks about the
    //     contact chosen rather than the contact the warning names;
    //   - INFO_STORAGE_UNREADABLE, which is not about a contact at all. Worse than losing the text:
    //     refreshActionButtons derives button state from the banner, so Encrypt and Decrypt came
    //     back on for an install whose account cannot be decrypted, while the sentence telling the
    //     user not to re-invite anyone was gone.
    //
    // So selection now clears nothing. The deliberate responses still do - comparing a number, or
    // saying it does not match - and those are the two the field's javadoc actually rests on.
    setChosenContact(contact);
    // ...but a pending identity change is a state, not a notice that has been read. Choosing the
    // contact from the list is not the user having dealt with it, and clearing the flag here meant
    // "Chosen contact: Bob" replaced the substitution warning for good - the one screen the user
    // goes to when something looks wrong was also the one that made it stop looking wrong.
    // Re-assert it. Only comparing the number, or another deliberate response, may put it down.
    //
    // And a standing REJECTION is a state by the same argument, which this route used to answer for
    // only one of the two records. rejectedAddresses is documented as "cleared only by a fresh
    // comparison ... never by anything an attacker can trigger", and isContactKeyTrustworthy makes
    // it outrank a verified badge - so every other reader treats it as a state. Here it was treated
    // as a notice: after a rejected address was re-pinned and warned about, one tap on the contact
    // row left "Chosen contact: Bob" over the attacker's key, which is byte-identical to a healthy
    // contact. The warning's own last sentence is "Compare the number by voice before sending
    // anything", and opening the contact list to do that is the gesture that erased it.
    // A rejection alone is not a warning about a key, and on this path nothing is being pinned.
    //
    // INFO_PINNED_AFTER_REJECT states as fact that "this IS a new key for that address". After a
    // BARE rejection there is no key there at all - rejectContactKey removes the identity, the
    // pending change and the session - so firing on the rejection record alone made that claim
    // false, and made it permanent: the user rejects, then taps the row (the ordinary gesture, and
    // the one the warning's own last sentence invites) and gets a sticky warning about a key that
    // does not exist. Following its instruction to the verify screen finds no fingerprint, so
    // clearFingerprintViews disables Verify AND Reject - both deliberate responses physically
    // unavailable, the flag carried across rebuilds, every routine banner suppressed from then on.
    // The only exits were deleting the contact or the attacker delivering another key.
    //
    // The condition now lives in warnIfKeyWasRejected itself, because the arrival paths turned out
    // to need it too: a bundle whose signature fails is refused and pins nothing, so "the pin lands
    // after the check" was only true for good bundles. Keeping a copy of it here as well would be a
    // guard no mutation could kill.
    final boolean warnedAboutThisContact =
        warnIfIdentityChanged(contact) || warnIfKeyWasRejected(contact);
    if (mWarningStanding) {
      // Do not write OVER the warning - that is the same erasure whether or not the flag comes down
      // with it, because what the user reads is the banner. Repaint it with the new recipient named
      // underneath instead, so the screen says both what is wrong and who the next message goes to.
      setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
    } else if (!warnedAboutThisContact) {
      showChosenContactInMainInfoField();
    }
    showOnlyUIView(UIView.MAIN_VIEW);
  }

  @Override
  public void removeContact(Contact contact) {
    final boolean deleted;
    try {
      deleted = mE2EEStrip.removeContact(contact);
    } catch (ChatLogUnavailableException e) {
      // Deleting a contact has to sweep that contact's messages out of the log, which needs the
      // log. If it cannot be read the deletion cannot be completed correctly, so it is refused
      // rather than half-done - a contact row removed while its plaintext stayed behind is the
      // worse outcome, and it is the one the help text promises does not happen.
      //
      // The message says that, rather than "there are no saved messages for this contact" - which
      // is a claim about the history made in the one state where the app cannot see it, and which
      // asserts the opposite of the reason for the refusal. The sibling failure on this same
      // operation, the write not landing, has said so accurately since it was added.
      Toast.makeText(getContext(), INFO_SAVED_MESSAGES_UNREADABLE, Toast.LENGTH_LONG).show();
      Log.e(TAG, "the chat log could not be read, so the contact was not deleted", e);
      return;
    }

    // And the same promise, broken the other way. The refusal above covers a log that cannot be
    // READ; a log-and-account write that does not land leaves the row gone, the messages swept and
    // the session deleted in memory only, with none of it on disk - so the next raise brings the
    // contact and its plaintext back, after the app has said it was deleted. That is the outcome
    // the comment above calls the worse one, arrived at from the other side.
    if (!deleted) {
      Toast.makeText(getContext(), INFO_DELETE_NOT_SAVED, Toast.LENGTH_LONG).show();
      Log.e(TAG, "the deletion could not be written; it will not survive the next raise");
      // On the surface that lasts, not only in a toast.
      //
      // A failed deletion was the one member of this family with no durable notice - three and a
      // half seconds and then a screen that looks like an ordinary success, with the row, the
      // session, the pinned key and the messages all restored. A user who deleted a contact because
      // they suspected a key substitution, which is this app's own standard advice, would have no
      // way to know the deletion had not happened.
      // The deletion's own sentence, and NOT the missing-row machinery.
      //
      // Both were wrong here, in the same direction. The text said the contact "was set up here"
      // and "will be gone once this keyboard restarts" - the exact inverse: the row was restored,
      // disk was never touched, and they come back. And rememberContactIsNotOnDisk means "this row
      // is missing from disk", which is false; the row is there, its removal is what failed. The
      // refusal built on that then expired on the next landed write - because that rule was written
      // for a failed ADD, where a later write supplies the missing row, and it is inverted for a
      // failed delete, where a later write persists the restored one. So one tap on the still-dark
      // Encrypt button expired the entry, sent the message, and wiped the notice in the same call.
      //
      // Nothing about a failed deletion makes sending unsafe: the contact, their key and their
      // session are exactly as they were before the user asked.
      setCautionBesideAnyWarning(INFO_DELETE_NOT_SAVED, contact);
    } else if (mE2EEStrip.lastDeletionLeftMessagesBehind()) {
      Toast.makeText(getContext(),
          String.format(INFO_DELETED_BUT_MESSAGES_REMAIN, labelFor(contact)),
          Toast.LENGTH_LONG).show();
      Log.e(TAG, "the contact was deleted but their stored messages could not be removed");
    }

    // Only now, once the deletion has actually happened.
    //
    // Clearing before the call was a one-tap warning eraser for anyone whose chat log will not
    // read: removeContact sweeps that contact's messages, which needs the log, so it throws and
    // the contact list is left untouched - but the flag was already down while the banner still
    // showed the warning text. That is the inverse wedge this file documents elsewhere: the screen
    // says "warning", the model says none, so the messenger's next clipboard event overwrites it
    // with "Keybundle detected" and the app's only lasting warning is gone, with the contact still
    // there.
    //
    // Deleting the contact a warning names is the one deliberate response that the verify screen
    // cannot offer once the row is gone. Scoped to the address, so a warning about Bob survives
    // deleting Alice.
    // And only if it REACHED DISK, which is the other half of the same rule.
    //
    // The arm above covers a deletion that was refused; this covers one that was performed in
    // memory and not written. The row is gone from the list right now, so the warning has nothing
    // visible to point at - and that is exactly why clearing it was wrong. The next reloadAccount
    // brings the contact, its pinned key and its messages back, and the app's only lasting warning
    // about that key does not come back with them. The messenger chooses when that raise happens.
    //
    // So the warning stands over a contact that is temporarily not in the list. That is the lesser
    // evil, and the same trade the unreadable-log arm above settles the same way: a warning with no
    // visible subject is confusing, a subject that returns with no warning is a silent
    // key-substitution window.
    if (deleted && mWarningStanding && contact != null && mStandingWarningAddress != null
        && mStandingWarningAddress.equals(String.valueOf(contact.getSignalProtocolAddress()))) {
      clearStandingWarning();
    }
    // The caution about a deleted contact has nothing left to be about, and the verify screen it
    // points at is gone with the row - unless the deletion did not land, in which case the row and
    // everything the caution is about are coming back.
    if (deleted) {
      clearCautionIfAbout(contact);
      // Nothing left to refuse about: the row is gone from disk as well as from memory.
      if (contact != null) {
        mContactsNotOnDisk.remove(String.valueOf(contact.getSignalProtocolAddress()));
      }
      // AFTER the clear, which is the only place it survives.
      //
      // This caution names no contact - the contact is gone, so nothing about them could ever take
      // it down, and a caution nobody can clear is the dead end this file keeps closing. But a
      // null address is exactly what clearCautionIfAbout treats as "about anyone", so posting it
      // above would have had the very next line erase it. Ordering, not scoping, is what makes it
      // stand.
      if (mE2EEStrip.lastDeletionLeftMessagesBehind()) {
        mStandingStoreNotice = String.format(INFO_DELETED_BUT_MESSAGES_REMAIN, labelFor(contact));
        mLogWritesLandedWhenNoticeRaised = mE2EEStrip.messageLogWritesLanded();
        setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
      }
    }
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
    // The same last rites the rebuild path performs, because this throws the strip away too.
    //
    // LatinIME.onDestroy reaches here, and this used to remove the child views and stop. The
    // clipboard registration survived it, so a strip discarded when the IME is destroyed stayed
    // attached to the process-wide ClipboardManager and went on running EnvelopeCodec.fromWire on
    // messenger-chosen bytes - and kept the whole decrypted conversation in its chat-log adapter.
    //
    // setInputView is one of two paths that discard a strip. The previous round fixed that one and
    // did not look for a second.
    clearDecryptedContent();
    resetAddContactInputTextFields();
    clearFingerprintViews();
    releaseClipboardListener();
    if (mRichInputConnection != null) mRichInputConnection.setOtherIC(null);

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
