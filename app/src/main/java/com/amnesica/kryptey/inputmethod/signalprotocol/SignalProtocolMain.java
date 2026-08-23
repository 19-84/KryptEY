package com.amnesica.kryptey.inputmethod.signalprotocol;

import android.content.Context;
import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.DuplicateContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.InvalidContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownMessageException;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponse;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.PreKeyResponseItem;
import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.SignedPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Main class for signal protocol
 */
public class SignalProtocolMain {
  static final String TAG = SignalProtocolMain.class.getSimpleName();

  private StorageHelper mStorageHelper;
  private Account mAccount;

  private static final SignalProtocolMain sInstance = new SignalProtocolMain();

  // know when tests are running so mock values can be used
  public static boolean testIsRunning = false;

  public static SignalProtocolMain getInstance() {
    return sInstance;
  }

  private SignalProtocolMain() {
    // Intentional empty constructor for singleton.
  }

  /**
   * Creates a brand-new identity, unless one already exists.
   *
   * @return true if an account is now loaded; false if nothing usable could be established, in
   *     which case the caller must NOT record that first-run setup completed.
   */
  public static boolean initialize(final Context context) {
    Log.d(TAG, "Initializing signal protocol...");
    sInstance.initializeStorageHelper(context);

    // Refuse to generate over existing data. The caller decides to call initialize() based on a
    // "first run" boolean held in device-protected storage, while the identity lives in
    // credential-protected storage; the two can be lost independently, and the boolean defaults to
    // true on any read failure. Generating here would silently and irrecoverably destroy the user's
    // identity key, every session, and their verified contacts.
    if (sInstance.mStorageHelper != null && sInstance.mStorageHelper.hasExistingProtocolData()) {
      Log.w(TAG, "Protocol data already exists; refusing to generate a new identity. "
          + "Loading the existing account instead.");
      sInstance.reloadAccountFromSharedPreferences();
      return sInstance.mAccount != null;
    }

    sInstance.initializeProtocol();
    if (sInstance.mAccount == null) return false;

    // Only report success if the identity actually reached disk. Storage now depends on the
    // Keystore and can fail; a caller that recorded "setup done" after a failed write would come
    // back on the next raise, find nothing stored, and generate a different identity.
    return sInstance.mStorageHelper != null && sInstance.mStorageHelper.hasExistingProtocolData();
  }

  public static void reloadAccount(final Context context) {
    Log.d(TAG, "Reloading local account for signal protocol (not first app run)...");
    sInstance.initializeStorageHelper(context);
    sInstance.reloadAccountFromSharedPreferences();
    // Only write back if we actually loaded something. Storing a null account here would NPE, and
    // storing a partially-populated one would overwrite the user's identity keys with blanks.
    if (sInstance.mAccount != null) {
      sInstance.storeAllAccountInformationInSharedPreferences();
    } else {
      Log.e(TAG, "Error: account could not be reloaded; leaving stored data untouched");
    }
  }

  public static MessageEnvelope encryptMessage(final String unencryptedMessage, final SignalProtocolAddress signalProtocolAddress) {
    Log.d(TAG, "Encrypting signal message...");
    return sInstance.encrypt(unencryptedMessage, signalProtocolAddress);
  }

  public static String decryptMessage(final MessageEnvelope messageEnvelope, final SignalProtocolAddress signalProtocolAddress) throws InvalidMessageException, InvalidContactException, UnknownMessageException, UntrustedIdentityException, DuplicateMessageException, InvalidVersionException, InvalidKeyIdException, LegacyMessageException, InvalidKeyException, NoSessionException {
    Log.d(TAG, "Decrypting signal message...");
    return sInstance.decrypt(messageEnvelope, signalProtocolAddress);
  }

  public static boolean processPreKeyResponseMessage(final MessageEnvelope messageEnvelope, final SignalProtocolAddress signalProtocolAddress) {
    Log.d(TAG, "Processing pre key response signal message...");
    return sInstance.processPreKeyResponse(messageEnvelope, signalProtocolAddress);
  }

  public static MessageEnvelope getPreKeyResponseMessage() {
    Log.d(TAG, "Creating pre key response message...");
    return sInstance.createPreKeyResponseMessage();
  }

  /**
   * Whether this contact's identity key changed and the user has not acknowledged it.
   *
   * <p>A changed safety number is the highest-signal security event the protocol produces: it means
   * either the contact reinstalled, or someone is impersonating them. The store refuses to send
   * until it is acknowledged; this is how the UI finds out why.
   */
  public static boolean hasUnacceptedIdentityChange(final SignalProtocolAddress address) {
    if (address == null || sInstance.mAccount == null) return false;
    return sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .hasUnacceptedIdentityChange(address);
  }

