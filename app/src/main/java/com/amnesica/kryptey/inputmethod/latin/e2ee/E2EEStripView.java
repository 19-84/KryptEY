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
import android.view.MotionEvent;
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
import java.util.LinkedHashMap;
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

  /**
   * The pinned key the digits currently on screen were derived from, and the address it was pinned
   * at. Null whenever no number is painted.
   *
   * <p>The verify screen shows a number and offers two buttons that act on a key. Those were not
   * the same thing. The digits are painted once, from the account held at that moment, and the
   * account object underneath can be replaced afterwards without anything repainting them.
   *
   * <p>Two production paths replace it, and only one of them is a hazard. {@code reloadAccount}
   * runs from {@code LatinIME.setInputView} on a theme or ui-mode change - but three statements
   * later that same method calls {@code surrenderState}, which runs {@code clearFingerprintViews}
   * and so blanks the digits and nulls this binding, and {@code adoptState} restores neither the
   * chosen contact nor the verify screen. There is no window in which a press lands. The hazard is
   * the other one: {@code reloadAccountIfStorageRecovered}, on every keyboard raise while a store
   * fault stands, which repaints nothing and disables nothing - and
   * {@code onStartInputViewInternal} runs on any {@code restartInput} or focus move, without the
   * window hiding, so the messenger picks the moment by presenting a text field.
   *
   * <p>The theme path is named here rather than left out because under-stating the reachability is
   * the direction that gets a guard removed: if {@code reloadAccount} ever gains a caller that does
   * not rebuild, or {@code setInputView}'s last rites are reordered, this paragraph is what says
   * what was being relied on.
   *
   * <p>Verify's whole meaning is "the user compared this key against the peer's own device". A
   * verified badge recorded against a key that was never on screen is the one failure the trust
   * model has no recovery from, because the badge is what the user checks instead of comparing
   * again. Reject is worse in a different way: it is permanent, and it lands by address on whatever
   * the store holds now.
   */
  private org.signal.libsignal.protocol.IdentityKey mCodesWereDerivedFrom;
  private String mCodesWereDerivedForAddress;

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
    // Re-derived HERE, not only where the user taps a row.
    //
    // This file states, in warnIfNameIsShared's own comment, that "the identity change, the
    // rejection and the shared name are all re-derived on selection" - and that was true of exactly
    // one selection path. selectContact is the contact list being tapped; the three decrypt arms
    // move the recipient through this setter and asked the other two questions and not this one.
    // Which path runs is the messenger's choice, since it decides what arrives and the user only
    // presses Decrypt, so a control on one branch was not a control: relay an invite, let the user
    // add the impostor under a name they already have (which warns once, correctly), displace that
    // warning with any cheaper one about somebody else, let the user resolve THAT, and from then on
    // every relayed message from the impostor selects it silently. The banner reads exactly like a
    // healthy contact, with Encrypt aimed at the row the messenger chose.
    //
    // Only this one moves, because it is the one that was missing. The other two are still called
    // by selectContact in reverse severity, and calling them from here would reorder them against
    // each other. Raising here is safe for the same reason it is safe there: the writer is
    // idempotent, and its lowering half is scoped to this contact's own address and to its own two
    // sentences, so it cannot take down an identity change or a rejection standing about anybody.
    if (changed && contact != null) warnIfNameIsShared(contact);
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
  private final String INFO_VERIFY_CONTACT = "To check your encryption with %s, read the numbers above out to them and have them read theirs back.";

  /**
   * How to compare, and why it has to be that way - carried by every state of this screen.
   *
   * <p>It used to live inside {@code INFO_VERIFY_CONTACT}, which the two notices below REPLACE. So
   * the states an attacker produces - a pending change needs one forged bundle - were exactly the
   * states in which the screen stopped saying which channel to use and why. A user satisfies "if it
   * still matches what they read out" by pasting the digits into the chat they are already in,
   * which is the failure the sentence exists to prevent, reappearing on the screen the app's own
   * warnings route them to.
   *
   * <p>Its own constant, appended rather than embedded, so no notice can overwrite it: a sentence
   * that must survive every branch cannot live inside one of them.
   */
  static final String INFO_VERIFY_CHANNEL = "Read them out by voice - in person or on a call. Do not send the numbers through the messenger you are chatting in: anything that could change your keys could change those numbers to match.";
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
   * A contact created in memory only, which the next raise will undo.
   *
   * <p>The last member of the write family that could not say this. The banner otherwise reads
   * "Contact X created … compare the security number by voice before sending anything private" and
   * sends the user to verify a contact that will not be there after the next keyboard raise — and
   * the host app decides when that happens.
   *
   * <p>It leads with the problem, and says nothing about when the contact disappears that is not
   * true.
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
  private final String INFO_DELETED_BUT_MESSAGES_REMAIN = "%s was removed, but their saved messages could not be deleted - the app could not write to its own storage. Those messages are still on this device and no screen can reach them now. Deleting another contact does not remove them - it rewrites the same file with these messages still in it. Clearing the app's storage is what removes them.";

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
  private final String INFO_INVITE_REFUSED_BUT_KEY_PINNED = "The key update from %s could not be used - it does not verify, which means it was changed on the way here. The message it arrived with has set up a key for them anyway, and this app cannot tell whose it is - compare the security number by voice before sending anything private.";

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
  /**
   * The shared-name wording for when the two rows hold the SAME identity key.
   *
   * <p>{@code INFO_DUPLICATE_CONTACT_NAME} tells the user to compare the security number against
   * each row, because "the one they confirm is theirs". That is the correct instruction for an
   * impostor, who holds a different key and therefore shows different digits. It is the wrong
   * instruction here, and wrong in the direction that costs something: a relayed invite carries the
   * peer's REAL key, so both rows show the same number, the peer confirms both, and a user following
   * the sentence literally is told to pick by a signal that cannot distinguish them.
   *
   * <p>So the question it asks is one the peer can actually answer. How many invites they sent is
   * something they know; which of two rows in someone else's contact list is theirs is not - the
   * rows are told apart by a tag derived under the reader's own per-install secret, which the peer
   * cannot compute and has never seen.
   *
   * <p>It keeps the same ending as its sibling, because the ending has to be an action that ends the
   * condition: this warning is raised from the shared-name check and lowered by it, so deleting
   * either row puts it down. The pin is deliberately left behind by a deletion, which is why the
   * condition is the shared NAME and not the shared key - a warning keyed on the pin could not be
   * ended by anything the user can do, and this file has twice paid for a sentence with no exit.
   */
  private final String INFO_DUPLICATE_NAME_SAME_KEY = "You already have a contact called %s, and "
      + "this one holds the SAME key. One person's key belongs to one address, and even "
      + "reinstalling gives them a new one - so these two entries did not both come from them. "
      + "Comparing security numbers will NOT tell them apart, because both show the same number. "
      + "Ask them whether they sent you two invites, and do not send anything private to either "
      + "until you have agreed a fresh invite with them by some other channel. To end this notice, "
      + "delete one of them - it stays while two contacts share a name.";

  private final String INFO_DUPLICATE_CONTACT_NAME = "You already have a contact called %s, and this is a different one - not a replacement. A reinstall really does create a new contact, and so does someone pretending to be them. Both now appear in your list, tagged by address. Compare the security number by voice against EACH of them: the one they confirm is theirs. To end this notice, delete the one they do not confirm - it stays while two contacts share a name.";

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
   * The rejection did not reach storage, so it will not survive the next raise.
   *
   * <p>Its own message because the alternative is printing "Forgot the stored key for %s" over a
   * key that is still pinned on disk. Reads keep succeeding from the in-memory map, so nothing looks
   * wrong until {@code reloadAccount} runs on the next {@code setInputView} - and then the key the
   * user rejected is back, with no record that they rejected it.
   */
  private final String INFO_REJECTION_NOT_SAVED = "This could not be saved - the app could not write to its own storage, so it will not be remembered the next time the keyboard opens. Do not send anything to %s until you have compared the security number with them by voice.";

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
    setInfoTextViewMessage(mVerifyContactInfoTextView,
        String.format(INFO_VERIFY_CONTACT, labelFor(chosenContact)) + "\n\n" + INFO_VERIFY_CHANNEL);
  }

  /**
   * "The number does not match." The only control that un-pins a key, and the only correct action
   * when trust-on-first-use pinned an impostor - every other control assumes the pin is genuine.
   */
  private void createVerifyContactRejectButtonClickListener() {
    if (mVerifyContactRejectButton == null) return;
    mVerifyContactRejectButton.setContentDescription(
        "The number does not match - forget this contact's key");
    refuseTapsThroughAnotherWindow(mVerifyContactRejectButton);
    mVerifyContactRejectButton.setOnClickListener(v -> {
      if (chosenContact == null) return;
      // Same binding as Verify, and for a harsher reason: rejectContactKey acts purely by address
      // on whatever the account holds now, and the record it writes is deliberately permanent. A
      // press aimed at a key the user just read aloud must not land on a key that replaced it
      // underneath. When no number is painted the guard passes, which keeps the deliberate
      // escape hatch below working for a contact with nothing pinned.
      if (!thenumberOnScreenIsStillTheKeyWeWouldActOn()) {
        refuseThePressAndRepaintTheNumber();
        return;
      }
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
        // The record too, not only the sentence. Without this the refusal is re-derived on the
        // next selection and the user has no way to end it at all.
        forgetRefusedInviteBecauseTheUserAnsweredIt(chosenContact);
      } else {
        // On the surface that lasts, not only in a toast.
        //
        // This was the last member of the *_NOT_SAVED family with no durable copy, and the one
        // whose consequence is worst: measured in ArejectionThatDidNotLandIsForgottenTest, the key
        // the user reported as not matching is pinned again at the next reload AND the address is
        // unmarked, so the next bundle there is a clean first sighting and nothing warns. That is
        // the silent trust-on-first-use markKeyRejected exists to prevent, reached from the other
        // side. The user performed the app's strongest deliberate refusal, was told for three and
        // a half seconds that it had not stuck, and had no way to check afterwards.
        //
        // Flagged as a kind that no later write settles, for the same reason the failed deletion
        // is: nothing a later write does makes it false. Only a rejection that lands does, and
        // that is reachable - the contact is still in the list, so the user can try again.
        // Only when the failed write actually lost something. Pressing Reject at an address that
        // is already marked - reachable through the escape hatch, which needs no pin - changes
        // nothing, so "it will not be remembered the next time the keyboard opens" would be false:
        // the earlier mark is on disk and will be. The toast beside this already asks both
        // questions; the durable sentence must too, because it is durable.
        if (hadAkeyToForget || !alreadyRejected) {
          postStorageCaution(String.format(INFO_REJECTION_NOT_SAVED, label), chosenContact);
        }
      }
      loadContactsIntoContactsListView();
      showOnlyUIView(UIView.CONTACT_LIST_VIEW);
    });
  }

  /**
   * Whether the key on screen is still the key the buttons would act on.
   *
   * <p>True when no number is painted: the Reject escape hatch is deliberately live in that state,
   * for a contact with nothing pinned, and there is no comparison to invalidate.
   *
   * <p>Otherwise the store must still pin exactly the key the digits came from. Not "something is
   * pinned", which is what {@code verifyContactInContactList} asks and is satisfied by any key at
   * all - including one installed after the user started reading the number aloud.
   */
  private boolean thenumberOnScreenIsStillTheKeyWeWouldActOn() {
    if (mCodesWereDerivedFrom == null || chosenContact == null) return true;
    if (!mCodesWereDerivedForAddress
        .equals(String.valueOf(chosenContact.getSignalProtocolAddress()))) {
      return false;
    }
    return mCodesWereDerivedFrom
        .equals(mE2EEStrip.pinnedIdentityFor(chosenContact.getSignalProtocolAddress()));
  }

  /**
   * Refuses a press whose subject moved, and puts the current number on screen.
   *
   * <p>Repainting rather than only refusing, because a refusal with no next step is the dead end
   * this screen has produced three times: the user is told something is wrong and finds nothing to
   * press. After this the digits are the current ones and both buttons work again, so the response
   * available is the right one - compare the number again.
   *
   * <p><b>The repaint runs first and the sentence is chosen from what it left on screen.</b> Said
   * before, the message was picked from what the presser hoped for rather than from what they would
   * see: the reloaded account may pin nothing at that address - it may be a rejected address, or a
   * session whose write never landed - and then {@code loadFingerprintInVerifyContactView} takes
   * the null-fingerprint arm, blanks all twelve digits and darkens both buttons. "The number below
   * is the current one - compare it" would then be sitting for three and a half seconds over a line
   * that says there is no number to compare, telling the user to use a control that is not live.
   * That is the reuse this file rejected once already, arriving by toast instead of by info text,
   * and the argument against it is the same: a sentence the screen disproves is one the user stops
   * believing, and everything else this app has to say is a sentence.
   */
  private void refuseThePressAndRepaintTheNumber() {
    loadFingerprintInVerifyContactView();
    // Asked of the binding rather than of the store: it is written from the paint, so it is the one
    // thing that describes what is on screen NOW rather than what could be worked out again.
    //
    // Two calls rather than one call with a chosen string. NoToastCarriesMessageContentTest reads
    // the ARGUMENT, and a local holding either constant is opaque to it - the guard said so the
    // moment this was written the other way. Every toast on this strip is drawn outside
    // FLAG_SECURE, so what it interpolates has to be visible at the call site.
    if (mCodesWereDerivedFrom != null) {
      Toast.makeText(getContext(),
          String.format(INFO_NUMBER_MOVED_UNDER_THE_SCREEN, labelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
    } else {
      Toast.makeText(getContext(),
          String.format(INFO_NUMBER_MOVED_AND_IS_GONE, labelFor(chosenContact)),
          Toast.LENGTH_LONG).show();
    }
  }

  private void createVerifyContactVerifyButtonClickListener() {
    if (mVerifyContactVerifyButton == null) return;
    refuseTapsThroughAnotherWindow(mVerifyContactVerifyButton);
    mVerifyContactVerifyButton.setOnClickListener(v -> {
      try {
        // false means "this could not be recorded" - no contact, no account, or nothing pinned to
        // verify. The comment here used to say verification itself never refuses, which stopped
        // being true when verifyContact gained its no-pin refusal. Saying "a different security
        // number was offered" would fabricate a security claim out of a failed load, which is why
        // the message names neither cause.
        // Before anything is recorded: the badge means "the user compared THIS key", and the
        // account underneath can be replaced while this screen is up.
        if (!thenumberOnScreenIsStillTheKeyWeWouldActOn()) {
          refuseThePressAndRepaintTheNumber();
          return;
        }
        if (!mE2EEStrip.verifyContact(chosenContact)) {
          Toast.makeText(getContext(), INFO_VERIFY_UNAVAILABLE, Toast.LENGTH_LONG).show();
          return;
        }
        // Only now: the user has compared the number and confirmed it. Not on arriving at the
        // screen, and not on a failed load, which is why this sits after the guard above.
        clearStandingWarningIfAbout(chosenContact);
        clearCautionIfAbout(chosenContact);
        // The record too, not only the sentence. Without this the refusal is re-derived on the
        // next selection and the user has no way to end it at all.
        forgetRefusedInviteBecauseTheUserAnsweredIt(chosenContact);
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
    if (!isPassword) stripInputsCanTakeFocus(true);
    if (isPassword) {
      // The compose box and the caches, and NOT the screens.
      //
      // This used to call clearDecryptedContent(), which is written for a different event: the
      // keyboard going away, where the chat log, the safety-number digits and the contact list must
      // not survive into whatever app the keyboard rises in next. A password field is not that. The
      // keyboard is not going anywhere - it is the same app, the same session - and the whole block
      // ran on EVERY input session start rather than on a change, because this arm has no
      // transition test while its sibling below does.
      //
      // So the messenger could close the safety-number screen whenever the user opened it, from
      // inside the app they are talking to their contact in: it declares the inputType of every
      // field it presents and InputMethodManager.restartInput is unprivileged. Comparing that
      // number is this app's only defence against a substituted key and the only thing that clears
      // a standing warning, so an adversary who had just substituted one could abort the check
      // every time. The contact list went the same way.
      //
      // Nothing is hidden by keeping them: a safety number is derived from public keys, and the
      // user is not typing into the strip. What must still go is what the capture guard is about -
      // the draft and the IME's copies of it - and that is what stays here.
      if (mInputEditText != null && mInputEditText.getText().length() > 0) {
        clearComposeFieldAndCaches();
      }
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
      // And the fields must not accept a caret while the guard stands. See stripInputsCanTakeFocus.
      stripInputsCanTakeFocus(false);
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

  /** The add-contact screen, for the tests that are about what its two name fields do. */
  void showAddContactViewForTest() { showOnlyUIView(UIView.ADD_CONTACT_VIEW); }

  void showVerifyContactForTest(final Contact contact) { verifyContact(contact); }

  /** Package-visible so a test can drive the real verify screen rather than the pieces. */
  void loadFingerprintInVerifyContactView() {
    if (chosenContact == null) return;

    createVerifyContactReturnButtonClickListener();
    createVerifyContactRejectButtonClickListener();
    // With the channel sentence, like every other write on this screen. There are two of these
    // base writes, in different methods, and only one of them is the live path - which is its own
    // small hazard: a sentence added to one and not the other is a screen that says different
    // things depending on how it was opened.
    setInfoTextViewMessage(mVerifyContactInfoTextView,
        String.format(INFO_VERIFY_CONTACT, labelFor(chosenContact)) + "\n\n" + INFO_VERIFY_CHANNEL);

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
      // And NOT for a refused invite, by the same argument the paragraph above makes about a
      // storage caution.
      //
      // That warning says a bundle was altered in transit and that nothing has been set up. Its own
      // advice is "ask them to send another"; it is not a complaint about the peer's key, and there
      // is no key here to complain about. Pressing Reject on it calls markKeyRejected on an address
      // the user has nothing against, permanently - so that contact's next GENUINE invite is met
      // with "you told this app not to trust keys arriving for them", which is simply untrue, and
      // their row cannot show verified until a fresh comparison clears it.
      //
      // It became reachable when the refusal was made recomputable: before that it was a warning
      // the next repaint removed, and now it stands until it is answered, which makes the one lit
      // button on that screen look like the answer.
      //
      // Not a dead end. The response this warning asks for is out of band, and the in-app one is
      // deleting the row, which the contact list offers and which clears both the warning and the
      // record. That is the same exit the storage caution was left with when Reject was withdrawn
      // from it.
      if (mWarningStanding && !mStandingWarningIsInviteRefusal
          && mVerifyContactRejectButton != null
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
    // Recorded BEFORE the buttons go live, so there is no window in which they are pressable
    // without a binding.
    mCodesWereDerivedFrom =
        mE2EEStrip.pinnedIdentityFor(chosenContact.getSignalProtocolAddress());
    mCodesWereDerivedForAddress = String.valueOf(chosenContact.getSignalProtocolAddress());
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
      final String notice = mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress())
          ? pending + "\n\n" + String.format(INFO_VERIFY_AFTER_REJECTION, labelFor(chosenContact))
          : pending;
      // With the channel sentence, which this branch used to drop. See INFO_VERIFY_CHANNEL.
      setInfoTextViewMessage(mVerifyContactInfoTextView, notice + "\n\n" + INFO_VERIFY_CHANNEL);
    } else if (mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress())) {
      // A rejection with no pending change - and the channel sentence, which this dropped too.
      setInfoTextViewMessage(mVerifyContactInfoTextView,
          String.format(INFO_VERIFY_AFTER_REJECTION, labelFor(chosenContact))
              + "\n\n" + INFO_VERIFY_CHANNEL);
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
   * <p>Untestable under Robolectric, and tested on a device instead. An un-cancelled animator
   * delivers no further frames once the looper is idled past the view change, so the late repaint
   * does not happen and a JVM test of it passes with or without this cancel - removing the cancel
   * is invisible there. {@code AcontactSwitchDoesNotRepaintThePreviousNumberTest} runs on the
   * emulator: it switches contacts 200ms into the count-up and reads the twelve views two seconds
   * later, and reverting this cancel turns it red.
   *
   * <p>Why that matters beyond the digits themselves: {@code clearFingerprintViews} nulls the
   * key binding in the same call that cancels these, and a null binding means the Verify/Reject
   * guard passes. So an animator that outlived its cancel would be the one way digits can be on
   * screen with the guard failing open. That state is now measured not to exist.
   */
  private final java.util.List<ValueAnimator> mCodeAnimators = new ArrayList<>();

  private void cancelCodeAnimations() {
    for (final ValueAnimator animator : mCodeAnimators) {
      animator.cancel();
    }
    mCodeAnimators.clear();
  }

  /**
   * Said when a press is refused because the key moved while the screen was open, and the current
   * number is now painted.
   *
   * <p>Names what happened rather than blaming the user or the store. "Nothing was recorded" is the
   * clause that matters - without it a user who pressed Verify has no way to know whether the badge
   * they were about to earn exists - and it is the clause both of these must share, which is why
   * they are written next to each other.
   */
  static final String INFO_NUMBER_MOVED_UNDER_THE_SCREEN = "The safety number for %s changed while "
      + "this screen was open, so nothing was recorded. The number below is the current one - "
      + "compare it with them by voice again before verifying or rejecting.";

  /**
   * The same refusal, for the arm where the repaint leaves no number at all.
   *
   * <p>A separate sentence rather than one that covers both, because the first one ends "the number
   * below is the current one - compare it" and in this cell the digits are blank and both buttons
   * are dark. This file has already rejected exactly that reuse once, in
   * {@code INFO_VERIFY_AFTER_REJECTION}'s javadoc: a claim the screen disproves is a claim the user
   * stops believing, and the next thing they are asked to believe here is about a key.
   */
  static final String INFO_NUMBER_MOVED_AND_IS_GONE = "The safety number for %s changed while this "
      + "screen was open, so nothing was recorded - and this app no longer has a number for them "
      + "at all. Ask them to send a new invite, and compare the number by voice when it arrives.";

  /** Blanks the safety-number digits and disables confirmation. */
  private void clearFingerprintViews() {
    cancelCodeAnimations();
    // With the digits, not separately: the binding describes what is on screen, and blanking the
    // screen without it would leave both buttons refusing against a number nobody can see.
    mCodesWereDerivedFrom = null;
    mCodesWereDerivedForAddress = null;
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
    }
    // Nothing is logged about the contact that was just added.
    //
    // There was a line here interpolating the whole Contact, whose toString concatenates first
    // name, last name, device id, protocol UUID and the verified flag - in release, since this
    // build does not minify and nothing strips Log calls. A logcat reader (adb on an unlocked
    // device, a shared bug report, a vendor log collector) learned the display name and durable
    // address of a correspondent the moment the user added them.
    //
    // Deleted rather than reduced. The tempting repair is to log the keyed display tag instead,
    // which is not an off-device identifier - and is a stable correlator that ties every line about
    // that contact together, while looking obviously safe to whoever reads the diff.

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
        // Second, so the deferral above has a premise on this arm - it steps aside for a pending
        // change, and this is what writes that sentence here. Moved ahead of the pinned-elsewhere
        // call after a round pointed out that putting it LAST deleted the one warning nothing
        // re-derives, by rank instead of by the return this commit had just removed. All three
        // decrypt arms write the pinned-elsewhere sentence last, and this file's own note on rank
        // says why: a pending change is fail-closed, the offered key was refused and the pin stands,
        // while one key at two addresses is fail-open - the pin is live and the number is defeated
        // by construction.
        //
        // warnIfKeyWasRejected steps aside when a substitution is pending at the address, on the
        // stated grounds that the higher-ranked sentence was written in this same pass. That is true
        // on the three decrypt arms, where the identity-change warning is written from inside the
        // decrypt. It was NOT true here: this arm never called it, so a pending change silenced the
        // rejection warning and put nothing in its place - "Contact created" over an address where a
        // substitution is on record.
        //
        // Safe to call for a bundle that was ACCEPTED, which is the objection to look at twice. This
        // raiser is a function of hasUnacceptedIdentityChange alone; it says nothing about the
        // envelope just processed. Its sentence - someone offered a different key, it was refused
        // and is not in use, your messages still go to the key you already had - is exactly true
        // whenever a change is pending, and it is pending here or this line writes nothing.
        warnIfIdentityChanged(chosenContact);
        // Last, matching the decrypt arms. Nothing re-derives this one, so whatever writes after it
        // does not displace it - it erases it.
        warnIfThisKeyIsPinnedElsewhere(chosenContact);
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
        // And recorded so it can be worked out again, which the counter above cannot do.
        //
        // This is the FIRST invite from a new contact - the common case, and the one the help text
        // describes - and it was the arm the refusal record did not cover. The line below is a
        // plain banner write, so anything that repaints destroys it and nothing puts it back; the
        // update arms have been re-derivable since the record was added and this one was not.
        //
        // Only when the identity-change warning did not fire. That one is re-derived by
        // warnIfIdentityChanged from the pending change itself, it is more specific, and recording
        // a refusal beside it would put two sentences about the same envelope into a slot that
        // holds one.
        //
        // The plain sentence, because it is the true one here: createSessionWithContact returned
        // false, so nothing was set up. If the same paste then pins a key through its ciphertext
        // arm below, the transition detected there upgrades this to the sentence that tells the
        // user to compare the number.
        if (!warned) {
          rememberRefusedInvite(String.valueOf(recipientProtocolAddress),
              String.format(INFO_INVITE_REFUSED, labelFor(chosenContact)));
        }
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

    if (messageEnvelope.getCiphertextMessage() != null && !actionsAreAvailable()) {
      // The fourth action path, and the only one that was not asking.
      //
      // actionsAreAvailable() gates encryptAndSendInputFieldContent,
      // sendPreKeyResponseMessageToApplication and decryptMessageInClipboard. Add reaches the same
      // decrypt and was not gated, and nothing closes this screen when the guard arms:
      // isShowingSensitiveContent does not count the add-contact view, so clearDecryptedContent
      // leaves it up. So the user meets an invite over an ordinary field, the messenger starts a
      // password-typed session - it declares the inputType of every field it presents - and Add
      // rendered the peer's plaintext against a banner saying decryption is turned off here.
      // Then setHostFieldIsPassword(false) wipes the box on the way back, destroying the message
      // with no notice.
      //
      // The CONTACT is still created, deliberately. Refusing the whole Add would strand the user on
      // a screen whose only other exit discards the invite, which would let the messenger deny
      // contact-adding outright by declaring a password field. Skipping only the decrypt leaves
      // nothing pinned either, so the caution and the rejection check below have nothing to report
      // and their absence is correct rather than a dropped warning - the envelope is still on the
      // clipboard, and decrypting it over an ordinary field runs the whole path with its warnings.
      setInfoTextViewMessage(mInfoTextView, INFO_PASSWORD_FIELD);
    } else if (messageEnvelope.getCiphertextMessage() != null) {
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
      warnIfThisKeyIsPinnedElsewhere(chosenContact);

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
      // Which sentence depends on WHICH write was lost, because they are different failures with
      // different truths and different advice.
      //
      // Both were reported with the contact-row sentence, which says the contact "will be gone once
      // this keyboard restarts" and to "add them again successfully". When it is the row's write
      // that failed, that is true and the advice is right. When the row landed and the SESSION
      // write did not, both clauses are false - the row is on disk - and "add them again" is the
      // delete-and-re-invite instruction this project spent a commit removing from storage notices,
      // because a messenger can provoke the exchange it names. INFO_SESSION_NOT_SAVED exists for
      // exactly this state and says "nothing here needs deleting or re-inviting".
      //
      // The comment that justified the reuse said "same sentence for both, because it is true of
      // both". It was written before the second sentence existed, and it was wrong the moment it
      // did. The receive path was corrected then; this one was not.
      rememberContactIsNotOnDisk(chosenContact);
      final String notSaved = rowReachedDisk
          ? String.format(INFO_SESSION_NOT_SAVED, labelFor(chosenContact))
          : String.format(INFO_CONTACT_NOT_SAVED, labelFor(chosenContact));
      // Through the composer, like the other two. This was the call site the composer never
      // reached: it wrote the shared caution field directly, so on the arm where the row lands and
      // the session write does not, the pin caution posted moments earlier - about a key this very
      // add pinned by trust-on-first-use - was overwritten by a storage sentence that says nothing
      // about comparing a number. Nothing re-posts it, so the loss was permanent.
      // And when the ROW is what was lost, the compare-the-number caution goes with it.
      //
      // This is the distinction neither previous behaviour had. The old code replaced the pin
      // caution unconditionally, justified by containment - "do not send them anything until you
      // have added them again successfully" subsuming "compare the number before sending anything
      // private". That argument is sound when the contact is about to disappear and false when it
      // is not, and the two cases were being treated the same:
      //
      //   - the ROW write failed: the contact will not survive the restart, so asking the user to
      //     compare a security number for it is asking for work that is about to be thrown away.
      //   - only the SESSION write failed: the row is on disk, the contact stays, and the key that
      //     was just pinned by trust-on-first-use is exactly what the user should be comparing.
      if (!rowReachedDisk) clearCautionIfAbout(chosenContact);
      postStorageCaution(
          sessionCreationFailed ? notSaved + " " + INFO_SESSION_CREATION_FAILED : notSaved,
          chosenContact);
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
   *
   * <p>What it says: the caution owed when a key was pinned by THIS paste.
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
    cautionThatAkeyWasPinned(pinnedBefore, true);
  }

  /**
   * @param contactWasCreated whether a contact row was created by the same action. When it was not,
   *                          the sentence must not say one was.
   */
  private void cautionThatAkeyWasPinned(final boolean pinnedBefore,
      final boolean contactWasCreated) {
    if (chosenContact == null || pinnedBefore) return;
    if (!mE2EEStrip.hasPinnedKey(chosenContact.getSignalProtocolAddress())) return;

    // "created" only where something was created.
    //
    // The sentence was written for the two addContact arms, where a row had just been made. Moving
    // the caution onto the decrypt path - correctly, since that is where every arm's pin passes -
    // took the wording with it to states where no contact was created at all. The clearest is the
    // app's own recovery flow: the user rejects a key, is told "nothing can be sent to them until
    // they send a new invite", the peer sends one, and the banner then read the post-rejection
    // warning above "Contact X created" below. Two sentences contradicting each other about the
    // same event, on the screen whose entire job is to be believed.
    setCautionBesideAnyWarning((contactWasCreated
        ? "Contact " + labelFor(chosenContact) + " created. "
        : "A key for " + labelFor(chosenContact) + " has been stored. ")
        + "This key reached you through the messenger and the app cannot tell whose it is"
        + " - compare the security number by voice before sending anything private.", chosenContact);
  }

  private void abortContactAdding() {
    Toast.makeText(getContext(), INFO_CONTACT_CREATION_FAILED, Toast.LENGTH_SHORT).show();
    Log.d(TAG, INFO_CONTACT_CREATION_FAILED);
    // The typed name goes with the screen, as it does on the other three exits.
    //
    // Four routes leave the add-contact screen and this was the only one that left the name behind.
    // Cancel's comment says what that costs: the next invite's screen opens pre-filled, so a user
    // who declines one invite and accepts the next without re-reading the fields names a new
    // address after the old contact. forgetAbandonedInvite and the successful add reset for the
    // same reason; the failure exit was simply missed.
    //
    // Before the screen switch, not after. It makes no difference today, and it would if the
    // re-point guard in showOnlyUIViewInternal ever learned to read these fields the way it reads
    // the compose box.
    resetAddContactInputTextFields();
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

  /**
   * Whether an info message means encrypt and decrypt cannot work.
   *
   * <p>Separated so it can be tested: the watcher itself needs an inflated IME, and this is the
   * decision, not the wiring.
   */
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
   * Contacts whose row is known not to have reached disk, and the write count at that moment.
   *
   * <p>A fact, kept because the alternative was reading it back out of the banner - and the banner
   * is composed warning-first, so any warning sharing it moved the notice off the start of the
   * string and a prefix match missed it precisely when a security warning was already on screen.
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
   * Addresses whose last attached bundle was refused, and the sentence that was said about it.
   *
   * <p>This exists because the invite-refusal warning was the one warning nothing could work out
   * again. The banner holds one warning and any other writer takes it - including the condition
   * warning that a keyboard raise re-raises on every raise while a store fault stands. The other
   * three warnings survive that: {@code selectContact} re-derives the shared name, the rejection
   * and the identity change, and the last also re-raises on every decrypt from that sender. So
   * painting over them is a displacement. Painting over this one was an erasure, and what it erased
   * is "that invite was changed on the way here" - the notice bought back from a relay that
   * otherwise gets silence for one stripped unsigned byte per message.
   *
   * <p>The SENTENCE is stored, not a flag. Three outcomes have three texts and they are not
   * interchangeable: one says nothing was set up, one says a key was pinned anyway and to compare
   * the number, one says what you already had is unchanged. Re-deriving from a flag would have to
   * pick, and picking wrong writes a false sentence onto the one durable surface this app has -
   * "nothing has been set up" over a session that is fine, or the reassuring one over a first pin.
   *
   * <p>Not persisted, deliberately. The fact is about a paste in this session, and it is retracted
   * by a later good invite at the same address; carrying it across process death would mean
   * re-asserting a refusal the user may already have resolved by asking for another invite.
   */
  private final Map<String, String> mRefusedInvites = new LinkedHashMap<String, String>() {
    @Override
    protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
      // Bounded, though not for the reason first written here. That said "the addresses need not
      // be contacts: a relay can staple a tampered bundle to as many addresses as it likes", and a
      // reviewer showed it is false - both insertion sites record for a row that exists at the
      // moment of insertion, so the map is self-bounded by the contact list and nothing can flood
      // it. The cap stays because welding the map's size to an invariant that lives in three other
      // dispatch methods is how a guard becomes wrong later, and because the cost of keeping it is
      // one comparison. It is set well above any plausible contact list, so it is the sole
      // eviction mechanism on a map nothing can fill - which is the honest description of it.
      return size() > REFUSED_INVITES_REMEMBERED;
    }
  };

  /** How many refused invites are re-derivable at once. See {@code mRefusedInvites}. */
  private static final int REFUSED_INVITES_REMEMBERED = 256;

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

  private String mStandingStoreNotice;

  /** The message-log write count when that notice went up; see {@link #clearAstoreNoticeThatHasBeenResolved}. */
  private long mLogWritesLandedWhenNoticeRaised = -1;

  /**
   * The reload count when that notice went up, so a reload can void its retirement rule.
   *
   * <p>The rule beside it retires the notice on the next landed log write, and the premise that
   * makes that a resolution rather than a timeout is that the in-memory log is the PRUNED one - the
   * deletion removed the entries, only the write failed. A reload ends that premise: it replaces
   * the account with the stored copy, whose log was never pruned, so the orphaned plaintext is back
   * in memory and the next write persists it. Measured, not reasoned: the reload brings the entries
   * back, and following the notice's own advice then rewrites the file with them still in it.
   *
   * <p>Carried across a rebuild with the notice and its write count, for the reason those are: a
   * fresh strip starts this below every real count, so dropping it would make the first repaint
   * after a rotation decide the premise still held.
   */
  private long mAccountReloadsWhenNoticeRaised = -1;

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
   * Whether the app is refusing to send to whoever is chosen. A question, and only a question.
   *
   * <p>Scoped to the address, like every other deliberate response on this screen: a contact whose
   * row failed to save must not disable Encrypt for a different contact whose row is on disk. And
   * asked live rather than cached, so re-selecting the contact the caution is about brings the
   * refusal back with them - which is the whole point, since the messenger can move the recipient
   * off that contact whenever it likes.
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
   * Posts a storage caution. It has its own slot, so there is nothing to merge and nothing to lose.
   *
   * <p>This composed the storage sentence onto a standing pin caution, because the two shared one
   * field. That merge was hand-rolled at three call sites in three different ways and every one lost
   * something: it could not compose onto its own output, so a second storage failure for the same
   * contact dropped the pin half; the refusal retirement cleared the composed string on the strength
   * of a comment saying the pin caution would be re-posted, which it never is; and one call site
   * never reached the composer at all.
   *
   * <p>The fields beside this one make all of that unnecessary rather than careful.
   */
  private void postStorageCaution(final String notice, final Contact about) {
    postStorageCaution(notice, about, StorageCautionKind.SETTLED_BY_ALATER_WRITE);
  }

  /**
   * @param kind what ends this caution - see {@link #mStandingStorageCautionKind}
   */
  private void postStorageCaution(final String notice, final Contact about,
      final StorageCautionKind kind) {
    mAccountWritesLandedWhenStorageCautionRaised = mE2EEStrip.accountWritesLanded();
    // A deletion that did not happen outranks any other storage caution, and the slot is single.
    //
    // Without this, one relayed message carrying a bundle is enough: the rotation's own write also
    // fails during the same disk-full or locked episode, posts "a key update could not be saved",
    // and the sentence saying a contact the user tried to delete is still present - with their key
    // and their plaintext - is gone, flag and all. That one is not settled by a later landed write
    // and the replacement is, so the next successful write clears the weaker sentence too and the
    // screen reads like an ordinary success. The other cautions all have a route back: the failed
    // operation can simply be tried again.
    if (!mStandingStorageCautionKind.isSettledByAnyLaterWrite()
        && kind.isSettledByAnyLaterWrite()) {
      return;
    }
    mStandingStorageCautionKind = kind;
    mStandingStorageCaution = notice;
    mStandingStorageCautionAddress = about == null ? null
        : String.valueOf(about.getSignalProtocolAddress());
    setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
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
    // Before the early return: a storage caution can stand with no refusal entry beside it, and
    // that is exactly the case that had no exit.
    retireAstorageCautionSettledByAlaterWrite();
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
      // Whatever this drops, its caution goes with it. Dropping the refusal alone left "Do not
      // send them anything until you have added them again successfully" on the banner with
      // Encrypt live beside it - the app offering exactly what its own durable surface forbids,
      // which is the invariant TheButtonsNeverContradictTheBannerTest exists for. The reasoning
      // that used to sit here asked only whether this could produce a FALSE REFUSAL and concluded
      // it could not; the direction it does produce is the opposite one, and the sweep is reached
      // by two production paths that replace the account - reloadAccount on a host-forced theme
      // change, and the per-raise recovery re-read.
      // ...except for the contact the user is standing on, which is where dropping it does harm.
      //
      // The recovery re-read is what makes a row vanish: it adopts the stored account and discards
      // everything the session did while writes were refused, which its own javadoc calls
      // deliberate. That happens on a keyboard raise, and the raise is at the messenger's disposal.
      // So the moment a row disappears is precisely the moment the caution beside it becomes the
      // only true sentence on screen - "contact X was set up here, but it could not be saved ...
      // add them again" - and dropping the refusal there lit Encrypt under it.
      //
      // Scoped to the chosen contact rather than kept for everyone, because an entry no user
      // action can clear is the dead end this file has closed twice: a vanished contact is
      // unselectable and undeletable, so a refusal held for one would sit in the banner for the
      // life of the process. Choosing anybody else drops it, and adding a contact is always
      // available - which is the exact action the sentence asks for.
      final String standingOn = chosenContact == null ? null
          : String.valueOf(chosenContact.getSignalProtocolAddress());
      final List<String> vanished = new ArrayList<>();
      for (final String address : mContactsNotOnDisk.keySet()) {
        if (!live.contains(address) && !address.equals(standingOn)) vanished.add(address);
      }
      mContactsNotOnDisk.keySet().removeAll(vanished);
      for (final String address : vanished) {
        retireTheStorageCautionFor(address);
        // And a refusal remembered for a row that never reached disk goes with it. The add arm
        // records one, and that row can be the one the recovery re-read discards - after which the
        // contact is unselectable and undeletable, so the record could never be answered and could
        // never be re-raised either. Kept on exactly the terms mContactsNotOnDisk is kept on, which
        // includes staying while the user is standing on the row it names.
        mRefusedInvites.remove(address);
      }
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
      // A composed caution counts: it is stored with the storage flag set, so this finds it, and
      // retiring it takes the pin sentence with it - which is correct, because the pin caution is
      // re-posted by the next paste that pins and the storage half is what has just been resolved.
      // Only the STORAGE half, which is the half a landed write settles.
      //
      // It used to clear the whole composed caution, justified by a comment saying the pin caution
      // would be re-posted by the next paste that pins. It never is: cautionThatAkeyWasPinned
      // returns immediately once a key is pinned, and after the pin it always is. So the sentence
      // saying a messenger-supplied key had been pinned by trust-on-first-use was deleted by any
      // later write anywhere - and the messenger chooses when that happens, since relaying any
      // message is enough.
      // Only the storage caution this refusal is about, which is not the same as "any storage
      // caution at this address".
      //
      // The failed-delete arm deliberately records no refusal, because expiring one used to send
      // the message on the first tap. But the expiry keyed on the ADDRESS, so a refusal recorded by
      // an EARLIER failure at that address retired the delete notice anyway - and a failed deletion
      // rolls the row back, so the entry survives the sweep that drops entries for contacts that no
      // longer exist. Two storage failures in sequence, and a later landed write erased "that
      // contact was not removed" while the contact, its pinned key and its plaintext were all still
      // there. The screen then read "No contact chosen", which is what a healthy app looks like.
      retireTheStorageCautionFor(address);
    }
  }

  /**
   * Takes down the storage caution for one address, and repaints what is left.
   *
   * <p>Called from both arms of the sweep, which is the point: a refusal and the sentence
   * justifying it are one fact, and the arm that dropped the refusal without the sentence put "Do
   * not send them anything" on the banner with Encrypt live beside it.
   *
   * <p>Never for a caution whose kind says a later write does not settle it. A refusal is settled
   * by a later landed write and a failed deletion is not: the row, its pinned key and its
   * plaintext are all still there, so "that contact was not removed" stays until the deletion is
   * actually done.
   */
  private void retireTheStorageCautionFor(final String address) {
    if (!address.equals(mStandingStorageCautionAddress)
        || !mStandingStorageCautionKind.isSettledByAnyLaterWrite()) {
      return;
    }
    mStandingStorageCaution = null;
    mStandingStorageCautionAddress = null;
    setInfoTextViewMessage(mInfoTextView,
        aStandingItemHoldsTheBanner() ? warningWithRecipient()
            : chosenContact != null ? "Chosen contact: " + labelFor(chosenContact)
                : INFO_NO_CONTACT_CHOSEN);
  }

  /**
   * Takes down an ordinary storage caution once a later write has landed, which is what settles it.
   *
   * <p>Not for the protected kind: a failed deletion rolls the row back, so a later landed write
   * persists the restored contact rather than completing the deletion. That one is ended by a
   * deletion that lands and by nothing else.
   */
  private void retireAstorageCautionSettledByAlaterWrite() {
    if (mStandingStorageCaution == null) return;
    if (!mStandingStorageCautionKind.isSettledByAnyLaterWrite()) return;
    if (mE2EEStrip.accountWritesLanded() <= mAccountWritesLandedWhenStorageCautionRaised) return;
    // A later write settles one of these only if it carried the thing that was lost.
    //
    // The whole argument for reclassifying a failed rejection was that rejectContactKey leaves the
    // decision in memory, so the next landed write persists it. That holds until the in-memory
    // account is replaced - and reloadAccount does exactly that, on a theme change the host app can
    // force, restoring the pinned key and emptying rejectedAddresses. Its write-back is deliberately
    // not counted, so the reload is invisible here, and adoptState carries the caution AND its
    // captured count across the same rebuild. The caution then survived the event that made it
    // permanently true and was retired by the next unrelated write - taking away the only durable
    // sentence saying the refused key had come back, at the moment it actually had.
    //
    // So the question is asked of the decision rather than of the counter: if this caution names an
    // address the user rejected and that rejection is no longer in memory, nothing has been
    // persisted and the sentence stays.
    if (mStandingStorageCautionAddress != null && aRejectionWasLostAt(mStandingStorageCautionAddress)) {
      return;
    }
    mStandingStorageCaution = null;
    mStandingStorageCautionAddress = null;
    mAccountWritesLandedWhenStorageCautionRaised = -1;
    // Guarded like both siblings, and evaluated AFTER the caution is nulled so this cannot repaint
    // the sentence it just removed. The count half of the store notice's idiom was copied here and
    // the repaint half was not: warningWithRecipient returns null when nothing stands and no
    // contact is chosen, so a bare setText painted the banner blank - and an empty banner matches
    // no prefix in disablesActionButtons, so Encrypt and Decrypt lit up on a strip with no
    // recipient. The chosenContact == null case is not a corner: the Invite path posts its caution
    // with a null contact, and closing that caution's missing exit is what this method was added
    // for.
    setInfoTextViewMessage(mInfoTextView, aStandingItemHoldsTheBanner()
        ? warningWithRecipient()
        : chosenContact != null ? "Chosen contact: " + labelFor(chosenContact)
            : INFO_NO_CONTACT_CHOSEN);
  }

  /**
   * Whether this caution is about a rejection the app no longer holds.
   *
   * <p>True only for the sentence the failed-reject arm posts, and only when the address is no
   * longer marked - which is what a reload that discarded the un-persisted decision leaves behind.
   * Matched on the sentence rather than a kind because the kind is what settles it, and this is not
   * a different settlement: it is the same one, asked correctly.
   */
  private boolean aRejectionWasLostAt(final String address) {
    if (mStandingStorageCaution == null
        || !mStandingStorageCaution.startsWith(literalPrefixOf(INFO_REJECTION_NOT_SAVED))) {
      return false;
    }
    if (chosenContact == null
        || !address.equals(String.valueOf(chosenContact.getSignalProtocolAddress()))) {
      // Cannot ask without the address object the store keys on; keep the sentence rather than
      // retire one that might be about a lost rejection.
      return true;
    }
    return !mE2EEStrip.wasKeyRejected(chosenContact.getSignalProtocolAddress());
  }

  /** Records that this contact's row did not reach disk. */
  private void rememberContactIsNotOnDisk(final Contact contact) {
    if (contact == null) return;
    mContactsNotOnDisk.put(String.valueOf(contact.getSignalProtocolAddress()),
        mE2EEStrip.accountWritesLanded());
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
    // Asked of the LIVE list alone. This used to be "the name is known AND it is not retired", so a
    // retirement suppressed the live wording even when a live row of that name was sitting in the
    // list - and the live wording is the only one that ends "Both now appear in your list, tagged
    // by address", which is the sentence pointing at the tag the user is meant to read. The case
    // where both hold is the attacker's second attempt at a name this user already deleted once,
    // i.e. the one furthest along.
    final boolean live = SignalProtocolMain.hasLiveContactWithSameDisplayName(
        contact.getFirstName(), contact.getLastName(), contact.getSignalProtocolAddress());
    if (!live) {
      return INFO_RETIRED_CONTACT_NAME;
    }

    // The same key at both addresses outranks the ordinary duplicate wording.
    //
    // Not a stylistic preference between two true sentences: the ordinary one instructs the user to
    // compare the number against each row and keep "the one they confirm", and in this state that
    // instruction cannot be carried out, because both rows show the same digits and the peer
    // confirms both. Asked here rather than added to the re-asserted warnings in selectContact,
    // which is where it looks like it belongs: the pin survives contact deletion, so a warning
    // conditioned on the pin has no action that ends it and would sit on the banner for the life of
    // the install - the retired-name failure this file has already paid for once. Conditioned on the
    // shared NAME, it is raised, re-derived and lowered exactly like its sibling.
    // Asked as ONE question about the same-named row, not as two that can be about different
    // people. This read addressesAlreadyPinningTheSameKey directly, which answers "does ANY address
    // pin this key" - so two genuine Bob Joneses with different keys, plus any third pin holding one
    // of those keys, satisfied it. The user was then told the two Bobs hold one key and that
    // comparing numbers could not tell them apart, which is false when the keys differ, and the
    // sentence that exposes an impostor - compare against each, the one they confirm is theirs - was
    // the one it replaced. Widening a warning to cover the relay attack must not switch off the
    // control for the impostor attack.
    if (SignalProtocolMain.asameNamedLiveContactPinsTheSameKey(contact.getSignalProtocolAddress(),
        contact.getFirstName(), contact.getLastName())) {
      return INFO_DUPLICATE_NAME_SAME_KEY;
    }
    return INFO_DUPLICATE_CONTACT_NAME;
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
            // Composed rather than replacing: a pin caution about this contact may be standing,
            // and this sentence says nothing about comparing a number.
            postStorageCaution(
                String.format(INFO_SEND_STATE_NOT_SAVED, labelFor(chosenContact)), chosenContact);
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

    // Leaving the add-contact screen hands the redirect back.
    //
    // The two name fields raise it on focus and deliberately do not lower it on blur, so this is
    // what stops it being left up pointing at a field that is no longer on screen. Asked of the
    // screen being left rather than of the field, because a field can lose focus for reasons that
    // are not the screen closing - which is the whole point of the asymmetry.
    if (!uiView.equals(UIView.ADD_CONTACT_VIEW)
        && mLayoutE2EEAddContactView.getVisibility() == VISIBLE
        && mRichInputConnection != null) {
      // Asked of the compose box's TEXT, not of its focus.
      //
      // It asked hasFocus(), and that condition is dead: the compose box lives inside the main
      // wrapper, which this method sets GONE for the whole life of the add-contact screen, and a
      // GONE subtree cannot hold focus. So the escape hatch never fired and the lowering was
      // unconditional - including on the way back to the main view with the user's draft still
      // rendered in the box. The only visible change was two small buttons going dark, and the
      // next keystroke went to the messenger in cleartext. That is the state
      // TypingDestinationTest exists to forbid, reached by a route it did not drive.
      //
      // The attacker picks the moment: an invite from an unknown address routes the decrypt to the
      // add-contact screen, and Cancel is the response this file calls the correct one.
      //
      // Re-pointed rather than cleared. Clearing the box would make the screen honest and hand the
      // messenger a draft-eraser - one relayed envelope destroying what the user typed, on demand
      // and repeatably. And re-pointing only when there is something to protect, rather than
      // always: raising the redirect on a return to an empty box would take focus the user had not
      // given it, which is the mirror defect.
      final boolean somethingToProtect = mInputEditText != null
          && mInputEditText.getText() != null && mInputEditText.getText().length() > 0;
      if (somethingToProtect && uiView.equals(UIView.MAIN_VIEW)) {
        composeInsideTheKeyboard();
      } else {
        mRichInputConnection.setShouldUseOtherIC(false);
      }
    }

    if (uiView.equals(UIView.MAIN_VIEW)) {
      mLayoutE2EEMainView.setVisibility(VISIBLE);
      // Coming back with the redirect still up, the affordances have to come back with it.
      //
      // Opening any other screen sets this layout GONE, which clears the compose box's focus, and
      // the blur hides the Clear button and the encoding selector while deliberately not lowering
      // the redirect. Returning restored neither, so typing went on landing in the strip while the
      // two controls that ARE the app's statement that it does stayed dark - and Clear, the only
      // control that erases a decrypted message from the box, was unreachable until the user
      // happened to tap it.
      //
      // Conditional on the redirect already being up, and that is the whole care here. Lighting
      // them unconditionally would mean re-raising it on every return to an empty box, taking
      // focus the user never gave - the mirror defect the add-contact re-point refuses to commit
      // twenty lines above. Through requestFocus rather than by painting the buttons directly, so
      // the state arrives by the same route a user's tap would take.
      if (mRichInputConnection != null && mRichInputConnection.isUsingOtherIC()
          && mInputEditText != null && !mInputEditText.hasFocus()) {
        mInputEditText.requestFocus();
      }
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
    // Scrolling, and it is doing security work as well as scrolling.
    //
    // A movement method that cannot select arbitrarily leaves TextView unable to build a selection
    // controller, so the Copy / Share / PROCESS_TEXT toolbar never appears over this field. That is
    // the only thing stopping a long-press putting a decrypted message on the system clipboard -
    // which the messenger reads - or handing it to any installed app in an Intent extra. Nothing
    // else refuses those: there is no setCustomSelectionActionModeCallback and no
    // textIsSelectable="false" anywhere in this file.
    //
    // Written down because the property is accidental: this line was added to scroll a tall
    // message, and swapping it for ArrowKeyMovementMethod - an ordinary fix for "tapping cannot
    // place the cursor" - would reopen both paths silently. NoTextIsSelectableOutOfTheStripTest
    // pins it.
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
      // Focus loss is not the user saying "type into the host now". Four things are, and they
      // lower it explicitly: a send, the keyboard being dismissed, the password-field guard, and
      // leaving the add-contact screen with an empty compose box. See
      // theloweringSitesAreAllAccountedForTest, which fails if a fifth appears without being
      // written down - three comments in this file once said "two", and that is why nobody asked
      // what the add-contact one did to a draft still on screen.
      changeVisibilityInputFieldButtons(hasFocus);
    });

    mClearUserInputButton.setVisibility(GONE);
    mSelectEncodingFairyTaleButton.setVisibility(GONE);
  }

  private void setupFirstNameInputEditTextField() {
    mAddContactFirstNameInputEditText.setMovementMethod(new ScrollingMovementMethod());
    mAddContactFirstNameInputEditText.setOnFocusChangeListener((v, hasFocus) -> {
      // Not `hasFocus && refused(...)`: that puts a writer where Java may skip it, and
      // widening the left term would then delete the call rather than tighten the guard.
      if (hasFocus) {
        if (refusedToComposeOverApasswordField(mAddContactFirstNameInputEditText)) return;
      }
      if (hasFocus) mRichInputConnection.setOtherIC(mAddContactFirstNameInputEditText);
      // Raised on focus, and NOT lowered on blur - the asymmetry the compose box's listener argues
      // for twelve lines up, applied to the fields that hold a correspondent's name.
      //
      // Lowering on blur means focus loss hands typing to the messenger's own field, and focus loss
      // is not the user saying "type into the host now". These two fields had the shape the compose
      // box's comment calls the app's central promise broken; no path was found that drives it
      // today, which is why it is written down as an invariant rather than as a fixed exploit.
      //
      // What makes not lowering safe is that leaving this screen deals with the redirect:
      // showOnlyUIView hands it back when the add-contact view goes away, so it cannot be left up
      // pointing at a hidden field - which would be a total functional break rather than a leak,
      // and is the trap in copying the compose box's asymmetry without its lowering paths. It
      // hands it back only when there is nothing in the compose box; with a draft there it is
      // re-pointed at the box instead, which is the same guarantee reached the other way.
      if (hasFocus) mRichInputConnection.setShouldUseOtherIC(true);
    });
  }

  private void setupLastNameInputEditTextField() {
    mAddContactLastNameInputEditText.setMovementMethod(new ScrollingMovementMethod());
    mAddContactLastNameInputEditText.setOnFocusChangeListener((v, hasFocus) -> {
      // Not `hasFocus && refused(...)`: that puts a writer where Java may skip it, and
      // widening the left term would then delete the call rather than tighten the guard.
      if (hasFocus) {
        if (refusedToComposeOverApasswordField(mAddContactLastNameInputEditText)) return;
      }
      if (hasFocus) mRichInputConnection.setOtherIC(mAddContactLastNameInputEditText);
      // Same asymmetry as its sibling above, for the same reason.
      if (hasFocus) mRichInputConnection.setShouldUseOtherIC(true);
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
      // Through the composer. Note chosenContact is usually null here - the Invite button on the
      // contact list is the flow for a NEW contact - and a null address is what clearCautionIfAbout
      // reads as "about anyone". The composer keeps that honest by never composing onto somebody
      // else's caution when there is no address to compare.
      // The accurate sentence for the state that produces this permanently. INFO_INVITE_NOT_SAVED
      // says "free up space or unlock the device, then make a new one", which is right for a
      // transient failure and is an instruction that can never be followed when the contact list
      // cannot be read - there, every write is refused for as long as that holds.
      postStorageCaution(mE2EEStrip.contactsAreUnreadable()
          ? INFO_CONTACTS_UNREADABLE : INFO_INVITE_NOT_SAVED, chosenContact);
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
      final Contact previousRecipient = chosenContact;
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
        warnIfThisKeyIsPinnedElsewhere(sender);
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
        // Nothing decrypted, so give the recipient back - to what it WAS, through the one helper
        // all three arms use. Nulling it is a further change rather than an undo, and it empties
        // the compose box on the way past.
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
        giveTheRecipientBack(previousRecipient);
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
      final Contact previousRecipient = chosenContact;
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
      warnIfThisKeyIsPinnedElsewhere(sender);
      if (reportIfTheRotationWasNotSaved()) return;
      // Only if there is actually a session. Otherwise the refusal notice written above stands,
      // instead of being painted over by a line saying the contact was detected.
      if (usable) {
        setInfoUnlessWarned("Detected contact: " + labelFor(chosenContact));
      } else {
        // Nothing came of it, so give the recipient back - the same undo the plain-message arm
        // performs, for the same reason and against the same input.
        //
        // Back to what it WAS, not to nobody. Nulling it destroys the user's draft through
        // setChosenContact's recipient-changed path, and a tampered re-invite relayed from the very
        // contact they are writing to is a cheap way to make that happen on demand. Restoring the
        // previous recipient is a no-op when this envelope did not move it, which is exactly the
        // case where there is nothing to undo.
        //
        // The address that moved the recipient is an unsigned header the relay copies out of any
        // envelope that contact ever sent, and which arm handles an envelope is decided by field
        // presence alone. So this asymmetry cost one appended field: staple any bundle - including
        // one built to be refused - to any ciphertext, and the recipient moves on an envelope where
        // nothing was accepted and nothing decrypted, on an arm that did not undo it.
        giveTheRecipientBack(previousRecipient);
      }
    }
  }

  /**
   * The one sentence that fits a relabelled invite, and the state it names.
   *
   * <p>Says what happened rather than what to do about the messenger, because there is nothing the
   * user can do about the messenger. It names the other contact so the two rows can be told apart,
   * and it does not tell them to delete anything: which row is the one they meant is a question only
   * they can answer, and the app guessing would delete a conversation.
   */
  private final String INFO_SAME_KEY_AT_ANOTHER_ADDRESS = "Careful: the key saved for %s is "
      + "the same key already saved for %s. One person's key belongs to one address, and even "
      + "reinstalling gives them a new one - so these two entries did not both come from them. "
      + "Comparing security numbers will NOT tell them apart, because both show the same number. "
      + "Ask them whether they sent you two invites: if they sent one, something in between made the other. Do not send anything private to either until you have agreed a fresh invite with them by some other channel.";

  /**
   * Whether a detected substitution at this address has already claimed the banner this pass.
   *
   * <p>The three decrypt arms write their warnings in ASCENDING severity - the identity change
   * first, from inside the decrypt, then the rejection, then the same-key notice - and
   * {@code setWarningMessage} is last-writer-wins. {@code selectContact} states the opposite rank in
   * forty lines of comment, ending "a detected key substitution outranks everything and is written
   * last".
   *
   * <p>Displacement is normally survivable here, and the no-yield trade rests on it: everything is
   * re-derived on the next selection, so a displaced warning comes back. This file's own rule says
   * where that stops - <em>recomputable and never rendered is not a displacement, it is a permanent
   * loss</em> - and on these arms the displacer runs in the SAME pass, unconditionally, before
   * anything is drawn. Measured: reject, let the next bundle re-pin by trust-on-first-use, then post
   * a substitution, and the banner ends the pass saying the key was rejected rather than that a
   * different key was just offered and refused.
   *
   * <p>Asked of the STORE, never of {@code mStandingWarningText} or {@code mWarningStanding}.
   * Yielding to "something is standing" was tried and reverted because it reopens an eviction: an
   * attacker raises a cheap warning about a DIFFERENT contact and the yielding warning never returns.
   * A store fact about THIS address cannot be raised by anyone else, so deferring to it is a rank,
   * not a yield.
   *
   * <p><b>Only one caller may use this, and only because {@code selectContact} re-derives it.</b>
   * The first version of this javadoc said "both deferring warnings are re-derived by
   * {@code selectContact}" and that was false: it re-derives the shared name, the refused invite,
   * the rejection and the identity change, and {@code warnIfThisKeyIsPinnedElsewhere} is the fifth
   * warning on this surface and is not among them. Deferring a warning nothing re-derives deletes it
   * for as long as the condition stands, and a pending identity change stands for as long as the
   * messenger keeps stapling bundles. Before adding a second caller, check that
   * {@code selectContact} raises it.
   */
  private boolean asubstitutionAtThisAddressOutranksThis(final Contact sender) {
    return sender != null
        && SignalProtocolMain.hasUnacceptedIdentityChange(sender.getSignalProtocolAddress());
  }

  /**
   * Warns when an arriving key is already pinned at a different address.
   *
   * <p>The check every other control on this path is blind to. A relay can re-deliver a genuine,
   * correctly signed invite under an address of its own choosing — the sender name and device id are
   * written outside the bundle signature, so nothing is forged and the signature still verifies —
   * and what lands is a second contact holding the peer's real identity key. The safety number is a
   * function of the two identity keys, so that row shows the SAME digits as the real one: the user
   * reads them aloud, the peer confirms them, and the comparison this whole design rests on endorses
   * a row the messenger created. Measured, both halves, in
   * {@code ArelayCanMintExtraRowsForAgenuinePeerTest}.
   *
   * <p>Warns rather than refuses, deliberately. Refusing an invite whose key is already pinned
   * elsewhere hands the messenger an eviction: one relayed copy at an address it invents would burn
   * a real contact's key, and every genuine invite from that peer afterwards would be refused, with
   * the app's own advice to ask for another looping forever. This file has already paid for one
   * refusal that locked the keyboard.
   *
   * <p>Safe to say because it cannot fire on an honest peer: {@code initializeProtocol} mints a
   * fresh identity key with every new address, so even a reinstall produces a different key. That
   * property is asserted rather than assumed, in the same test.
   */
  private boolean warnIfThisKeyIsPinnedElsewhere(final Contact sender) {
    if (sender == null) return false;
    final java.util.List<org.signal.libsignal.protocol.SignalProtocolAddress> elsewhere =
        SignalProtocolMain.addressesAlreadyPinningTheSameKey(sender.getSignalProtocolAddress());
    if (elsewhere.isEmpty()) return false;
    // Deliberately does NOT defer to a pending identity change, though its sibling does.
    //
    // Deferring is only survivable for a warning something re-derives. selectContact re-derives four
    // - shared name, refused invite, rejection, identity change - and this is the fifth and is not
    // among them; all five of its call sites are arrival paths. So a deferral here is not a
    // displacement, it is a deletion for as long as the pending change stands, and the messenger
    // chooses how long that is: one stapled bundle per relayed message keeps a change pending
    // indefinitely, and an unchanged key returns from saveIdentity before pendingIdentities is
    // cleared, so ordinary traffic never ends it.
    //
    // The rank is the other way round here on the merits, which is why this is not simply a missing
    // re-derivation. A pending change is fail-CLOSED: the offered key was refused, the pin stands,
    // and the sentence says so. One key at two addresses is fail-OPEN: the pin is live, it is the
    // peer's real key, and the safety number is defeated by construction. Worse, the substitution
    // sentence tells the user to compare the number by voice - which SUCCEEDS for a relay-minted
    // row - and that road ends at Verify, which green-badges the relay's row and clears everything.
    // The sentence forbidding exactly that is the one a deferral here would suppress.

    // Named from the contact list if a row is there, and described plainly if not. A pin can
    // outlive its row - deleting a contact keeps the key on purpose - so "another contact" is the
    // honest wording for a key pinned at an address no row currently holds.
    final String elsewhereAddress = String.valueOf(elsewhere.get(0));
    String otherLabel = "another entry you already have";
    final java.util.ArrayList<Contact> contacts = mE2EEStrip.getContacts();
    if (contacts != null) {
      for (final Contact candidate : contacts) {
        if (candidate != null
            && elsewhereAddress.equals(String.valueOf(candidate.getSignalProtocolAddress()))) {
          otherLabel = labelFor(candidate);
          break;
        }
      }
    }
    final String warning =
        String.format(INFO_SAME_KEY_AT_ANOTHER_ADDRESS, labelFor(sender), otherLabel);
    Toast.makeText(getContext(), warning, Toast.LENGTH_LONG).show();
    setWarningMessage(warning, String.valueOf(sender.getSignalProtocolAddress()));
    return true;
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
    // Reported as raised, because the caller's question is "was this contact warned about", and it
    // was - by the higher-ranked sentence, in this same pass.
    if (asubstitutionAtThisAddressOutranksThis(sender)) return true;
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
    // Composed with the pin caution when that is what is standing, rather than replacing it.
    //
    // Both are true of the same paste and there is one caution slot. Replacing lost the more
    // important half: a messenger-supplied key had just been pinned by trust-on-first-use, and the
    // ONLY sentence saying so was overwritten by a storage message whose advice is "nothing here
    // needs deleting or re-inviting, wait until the device has free space" - which does not contain
    // "compare the security number", the whole point of the caution.
    //
    // addContact solved this collision by appending and argued the storage sentence should win
    // because its advice contains the other's. That containment is not true here: this storage
    // sentence says nothing about comparing a number, and the thing being lost is the notice that
    // fires because nothing was noticed. So the pin caution is kept and the storage one appended.
    final String storageNotice = String.format(INFO_SESSION_NOT_SAVED, labelFor(chosenContact));

    // Through the shared composer, which is where this reasoning now lives - three call sites
    // had solved the same collision three different ways.
    postStorageCaution(storageNotice, chosenContact);
    return true;
  }

  private void processUpdatedPreKeyResponse(MessageEnvelope messageEnvelope, Contact sender) {
    // debug only Toast.makeText(getContext(), "Updated signed pre key detected!", Toast.LENGTH_SHORT).show();
    if (sender == null) {
      // contact was not added before -> proceed as normal preKeyMessage
      processPreKeyResponse(messageEnvelope, sender);
    } else {
      // update contact with preKey information
      final Contact previousRecipient = chosenContact;
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
      warnIfThisKeyIsPinnedElsewhere(sender);
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
      } else if (!mLastDecryptShowedAmessage) {
        // Neither half of the envelope was good for anything, so the recipient goes back. Asked of
        // both halves because this arm carries both: a rotation that landed is a reason to keep the
        // recipient even when the accompanying message was a replay, and a message that decrypted
        // is a reason to keep it even when the bundle was refused.
        //
        // The second half is asked of the FACT, not of the decrypt method's return value. That
        // value collapses to false whenever the bundle was refused - its refusal branch displays
        // the plaintext and then returns false - so reading it here wiped the message the user had
        // just been shown, on the envelope shape that is ordinary for a signed-pre-key rotation.
        giveTheRecipientBack(previousRecipient);
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
    if (mStandingCaution != null || mStandingStorageCaution != null
        || mStandingStoreNotice != null) {
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
  /**
   * Whether the last decrypt attempt put a message on screen.
   *
   * <p>A fact rather than an inference, because the inference was wrong: the method's return value
   * means "was this envelope wholly good", and its refusal branch displays the plaintext and then
   * returns false. Per-operation, cleared at the top of every attempt, and deliberately not carried
   * across a rebuild - it describes one press.
   */
  private boolean mLastDecryptShowedAmessage = false;

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
  /**
   * The caution about a KEY: a pin the app cannot attribute, and the instruction to compare.
   *
   * <p>Split from the storage caution below because the two are independent facts about one contact
   * and sharing a slot produced four separate defects in as many rounds: a composer that could not
   * compose onto its own output and therefore dropped the pin half on the second storage failure; a
   * retirement that dropped the pin half on the strength of a comment claiming it would be re-posted,
   * which it never is; one call site that never reached the composer at all and simply overwrote;
   * and an address-less storage notice that re-scoped the whole thing.
   *
   * <p>Every one of those was a merge hand-rolled at a call site. Two fields need no merge.
   */
  private String mStandingCaution = null;

  /** The caution about STORAGE for the same contact: a write that did not land. */
  private String mStandingStorageCaution = null;

  /** The address that storage caution is about, scoped like every other item here. */
  private String mStandingStorageCautionAddress = null;

  /**
   * What kind of storage caution is standing, because they are not all settled by the same thing.
   *
   * <p>Most of this family report a write that did not land and are ended by a later one that does,
   * or by retrying the operation. Two are not like that, and each is ended by exactly one event:
   *
   * <ul>
   *   <li>{@code DELETION_DID_NOT_HAPPEN} - the contact, their pinned key and their plaintext are
   *       all still on disk and come back at the next raise. Ended by a deletion that lands.</li>
   * </ul>
   *
   * <p>A failed REJECTION looked like a second one of those and is not, which is worth writing down
   * because getting it wrong produced two defects at once. {@code removeContact} rolls the row, its
   * messages and its session back into memory when the write fails, so a later write persists the
   * <em>restored</em> contact and nothing a later write does completes the deletion.
   * {@code rejectContactKey} has no rollback: the identity is removed, the address marked and the
   * session deleted in memory, and only the write failed - so the state the user asked for is
   * already there and the next landed account write puts it on disk. Measured in
   * {@code ArejectionThatDidNotLandIsForgottenTest}. Classifying it as protected made it collide
   * with the deletion notice in the one slot, and stranded it: rejecting removes the pin, so that
   * contact's verify screen has no number and both its buttons go dark, and "reject again" - the
   * exit the protection required - could never be reached.
   *
   * <p>A boolean was enough while only the deletion needed protecting. It stopped being enough the
   * moment a second one did: with one flag, the escape that lets a landed deletion end its own
   * notice would also let a landed REJECTION end it, and a landed rejection says nothing about
   * whether a contact was removed.
   */
  private StorageCautionKind mStandingStorageCautionKind = StorageCautionKind.SETTLED_BY_ALATER_WRITE;

  /**
   * The account-write count when the standing storage caution went up.
   *
   * <p>An ordinary storage caution says a write did not land, and a later one that does settles it.
   * Until now the only thing that acted on that was the refusal sweep, which retires a caution only
   * for an address that also has a {@code mContactsNotOnDisk} entry - so a caution raised without
   * one could be ended only by acting on the contact it names. That is a dead end whenever those
   * controls are unavailable, and after a failed rejection they are: the pin is gone from memory,
   * so the verify screen has no number and both its buttons are dark.
   *
   * <p>Starts below every real count, so a fresh strip does not retire a caution it has just
   * adopted; carried for the same reason the store notice's count is.
   */
  private long mAccountWritesLandedWhenStorageCautionRaised = -1;

  /** See {@code mStandingStorageCautionKind}. */
  enum StorageCautionKind {
    /** The ordinary case: a write that did not land, ended by a later one that does. */
    SETTLED_BY_ALATER_WRITE,
    /** A deletion that did not happen. Ended only by a deletion that lands. */
    DELETION_DID_NOT_HAPPEN;

    boolean isSettledByAnyLaterWrite() {
      return this == SETTLED_BY_ALATER_WRITE;
    }
  }

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
    if (mStandingWarningText == null && mStandingCaution == null
        && mStandingStorageCaution == null && mStandingStoreNotice == null) {
      return null;
    }
    final StringBuilder body = new StringBuilder();
    if (mStandingWarningText != null) body.append(mStandingWarningText);
    if (mStandingCaution != null) {
      if (body.length() > 0) body.append("\n\n");
      body.append(mStandingCaution);
    }
    // After the key caution and before the store notice: a storage failure about this contact is
    // less urgent than an unattributable key and more specific than a notice about the whole store.
    if (mStandingStorageCaution != null) {
      if (body.length() > 0) body.append("\n\n");
      body.append(mStandingStorageCaution);
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
  /**
   * The one storage failure the app could not describe.
   *
   * <p>When only the CONTACTS value fails to open, everything else reads fine and
   * {@code storageState()} - which trial-decrypts the protocol store - reports READABLE. The strip
   * then showed an empty contact list under "invite someone", byte-identical to a fresh install,
   * which is exactly the reading the storage warning exists to prevent. Meanwhile every write is
   * refused, so the user could never produce an invite again and was told to free up space, which
   * will never help.
   *
   * <p>Said plainly instead. It is a warning rather than an informational line because it must not
   * be painted over by ordinary clipboard traffic, and because the response it has to head off -
   * re-inviting everyone into an apparently empty app - is the same one the storage warning heads
   * off.
   */
  private final String INFO_CONTACTS_UNREADABLE = "Your contacts are still on this device and this app cannot open them right now. It is not saving anything until it can, so that it does not replace them with an empty list. This is not an empty app: do NOT re-invite anyone, because re-inviting replaces keys you have already checked. Freeing up space will not help - this clears when the device can read its own storage again, usually after an unlock or a restart.";

  /**
   * Whether the banner is holding one of the two warnings raised from a CONDITION.
   *
   * <p>One half of {@code theStoreMustBeRereadOnThisRaise}, which is what the keyboard raise asks.
   * It used to be the whole of it, and the other half is there because this one can be overwritten
   * by any other warning. These two are still the only warnings with no other way down. A review round found that the lowering path
   * inside {@code refreshOpeningMessage} could not execute in production at all: its only callers
   * are {@code setInputView} on a freshly inflated strip and {@code adoptState} guarded on the same
   * flag, and {@code setInputView} runs once per process unless the theme changes. So a keyboard
   * that started while the device was locked kept "this clears when the device can read its own
   * storage again" on screen after the unlock, held {@code mWarningStanding} so every other notice
   * was suppressed, and left Encrypt and Decrypt dark on an install whose storage works — with no
   * user action that clears it, and on the contacts arm no exit at all, since the contact list the
   * suggested Verify or Reject would act on is precisely what cannot be read.
   *
   * <p>The re-derivation is gated on this rather than run unconditionally, for two reasons. A
   * refresh with no warning standing repaints the opening banner, which would wipe whatever the
   * strip is currently saying on every raise. And re-asking the question means re-reading the
   * store, which is worth paying for exactly when the answer on file is "it could not be read".
   */
  public boolean hasStandingConditionWarning() {
    return mWarningStanding && isAconditionWarning(mStandingWarningText);
  }

  private boolean isAconditionWarning(final String text) {
    return INFO_STORAGE_UNREADABLE.equals(text) || INFO_CONTACTS_UNREADABLE.equals(text);
  }

  /**
   * Whether the keyboard raise must re-read the store. Asked by
   * {@code LatinIME.onStartInputViewInternal} on every raise.
   *
   * <p>This used to BE {@code hasStandingConditionWarning()}, and that was a control on a slot the
   * adversary can overwrite. The banner holds one warning; any other writer takes it - a relayed
   * message offering a different key, a shared name, a refused invite - and after that the question
   * was answered by asking what the banner happened to be saying, which was no longer about the
   * store. The re-read stopped for the life of the process.
   *
   * <p>What that cost was not cosmetic. The account carrying {@code contactsWereUnreadable} is
   * replaced only by this re-read, and {@code StorageHelper} refuses every write while it is
   * carried. So a messenger that could hold the fault open and then take the banner left the
   * keyboard looking entirely healthy and persisting nothing - contacts, pins, verifications,
   * rejections and the chat log all in RAM until the process died - with the sentence explaining it
   * removed by the party who benefits from removing it. The device being unlocked did not help,
   * which is precisely what that sentence promised would happen.
   *
   * <p>So it asks the fact as well. {@code contactsAreUnreadable()} is a field read on the account
   * in hand, not a probe: no store read, no Keystore work, nothing to pay for on a healthy raise.
   *
   * <p>Both arms are asked, not just the one that latches. {@code storageIsUnreadable()} does cost a
   * trial decryption - but {@code refreshActionButtons} already asks it on ordinary clipboard
   * traffic, so this is not a new class of cost. On a healthy install both facts are false and the
   * raise pays one field read and one AES-GCM open against the helper's cached store; on an
   * unreadable store the re-read already ran on every raise, because nothing displaces that arm's
   * banner in practice.
   *
   * <p>Not "the store is cached, so the second read is free" - which is what this said, and it is
   * false in exactly the case the gate is for. When the gate answers yes,
   * {@code reloadAccountIfStorageRecovered} begins by rebuilding the {@code StorageHelper}, and
   * {@code mSecureStore} is a per-helper field: the {@code storageState()} inside
   * {@code refreshOpeningMessage} afterwards therefore reconstructs the store, CryptoBox and all.
   * That is deliberate rather than wasteful - a stale secure store is what would make a recovered
   * store keep reporting UNREADABLE - and it is measured at one crypto box per raise by
   * {@code AraiseNeverAuthorisesAfreshMasterKeyTest}.
   */
  public boolean theStoreMustBeRereadOnThisRaise() {
    return hasStandingConditionWarning() || mE2EEStrip.contactsAreUnreadable()
        || storageIsUnreadable();
  }

  /**
   * Which warning wins the slot while a store fault stands, and why it is this one.
   *
   * <p>The per-raise re-read now fires on the FACT, so this method runs on every raise while a
   * fault stands - including while a key-substitution warning is on screen, which it then paints
   * over, again on the next raise, at moments the messenger picks by presenting a field. That was
   * examined and a yield was written for it, then reverted, because the yield is worse:
   *
   * <ul>
   *   <li>During a contacts-unreadable fault the contact list <em>appears empty</em>. This sentence
   *       is the only thing standing between the user and re-inviting everybody, which discards
   *       every pin they have already checked and reopens trust-on-first-use for all of them. It
   *       has no other way onto the screen.</li>
   *   <li>And it cannot be recovered by anything the user does while the fault stands: Verify and
   *       Reject clear a standing warning only once the response reaches disk, which is exactly
   *       what the fault refuses. So a yield hides it until the device is unlocked - and unlocking
   *       is the thing the user does not know to do, because the sentence saying so is hidden.</li>
   *   <li>Whereas the warnings it displaces are, with one exception, recomputable - the property
   *       this file relies on everywhere else. {@code selectContact} re-derives exactly three:
   *       {@code warnIfNameIsShared}, {@code warnIfKeyWasRejected} and
   *       {@code warnIfIdentityChanged} - four now, with {@code warnIfLastInviteWasRefused} - and
   *       the identity change also re-raises on every decrypt from that sender. All three are actions the warnings' own text asks for. An eviction the subject
   *       re-derives is a displacement.</li>
   * </ul>
   *
   * <p><b>There was a fourth warning, and it was the exception.</b> The invite-refusal warning was
   * re-derived by nothing: no per-address record said the last attached bundle had been refused, so
   * a raise during a fault repainted over "that invite was changed on the way here" and left only a
   * 3.5-second toast - the silence the refusal warning exists to buy back from a relay that pays
   * one stripped unsigned byte per message. It is recomputable now, from {@code mRefusedInvites},
   * and {@code selectContact} raises it second of four. Made recomputable rather than given a
   * yield, because yielding is what reopens the re-invite hazard above.
   *
   * <p>So the ordering is deliberate and the cost is stated rather than hidden: while a fault
   * stands, a key-substitution warning is repainted away on raises the messenger can trigger, and
   * the user gets it back by tapping the contact. Pinned by
   * {@code AstorageFaultOutlivesTheSentenceThatDescribesItTest}.
   */
  public void refreshOpeningMessage() {
    if (mInfoTextView == null) return;
    // Asked first, because it is invisible to storageState(): the protocol store opens, so that
    // reports READABLE while the contact list is unreadable and every write is refused.
    if (mE2EEStrip.contactsAreUnreadable()) {
      setWarningMessage(INFO_CONTACTS_UNREADABLE);
      return;
    }
    final String opening = openingMessage(SignalProtocolMain.storageState());
    if (INFO_STORAGE_UNREADABLE.equals(opening)) {
      setWarningMessage(opening);
      return;
    }

    // Lowered when the condition goes away, which nothing did.
    //
    // These two are the only warnings raised from a condition rather than from an event, and they
    // were raised and never taken down: when control reached the branch below, a standing warning
    // meant "leave the banner alone", so the sentence stayed after storage recovered. Every clause
    // of it was then false, including the one describing its own exit - "this clears when the
    // device can read its own storage again" - and it held mWarningStanding, so every informational
    // line was suppressed for the life of the process. The only way out was pressing Verify or
    // Reject on some contact, a security gesture performed for a cosmetic reason, which this file
    // calls a false affordance everywhere else.
    //
    // Only these two are lowered here, and only when they are the text standing: every other
    // warning is about an event that happened, and an event does not stop having happened.
    if (mWarningStanding && (INFO_STORAGE_UNREADABLE.equals(mStandingWarningText)
        || INFO_CONTACTS_UNREADABLE.equals(mStandingWarningText))) {
      clearStandingWarning();
    }

    if (!mWarningStanding) {
      // Through the composed banner, like every other writer in this file.
      //
      // This line wrote the opening string RAW, and it was the only banner writer that did not ask
      // aStandingItemHoldsTheBanner() first. That was harmless while the only callers were a
      // freshly inflated strip and adoptState - which restores the cautions AFTER calling this and
      // repaints - and stopped being harmless the moment a keyboard raise could reach it on a live
      // strip. The lowering it performs one branch above is exactly when a strip holds cautions:
      // the compare-the-number caution for a key just pinned, "do not send them anything" for a row
      // that never reached disk, the notice about plaintext owned by no row. All three would have
      // been wiped off the only durable surface the app has while their fields stayed set, so
      // nothing would ever have painted them again - the erasure this file's own warning machinery
      // exists to prevent, performed by the fix for a different one.
      //
      // And over a password field the strip has something more specific to say than the opening
      // line. setInfoUnlessWarned refuses to write it while a warning stands, which is correct; but
      // then this method takes that warning down, and writing "No contact chosen" over a password
      // field loses the notice with no later transition to bring it back.
      if (aStandingItemHoldsTheBanner()) {
        setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
      } else if (mHostFieldIsPassword) {
        setInfoTextViewMessage(mInfoTextView, INFO_PASSWORD_FIELD);
      } else {
        // Through the chooser that knows about the recipient, not the opening constant.
        //
        // Writing the opening line raw says "No contact chosen" on a strip that HAS one - and
        // disablesActionButtons matches that sentence by prefix, so it also darkens Encrypt and
        // Decrypt on a working install. A keyboard raise reaches this now, and a raise arrives with
        // the recipient still set: the window is still up, so onKeyboardHidden has not run. Three
        // separate defects in this file were this same sentence painted over a live state; the
        // enumeration written last round covered two of the three.
        showChosenContactInMainInfoField();
      }
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
  /**
   * Undoes a recipient this envelope moved, without punishing the user for the envelope.
   *
   * <p>Nulling it instead was the first shape, and it destroys the user's draft: {@code
   * setChosenContact} empties the compose box whenever the recipient changes, so a tampered
   * envelope relayed from the contact they are currently writing to costs them what they typed,
   * on demand and repeatably. Restoring what was there is a no-op when this envelope moved nothing.
   */
  private void giveTheRecipientBack(final Contact previous) {
    if (previous == null) {
      forgetChosenRecipient();
      return;
    }
    setChosenContact(previous);
  }

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
   *
   * <p><b>The clipboard listener is deliberately NOT released here</b>, and this is a different list
   * from {@code surrenderState}'s for that reason. A reviewer raised it as a gap: with the keyboard
   * down and the user in an unrelated app, the strip still runs {@code EnvelopeCodec.fromWire} over
   * every clip they copy anywhere on the device, so a password copied out of a manager passes
   * through the decoder with this app's UI nowhere in sight.
   *
   * <p>Releasing it here would break the workflow the app is built around. The listener is what
   * raises "Keybundle detected: click on decrypt", and the sequence is copy in the messenger and
   * <em>then</em> raise the keyboard - so a listener that is down while hidden misses exactly the
   * copy that matters, and the banner never appears. What the listener does with a clip is also
   * narrow: it passes a {@code MessageType} on, retains no text, and the decoder it runs is the one
   * four fuzz corpora already cover, in a process with no permission to send anything anywhere.
   *
   * <p>So the trade is taken deliberately and written down rather than left to be rediscovered. The
   * lifetime that matters is the strip's, not the window's: {@code surrenderState} and
   * {@code clear()} both release it, which is what stopped a discarded strip decoding clipboard
   * traffic for the life of the process.
   */
  public void onKeyboardHidden() {
    // The user is done with this keyboard session, so the next one types into the host until they
    // choose the compose box again. This is one of four deliberate lowerings - the others are the
    // send path, the password-field guard, and leaving the add-contact screen with nothing in the
    // compose box. Three comments in this file used to say there were two or three, and that
    // enumeration is why nobody asked what the add-contact one did to a draft;
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

  /** The standing store notice, for tests that ask whether it survived something. */
  String storeNoticeForTest() {
    return mStandingStoreNotice;
  }

  /** Posts the store notice, for tests that need the third standing item up. */
  void setStoreNoticeForTest(final String notice) {
    mStandingStoreNotice = notice;
    mLogWritesLandedWhenNoticeRaised = mE2EEStrip.messageLogWritesLanded();
    mAccountReloadsWhenNoticeRaised = SignalProtocolMain.accountReloads();
    setInfoTextViewMessage(mInfoTextView, warningWithRecipient());
  }

  /** Posts a caution, for tests that need one standing without a warning beside it. */
  void setCautionForTest(final String caution, final Contact about) {
    setCautionBesideAnyWarning(caution, about);
  }

  /**
   * Posts an ordinary storage caution through the real writer, for tests.
   *
   * <p>The two-argument overload specifically, which is what every arm except the failed deletion
   * uses - the one that means "a write did not land", is settled by a later one that does, and used
   * to replace the deletion notice.
   */
  void setStorageCautionForTest(final String caution, final Contact about) {
    postStorageCaution(caution, about);
  }

  /**
   * Records a refused invite through the real writer, for tests.
   *
   * <p>The production route is a paste plus a Decrypt press on an envelope whose bundle fails its
   * issuing signature; this is that fact, without rebuilding the envelope.
   */
  void rememberRefusedInviteForTest(final Contact about, final String message) {
    rememberRefusedInvite(String.valueOf(about.getSignalProtocolAddress()), message);
  }

  /**
   * Moves the recipient the way the decrypt path does, for tests.
   *
   * <p>Distinct from {@code selectContact}, and the distinction is the point: a tap re-derives all
   * four warnings, while a recipient moved by an arriving message re-derives only the shared-name
   * one. Warnings that are only correct after the four-writer pass are wrong on this route.
   */
  void moveRecipientTheWayAmessageDoesForTest(final Contact contact) {
    setChosenContact(contact);
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

  /**
   * Shows a caution that must not be dropped, without taking down a warning that must not be lost.
   *
   * <p>The banner is the only surface either has, so when both exist they share it. The standing
   * warning's flag, text and address are untouched — only what is painted changes — so every
   * deliberate response that clears it still works, and {@code warningWithRecipient} still rebuilds
   * from the warning alone rather than from what is on screen.
   */
  private void setCautionBesideAnyWarning(final String caution, final Contact about) {

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
    // A reload voids the rule rather than satisfying it.
    //
    // Everything below rests on the in-memory log being the pruned one. reloadAccount replaces the
    // account with the stored copy, whose log still holds the entries the failed write never
    // removed - so after one, a landed write persists the orphaned plaintext instead of erasing it,
    // and retiring here would take the notice down at the exact moment the leak became permanent.
    //
    // Asked with a counter rather than by scanning the log, deliberately. The only honest scan is
    // "does the log still hold entries for that address", and the log after a reload is DEFERRED -
    // so the scan would force a load from refreshActionButtons, which runs on essentially every
    // repaint. That load throws an unchecked re-entrancy guard the chat-log screen does not catch.
    // The counter answers the same question without reading anything.
    if (SignalProtocolMain.accountReloads() > mAccountReloadsWhenNoticeRaised) return;
    if (mE2EEStrip.messageLogWritesLanded() > mLogWritesLandedWhenNoticeRaised) {
      mStandingStoreNotice = null;
      mLogWritesLandedWhenNoticeRaised = -1;
      mAccountReloadsWhenNoticeRaised = -1;
      setInfoTextViewMessage(mInfoTextView,
          aStandingItemHoldsTheBanner() ? warningWithRecipient() : INFO_NO_CONTACT_CHOSEN);
    }
  }

  private boolean aStandingItemHoldsTheBanner() {
    // The store notice counts: it holds the banner exactly as the other two do, and leaving it out
    // meant every routine informational line painted straight over it.
    return mWarningStanding || mStandingCaution != null || mStandingStorageCaution != null
        || mStandingStoreNotice != null;
  }

  private void clearCautionIfAbout(final Contact contact) {
    clearStorageCautionIfAbout(contact);
    if (mStandingCaution == null) return;
    if (mStandingCautionAddress == null || (contact != null && mStandingCautionAddress
        .equals(String.valueOf(contact.getSignalProtocolAddress())))) {
      mStandingCaution = null;
      mStandingCautionAddress = null;
    }
  }

  /**
   * Re-raises the duplicate-name warning while two rows still share a rendered name.
   *
   * <p>Recomputed rather than remembered. The condition is a fact about the contact list, so asking
   * it is always possible, and a warning that can be asked again cannot be evicted for good by an
   * attacker who raises a cheaper one to take the slot.
   *
   * <p>Scoped to this contact's address, so deleting either of the two rows puts it down - which is
   * the resolution the warning is asking for, and the reason the original was addressed.
   */
  /**
   * Whether the banner is holding one of the two shared-name warnings.
   *
   * <p>Derived from the sentences themselves rather than from copies of their opening words. The
   * copies were recorded as fragility by a review round and then needed a second reader, which is
   * the moment a duplicated literal becomes a real defect rather than an aesthetic one: two readers
   * disagreeing about which warning is standing is how a warning gets silenced by the wrong rule.
   */
  private boolean standingWarningIsAboutAsharedName() {
    if (mStandingWarningText == null) return false;
    return mStandingWarningText.startsWith(literalPrefixOf(INFO_DUPLICATE_CONTACT_NAME))
        // Byte-identical to the clause above it, and kept deliberately.
        //
        // The same-key wording opens with the same words as the plain duplicate one, so
        // literalPrefixOf truncates both to "You already have a contact called " and the first
        // clause already matched it. The claim originally written here - that the third wording
        // "has to be recognised or it is never lowered" - was false: it was recognised, by
        // accident. Every other reader that keys on this opening is carried by the same collision,
        // and none of them declares that it depends on it.
        //
        // Named here so the dependency is written down rather than inferred, and pinned by
        // AtherdWordingIsCarriedByAsharedOpeningTest: shortening either opening so the prefixes
        // differ silently drops this wording out of every prefix reader at once, and the failure
        // would appear as a warning that will not go down rather than as anything to do with text.
        || mStandingWarningText.startsWith(literalPrefixOf(INFO_DUPLICATE_NAME_SAME_KEY))
        || mStandingWarningText.startsWith(literalPrefixOf(INFO_RETIRED_CONTACT_NAME));
  }

  /** The fixed opening of a format string, up to its first placeholder. */
  private static String literalPrefixOf(final String template) {
    final int placeholder = template.indexOf('%');
    return placeholder < 0 ? template : template.substring(0, placeholder);
  }

  private boolean warnIfNameIsShared(final Contact contact) {
    if (contact == null) return false;
    // Both halves now, because both are resolvable.
    //
    // This asked the live half only, and the reasoning was right at the time: a warning may be
    // re-asserted on every selection only if the user can end it, and a retired name had no action
    // that ended it - nothing prunes the retired list, so re-raising it meant a sentence on every
    // send for the life of the install.
    //
    // The cost of leaving it out was the mirror image: raised once at add time and never
    // re-derived, so an attacker displaced it with any cheap warning, the user resolved that one,
    // and the impostor row was indistinguishable from a healthy contact from then on.
    //
    // Verifying is the resolution, and hasRetiredDisplayName now honours it. The warning's own text
    // says the app "cannot confirm that this is the same person coming back"; comparing the number
    // by voice is how the user confirms it. So the question can be asked in full, on every
    // selection, and it stops the moment the user does the thing it is asking for.
    if (!mE2EEStrip.hasContactWithSameDisplayName(contact.getFirstName(),
        contact.getLastName(), contact.getSignalProtocolAddress())) {
      // Lowered when the condition goes, which is the other half of being re-derivable.
      //
      // Raising from a condition and never lowering is a defect this file has now made twice: the
      // storage warnings kept asserting an unreadable store after it recovered, and this one kept
      // asserting a shared name after the user had resolved it by comparing the number. Recomputing
      // the answer is only useful if BOTH answers are acted on.
      //
      // Scoped to this contact's own address and to this warning's own text, so it cannot take down
      // an identity change or a rejection that happens to be standing about the same person.
      //
      // ...and a COMPOSED warning is reduced rather than cleared. Once the refusal began composing
      // itself onto this sentence, the composed string still starts with this one's opening words,
      // so this branch read it as "a shared-name warning" and took the refusal half down with it -
      // which a relayed message can trigger, because setChosenContact re-derives only this warning
      // when the recipient changes. That is the two-readers-disagree hazard
      // standingWarningIsAboutAsharedName's own javadoc names, arrived at by composing.
      //
      // Reducing is what the condition ending actually means: the fold is gone, the refusal is not.
      if (mWarningStanding
          && String.valueOf(contact.getSignalProtocolAddress()).equals(mStandingWarningAddress)
          && standingWarningIsAboutAsharedName()) {
        final String refusalAlone =
            mRefusedInvites.get(String.valueOf(contact.getSignalProtocolAddress()));
        if (refusalAlone != null) {
          setInviteRefusalWarning(refusalAlone,
              String.valueOf(contact.getSignalProtocolAddress()));
        } else {
          clearStandingWarning();
          setInfoTextViewMessage(mInfoTextView, aStandingItemHoldsTheBanner()
              ? warningWithRecipient() : "Chosen contact: " + labelFor(contact));
        }
      }
      return false;
    }
    // Deliberately does NOT yield to a warning standing about something else.
    //
    // Yielding was tried and reverted, and the test that caught it is the one written for the
    // eviction attack: an attacker raises any cheap warning about a different contact, and if this
    // one yields to it, the duplicate-name warning never comes back and the impostor row is
    // indistinguishable again. Yielding closes a displacement and reopens an eviction, which is
    // strictly worse.
    //
    // What makes last-writer-wins acceptable here is that ALMOST every warning on this surface is
    // RECOMPUTABLE: selectContact re-derives four - the shared name, the refused invite, the
    // rejection and the identity change - and the storage warnings are re-raised on every
    // setInputView. So a displaced warning returns the moment its own subject is looked at, and for
    // those, displacement is a momentary ordering question rather than a loss. That property is the
    // thing to protect, and it is what AwarningDisplacedIsAwarningThatComesBackTest pins.
    //
    // THE EXCEPTION, and it is load-bearing: warnIfThisKeyIsPinnedElsewhere is raised only on
    // arrival paths and re-derived nowhere. This sentence used to say "every warning", naming three
    // of the four - it predated the refusal being added, and predated the fifth warning entirely -
    // and a reader taking it at its word treats displacement of that one as survivable when it is
    // permanent. That is not hypothetical: a deferral was added to that raiser on exactly this
    // reasoning, which turned a displacement into a deletion the messenger could hold open
    // indefinitely, and it was caught by a review round rather than by anything here.
    //
    // It cannot simply be added to selectContact. Its condition reads the PINS, and a pin survives
    // contact deletion, so re-raising it on every selection is a banner with no action that ends it
    // - the dead end this file has paid for twice. Where the two rows share a display name the fact
    // is carried by INFO_DUPLICATE_NAME_SAME_KEY, which IS re-derived here; where they do not, the
    // fact is lost once displaced, and that gap is recorded rather than closed.
    final String duplicate = String.format(duplicateNameMessage(contact), labelFor(contact));
    setWarningMessage(duplicate, String.valueOf(contact.getSignalProtocolAddress()));
    return true;
  }

  /** The storage half of the same scoped clear. */
  private void clearStorageCautionIfAbout(final Contact contact) {
    clearStorageCautionIfAbout(contact, StorageCautionKind.SETTLED_BY_ALATER_WRITE);
  }

  /**
   * @param becauseThisLanded the operation that has just reached disk, from the arm that knows it
   *                          did. Only the kind that matches ends its own notice: a landed
   *                          rejection says nothing about whether a contact was removed.
   */
  private void clearStorageCautionIfAbout(final Contact contact,
      final StorageCautionKind becauseThisLanded) {
    if (mStandingStorageCaution == null) return;
    // The same rule retireTheStorageCautionFor already had, and this path did not.
    //
    // A caution about a deletion that did not happen is not settled by anything the other cautions
    // are settled by: the row, its pinned key and its plaintext are all still on disk, and the next
    // reload brings them back. Verifying or rejecting that contact is a plausible next move for
    // somebody who has just failed to delete them, and it cleared the sentence saying the deletion
    // had not happened - after which the screen reads like an ordinary success. Only a deletion
    // that lands ends it, which is the exit that keeps this from being the dead end this file has
    // closed twice.
    if (!mStandingStorageCautionKind.isSettledByAnyLaterWrite()
        && mStandingStorageCautionKind != becauseThisLanded) {
      return;
    }
    if (mStandingStorageCautionAddress == null || (contact != null && mStandingStorageCautionAddress
        .equals(String.valueOf(contact.getSignalProtocolAddress())))) {
      mStandingStorageCaution = null;
      mStandingStorageCautionAddress = null;
      mStandingStorageCautionKind = StorageCautionKind.SETTLED_BY_ALATER_WRITE;
    }
  }

  /** Posts the refused-invite warning, tagged so a later good invite can retract it. */
  private void setInviteRefusalWarning(final String message, final String aboutAddress) {
    setWarningMessage(message, aboutAddress);
    mStandingWarningIsInviteRefusal = true;
  }

  /**
   * Records that the bundle attached at this address was refused, and what was said about it.
   *
   * <p>Separate from the painting, and called unconditionally. See {@code mRefusedInvites}: the
   * decision about whether to paint belongs to whatever else is holding the banner, and the fact
   * does not.
   */
  private void rememberRefusedInvite(final String address, final String message) {
    if (address == null || message == null) return;
    mRefusedInvites.put(address, message);
  }

  /**
   * Replaces a remembered "nothing has been set up" once something has been.
   *
   * <p>Only that sentence. The other two describe states a later pin does not contradict: one
   * already says a key was pinned, and one says an existing session is unchanged, which it is.
   */
  private void upgradeAremeberedRefusalThatAkeyNowContradicts(final Contact sender) {
    if (sender == null) return;
    final String address = String.valueOf(sender.getSignalProtocolAddress());
    final String remembered = mRefusedInvites.get(address);
    if (remembered == null
        || !remembered.equals(String.format(INFO_INVITE_REFUSED, labelFor(sender)))) {
      return;
    }
    mRefusedInvites.put(address,
        String.format(INFO_INVITE_REFUSED_BUT_KEY_PINNED, labelFor(sender)));
  }

  /**
   * Forgets a remembered refusal because the user answered it.
   *
   * <p>Verifying, rejecting and deleting are the three deliberate responses this file's own
   * classification names as what ends an event warning, and the refusal record had only the third.
   * So the warning came back on the next selection after the user had done exactly what it asked,
   * held {@code mWarningStanding}, and suppressed every routine line for that contact for the life
   * of the process - and after a rejection it came back <em>false</em>, claiming a key had been set
   * up at an address whose key had just been discarded.
   *
   * <p>Address-scoped explicitly, not through {@code clearStandingWarningIfAbout}: that helper
   * treats a null-addressed warning as being about anybody, which is right for a banner with one
   * slot and wrong for a per-address record. Routing this through it would let rejecting Alice
   * erase Bob's refusal - the cross-contact erase two other clears were narrowed to prevent.
   *
   * <p>This does not hand an attacker an eviction. A later tampered invite records again
   * unconditionally, so what is forgotten is the answered event, not the next one.
   */
  private void forgetRefusedInviteBecauseTheUserAnsweredIt(final Contact contact) {
    if (contact == null) return;
    mRefusedInvites.remove(String.valueOf(contact.getSignalProtocolAddress()));
  }

  /**
   * Re-raises the refusal for a contact whose last attached bundle was refused.
   *
   * <p>An EVENT raiser: a bundle that was changed in transit does not stop having been changed, so
   * this never lowers anything. What ends it is the retraction - a later good invite at the same
   * address - or the contact being deleted.
   *
   * <p>Deliberately silent when there is nothing recorded, so it cannot blank a banner.
   */
  private boolean warnIfLastInviteWasRefused(final Contact contact) {
    if (contact == null) return false;
    final String address = String.valueOf(contact.getSignalProtocolAddress());
    final String remembered = mRefusedInvites.get(address);
    if (remembered == null) return false;
    setInviteRefusalWarning(composedWithAsharedNameWarningAbout(address, remembered), address);
    return true;
  }

  /**
   * Keeps the shared-name warning when this one would otherwise overwrite it.
   *
   * <p>Displacement is survivable here because every warning can be worked out again - but that
   * argument fails when the displacer is worked out again in the SAME pass. {@code selectContact}
   * re-derives four warnings in reverse severity and the last writer wins, so once a refusal was
   * recorded for a row whose name folds onto another, the duplicate-name warning was recomputed and
   * immediately overwritten on every single selection. Recomputable and never rendered is not a
   * displacement, it is a permanent loss, and it lands on the one control covering the case the pin
   * cannot: two rows the user cannot tell apart.
   *
   * <p>Reordering only moves the loss - the refusal sits second of four precisely because being
   * below the shared name reduced it to a three-second toast for exactly these contacts. So both
   * are said. They are about the same row and the same tap, the slot is not widened, and the
   * composition is idempotent: the next selection re-derives the shared name first and composes
   * again.
   */
  private String composedWithAsharedNameWarningAbout(final String address, final String refusal) {
    if (!mWarningStanding || !standingWarningIsAboutAsharedName()) return refusal;
    if (!address.equals(mStandingWarningAddress)) return refusal;
    return mStandingWarningText + "\n\n" + refusal;
  }

  /**
   * Retracts a standing refused-invite warning about this contact.
   *
   * <p>Scoped twice over: only the refusal warning, and only for the contact it names. An accepted
   * invite says nothing about an identity change or a rejection recorded for the same address, so
   * those must survive it.
   */
  private void clearInviteRefusalIfAbout(final Contact contact) {
    // The record goes first and unconditionally. It is retracted by the same event that retracts
    // the warning - a later good invite at this address - and gating its removal on a warning
    // happening to be standing would leave a refusal that comes back on the next selection after
    // the user has already fixed it.
    if (contact != null) {
      mRefusedInvites.remove(String.valueOf(contact.getSignalProtocolAddress()));
    }
    if (!mWarningStanding || !mStandingWarningIsInviteRefusal) return;
    clearStandingWarningIfAbout(contact);
  }

  /**
   * Clears a standing warning only when this contact is who it was about.
   *
   * <p>A warning with no address still clears: {@code INFO_STORAGE_UNREADABLE} and
   * {@code INFO_SAME_ADDRESS_DIFFERENT_NAME} are posted without one, and a deliberate response is
   * their only exit.
   */
  private void clearStandingWarningIfAbout(final Contact contact) {
    if (!mWarningStanding) return;
    if (mStandingWarningAddress == null || (contact != null && mStandingWarningAddress
        .equals(String.valueOf(contact.getSignalProtocolAddress())))) {
      clearStandingWarning();
    }
  }

  /** Clears a standing warning. Only call this from a deliberate user action. */
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
    // Cleared per attempt, and recorded rather than inferred.
    //
    // This method's RETURN VALUE cannot answer "did the user get their message": the refusal branch
    // writes the plaintext on screen and then returns false, because its boolean means "was this
    // envelope wholly good". A caller that read it as "nothing decrypted" therefore threw away a
    // message it had just displayed - which is what the recipient undo added last round did, on the
    // envelope shape this file itself calls the ordinary one for a signed-pre-key rotation. Staple
    // a bundle with its one-time pre-key stripped to every relayed message and every decrypt wiped
    // the compose box: a per-message, zero-crypto suppression of the app's main function.
    mLastDecryptShowedAmessage = false;
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
      cautionThatAkeyWasPinned(false, false);
      // And a remembered refusal saying "Nothing has been set up" has just stopped being true.
      //
      // This is the shape that makes storing a sentence dangerous as well as right. The record is
      // retracted by a later good BUNDLE, and this route pins a key with no bundle at all - the
      // two-step above, which costs the attacker one omitted optional field. Without this, the next
      // selection repaints "Nothing has been set up. Ask them to send another" over an address that
      // now holds a messenger-chosen key the app is encrypting to, on the only durable surface
      // there is, in exactly the state a successful substitution leaves the app in.
      //
      // Upgraded rather than dropped: the invite really was changed on the way here, and that is
      // still worth saying. The sentence it becomes is the one written for this state, which tells
      // the user to compare the number.
      upgradeAremeberedRefusalThatAkeyNowContradicts(sender);
    }

    if (bundleRefused) {
      // A warning rather than a plain line: the banner is repainted straight after this by
      // showChosenContactInMainInfoField, which is guarded only by a standing warning, so an
      // ordinary message would be overwritten with "Chosen contact: Bob" and the refusal would be
      // as silent as before. Soft, because it must not be able to suppress a message that says
      // more than it does - see setInfoUnlessWarned.
      // The fact is recorded whether or not the sentence is painted, which is the same separation
      // the add path needed and got.
      //
      // Suppressing the sentence when another warning already stands is right - a refusal must not
      // paint over a detected key substitution. Losing the FACT with it was not: an attacker who
      // first raises any cheap warning (one forged bundle) can then strip the one-time pre-key -
      // one unsigned byte - from every subsequent invite, and "it does not verify, which means it
      // was changed on the way here" is never shown at all. That claim is strictly stronger than
      // anything the pin caution says, and it was the half being dropped.
      // Said out loud even when the banner is not available to say it on.
      //
      // Recorded as a field first, "separately from whether the sentence is painted", and nothing
      // ever read it - the toast below was the only surface either way. A fact with no consumer is
      // not a separation of concerns, it is a variable; the separation that mattered was making the
      // TOAST unconditional, which is what this line is. Removed rather than kept in case somebody
      // wants it: speculative state on this surface is how the last four rounds of defects started.
      //
      // A toast cannot displace the standing warning, so this keeps the suppression's purpose - a
      // refusal must not paint over a detected key substitution - while still telling the user that
      // the invite they just pasted was altered in transit. Three and a half seconds is a poor
      // surface for it, and it is strictly better than the nothing that was there: an attacker who
      // raises one cheap warning first was otherwise buying silence on every tampered invite that
      // followed, for one unsigned byte each.
      Toast.makeText(getContext(),
          String.format(INFO_INVITE_REFUSED, labelFor(sender)), Toast.LENGTH_LONG).show();

      // A standing shared-name warning does not silence this one.
      //
      // The refusal is about THIS envelope and outranks a warning derived from the contact list -
      // the same reverse-severity order selectContact uses. It became reachable when the shared-name
      // re-derivation moved into setChosenContact, which runs before this on all three arms: for
      // any contact whose name folds onto another, the "that invite was changed on the way here"
      // warning was reduced to a three-second toast, which is precisely the silence-for-one-unsigned
      // -byte the refusal warning exists to close.
      // Three states, and each needs its own sentence. Two of them were collapsed into one and
      // the collapse pointed the wrong way: the reassuring wording landed on the first-pin case.
      // A key pinned by this paste is the only state INFO_INVITE_REFUSED_BUT_KEY_PINNED
      // describes; a session built against a key that was already trusted is not.
      final boolean keyPinnedByThisPaste = !keyPinnedBefore
          && mE2EEStrip.hasPinnedKey(sender.getSignalProtocolAddress());
      final boolean somethingSurvived = sessionExistedBefore || keyPinnedBefore;
      final String outcome = keyPinnedByThisPaste ? INFO_INVITE_REFUSED_BUT_KEY_PINNED
          : (somethingSurvived ? INFO_INVITE_REFUSED_SESSION_KEPT : INFO_INVITE_REFUSED);

      // RECORDED FIRST, and outside the guard below. Record the fact; decide separately whether to
      // paint it.
      //
      // That rule is written in REVIVAL.md as one this file has needed three times, and the round
      // that added mRefusedInvites put the new fact inside the paint guard - so the erasure it was
      // written to close was still open in the case its own commit message described. One tampered
      // invite from A raises and records A's refusal and leaves a warning standing; every tampered
      // invite from B afterwards took the toast-only arm and recorded nothing, so B's refusal
      // existed nowhere the moment those 3.5 seconds elapsed. That is silence for one unsigned
      // byte per message, which is precisely what the refusal warning exists to buy back.
      //
      // The outcome is computed above rather than inside, and deliberately not moved any earlier:
      // keyPinnedByThisPaste re-reads hasPinnedKey, and computing it before the pin caution would
      // attribute pins that did not come from this paste.
      rememberRefusedInvite(String.valueOf(sender.getSignalProtocolAddress()),
          String.format(outcome, labelFor(sender)));

      // A standing shared-name warning does not silence this one.
      if (!identityChanged && (!mWarningStanding || standingWarningIsAboutAsharedName())) {
        setInviteRefusalWarning(String.format(outcome, labelFor(sender)),
            String.valueOf(sender.getSignalProtocolAddress()));
      }
      // The message itself, if one came with the refused bundle, is still the user's to read: the
      // ciphertext is authenticated by the existing session and has nothing to do with the bundle
      // that was stapled to it.
      if (!isSessionCreation && decryptedMessage != null) {
        mLastDecryptShowedAmessage = true;
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
      mLastDecryptShowedAmessage = true;
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
     * The refused invites, so a rebuild does not make the one warning nothing re-derives
     * un-re-derivable again. A configuration change is host-forceable, so dropping this would hand
     * the erasure straight back by a different route.
     */
    private final Map<String, String> refusedInvites;
    /**
     * The store notice and the log-write count when it went up.
     *
     * <p>Carried because the condition it reports survives a rebuild and nothing re-asserts it: the
     * user's one chance to learn that a deletion left their plaintext behind would be spent on
     * whichever rotation happened first.
     */
    /** The storage caution and the address it is about; a separate slot, carried separately. */
    private final String standingStorageCaution;
    private final String standingStorageCautionAddress;
    /** Whether that caution is about a deletion, which no later write settles. */
    private final StorageCautionKind standingStorageCautionKind;
    /** The write count when it went up, so a rebuild does not retire it early or never. */
    private final long accountWritesLandedWhenStorageCautionRaised;
    private final String standingStoreNotice;
    private final long logWritesLandedWhenNoticeRaised;
    private final long accountReloadsWhenNoticeRaised;
    private final boolean hostFieldIsPassword;
    private final Encoder encoding;

    private CarriedState(final CharSequence draft, final boolean wasComposing,
        final CharSequence banner, final boolean warningStanding,
        final String standingWarningText, final String standingWarningAddress,
        final boolean standingWarningIsInviteRefusal, final String standingCaution,
        final String standingCautionAddress, final Map<String, Long> contactsNotOnDisk,
        final Map<String, String> refusedInvites,
        final String standingStorageCaution, final String standingStorageCautionAddress,
        final StorageCautionKind standingStorageCautionKind,
        final long accountWritesLandedWhenStorageCautionRaised,
        final String standingStoreNotice, final long logWritesLandedWhenNoticeRaised,
        final long accountReloadsWhenNoticeRaised,
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
      this.refusedInvites = refusedInvites;
      this.standingStorageCaution = standingStorageCaution;
      this.standingStorageCautionAddress = standingStorageCautionAddress;
      this.standingStorageCautionKind = standingStorageCautionKind;
      this.accountWritesLandedWhenStorageCautionRaised = accountWritesLandedWhenStorageCautionRaised;
      this.standingStoreNotice = standingStoreNotice;
      this.logWritesLandedWhenNoticeRaised = logWritesLandedWhenNoticeRaised;
      this.accountReloadsWhenNoticeRaised = accountReloadsWhenNoticeRaised;
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
        new LinkedHashMap<>(mRefusedInvites),
        mStandingStorageCaution, mStandingStorageCautionAddress,
        mStandingStorageCautionKind, mAccountWritesLandedWhenStorageCautionRaised,
        mStandingStoreNotice, mLogWritesLandedWhenNoticeRaised,
        mAccountReloadsWhenNoticeRaised,
        mHostFieldIsPassword, encodingMethod);
  }

  /** Restores what the outgoing view surrendered. */
  public void adoptState(final CarriedState carried) {
    if (carried == null) return;

    // Before anything renders from it. adoptState re-raises the redirect with requestFocus(), whose
    // listener calls changeVisibilityInputFieldButtons, which reads encodingMethod to decide which
    // encoding indicator to show - so applying the carried value afterwards left the strip showing
    // RAW while it would in fact encode FairyTale. No plaintext exposure; the app naming the wrong
    // encoding to the user who chose one.
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
    // Through composeInsideTheKeyboard, not a bare requestFocus.
    //
    // That method exists because requestFocus() returns false silently whenever the view cannot
    // take focus at that moment - a GONE ancestor, a window not yet focusable - and its own javadoc
    // calls a silent failure here "precisely the disclosure this exists to prevent". This site
    // discarded the return value and relied on the focus listener firing as a side effect, which is
    // the mechanism that method was written to distrust. If the focus is ever refused, the flag
    // stays up from before the rebuild while mOtherIC is null - surrenderState nulls it and does
    // not lower - so getIC() returns null and every keystroke is silently discarded.
    //
    // Still gated on wasComposing. Raising it on every rebuild would take focus on an event the
    // host app can force at will, including rebuilds that happen while the user is typing into the
    // messenger, which is the mirror defect.
    if (carried.wasComposing) {
      composeInsideTheKeyboard();
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
    // A condition warning is RE-DERIVED here rather than replayed, and that distinction is the whole
    // of a fix that did not work.
    //
    // The lowering path added to refreshOpeningMessage is unreachable in production. That method has
    // one production caller, LatinIME.setInputView, and the view it is given is always freshly
    // inflated - so mWarningStanding is always false there and the lowering never runs. Worse, the
    // order is refreshOpeningMessage first and adoptState second, so this block then re-posted the
    // stale warning the lowering was meant to remove. The test pinning that lowering called
    // refreshOpeningMessage twice on ONE strip and described it as "what LatinIME does on every
    // setInputView"; LatinIME does not do that, and the control was hollow.
    //
    // Replaying a warning about a CONDITION is the same mistake as never lowering it: it asserts on
    // the new strip something that was true on the old one. So the two condition warnings are asked
    // again instead of copied, and the answer decides. Everything else here is about an event, and
    // an event still happened, so it is replayed as before.
    final boolean carriedIsAconditionWarning =
        INFO_STORAGE_UNREADABLE.equals(carried.standingWarningText)
            || INFO_CONTACTS_UNREADABLE.equals(carried.standingWarningText);
    if (carried.warningStanding && !mWarningStanding && carriedIsAconditionWarning) {
      // Re-derived rather than replayed, and the call is redundant in the ORDER LatinIME uses
      // today: setInputView refreshes the fresh strip before adopting, so reaching this branch
      // already means that refresh raised nothing, and calling it again takes the same path to the
      // same answer. It is kept because it makes this method independent of that order - adopting
      // before refreshing would otherwise drop a carried condition warning without ever asking
      // whether it is still true, which is the fail-open direction - and because a review round
      // showed the previous justification for this line described a mechanism that was not running.
      refreshOpeningMessage();
    } else if (carried.warningStanding && !mWarningStanding) {
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
    mRefusedInvites.clear();
    if (carried.refusedInvites != null) mRefusedInvites.putAll(carried.refusedInvites);
    mStandingStorageCaution = carried.standingStorageCaution;
    mStandingStorageCautionAddress = carried.standingStorageCautionAddress;
    mStandingStorageCautionKind = carried.standingStorageCautionKind;
    mAccountWritesLandedWhenStorageCautionRaised =
        carried.accountWritesLandedWhenStorageCautionRaised;
    mStandingStoreNotice = carried.standingStoreNotice;
    mLogWritesLandedWhenNoticeRaised = carried.logWritesLandedWhenNoticeRaised;
    mAccountReloadsWhenNoticeRaised = carried.accountReloadsWhenNoticeRaised;
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
    // Choosing FairyTale is the user saying "do not let this look like ciphertext to somebody
    // glancing at my screen", which is the only thing the mode does - the app's own help text says
    // so, because every FairyTale message ends in a run of invisible characters nothing else
    // produces. A rebuild put the choice back to RAW, and the host app picks when rebuilds happen.


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
   * password-field guard. The other three lowerings - a send, the keyboard being dismissed, and
   * leaving the add-contact screen with an empty compose box - do
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
    if (refusedToComposeOverApasswordField(mInputEditText)) return;
    if (mInputEditText == null || mRichInputConnection == null) return;
    // Guarded, because requestFocus() on an already-focused view is a no-op but the listener it
    // would fire re-enters changeVisibilityInputFieldButtons, which calls this again.
    if (!mInputEditText.hasFocus()) mInputEditText.requestFocus();
    mRichInputConnection.setOtherIC(mInputEditText);
    mRichInputConnection.setShouldUseOtherIC(true);
  }

  /**
   * A notice for a tap that arrived through another application's window.
   *
   * <p>Its own sentence, because the user has to be told the press did nothing and why. The
   * platform's own answer - {@code android:filterTouchesWhenObscured} - discards the touch in
   * silence, which turns every security control dark for anyone running a blue-light filter, a
   * screen dimmer, a chat head or accessibility magnification, with nothing on screen to explain
   * it. That is this file's recorded "refusal that locked the keyboard" failure, and a refusal the
   * user cannot see is indistinguishable from a broken button.
   */
  static final String INFO_TAP_CAME_THROUGH_ANOTHER_WINDOW = "Something is drawn over this "
      + "keyboard, so that press was ignored. An app on top of the screen can put a button where "
      + "you did not expect one. Close whatever is overlaying the screen and try again.";

  /**
   * Whether a touch reached a security control through some other app's window.
   *
   * <p>The controls this guards are all single, unconfirmed taps, and that is deliberate: a
   * standing warning must always leave one deliberate response available, so this app has no
   * confirmation dialog anywhere. The design assumes the tap came from the user, and Android says
   * when it did not - {@code FLAG_WINDOW_IS_OBSCURED} is set when another window was over the
   * touched point. An app holding "Display over other apps" can place a full-screen
   * {@code TYPE_APPLICATION_OVERLAY}, a higher policy layer than {@code TYPE_INPUT_METHOD}, mark it
   * {@code FLAG_NOT_TOUCHABLE}, and let taps fall through to whatever it drew over. The platform
   * began blocking untrusted touches itself in API 31; this app supports 26 and says so in its
   * README, so 26-30 is the range where nothing else is watching.
   *
   * <p>What that buys the attacker is specific: Verify asserts that the user read twelve digits
   * aloud and heard the same ones back, which is the single claim this app makes that the user
   * cannot re-derive from anything on screen. Reject destroys a pin. Delete destroys a
   * conversation. None of them asks twice.
   *
   * <p>PARTIALLY obscured is deliberately not refused. A partially-obscured window is the ordinary
   * state under a status-bar chip or a notification shade peek, and refusing it would be the silent
   * dead button by another route.
   */
  private boolean tapCameThroughAnotherWindow(final MotionEvent event) {
    if (event == null) return false;
    final int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) mRefusedThisGesture = false;

    final boolean obscuredNow = (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0;
    if (obscuredNow && !mRefusedThisGesture) {
      mRefusedThisGesture = true;
      // A toast, not the banner.
      //
      // The first version wrote INFO_TAP_CAME_THROUGH_ANOTHER_WINDOW to mInfoTextView through
      // setInfoUnlessWarned, and that message could never be seen. Both guarded buttons live on the
      // verify screen, and showing that screen sets mLayoutE2EEMainView - which contains the banner
      // - GONE. setText on a view under a GONE ancestor changes nothing the user can look at. Worse,
      // setInfoUnlessWarned refuses to write over a standing item, and a standing key-change
      // warning is exactly the state a user opens Verify in.
      //
      // So the refusal was silent, and consuming the touch removes the pressed state and ripple
      // too: an absolutely inert button, which is precisely the failure this guard's javadoc says
      // filterTouchesWhenObscured produces and that this was written to avoid. The sibling refusal
      // on this same button - refuseThePressAndRepaintTheNumber - already uses a toast, for the
      // same reason, three lines away.
      //
      // Safe as a toast specifically because it carries no content: it names no contact and no
      // message, so the toast channel's lack of FLAG_SECURE costs nothing here.
      Toast.makeText(getContext(), INFO_TAP_CAME_THROUGH_ANOTHER_WINDOW, Toast.LENGTH_LONG).show();
    }

    // Once refused, the whole gesture is refused.
    //
    // Acting only on ACTION_DOWN and letting the rest through re-opens the press: if DOWN is clean
    // and the overlay appears before UP, the view is already the touch target and performClick
    // runs. And refusing every event without remembering would toast on DOWN, on every MOVE and on
    // UP - the flag is carried on each event of the stream, not only the first.
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      final boolean refused = mRefusedThisGesture;
      mRefusedThisGesture = false;
      return refused;
    }
    return mRefusedThisGesture;
  }

  /**
   * True from the moment one gesture is refused until it ends.
   *
   * <p>Per-operation, like {@code mLastDecryptShowedAmessage}: it describes one touch stream and a
   * touch stream does not survive the view it happened on.
   */
  private boolean mRefusedThisGesture;

  /**
   * Wires one security-consequential control to refuse taps delivered through another window.
   *
   * <p>Applied at the touch stage rather than inside each click listener, so the click never
   * happens at all - a listener that checks afterwards has already been entered, and several of
   * these do their work before any early return.
   */
  private void refuseTapsThroughAnotherWindow(final View button) {
    if (button == null) return;
    button.setOnTouchListener((v, event) -> tapCameThroughAnotherWindow(event));
  }

  /**
   * Refuses to start composing here, and takes back the focus that asked to.
   *
   * <p>The password guard lowers the redirect once, in {@code setHostFieldIsPassword}. That was
   * enough while nothing raised it again, and three focus listeners do: this method's caller, and
   * the two add-contact name fields. Nothing stopped a tap - the strip is on screen throughout,
   * because {@code LatinIME.onComputeInsets} makes it VISIBLE unconditionally - so one tap on any
   * of the three restored exactly the state the guard exists to forbid. Measured: the host's
   * password box received nothing and the password was committed into the strip.
   *
   * <p>This paragraph used to say focus is cleared "rather than the fields being made unfocusable",
   * and argued against exactly what the method below it does. The fields ARE made untakeable while
   * the guard is armed - see {@code stripInputsCanTakeFocus} - because clearing focus reactively
   * does not hold: the container re-grants it to the next candidate, and a clear issued from inside
   * a focus-change callback is undone by the {@code requestFocus()} unwinding around it. The
   * sentence survived the fix that refuted it, which matters because it pre-argues against the
   * code: a reader who found the add-contact screen inert over a password field would have had a
   * javadoc telling them that outcome was ruled out, and the natural response is to delete the
   * thing keeping the password out of the strip.
   *
   * <p>The concern it raised is real and is answered elsewhere rather than by not doing it: the
   * fields are restored the moment the guard disarms, so the screen is inert only while the host
   * field is a password box, and the refusal is explained on the add-contact screen's own banner.
   *
   * @return true when composing may not start, and the caller must do nothing further.
   */
  private boolean refusedToComposeOverApasswordField(final View asked) {
    if (actionsAreAvailable()) return false;
    // No focus fight here. The strip's three input fields are made untakeable for as long as the
    // guard is armed (see stripInputsCanTakeFocus), so nothing can be focused at this point and
    // clearing focus from inside a focus-change callback - which the in-progress requestFocus()
    // simply undoes on the way out - is not attempted. This stays as the second lock: it is what
    // refuses the raise itself.
    if (asked != null && asked.hasFocus()) asked.clearFocus();
    // Said where it happened. The password notice lives on the main view's banner, and the
    // add-contact screen covers it - so a user who taps a name field there would otherwise get
    // silence from a field that simply stops working.
    if (mAddContactInfoTextView != null
        && mLayoutE2EEAddContactView.getVisibility() == VISIBLE) {
      setInfoTextViewMessage(mAddContactInfoTextView, INFO_PASSWORD_FIELD);
    }
    return true;
  }

  /**
   * Whether the strip's own input fields may take focus at all.
   *
   * <p>Set false for as long as the password guard is armed. Clearing focus reactively does not
   * work: the container re-grants it to the next candidate, and a clear issued from inside a
   * focus-change callback is undone by the {@code requestFocus()} still unwinding around it. Making
   * the fields untakeable is the only form that holds, and it is also the honest one - over a
   * password field the app has announced it will not compose here, so the fields should not accept
   * a caret.
   *
   * <p>Restored the moment the guard disarms. Leaving them untakeable would let the messenger
   * disable the add-contact screen outright by declaring a password field, and that screen is where
   * its own invite payload sends the user. The refusal is explained rather than silent: the notice
   * goes to the add-contact screen's own banner, because the main view's is covered.
   */
  private void stripInputsCanTakeFocus(final boolean canTake) {
    final EditText[] inputs = {mInputEditText, mAddContactFirstNameInputEditText,
        mAddContactLastNameInputEditText};
    for (final EditText input : inputs) {
      if (input == null) continue;
      input.setFocusableInTouchMode(canTake);
      input.setFocusable(canTake);
    }
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
   * the focus listener's comment argues for at length - the four sites that do lower it are
   * enumerated there and pinned by {@code theloweringSitesAreAllAccountedForTest}.
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
    if (mStandingCaution != null || mStandingStorageCaution != null
        || mStandingStoreNotice != null) {
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
    // Three now, not two, and the third is the one that could be lost for good.
    //
    // The duplicate-name warning was raised in exactly one place - inside addContact - and never
    // re-asserted. The warning slot holds one thing, so any later warning REPLACED it: a forged
    // bundle for a different contact costs an attacker nothing and calls setWarningMessage
    // unconditionally. The user then does what that second warning tells them - compares the number
    // for the genuine contact, it matches, presses Verify - and the clear is address-scoped to the
    // contact they just verified, so both warnings are gone. The impostor row is left
    // indistinguishable from a healthy contact and nothing mentions it again.
    //
    // Written as a re-assertion rather than a fourth slot because the condition is standing state:
    // two rows whose rendered names fold together is a fact about the contact list, recomputable at
    // any time, exactly like a pending identity change or a rejection record. A warning that can be
    // recomputed cannot be evicted permanently.
    // All three called, none of them behind a short circuit, and deliberately in reverse order of
    // severity.
    //
    // They are writers: each posts into the single warning slot, so the LAST one that fires is the
    // one left on screen. Calling them in an || chain skipped the later ones whenever an earlier
    // one fired, which is how a writer stops being called - the defect this file records about
    // warnIfIdentityChanged. Calling them in severity order would be worse: the least serious would
    // land last and hold the slot.
    //
    // So: shared name, then rejection, then identity change. A detected key substitution outranks
    // everything and is written last.
    final boolean sharedName = warnIfNameIsShared(contact);
    // Second of four, which is where the refusal already sat by severity: it outranks a warning
    // derived from the contact list, and it yields to a rejection and to a detected substitution.
    // Adding it here is what makes painting over it a displacement rather than an erasure - the
    // property the no-yield trade in refreshOpeningMessage rests on, and the one warning that did
    // not hold it.
    final boolean inviteRefused = warnIfLastInviteWasRefused(contact);
    final boolean rejected = warnIfKeyWasRejected(contact);
    final boolean identityChanged = warnIfIdentityChanged(contact);
    final boolean warnedAboutThisContact =
        sharedName || inviteRefused || rejected || identityChanged;
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
      // Flagged as what it is. The retirement asks this fact rather than searching the sentence
      // for a phrase - that was the whole reason the flag was introduced - and this call was still
      // using the two-argument overload, which says "not a storage notice" about a sentence whose
      // subject is the app failing to write to its own storage.
      postStorageCaution(INFO_DELETE_NOT_SAVED, contact, StorageCautionKind.DELETION_DID_NOT_HAPPEN);
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
      // Through the arm that is allowed to end a deletion caution, because this is the event that
      // ends it: the contact is gone from disk as well as from memory.
      clearStorageCautionIfAbout(contact, StorageCautionKind.DELETION_DID_NOT_HAPPEN);
      clearCautionIfAbout(contact);
      // Nothing left to refuse about: the row is gone from disk as well as from memory.
      if (contact != null) {
        mContactsNotOnDisk.remove(String.valueOf(contact.getSignalProtocolAddress()));
        // And the refused-invite record, for the same reason: a warning about a contact who no
        // longer exists points the user at a row that is gone.
        mRefusedInvites.remove(String.valueOf(contact.getSignalProtocolAddress()));
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
        mAccountReloadsWhenNoticeRaised = SignalProtocolMain.accountReloads();
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