  /**
   * Accepts a contact's changed identity key after the user has compared it out of band.
   *
   * @param shown the key the user was actually shown; the change is refused if it no longer matches
   *     what is pending, so a key that arrives between display and confirmation cannot slip through
   * @return true if the pinned key was replaced
   */
  /**
   * Forgets a contact's pinned key, because the user compared safety numbers and they did NOT
   * match.
   *
   * <p>This is the case the rest of the trust model does not cover. Everything else here assumes
   * the pinned key is the genuine one and treats any newly offered key as hostile — which is right
   * whenever the pin was established honestly. But the pin is set by trust-on-first-use, through
   * the same messenger the threat model says can forge anything, so at first contact it can just as
   * easily be the attacker's. In that mirror case every other control does the wrong thing:
   * dismissing throws away the peer's real key, deletion keeps the impostor's, and the badge ends up
   * green over the wrong identity. Without this, a user who does exactly the right thing — compares
   * numbers, finds a mismatch — has no action available, and the wrong pin is permanent for the
   * life of the install.
   *
   * <p>Why this does not reopen the fail-open that made contact deletion an unsafe exit: that path
   * was reachable from generic decryption-failure advice, and an attacker can induce a decryption
   * failure at will by replaying a message or flipping a bit. This is reachable only from the verify
   * screen, only after the user has been shown a number to compare. An attacker cannot deliver the
   * user to it, and cannot make the comparison fail for a genuine peer.
   *
   * <p>Drops the session and the verified badge with the key, so nothing downstream keeps treating
   * the old identity as current.
   *
   * @return true if a pinned key was forgotten.
   */
  public static boolean rejectContactKey(final Contact contact) {
    if (sInstance.mAccount == null || contact == null) return false;
    final SignalProtocolAddress address = contact.getSignalProtocolAddress();

    final boolean hadPin = sInstance.mAccount.getSignalProtocolStore()
        .getIdentityKeyStore().getIdentity(address) != null;
    sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore().removeIdentity(address);
    if (sInstance.mAccount.getSignalProtocolStore().containsSession(address)) {
      sInstance.mAccount.getSignalProtocolStore().deleteSession(address);
    }
    contact.setVerified(false);
    sInstance.clearVerificationFor(address);
    Log.w(TAG, "Forgot the pinned key for " + contact.getSignalProtocolAddressName()
        + ": the user reported the safety number did not match");
    sInstance.storeAllAccountInformationInSharedPreferences();
    return hadPin;
  }

  /**
   * Discards a pending identity change, keeping the pinned key. The safe exit from the state an
   * attacker can force; see {@code IdentityKeyStoreImpl.dismissIdentityChange}.
   */
  public static boolean dismissIdentityChange(final SignalProtocolAddress address) {
    if (sInstance.mAccount == null || address == null) return false;
    final boolean dismissed = sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .dismissIdentityChange(address);
    if (dismissed) {
      Log.i(TAG, "Discarded a pending identity change; the pinned key is unchanged");
      sInstance.storeAllAccountInformationInSharedPreferences();
    }
    return dismissed;
  }

  /**
   * Adopts a pending identity in place of the pin, taking the key the user was actually shown.
   *
   * <p><b>Deliberately unwired.</b> No production caller, and adding one needs an argument first:
   * a peer who reinstalls arrives at a fresh address, so a change at a pinned address is never a
   * legitimate re-key, and a screen offering to adopt one is an attack surface. The two wired exits
   * are {@link #dismissIdentityChange} (the pin is right; discard what was offered) and
   * {@link #rejectContactKey} (the pin is wrong; forget it). Between them they cover both
   * directions, which is what this was reached for before those existed.
   */
  public static boolean acceptIdentityChange(final SignalProtocolAddress address,
                                             final IdentityKey shown) {
    if (address == null || sInstance.mAccount == null) return false;
    final boolean accepted = sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .acceptIdentityChange(address, shown);
    if (accepted) sInstance.storeAllAccountInformationInSharedPreferences();
    return accepted;
  }

  /** The identity key offered for this address and refused, or null if no change is pending. */
  public static IdentityKey getPendingIdentity(final SignalProtocolAddress address) {
    if (address == null || sInstance.mAccount == null) return null;
    return sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .getPendingIdentity(address);
  }

  public static MessageType getMessageType(final MessageEnvelope messageEnvelope) {
    Log.d(TAG, "Getting message type...");
    if (messageEnvelope == null) return null;

    if (messageEnvelope.getPreKeyResponse() != null && messageEnvelope.getCiphertextMessage() != null) {
      Log.d(TAG, "UPDATED_PRE_KEY_MESSAGE_WITH_CONTENT detected...");
      return MessageType.UPDATED_PRE_KEY_RESPONSE_MESSAGE_AND_SIGNAL_MESSAGE;
    } else if (messageEnvelope.getPreKeyResponse() != null) {
      Log.d(TAG, "PRE_KEY_RESPONSE_MESSAGE detected...");
      return MessageType.PRE_KEY_RESPONSE_MESSAGE;
    } else if (messageEnvelope.getCiphertextMessage() != null) {
      Log.d(TAG, "SIGNAL_MESSAGE detected...");
      // hint: PreKeySignalMessage or SignalMessage (ciphertextType is only set here!)
      sInstance.logMessageType(messageEnvelope.getCiphertextType());
      return MessageType.SIGNAL_MESSAGE;
    }
    return null;
  }

  public static Object extractContactFromMessageEnvelope(final MessageEnvelope messageEnvelope) {
    Log.d(TAG, "Extracting contact from message envelope...");
    return sInstance.extractContactFromEnvelope(messageEnvelope);
  }


  /** The bundle text a user hands to a contact through a channel they trust. */
  public static String exportOwnKeyBundle() throws java.io.IOException {
    final MessageEnvelope envelope = getPreKeyResponseMessage();
    if (envelope == null) return null;
    return com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec.toWire(envelope);
  }

  /**
   * Imports a bundle the user received out of band and establishes a session from it.
   *
   * @return true only if the bundle parsed, its signatures held, and a session was built.
   */
  public static boolean importOutOfBandKeyBundle(final String wireText,
                                                 final SignalProtocolAddress address) {
    if (wireText == null || address == null) return false;
    final MessageEnvelope envelope;
    try {
      envelope = com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec
          .fromWire(wireText);
    } catch (java.io.IOException e) {
      Log.e(TAG, "Out-of-band bundle could not be parsed", e);
      return false;
    }
    if (envelope.getPreKeyResponse() == null) {
      Log.e(TAG, "Out-of-band text is not a key bundle");
      return false;
    }
    if (!processPreKeyResponseMessage(envelope, address)) return false;

    // Record provenance only here, where the out-of-band transfer was actually observed, and only
    // after the session genuinely established.
    sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore().markKeyOutOfBand(address);
    sInstance.storeAllAccountInformationInSharedPreferences();
    return true;
  }

  /**
   * Whether this contact's key has actually been checked against the peer.
   *
   * <p>Only an explicit safety-number comparison counts. An earlier version also accepted
   * out-of-band provenance as equivalent, on the reasoning that a key which never touched the
   * messenger needs no further check. That was wrong in a way worth spelling out, because the two
   * look similar and are not:
   *
   * <ul>
   *   <li>A comparison is verified <em>against the peer's own device</em>. Somebody reads the
   *       number back and it either matches or it does not.
   *   <li>A transfer is verified against nothing. The bundle text is byte-identical either way —
   *       {@code exportOwnKeyBundle} produces exactly what the invite flow sends — so the code can
   *       only observe that the import method was called, not how the bytes travelled. A user who
   *       copies an invite out of the messenger and pastes it into an import field produces a key
   *       that travelled entirely in-band and is stamped out-of-band.
   * </ul>
   *
   * <p>Worse, granting trust here <em>suppressed</em> the prompt to compare, so on first contact a
   * substituted bundle imported this way was promoted from "unverified pin" to "checked" — the
   * mechanism intended to close the first-contact gap widened it.
   *
   * <p>Provenance is still recorded; it is simply information about how a key arrived, not a
   * substitute for checking it.
   */
  public static boolean isContactKeyTrustworthy(final Contact contact) {
    if (contact == null) return false;
    if (!contact.isVerified()) return false;
    if (sInstance.mAccount == null) return false;
    // A verified badge must not outlive the key it was granted for.
    return !sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .hasUnacceptedIdentityChange(contact.getSignalProtocolAddress());
  }

  public static Contact addContact(final CharSequence firstName, final CharSequence lastName, final String signalProtocolAddressName, final int deviceId) throws DuplicateContactException, InvalidContactException {
    Log.d(TAG, "Creating and adding contact to contact list...");
    return sInstance.createAndAddContactToList(firstName, lastName, signalProtocolAddressName, deviceId);
  }

  public static ArrayList<Contact> getContactList() {
    Log.d(TAG, "Getting contact list...");
    return sInstance.getContactListFromAccount();
  }

  public static void removeContactFromContactListAndProtocol(final Contact contact) {
    Log.d(TAG, "Removing contact from contact list and protocol...");
    sInstance.removeContact(contact);
  }

  public static Fingerprint getFingerprint(Contact contact) {
    Log.d(TAG, "Generating fingerprint...");
    return sInstance.createFingerprint(contact);
  }

  /**
   * Marks a contact verified after the user compared safety numbers.
   *
   * @return false if the request was refused - the caller MUST report that. The refusal happens
   *     when a substituted identity is pending, and it is silent from the user's point of view: the
   *     screen advances and the badge simply never appears. A refusal the user cannot see teaches
   *     them the badge is unreliable, which is the same damage as showing a wrong badge.
   */
  public static boolean verifyContact(Contact contact) throws UnknownContactException {
    Log.d(TAG, "Verifying contact...");
    return sInstance.verifyContactInContactList(contact);
  }

  private boolean verifyContactInContactList(Contact contact) throws UnknownContactException {
    if (contact == null || mAccount == null) return false;

    // A pending identity change is DISMISSED here, not accepted.
    //
    // This is safe for exactly one reason, and it is a coupling rather than a local property:
    // createFingerprint always derives the displayed number from the PINNED key, never from the
    // offered one (see the comment there, and TrustScopingTest /
    // VerifyContactTest#theDisplayedFingerprintTracksThePinnedKeyNotThePendingOne). So a user
    // pressing this button has just compared the key already in use and found it correct. The
    // right response to "the key I have is the right one" is to throw away the key somebody else
    // offered - which is what dismissing does. The pin never moves.
    //
    // If createFingerprint is ever changed to show the offered key while a change is pending, this
    // becomes a one-tap adopt of an attacker's key and must be changed back to a refusal first.
    // VerifyContactTest#verifyingWouldBeUnsafeIfTheOfferedKeyWereEverDisplayed exists to make that
    // change fail loudly instead of silently.
    //
    // The alternative - refusing outright - was tried and is worse: it made the pending state
    // terminal, so one forged bundle from anyone who knows the address permanently destroyed the
    // contact's badge with no way back. Deleting the contact as the exit is worse still, because it
    // surrenders the pin and opens a substitution window on the app's generic "delete and
    // re-invite" advice, which an attacker can trigger by replaying any message.
    // Update the contact row FIRST: it throws UnknownContactException, and dismissing before it
    // left the store mutated in memory and never persisted when it did.
    contact.setVerified(true);
    mAccount.updateContactInContactList(contact);

    if (mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .dismissIdentityChange(contact.getSignalProtocolAddress())) {
      Log.i(TAG, "Discarded an offered identity for " + contact.getSignalProtocolAddressName()
          + " because the user confirmed the number of the key already pinned");
    }

    storeAllAccountInformationInSharedPreferences();
    return true;
  }

  /**
   * Drops a contact's verified badge when a different identity is offered for them.
   *
   * <p>{@code verified} is a sticky boolean on the contact row that nothing ever reset, and
   * {@code isContactKeyTrustworthy} short-circuits on it without consulting the store — so a
   * contact verified against one key kept showing a green badge after somebody presented another.
   * "Verified" has to mean the key in use was compared, not that some key once was.
   */
  private void clearVerificationFor(final SignalProtocolAddress address) {
    if (mAccount == null || mAccount.getContactList() == null) return;
    for (final Contact contact : mAccount.getContactList()) {
      if (contact.getSignalProtocolAddress().equals(address) && contact.isVerified()) {
        Log.w(TAG, "Clearing verification for " + contact.getSignalProtocolAddressName()
            + ": a different identity key was offered");
        contact.setVerified(false);
      }
    }
  }

  private Fingerprint createFingerprint(Contact contact) {
    if (contact == null || getAccount() == null) return null;

    final IdentityKey localIdentity = getAccount().getIdentityKeyPair().getPublicKey();

    // Always the PINNED key, never a pending one: the number on screen must describe the key
    // actually in use. Prefer the session's copy, falling back to the stored identity, so
    // verification is possible as soon as a bundle has been processed - before any message is
    // exchanged, which is when a user ought to compare safety numbers.
    IdentityKey remoteIdentity = getAccount().getSignalProtocolStore().getSessionStore()
        .getPublicKeyFromSession(contact.getSignalProtocolAddress());
    if (remoteIdentity == null) {
      remoteIdentity = getAccount().getSignalProtocolStore().getIdentityKeyStore()
          .getIdentity(contact.getSignalProtocolAddress());
    }

    // Was '&&'. localIdentity is essentially never null, so a missing remote identity fell straight
    // through into NumericFingerprintGenerator and NPE'd inside libsignal - crashing the keyboard
    // whenever the user opened "verify contact" without a session.
    if (localIdentity == null || remoteIdentity == null) {
      Log.w(TAG, "No identity available for " + contact.getSignalProtocolAddressName()
          + "; cannot build a fingerprint yet");
      return null;
    }

    final int version = 2; // use UUID
    final byte[] localId = getAccount().getSignalProtocolAddress().getName().getBytes();
    final byte[] remoteId = contact.getSignalProtocolAddress().getName().getBytes();

    NumericFingerprintGenerator numericFingerprintGenerator = new NumericFingerprintGenerator(5200);

    return numericFingerprintGenerator.createFor(version,
        localId, localIdentity,
        remoteId, remoteIdentity);
  }

  private ArrayList<Contact> getContactListFromAccount() {
    if (mAccount != null) {
      return mAccount.getContactList();
    }
    return null;
  }

  private Contact extractContactFromEnvelope(MessageEnvelope messageEnvelope) {
    // The device id here comes straight off the wire from the peer. A 0.1.5 peer generated it with
    // nextInt(10000), so ~99% are outside libsignal's [1,127] and the raw constructor throws an
    // unchecked IllegalArgumentException - which nothing on the clipboard-decrypt path catches, so
    // it killed the IME process on any message from a legacy contact.
    final SignalProtocolAddress signalProtocolAddress = ProtocolAddresses.of(messageEnvelope.signalProtocolAddressName, messageEnvelope.getDeviceId());
    return getContactFromAddressInContactList(signalProtocolAddress);
  }

  private Contact getContactFromAddressInContactList(SignalProtocolAddress signalProtocolAddress) {
    ArrayList<Contact> contacts = getContactListFromAccount();
    if (contacts == null) return null;
    return contacts.stream().filter(c -> c.getSignalProtocolAddress().equals(signalProtocolAddress)).findFirst().orElse(null);
  }

  private Contact createAndAddContactToList(final CharSequence firstName, final CharSequence lastName, final String signalProtocolAddressName, final int deviceId) throws DuplicateContactException, InvalidContactException {
    if (firstName == null || firstName.length() == 0 || signalProtocolAddressName == null || deviceId == 0)
      throw new InvalidContactException("Error: Contact is invalid. Some information is missing!");

    final Contact recipient = new Contact(String.valueOf(firstName), String.valueOf(lastName), signalProtocolAddressName, deviceId, false);
    mAccount.addContactToContactList(recipient);
    storeAllAccountInformationInSharedPreferences();
    return recipient;
  }

  private void removeContact(final Contact contactToRemove) {
    ArrayList<Contact> contacts = getContactListFromAccount();
    if (contacts == null) return;

    Log.d(TAG, "Deleting contact from contact list: " + contactToRemove.getFirstName() + " " + contactToRemove.getLastName());
    ArrayList<Contact> newContacts = new ArrayList<>();
    for (Contact contact : contacts) {
      if (!contact.equals(contactToRemove)) {
        newContacts.add(contact);
      }
    }
    mAccount.setContactList(newContacts);

    Log.d(TAG, "Deleting session for contact: " + contactToRemove.getFirstName() + " " + contactToRemove.getLastName());
    if (mAccount.getSignalProtocolStore().getSessionStore().containsSession(contactToRemove.getSignalProtocolAddress())) {
      mAccount.getSignalProtocolStore().getSessionStore().deleteSession(contactToRemove.getSignalProtocolAddress());
    }

    Log.d(TAG, "Deleting unencrypted messages from contact: " + contactToRemove.getFirstName() + " " + contactToRemove.getLastName());
    mAccount.removeAllUnencryptedMessages(contactToRemove);

    // Deliberately does NOT clear the pinned identity.
    //
    // This has now been argued in both directions and settled here, so the reasoning is worth
    // keeping in full.
    //
    // Clearing it opens a fail-open path: the user is shown "delete the contact and ask for a new
    // invite" advice, follows it, and whatever key arrives next is accepted as a clean first
    // sighting. Crucially that advice is NOT specific to an identity change - it is the app's
    // standard response to any decryption failure, and the messenger can induce one at will by
    // replaying a message (DuplicateMessageException) or flipping a bit (InvalidMessageException),
    // neither of which records a pending change. So warning on the identity-change branch cannot
    // close this; the attacker simply uses a branch that has no warning on it. The pin outliving
    // the contact is what makes all of those fail closed.
    //
    // A previous revision cleared the pin here, to escape a real problem: a pending identity change
    // was a terminal state that an attacker could force with one forged bundle and nobody could
    // leave, permanently destroying the contact's verified badge. That problem is real, but
    // deletion was the wrong exit - it traded a denial of service for a key-substitution window.
    // The right exit is dismissIdentityChange: drop the offered key, KEEP the pin. Nothing about
    // the pinned key changed, the safety number on screen is still computed from it, so the badge
    // can be restored by re-comparing the very same number. See its javadoc.
    //
    // The sanctioned ways out of a pending change are therefore: dismiss (keep the pinned key), or
    // acceptIdentityChange after comparing the offered number out of band - never deletion.

    storeAllAccountInformationInSharedPreferences();
  }

  public static List<StorageMessage> getUnencryptedMessagesList(Contact contact) throws UnknownContactException {
    Log.d(TAG, "Getting unencrypted messages list...");
    return sInstance.getUnencryptedMessagesListFromAccount(contact);
  }

  private List<StorageMessage> getUnencryptedMessagesListFromAccount(Contact contact) throws UnknownContactException {
    if (mAccount != null && contact != null) {
      List<StorageMessage> messagesWithContact = mAccount.getUnencryptedMessages().stream().filter(m -> m.getContactUUID().equals(contact.getSignalProtocolAddressName())).collect(Collectors.toList());
      if (messagesWithContact.size() == 0) {
        throw new UnknownContactException("No messages were found for contact: " + contact.getFirstName() + " " + contact.getLastName());
      }
      return messagesWithContact;
    }
    return null;
  }

  public static String getNameOfAccount() {
    Log.d(TAG, "Getting account name (uuid)...");
    return sInstance.getAccountName();
  }

  private String getAccountName() {
    return String.valueOf(getAccount().getName());
  }

  private MessageEnvelope encrypt(final String unencryptedMessage, final SignalProtocolAddress signalProtocolAddress) {
    if (unencryptedMessage == null || signalProtocolAddress == null) return null;
    try {
      MessageEnvelope messageEnvelope = null;
      // check age of signedPreKey and generate new one if necessary (and delete old ones after archive age)
      if (KeyUtil.refreshSignedPreKeyIfNecessary(mAccount.getSignalProtocolStore(), mAccount.getMetadataStore())) {
        // signed pre key was refreshed -> send new preKeyBundle together with message
        messageEnvelope = getPreKeyResponseMessage();
      }

      final SessionCipher sessionCipher = new SessionCipher(mAccount.getSignalProtocolStore(), signalProtocolAddress);
      final CiphertextMessage ciphertextMessage;
      try {
        ciphertextMessage = sessionCipher.encrypt(unencryptedMessage.getBytes());
      } catch (NoSessionException e) {
        // New in libsignal 0.86: encrypting with no established session is an explicit failure
        // rather than an implicit one. Returning null here matches how this method already reports
        // failure, and crucially avoids sending anything unencrypted.
        Log.e(TAG, "Error: no session with " + signalProtocolAddress.getName()
            + "; cannot encrypt", e);
        return null;
      }
      logMessageType(ciphertextMessage.getType());

      if (messageEnvelope == null) {
        messageEnvelope = new MessageEnvelope(ciphertextMessage.serialize(), ciphertextMessage.getType(), mAccount.getName(), mAccount.getDeviceId());
      } else {
        // add ciphertextMessage with type to preKeyResponse message
        messageEnvelope.setCiphertextMessage(ciphertextMessage.serialize());
        messageEnvelope.setCiphertextType(ciphertextMessage.getType());
        Log.d(TAG, "Signed pre key rotated. Adding ciphertextMessage...");
      }

      // store unencrypted message somewhere with recipient in map
      Log.d(TAG, "Attempting to save unencrypted message...");
      storeUnencryptedMessageInMap(mAccount, signalProtocolAddress, unencryptedMessage, Instant.ofEpochMilli(messageEnvelope.getTimestamp()), true);

      storeAllAccountInformationInSharedPreferences();

      return messageEnvelope;
    } catch (UntrustedIdentityException e) {
      // No key to record here — the exception carries none, and unlike the bundle path there is no
      // offered key to hand. The change is recorded where a substituted bundle is processed; this
      // is the downstream refusal, which must simply not send.
      Log.e(TAG, "Identity key mismatch while encrypting to " + signalProtocolAddress.getName());
      return null;
    } catch (InvalidContactException e) {
      e.printStackTrace();
    }
    return null;
  }

  private void logMessageType(int type) {
    if (type == 3) {
      Log.d(TAG, "CiphertextMessage = PRE_KEY");
    } else if (type == 2) {
      Log.d(TAG, "CiphertextMessage = WHISPER_TYPE");
    }
  }

  private boolean processPreKeyResponse(final MessageEnvelope messageEnvelope, final SignalProtocolAddress signalProtocolAddress) {
    if (messageEnvelope == null) return false;
    try {
      // build session with recipients protocol address when preKeyResponse was send
      if (messageEnvelope.getPreKeyResponse() != null) {
        final PreKeyBundle preKeyBundle = createPreKeyBundle(messageEnvelope.getPreKeyResponse());

        if (!buildSession(preKeyBundle, signalProtocolAddress)) {
          // Signature verification or an identity change. Report failure so the UI stops telling
          // the user a session was created when none was.
          Log.e(TAG, "Session could not be built - bundle rejected by libsignal");
          return false;
        }
        Log.d(TAG, "Session with PreKeyBundle created: " + sessionExists(signalProtocolAddress));
        Log.d(TAG, "Amount of pre key ids: " + mAccount.getSignalProtocolStore().getPreKeyStore().getSize());
        storeAllAccountInformationInSharedPreferences();
      }
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }

  private String decrypt(final MessageEnvelope messageEnvelope, final SignalProtocolAddress signalProtocolAddress) throws InvalidContactException, UnknownMessageException, InvalidMessageException, InvalidVersionException, LegacyMessageException, InvalidKeyException, UntrustedIdentityException, DuplicateMessageException, InvalidKeyIdException, NoSessionException {
    if (messageEnvelope == null) return null;
    String decryptedMessage;

    final SessionCipher sessionCipher = new SessionCipher(mAccount.getSignalProtocolStore(), signalProtocolAddress);

    // Process an attached bundle whenever there IS one - not only when a ciphertext accompanies it.
    //
    // The narrower guard was a regression: E2EEStrip.decryptMessage used to call this for any
    // envelope carrying a bundle, and removing that call on the grounds that "decrypt performs
    // exactly this check" was wrong, because this check also required a ciphertext. A bundle-only
    // re-invite from an existing contact therefore fell through to the UnknownMessageException
    // below and the UI showed nothing at all - so a substituted re-invite was a silent no-op.
    if (messageEnvelope.getPreKeyResponse() != null) {
      Log.d(TAG, "Message with cipherText and updated preKeyResponse received...");
      processPreKeyResponseMessage(messageEnvelope, signalProtocolAddress);
    }

    logMessageType(messageEnvelope.getCiphertextType());

    byte[] plaintext;
    if (messageEnvelope.getCiphertextType() == CiphertextMessage.PREKEY_TYPE) {
      // decrypting message and storing session with preKeySignalMessage
      final PreKeySignalMessage preKeySignalMessage = new PreKeySignalMessage(messageEnvelope.getCiphertextMessage());

      Log.d(TAG, "PreKeySignalMessage: Used signed prekey id: " + preKeySignalMessage.getSignedPreKeyId());

      try {
        plaintext = sessionCipher.decrypt(preKeySignalMessage);
      } catch (UntrustedIdentityException e) {
        // The other substitution path, and the one an attacker would choose: a PreKeySignalMessage
        // carries its own identity key and needs no attached bundle, so refusing here without
        // recording left hasUnacceptedIdentityChange false and no warning was ever shown. The
        // offered key is on the message itself.
        Log.e(TAG, "Identity key on an incoming pre-key message does not match the pinned one");
        mAccount.getSignalProtocolStore().getIdentityKeyStore()
            .recordIdentityChange(signalProtocolAddress, preKeySignalMessage.getIdentityKey());
        clearVerificationFor(signalProtocolAddress);
        storeAllAccountInformationInSharedPreferences();
        throw e;
      }
      decryptedMessage = new String(plaintext);

      if (preKeySignalMessage.getPreKeyId().isPresent())
        KeyUtil.generateAndStoreOneTimePreKey(mAccount.getSignalProtocolStore(), preKeySignalMessage.getPreKeyId().get());

      Log.d(TAG, "Session with PreKeySignalMessage created (after decryption): " + sessionExists(signalProtocolAddress));
      Log.d(TAG, "Amount of pre key ids: " + mAccount.getSignalProtocolStore().getPreKeyStore().getSize());
    } else if (messageEnvelope.getCiphertextType() == CiphertextMessage.WHISPER_TYPE) {
      // only decrypting message (session already exists)
      plaintext = sessionCipher.decrypt(new SignalMessage(messageEnvelope.getCiphertextMessage()));
      decryptedMessage = new String(plaintext);
      Log.d(TAG, "Amount of pre key ids: " + mAccount.getSignalProtocolStore().getPreKeyStore().getSize());
    } else {
      if (messageEnvelope.getPreKeyResponse() != null) {
        // A bundle with no ciphertext is legitimate - it is what a re-invite looks like. The bundle
        // was handled above; there is simply no plaintext to return.
        return null;
      }
      throw new UnknownMessageException("Received message is not of type PRE_KEY or WHISPER_TYPE");
    }

    if (plaintext != null) {
      // store unencrypted message somewhere with recipient in map
      Log.d(TAG, "Attempting to save unencrypted message...");
      storeUnencryptedMessageInMap(mAccount, signalProtocolAddress, decryptedMessage, Instant.ofEpochMilli(messageEnvelope.getTimestamp()), false);
    }
    storeAllAccountInformationInSharedPreferences();

    return decryptedMessage;
  }

  public PreKeyBundle createPreKeyBundle(PreKeyResponse preKeyResponse) throws IOException {
    if (preKeyResponse.getDevices() == null || preKeyResponse.getDevices().size() < 1)
      throw new IOException("Empty prekey list");

    PreKeyResponseItem device = preKeyResponse.getDevices().get(0);
    ECPublicKey preKey = null;
    ECPublicKey signedPreKey = null;
    byte[] signedPreKeySignature = null;
    int preKeyId = -1;
    int signedPreKeyId = -1;

    if (device.getPreKey() != null) {
      preKeyId = device.getPreKey().getKeyId();
      preKey = device.getPreKey().getPublicKey();
    }

    if (device.getSignedPreKey() != null) {
      signedPreKeyId = device.getSignedPreKey().getKeyId();
      signedPreKey = device.getSignedPreKey().getPublicKey();
      signedPreKeySignature = device.getSignedPreKey().getSignature();
    }

    if (device.getKyberPreKey() == null) {
      // A bundle from a pre-PQXDH peer. This libsignal has no classical-only PreKeyBundle, and
      // silently downgrading to X3DH is not an option, so fail loudly rather than appear to
      // succeed. IOException to match how this method already reports a malformed bundle.
      throw new IOException("peer bundle has no kyber pre key (pre-PQXDH sender)");
    }

    return new PreKeyBundle(device.getRegistrationId(), device.getDeviceId(), preKeyId, preKey,
        signedPreKeyId, signedPreKey, signedPreKeySignature, preKeyResponse.getIdentityKey(),
        device.getKyberPreKey().getKeyId(), device.getKyberPreKey().getPublicKey(),
        device.getKyberPreKey().getSignature());
  }

  private void storeUnencryptedMessageInMap(Account account, SignalProtocolAddress signalProtocolAddress, final String decryptedMessage, final Instant timestamp, final boolean isFromOwnAccount) throws InvalidContactException {
    final Optional<Contact> recipient;
    if (testIsRunning) {
      // for running tests only!
      recipient = Optional.of(new Contact("test", "test", signalProtocolAddress.getName(), signalProtocolAddress.getDeviceId(), false));
    } else {
      recipient = getContactList().stream().filter(c -> c.getSignalProtocolAddress().equals(signalProtocolAddress)).findFirst();
    }

    if (!recipient.isPresent())
      throw new InvalidContactException("No contact found with signalProtocolAddress: " + signalProtocolAddress);

    StorageMessage storageMessage;
    if (isFromOwnAccount) {
      storageMessage = new StorageMessage(signalProtocolAddress.getName(), account.getSignalProtocolAddress().getName(), signalProtocolAddress.getName(), timestamp, decryptedMessage);
    } else {
      storageMessage = new StorageMessage(signalProtocolAddress.getName(), signalProtocolAddress.getName(), account.getSignalProtocolAddress().getName(), timestamp, decryptedMessage);
    }

    recipient.ifPresent(contact -> account.addUnencryptedMessage(contact, storageMessage));
  }

  private boolean sessionExists(SignalProtocolAddress signalProtocolAddress) {
    return mAccount.getSignalProtocolStore().containsSession(signalProtocolAddress);
  }

  private MessageEnvelope createPreKeyResponseMessage() {
    try {
      final PreKeyResponse preKeyResponse = createPreKeyResponse();
      return new MessageEnvelope(preKeyResponse, mAccount.getSignalProtocolAddress().getName(), mAccount.getSignalProtocolAddress().getDeviceId());
    } catch (InvalidKeyIdException | InvalidKeyException e) {
      Log.e(TAG, "Error: Creating pre key response message failed");
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Create signature from identity private key and create pre key bundle
   *
   * @return PreKeyBundle
   * @throws InvalidKeyIdException InvalidKeyIdException
   * @throws InvalidKeyException   InvalidKeyException
   */
  private PreKeyBundle getPreKeyBundle() throws InvalidKeyIdException, InvalidKeyException {
    // check age of signedPreKey and generate new one if necessary (and delete old ones after archive age)
    KeyUtil.refreshSignedPreKeyIfNecessary(mAccount.getSignalProtocolStore(), mAccount.getMetadataStore());

    final byte[] signedPreKeySignature = mAccount.getSignalProtocolStore().getIdentityKeyPair().getPrivateKey()
        .calculateSignature(mAccount.getSignalProtocolStore().loadSignedPreKey(mAccount.getMetadataStore().getActiveSignedPreKeyId()).getKeyPair().getPublicKey().serialize());

    final Integer allocatedPreKeyId = KeyUtil.getUnusedOneTimePreKeyId(
        mAccount.getSignalProtocolStore(), mAccount.getMetadataStore());
    if (allocatedPreKeyId == null) {
      // Unboxing a null here previously produced an NPE out of the bundle path.
      throw new InvalidKeyIdException("could not allocate a one-time pre key");
    }
    final int preKeyId = allocatedPreKeyId;

    // PQXDH: libsignal 0.86.x has no non-Kyber PreKeyBundle constructor, so a Kyber pre key is
    // mandatory rather than optional. Generate one lazily for accounts created before this upgrade.
    if (!mAccount.getSignalProtocolStore().containsKyberPreKey(mAccount.getMetadataStore().getActiveKyberPreKeyId())) {
      KeyUtil.generateAndStoreKyberPreKey(mAccount.getSignalProtocolStore(), mAccount.getMetadataStore());
      // Persist immediately. This method hands the public half to a peer; if the private half is
      // still only in memory when the keyboard is dismissed, reloadAccount() replaces the account
      // from disk and the key is gone. The peer then builds a session against a key we can never
      // use, and ML-KEM implicit rejection turns that into an opaque MAC failure rather than a
      // diagnosable error.
      storeAllAccountInformationInSharedPreferences();
    }
    final int kyberPreKeyId = mAccount.getMetadataStore().getActiveKyberPreKeyId();
    final KyberPreKeyRecord kyberRecord = mAccount.getSignalProtocolStore().loadKyberPreKey(kyberPreKeyId);

    Log.d(TAG, "Generating PreKeyBundle with pre key id: " + preKeyId
        + " and kyber pre key id: " + kyberPreKeyId);
    final PreKeyBundle preKeyBundle = new PreKeyBundle(
        mAccount.getSignalProtocolStore().getLocalRegistrationId(),
        mAccount.getDeviceId(),
        preKeyId,
        mAccount.getSignalProtocolStore().loadPreKey(preKeyId).getKeyPair().getPublicKey(),
        mAccount.getMetadataStore().getActiveSignedPreKeyId(),
        mAccount.getSignalProtocolStore().loadSignedPreKey(mAccount.getMetadataStore().getActiveSignedPreKeyId()).getKeyPair().getPublicKey(),
        signedPreKeySignature,
        mAccount.getSignalProtocolStore().getIdentityKeyPair().getPublicKey(),
        kyberPreKeyId,
        kyberRecord.getKeyPair().getPublicKey(),
        kyberRecord.getSignature());

    return preKeyBundle;
  }

  private PreKeyResponse createPreKeyResponse() throws InvalidKeyIdException, InvalidKeyException {
    final PreKeyBundle preKeyBundle = getPreKeyBundle();

    List<PreKeyResponseItem> responseItems = new LinkedList<>();
    responseItems.add(new PreKeyResponseItem(
        preKeyBundle.getDeviceId(),
        preKeyBundle.getRegistrationId(),
        new SignedPreKeyEntity(preKeyBundle.getSignedPreKeyId(), preKeyBundle.getSignedPreKey(), preKeyBundle.getSignedPreKeySignature()),
        new PreKeyEntity(preKeyBundle.getPreKeyId(), preKeyBundle.getPreKey()),
        new KyberPreKeyEntity(preKeyBundle.getKyberPreKeyId(), preKeyBundle.getKyberPreKey(),
            preKeyBundle.getKyberPreKeySignature())));

    return new PreKeyResponse(preKeyBundle.getIdentityKey(), responseItems);
  }

  /**
   * Instantiate a SessionBuilder for a remote recipientId + deviceId tuple
   *
   * @param preKeyBundle                   PreKeyBundle
   * @param recipientSignalProtocolAddress SignalProtocolAddress
   */
  /** @return true only if the session was actually established and the bundle's signatures held. */
  private boolean buildSession(final PreKeyBundle preKeyBundle, final SignalProtocolAddress recipientSignalProtocolAddress) {
    try {
      SessionBuilder sessionBuilder = new SessionBuilder(mAccount.getSignalProtocolStore(), recipientSignalProtocolAddress);
      sessionBuilder.process(preKeyBundle);
      storeAllAccountInformationInSharedPreferences();
      return true;
    } catch (UntrustedIdentityException e) {
      // This is where an identity change actually surfaces. libsignal calls isTrustedIdentity
      // before saveIdentity, so refusing there means saveIdentity never runs — recording the change
      // here is the only way the flag can ever be set.
      Log.e(TAG, "Identity key for " + recipientSignalProtocolAddress.getName()
          + " does not match the pinned one");
      // Take the offered key from the bundle, not from the exception: libsignal 0.86 raises this
      // from its Rust layer with a null identity, so getUntrustedIdentity() is empty here. The
      // bundle we just failed to process is the thing that carried the substituted key.
      mAccount.getSignalProtocolStore().getIdentityKeyStore()
          .recordIdentityChange(recipientSignalProtocolAddress, preKeyBundle.getIdentityKey());
      clearVerificationFor(recipientSignalProtocolAddress);
      storeAllAccountInformationInSharedPreferences();
      return false;
    } catch (InvalidKeyException e) {
      // Signature verification on the bundle failed. Swallowing this into a log made a forged
      // bundle report "session created" to the user.
      Log.e(TAG, "Error: Building session with recipient id " + recipientSignalProtocolAddress.getName() + " failed");
      e.printStackTrace();
      return false;
    }
  }

  /**
   * Initializes the protocol by generating and storing all necessary keys and stores
   */
  private void initializeProtocol() {
    final String uniqueUserId = UUID.randomUUID().toString();
    final int deviceId = ProtocolAddresses.generateDeviceId();
    final SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(uniqueUserId, deviceId);
    final PreKeyMetadataStore metadataStore = new PreKeyMetadataStoreImpl();

    // generate IdentityKeyPair, registrationId
    final IdentityKeyPair identityKeyPair = KeyUtil.generateIdentityKeyPair();
    final int registrationId = KeyUtil.generateRegistrationId();

    // generate new signalProtocolStore
    final SignalProtocolStoreImpl signalProtocolStore = new SignalProtocolStoreImpl(identityKeyPair, registrationId);

    // generate and store preKeys in PreKeyStore
    KeyUtil.generateAndStoreOneTimePreKeys(signalProtocolStore, metadataStore);

    // generate and store signed prekey in SignedPreKeyStore
    final SignedPreKeyRecord signedPreKey = KeyUtil.generateAndStoreSignedPreKey(signalProtocolStore, metadataStore);

    metadataStore.setActiveSignedPreKeyId(signedPreKey.getId());
    metadataStore.setSignedPreKeyRegistered(true);

    // create account for device
    mAccount = new Account(uniqueUserId, deviceId, identityKeyPair, metadataStore, signalProtocolStore, signalProtocolAddress);

    storeAllAccountInformationInSharedPreferences();
  }

  private void reloadAccountFromSharedPreferences() {
    if (mStorageHelper == null) {
      Log.e(TAG, "Error: cannot reload account, storage helper is null");
      return;
    }
    final Account loaded = mStorageHelper.getAccountFromSharedPreferences();
    if (loaded == null) {
      // Keep whatever is already in memory. setInputView() runs on every input-view creation
      // (rotation, theme change), so a transient storage failure must not discard a live account
      // mid-conversation.
      Log.e(TAG, "Error: account could not be loaded; keeping the existing in-memory account");
      return;
    }
    mAccount = loaded;
  }

  private void storeAllAccountInformationInSharedPreferences() {
    if (mAccount == null) {
      Log.e(TAG, "Error: No protocol resources were stored (mAccount is null)");
      return;
    }
    if (mStorageHelper != null) {
      mStorageHelper.storeAllInformationInSharedPreferences(mAccount);
    } else {
      Log.e(TAG, "Error: No protocol resources were stored (mStorageHelper is null)");
    }
  }

  private void initializeStorageHelper(Context context) {
    if (context == null) {
      Log.e(TAG, "Error: mStorageHelper cannot get initialized because context is null");
      return;
    }
    mStorageHelper = new StorageHelper(context);
  }

  // needed for testing only
  public Account getAccount() {
    return mAccount;
  }

  // needed for testing only
  public void setAccount(final Account account) {
    this.mAccount = account;
  }
}
