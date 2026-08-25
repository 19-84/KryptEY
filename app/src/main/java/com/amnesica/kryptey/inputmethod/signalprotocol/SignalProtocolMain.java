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
      // Write back, as reloadAccount does. Without it, a store predating a persisted field gets one
      // raise with the freshly minted value un-persisted - so the display tags churn once and then
      // settle, which is the same bug this branch's sibling was fixed for. Guarded because storing
      // a partially-loaded account would overwrite good data with blanks.
      if (sInstance.mAccount != null) {
        sInstance.storeAllAccountInformationInSharedPreferences();
      }
      return sInstance.mAccount != null;
    }

    sInstance.initializeProtocol();
    if (sInstance.mAccount == null) return false;

    // Only report success if the identity actually reached disk. Storage now depends on the
    // Keystore and can fail; a caller that recorded "setup done" after a failed write would come
    // back on the next raise, find nothing stored, and generate a different identity.
    return sInstance.mStorageHelper != null && sInstance.mStorageHelper.hasExistingProtocolData();
  }

  /**
   * Why there is no account, when there is no account - see {@link StorageHelper.StorageState}.
   *
   * <p>Reports {@code NONE} when nothing has been initialised, which is indistinguishable from a
   * fresh install and is the correct answer for one.
   */
  public static StorageHelper.StorageState storageState() {
    if (storageStateForTest != null) return storageStateForTest;
    if (sInstance.mStorageHelper == null) return StorageHelper.StorageState.NONE;
    return sInstance.mStorageHelper.storageState();
  }

  private static StorageHelper.StorageState storageStateForTest;

  /**
   * Forces the reported storage state, for tests only.
   *
   * <p>Reaching UNREADABLE through the real path needs a Keystore-backed box, which has no JVM
   * implementation - so without this the one state the warning exists for cannot be reached from a
   * test of the view at all.
   */
  public static void setStorageStateForTest(final StorageHelper.StorageState state) {
    storageStateForTest = state;
  }

  public static void reloadAccount(final Context context) {
    Log.d(TAG, "Reloading local account for signal protocol (not first app run)...");
    sInstance.initializeStorageHelper(context);
    sInstance.reloadAccountFromSharedPreferences();
    // Only write back if we actually loaded something.
    //
    // Defence in depth, not the thing preventing the NPE: an earlier version of this comment said
    // "storing a null account here would NPE", and that is not true -
    // storeAllAccountInformationInSharedPreferences guards null itself and logs. So inverting this
    // condition is an equivalent mutant, which is why mutation testing flags it and no test can
    // kill it. It is kept because the inner guard is in a different method and a future refactor
    // that removes it would otherwise turn a failed reload into an overwrite with blanks.
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
   * someone is impersonating them: a reinstall mints a fresh address, so it can never collide
   * with an existing pin (see AddressingPremiseTest).
   *
   * <p>What the flag does NOT do is block sending. An earlier version of this sentence said the
   * store refuses to send until it is acknowledged; it does not, and never did. Encryption
   * continues to the key already pinned - which is the right behaviour, since the offered key was
   * refused and the old one is still the one the user compared - but a reader who believed the
   * comment would think there was an interlock here that has to be satisfied. There is not. This
   * flag exists so the UI can say why a decryption failed and keep saying it.
   */
  public static boolean hasUnacceptedIdentityChange(final SignalProtocolAddress address) {
    if (address == null || sInstance.mAccount == null) return false;
    return sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .hasUnacceptedIdentityChange(address);
  }

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
   * <p>An earlier version of this note claimed the control was safe because "an attacker cannot
   * deliver the user to the verify screen, and cannot make the comparison fail for a genuine peer".
   * <b>Both halves were false</b>, and the second was already written down as a known-deferred
   * defect in REVIVAL.md while this claimed the opposite:
   *
   * <ul>
   *   <li>The identity-change warning tells the user, in as many words, to open the contact and
   *       compare the number — so the app's own text routes them here, and any forged bundle at a
   *       known address triggers it.
   *   <li><b>Fixed since.</b> Safety numbers <em>were</em> computed over the peer-supplied address
   *       name, which neither the bundle signatures nor the message MAC cover, so a messenger could
   *       rewrite that field and manufacture unlimited <em>mismatches</em> between honest peers.
   *       {@code createFingerprint} now derives the number from the two identity keys alone, so
   *       this route is closed. Kept here because the first bullet still holds on its own, and
   *       because the conclusion below was reached partly on this basis.
   * </ul>
   *
   * <p>So an attacker <em>can</em> arrange for an honest user to arrive here and correctly observe a
   * mismatch. That does not make the control wrong — a user who sees a mismatch must be able to act
   * — but it means the state left behind cannot be assumed benign. Hence
   * {@code markKeyRejected}: the address is remembered as rejected even though the key is gone, so
   * the next bundle to arrive is a <em>warned</em> event rather than a silent first sighting.
   * Without that, re-delivering the forged bundle immediately after the rejection pins it clean.
   *
   * <p>What remains true, and is the actual reason this is safer than clearing the pin on contact
   * deletion: this is reachable only from the verify screen, so it takes a deliberate user action on
   * a specific contact, rather than following generic advice shown after any decryption failure.
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
    // Must outlive the key: without it the address looks untouched and the next bundle - including
    // the forged one that caused this - is pinned silently.
    sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore().markKeyRejected(address);
    if (sInstance.mAccount.getSignalProtocolStore().containsSession(address)) {
      sInstance.mAccount.getSignalProtocolStore().deleteSession(address);
    }
    contact.setVerified(false);
    sInstance.clearVerificationFor(address);
    Log.w(TAG, "Forgot the pinned key for a contact: the user reported the safety number did "
        + "not match");
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
   * @param shown the key the user was actually shown; the change is refused if it no longer matches
   *     what is pending, so a key arriving between display and confirmation cannot slip through
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

    // There must be a key for the badge to be about.
    //
    // verifyContact refuses to SET the badge with nothing pinned; this is the same invariant on the
    // read side. Enforcing it in one place and reading it in another is how the original defect
    // worked - a stale Contact object carrying verified=true was trusted over an address holding no
    // key at all. Not reachable from today's UI, which always renders from the account's live list,
    // but that is a property of the current call sites rather than of this method.
    if (sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .getIdentity(contact.getSignalProtocolAddress()) == null) {
      return false;
    }

    // A standing rejection outranks a verified badge.
    //
    // The user told the app this address's number did not match. Only a fresh comparison retires
    // that - verifyContact calls clearRejection - so while it stands, nothing at this address is
    // trustworthy however the Contact object is marked. Without this, rejecting and then letting
    // the attacker's bundle pin by trust-on-first-use gave back a green badge, because the
    // rejection was recorded in the store and read by nobody.
    if (sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .wasKeyRejected(contact.getSignalProtocolAddress())) {
      return false;
    }

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
   * @return false when there is no contact or no account loaded, AND when nothing is pinned for the
   *     contact's address - there is no key to have compared, so there is nothing to record. This
   *     javadoc used to say "only when there is no contact or no account loaded ... it does NOT mean
   *     refused", and a refusal was added below it without the doc being updated. A caller
   *     rendering false as "nothing is loaded" is now sometimes wrong; it should say that
   *     verification could not be recorded.
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

    // There must be a key to have verified.
    //
    // Placed AFTER updateContactInContactList so an unknown contact still raises
    // UnknownContactException rather than being reported as an ordinary refusal - those are
    // different failures and the caller renders them differently.
    //
    // Nothing here checked, so verifying a contact with no pinned identity set the badge, and
    // isContactKeyTrustworthy then returned true - over a key nobody had compared, because there
    // was no key. When the peer's bundle later arrived and trust-on-first-use pinned it, the
    // contact was ALREADY green. A hostile messenger substituting that first bundle inherits a
    // verified badge it never earned.
    //
    // The only thing preventing it was one runtime setEnabled(false) in the verify screen, in a
    // different file, with no test: deleting both of those lines left the whole suite green, and
    // the layout declares no android:enabled so the default is enabled. An invariant this class
    // depends on has to be enforced by this class.
    //
    // The number the user compares is derived from the pinned key, so with no pin there is
    // literally nothing on screen to have compared - getFingerprint returns null in that state.
    if (mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .getIdentity(contact.getSignalProtocolAddress()) == null) {
      Log.w(TAG, "Refusing to mark a contact verified with no pinned identity key");
      contact.setVerified(false);
      mAccount.updateContactInContactList(contact);
      return false;
    }

    // A fresh comparison is the only thing that retires a rejection warning. Nothing an attacker
    // can trigger clears it.
    if (mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .clearRejection(contact.getSignalProtocolAddress())) {
      Log.i(TAG, "Cleared a rejection warning" + ""
          + ": the user compared the number of the newly pinned key");
    }

    if (mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .dismissIdentityChange(contact.getSignalProtocolAddress())) {
      Log.i(TAG, "Discarded an offered identity" + ""
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
    // Equivalent mutant, recorded rather than chased: weakening this || to && cannot be observed.
    // The method is private and every caller has already established a non-null account, and the
    // contact list is initialised in Account's constructor and never set to null in production. So
    // neither arm is reachable and no test can distinguish the two forms. Kept as defence in depth
    // because the callers' checks are in another method.
    if (mAccount == null || mAccount.getContactList() == null) return;
    for (final Contact contact : mAccount.getContactList()) {
      if (contact.getSignalProtocolAddress().equals(address) && contact.isVerified()) {
        Log.w(TAG, "Clearing verification for a contact" + ""
            + ": a different identity key was offered");
        contact.setVerified(false);
      }
    }
  }

  private Fingerprint createFingerprint(Contact contact) {
    if (contact == null || getAccount() == null) return null;

    final IdentityKey localIdentity = getAccount().getIdentityKeyPair().getPublicKey();

    // The PIN is authoritative, and is read FIRST.
    //
    // This used to prefer the session's copy and fall back to the pin, which contradicted the
    // comment above it and was fail-open: the pin is the thing the user's decisions attach to, and
    // the session merely happens to carry a copy. The two agree on every path today, but they can
    // be made to disagree - acceptIdentityChange moves the pin without touching the session, so
    // wiring it up (which this codebase explicitly contemplates) would have made the verify screen
    // render the OLD key's digits over the NEW pin. That is the mirror of the one-tap-adopt failure
    // the coupling tests exist to prevent.
    //
    // The session is not consulted at all, and that is the point.
    //
    // Reading the pin first but keeping the session as a fallback does not fail closed - the
    // fallback is only ever REACHED when there is no pin, which is exactly the case that must not
    // render. A session without a pin means the key was un-pinned out from under it, and showing
    // its digits invites the user to confirm a key their own decision removed.
    //
    // Nothing legitimate needs the fallback: libsignal stores the identity when it builds a
    // session, so a session implies a pin; removeContact deletes the session and keeps the pin, and
    // is served by the pin; rejectContactKey clears both, and must show nothing. Verification is
    // still available as soon as a bundle has been processed, before any message is exchanged,
    // because processing a bundle is what sets the pin.
    final IdentityKey remoteIdentity = getAccount().getSignalProtocolStore().getIdentityKeyStore()
        .getIdentity(contact.getSignalProtocolAddress());

    // Was '&&'. localIdentity is essentially never null, so a missing remote identity fell straight
    // through into NumericFingerprintGenerator and NPE'd inside libsignal - crashing the keyboard
    // whenever the user opened "verify contact" without a session.
    if (localIdentity == null || remoteIdentity == null) {
      Log.w(TAG, "No identity available for a contact" + ""
          + "; cannot build a fingerprint yet");
      return null;
    }

    // The identifiers are the KEYS, not the addresses.
    //
    // These used to be the two address names, mirroring Signal, where the identifier is a
    // server-attested ACI. KryptEY has no server and nothing attests anything: the remote name is
    // simply whatever the peer wrote in the envelope, a plaintext field covered by neither the
    // bundle signatures nor the message MAC. So a messenger that rewrote that one field
    // consistently in both directions left every key genuine and every message decrypting, while
    // the two sides displayed DIFFERENT safety numbers.
    //
    // That is worse than it sounds. It cannot forge a match - the keys are still in the hash - but
    // it can manufacture unlimited mismatches between two entirely honest peers, at will and
    // undetectably. Every control in this trust model reads a mismatch as evidence of an attack:
    // it drops the badge, and it is the trigger for rejectContactKey, which discards a pin. An
    // adversary who can produce mismatches on demand can therefore walk a careful user into
    // throwing away a correct key, and users who see enough of them stop believing the number.
    //
    // Binding to the keys alone removes the attacker's only input. The number becomes a pure
    // function of the two identity keys, which is exactly what the comparison is meant to check.
    // Nothing is lost by dropping the address: it never carried any authenticated meaning here.
    //
    // Not a wire-format change - the fingerprint is computed independently on both sides and never
    // transmitted. It does change the digits shown for existing contacts once, so anyone who
    // already compared has to compare again.
    //
    // This argument is INERT in libsignal 0.86.5. Measured: versions 0, 1, 2, 3 and 99 all produce
    // byte-identical digits. It is passed because the API demands it, not because it does anything.
    //
    // Worth stating plainly, because the obvious use for it is exactly the thing it cannot do: a
    // maintainer who changes a key-derivation detail here and bumps this number to force everyone
    // to re-compare will achieve nothing, and the two sides will silently agree on a number that no
    // longer means what they think. Forcing a re-comparison has to be done by changing what goes
    // INTO the hash, as the switch from addresses to keys above did.
    final int version = 2;
    final byte[] localId = localIdentity.serialize();
    final byte[] remoteId = remoteIdentity.serialize();

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
    // Guarded like every sibling. Not reachable from the clipboard path today - getMessageType
    // returns null for a null envelope and the callers branch on that first - but this is public
    // API, it is the one method here that did not check, and the failure mode is an NPE out of a
    // clipboard callback where nothing catches it.
    if (messageEnvelope == null) return null;
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

  /**
   * Compares display names the way a person reads them, not the way a computer stores them.
   *
   * <p>Exact string equality let the check be sidestepped for free: "Alice " with a trailing space,
   * "alice", or "Аlice" with a Cyrillic А all failed to match a stored "Alice", suppressing both
   * the duplicate warning and the row tag so the two contacts rendered identically. And the natural
   * way for a user to fill this field is to copy the name out of the invite message, which is text
   * the attacker wrote — so every dodge below is one the attacker simply types.
   */
  public static boolean displayNamesMatch(final String aFirst, final String aLast,
      final String bFirst, final String bLast) {
    // Compare what is RENDERED, which is the concatenation - not the two fields pairwise.
    //
    // Pairwise comparison was a plain-ASCII bypass needing no Unicode at all: store Alice as
    // ("Alice","Smith"), then get the user to enter ("Alice Smith",""). The fields differ, so no
    // warning fired - but every render site in the app concatenates them, so the two rows read
    // identically. It was also a false negative in ordinary use, since only the first name is
    // mandatory and two honest contacts entered with the fields split differently would never be
    // flagged. Folding the joined string collapses both cases.
    return normalizeForDisplay(join(aFirst, aLast)).equals(normalizeForDisplay(join(bFirst, bLast)));
  }

  private static String join(final String first, final String last) {
    return (first == null ? "" : first) + " " + (last == null ? "" : last);
  }

  /**
   * Folds a display name to a skeleton, so two names that <em>render</em> the same compare the same.
   *
   * <p>The previous version was NFKC + trim + lowercase, which is not enough and was documented as
   * if it were. NFKC does not fold scripts: Cyrillic А (U+0410), Greek Α (U+0391) and Latin A are
   * three distinct characters that render identically, and none folds to another. Nor does it strip
   * format characters — a zero-width space, a soft hyphen, a word joiner or a right-to-left override
   * survive normalisation and render as nothing at all.
   *
   * <p>That used to matter more than a missed warning, because the contact-row tag was gated on this
   * same comparison: one invisible character suppressed the warning <em>and</em> removed the tag
   * from both rows. It is no longer gated, so a dodge now costs the warning and leaves the rows
   * still distinguishable. That is the whole reason for ungating it — this list will never be
   * complete.
   *
   * <p>Three steps now: strip characters that draw nothing, NFKC-normalise, and map confusables onto
   * their Latin counterpart. Combining marks are deliberately <em>not</em> stripped — doing so
   * collapsed Indic vowel signs and made unrelated names collide. That is a subset of UTS-39 confusables — enough
   * for the alphabets a name is realistically spoofed in, and honest about being a subset.
   */
  private static String normalizeForDisplay(final String value) {
    if (value == null) return "";

    // Line separators become a SPACE here, exactly as the display path does - not dropped.
    //
    // The two paths diverged and that divergence was itself the bug. sanitizeForInlineDisplay maps
    // \n \r U+0085 U+2028 U+2029 to a space so a name cannot break a banner; this path deleted the
    // same characters as "invisible". So "Bob<LF>Jones" RENDERED as "Bob Jones", identical to a
    // contact of that name, while folding to "bobjones" - which does not match "bob jones", so the
    // duplicate-name warning stayed silent. Nine characters, no homoglyphs, no cap to beat.
    //
    // The space family was always right (NFKC folds U+00A0, U+2003 and friends to U+0020 before the
    // whitespace collapse below). It was only the separators the display path had started treating
    // differently. Matching what is rendered is the invariant; anything else is a gap by
    // construction.
    String separatorsAsSpaces = value
        .replace('\n', ' ').replace('\r', ' ')
        // U+0085 renders as tofu here rather than breaking a line, so mapping it to a space is a
        // small over-fold. Kept deliberately: it is a line separator by definition, and a renderer
        // that honours that would break the banner in two. A cosmetic over-fold on a visible
        // character is the cheaper failure.
        .replace('\u0085', ' ').replace('\u2028', ' ').replace('\u2029', ' ')
        .replace('\u000B', ' ').replace('\f', ' ').replace('\t', ' ')
        .replace('\u1680', ' ')
        // Blanks, not nothing. These were deleted by rendersAsNothing, which is wrong for the same
        // reason deleting a newline was: measured with Skia they have zero ink but a POSITIVE
        // advance - measured, U+3164 advances 44px where a space advances 12 - so they draw a gap. "Bob<U+3164>Jones"
        // therefore reads as two words and did not match "Bob Jones". The existing tests only ever
        // put them at the END of a name, where deleting happens to give the same answer.
        .replace('\u3164', ' ').replace('\u115F', ' ').replace('\u1160', ' ')
        .replace('\uFFA0', ' ').replace('\u2800', ' ');

    // C0 controls are blanks too, and were the same bug as U+3164 above, still live.
    //
    // Measured with Skia: U+0001..U+001F draw no ink but advance 11-15px where a space advances 12
    // - within a pixel of a space at the row's real text size. They were DELETED by the fold, so
    // "Alice<U+0011>Smith" rendered as "Alice Smith" and did not match a contact of that name. And
    // Java's \s does not cover C0 outside \t\n\u000B\f\r, so they reached the TextView intact.
    //
    // The character that had been fixed was the one that looks wrong - U+3164 leaves a visibly wide
    // gap. These look right, which is why they are worse.
    final StringBuilder controlsAsSpaces = new StringBuilder(separatorsAsSpaces.length());
    for (int i = 0; i < separatorsAsSpaces.length(); i++) {
      final char c = separatorsAsSpaces.charAt(i);
      // Not every C0 draws a gap. U+0002 advances zero - it renders as nothing - so mapping it to
      // a space would make the fold disagree with the render in the other direction, and the sweep
      // reports that just as loudly. It falls through to rendersAsNothing and is deleted.
      //
      // The rule this encodes is measured, not guessed: zero ink with a positive advance folds to a
      // space, zero ink with zero advance folds to nothing. RenderedNameAgreementTest checks the
      // consequence for every code point from U+0000 up, so a wrong call here fails rather
      // than hides. That bound is load-bearing: it used to start at 1.
      // C0 only, and the two zero-advance members excepted. Measured: U+0001/0011/001F have ink 0
      // and advance 11-15 where a space is 12, so they draw a gap; U+0000 and U+0002 both advance
      // 0, so they draw nothing and fall through to rendersAsNothing.
      //
      // U+0000 was the expensive one. "c < 0x20" quietly included it, so NUL folded to a space
      // while painting nothing - which meant "Al<NUL>ice Smith" did NOT match a contact called
      // "Alice Smith" but rendered pixel-for-pixel identically to it. That is the bypass this whole
      // fold exists to prevent, reintroduced by the commit that fixed 25 other code points. No
      // sweep could see it: all of them began at cp = 1, so NUL was outside the test's world.
      //
      // C1 (U+007F-U+009F) is deliberately NOT here: measured, those have ink 349 and no glyph -
      // they render as a visible tofu box, not a gap. Folding a visible character to a space is
      // over-folding, which makes the warning fire on names a reader can plainly tell apart. I
      // added that range by assumption; measuring it took one test.
      final boolean drawsAGap = c != 0x0000 && c != 0x0002 && c < 0x20;
      controlsAsSpaces.append(drawsAGap ? ' ' : c);
    }
    separatorsAsSpaces = controlsAsSpaces.toString();
    // Drop combining marks that have no base character to combine with.
    //
    // Marks are deliberately NOT stripped in general - doing so collapses Indic vowel signs and
    // makes unrelated names collide. But a mark at the START of a name has nothing to attach to,
    // and what it then draws is device-dependent: nothing on some renderers, a dotted circle on
    // others. Since it MIGHT render as nothing, folding it away is the safe reading - it makes the
    // duplicate warning fire rather than stay silent. Nothing legitimate is lost, because a name
    // cannot begin with a mark that modifies a character before it.
    //
    // Only the non-spacing (Mn) arm is covered by a test: those are the marks the pixel sweep finds
    // painting a name identically. The Mc and Me arms are defensive - a spacing or enclosing mark
    // at the start does render something in the test font, so nothing here can distinguish folding
    // it from not. Stated rather than left to look like coverage.
    String withoutLeadingMarks = separatorsAsSpaces;
    while (!withoutLeadingMarks.isEmpty()) {
      final int first = withoutLeadingMarks.codePointAt(0);
      final int type = Character.getType(first);
      if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
          && type != Character.ENCLOSING_MARK) {
        break;
      }
      withoutLeadingMarks = withoutLeadingMarks.substring(Character.charCount(first));
    }

    final String normalized =
        java.text.Normalizer.normalize(withoutLeadingMarks, java.text.Normalizer.Form.NFKC);
    final StringBuilder skeleton = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); ) {
      final int cp = normalized.codePointAt(i);
      i += Character.charCount(cp);

      if (rendersAsNothing(cp)) continue;
      // Everything that has no glyph anywhere draws the SAME box, so it must fold to one value.
      //
      // This was a live substitution collision. Two invites, "Alice<U+0080>Smith" and
      // "Alice<U+0081>Smith", at two different addresses: both rows read "Alice[]Smith" in
      // pixel-identical ink, and no duplicate warning fired on the second because the two code
      // points are distinct and neither is deleted. Measured, 8520 BMP code points render
      // identically to "Alice<U+0080>Smith" - unassigned code points, private use, lone surrogates,
      // C1, and the format characters no font has a glyph for.
      //
      // Every pixel sweep missed it for one structural reason: they all compare candidates against
      // a single fixed baseline, so they test pairs of the form (baseline, baseline+X) and never
      // (baseline+X, baseline+Y). The property being claimed is about pairs of names, and only one
      // shape of pair was being generated.
      // A lone surrogate is NOT a box. The shaper substitutes U+FFFD REPLACEMENT CHARACTER for an
      // unpaired surrogate before any font is consulted, so it paints ink 1030 at advance 49 -
      // exactly like a literal U+FFFD, and nothing like the notdef box's 349 at 21.
      //
      // Putting surrogates in the box class was a bypass this fold created for itself. The
      // reasoning was that a surrogate "can never acquire a glyph", which is true of the code point
      // and irrelevant to what is drawn. "Alice<U+FFFD>Smith" and "Alice<U+D800>Smith" rendered
      // pixel-identically and folded to different keys, so a second invite carrying the surrogate
      // raised no duplicate warning. Jackson turns the JSON escape "\ud800" into a live lone
      // surrogate, so the messenger only has to write one.
      if (Character.isSurrogate((char) cp)) {
        skeleton.append('\uFFFD');
        continue;
      }
      if (rendersAsTofu(cp)) {
        skeleton.append(TOFU);
        continue;
      }
      // Case-fold BEFORE mapping confusables. The two interact: uppercase I and lowercase l draw
      // the same stroke, so mapping I onto l ahead of case folding made "ALICE" fold to "allce"
      // while "Alice" folded to "alice" - the two most ordinary spellings of one name stopped
      // matching each other. Folding case first lets one entry per confusable class cover both.
      skeleton.appendCodePoint(deconfuse(Character.toLowerCase(cp)));
    }
    // Collapse whitespace runs, not just the ends. Joining the two name fields introduces a space
    // that may sit beside one the user typed, and in any case a doubled space is not something a
    // reader distinguishes from a single one.
    return skeleton.toString().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Whether a code point draws nothing, so it cannot distinguish two names a reader compares.
   *
   * <p>Note what this method is NOT responsible for. Characters that draw a <em>blank</em> - zero ink
   * but a positive advance, so they leave a gap - are handled earlier, by the separator chain that
   * rewrites them to a space. U+3164, U+2800, U+2028/U+2029 and the C0 controls all used to be
   * deleted here, and that was wrong: deleting a gap joins two words that the reader sees as
   * separate. Every example this javadoc once cited has moved.
   *
   * <p>What remains here is the genuinely invisible: zero ink AND zero advance. The rule is
   * measured rather than listed - {@code RenderedNameAgreementTest} asserts, for every code point,
   * that a blank folds to a space and an invisible folds away - so a wrong call in either direction
   * fails a test rather than hiding. The explicit cases below are the ones no category expresses.
   */
  private static boolean rendersAsNothing(final int cp) {
    // C1 (U+0080-U+009F) and U+007F are category Cc but are not invisible: they have no glyph, so
    // they paint a notdef box. They are not deleted here - rendersAsTofu folds them instead.
    //
    // U+0085 never reaches here: it is a line separator by definition and is mapped to a space
    // earlier, a deliberate one-character over-fold, because a renderer that honours it would
    // break the banner in two.
    //
    // C0 below U+0020 IS deleted: the ones that draw a gap became spaces before this point, and the
    // two that reach here - U+0000 and U+0002 - measure zero ink AND zero advance.
    if (cp < 0x20) return true;
    if (cp >= 0x7F && cp <= 0x9F) return false;

    // Deletion is decided by Default_Ignorable_Code_Point, not by category Cf.
    //
    // It used to be "type == FORMAT || isIdentifierIgnorable", with a hand-written list of Cf
    // characters to keep. That list was taken from a Unicode 13 view of the world and was 21 code
    // points short: U+FFF9-FFFB and U+13430-1343F are Cf, are NOT default-ignorable, and were being
    // deleted, while U+0890/U+0891 - Prepended_Concatenation_Marks, the same class as the
    // U+0600-0605 the list did except - escaped only because this JDK's Character tables predate
    // them and report UNASSIGNED.
    //
    // Default_Ignorable is the property the comment always claimed to be computing: Unicode
    // subtracts exactly these characters from it so that a non-supporting renderer shows a fallback
    // glyph rather than nothing. Deriving the rule from it closes the whole class at once instead
    // of chasing the next range by hand, and stops the answer depending on how new the platform's
    // Unicode tables happen to be.
    return isDefaultIgnorable(cp);
  }

  /** The canonical stand-in for every code point that has no glyph. */
  private static final char TOFU = '\uFFFF';

  /**
   * Whether a code point has no glyph in any font, and therefore draws the notdef box.
   *
   * <p>These render as the notdef box - measured, ink 349 at advance 21 - so two names differing
   * only in which one they contain are indistinguishable to a reader and must fold together. They
   * are NOT deleted: a box is something the user can see, so a name containing one must still
   * differ from a name without it.
   *
   * <p>"All of these render identically" is what this said, and it was not true of the class as it
   * then stood. Lone surrogates were in it and paint U+FFFD (ink 1030, advance 49) rather than the
   * box; they are handled separately now. Seven of the kept format characters draw real Arabic
   * marks, and three private-use code points have real glyphs in the test font - those are
   * deliberate over-folds, not identical renderings, and saying otherwise is what hid the surrogate
   * bypass for a round.
   *
   * <p>Only font-independent members are listed. Unassigned code points and C1 have no glyph in any
   * font. Note that "no glyph" is a claim about rendering, not about the code point: a lone
   * surrogate also has no glyph of its own, and yet draws U+FFFD, which is why it is not here. Private use is font-dependent in principle - a device with a matching
   * icon font would draw something - and is included anyway, because folding it is the cry-wolf
   * direction while leaving it out is the bypass direction, and 6400 BMP private-use code points is
   * far too large a hole to leave open on that reasoning.
   *
   * <p>Format characters that are not default-ignorable are included for the same reason. Some of
   * them do have glyphs (the Arabic number signs draw real marks), so folding them together
   * over-folds by a few code points; the alternative is leaving U+FFF9-FFFB and U+13430-1343F -
   * which have no glyph - able to carry a substitution.
   */
  private static boolean rendersAsTofu(final int cp) {
    if (cp >= 0x7F && cp <= 0x9F) return true;

    final int type = Character.getType(cp);
    // Not SURROGATE: an unpaired surrogate is replaced by U+FFFD by the shaper, which is a
    // different picture from the notdef box and is handled before this is reached.
    if (type == Character.UNASSIGNED || type == Character.PRIVATE_USE) {
      return true;
    }
    return type == Character.FORMAT && !isDefaultIgnorable(cp);
  }

  /**
   * Unicode's Default_Ignorable_Code_Point, which the JDK does not expose.
   *
   * <p>{@code Character.isIdentifierIgnorable} is not the same property and does not cover the
   * variation selectors, which are the cheapest dodge of all - one appended character in the invite
   * text the user copies.
   */
  private static boolean isDefaultIgnorable(final int cp) {
    // Exclusions first: Unicode explicitly subtracts these from the property so a renderer without
    // support for them shows a fallback glyph instead of nothing.
    if (cp >= 0xFFF9 && cp <= 0xFFFB) return false;              // interlinear annotation
    if (cp >= 0x13430 && cp <= 0x1343F) return false;            // Egyptian format controls
    if ((cp >= 0x0600 && cp <= 0x0605) || cp == 0x06DD || cp == 0x070F || cp == 0x0890
        || cp == 0x0891 || cp == 0x08E2 || cp == 0x110BD || cp == 0x110CD) {
      return false;                                              // prepended concatenation marks
    }
    if (Character.isSpaceChar(cp)) return false;

    if (Character.getType(cp) == Character.FORMAT) return true;

    // Other_Default_Ignorable_Code_Point.
    if (cp == 0x034F || (cp >= 0x115F && cp <= 0x1160) || (cp >= 0x17B4 && cp <= 0x17B5)
        || cp == 0x2065 || cp == 0x3164 || cp == 0xFFA0 || (cp >= 0xFFF0 && cp <= 0xFFF8)
        || cp == 0xE0000 || (cp >= 0xE0002 && cp <= 0xE001F)
        || (cp >= 0xE0080 && cp <= 0xE00FF) || (cp >= 0xE01F0 && cp <= 0xE0FFF)) {
      return true;
    }

    // Variation selectors, including the Mongolian free variation selectors, which are category Mn
    // and so are missed by every category-based test.
    return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)
        || (cp >= 0x180B && cp <= 0x180D) || cp == 0x180F;
  }

  /**
   * Maps letters that share a glyph with a Latin letter onto that letter.
   *
   * <p><b>Scope, stated precisely,</b> because an earlier version of this javadoc said "Cyrillic and
   * Greek" without qualification and was wrong even about those: this is an explicit, auditable
   * subset of UTS-39 covering the Latin/Cyrillic/Greek confusables and the ASCII digit-letter pairs
   * that actually appear in name spoofing. It is not complete and cannot be — homoglyphs are an
   * open-ended set, and chasing them one review round at a time is an infinite regress. That is why
   * the address tag no longer depends on this succeeding.
   *
   * <p>Includes {@code I}/{@code l}/{@code 1} and {@code O}/{@code 0}, which are pure ASCII and the
   * commonest substitution of all: in Roboto, Android's default face, capital I and lowercase l draw
   * the same bare stroke, so "AIice" and "Alice" are indistinguishable with no exotic characters
   * involved at all.
   */
  private static int deconfuse(final int cp) {
    switch (cp) {
      // Entries are LOWERCASE only: the caller case-folds first, so an uppercase entry here would
      // be dead code. Cyrillic А folds to а, Greek Α to α, and so on, before reaching this table.
      // Cyrillic
      case 0x0430: return 'a'; case 0x0432: return 'b'; case 0x0435: return 'e';
      case 0x043A: return 'k'; case 0x043C: return 'm'; case 0x043D: return 'h';
      case 0x043E: return 'o'; case 0x0440: return 'p'; case 0x0441: return 'c';
      case 0x0442: return 't'; case 0x0443: return 'y'; case 0x0445: return 'x';
      case 0x0456: return 'l'; case 0x0458: return 'j'; case 0x0455: return 's';
      case 0x04CF: return 'l'; case 0x04C0: return 'l';  // palochka
      case 0x04BB: return 'h'; case 0x0501: return 'd'; case 0x051B: return 'q';
      case 0x051D: return 'w'; case 0x0475: return 'v';
      // Greek
      case 0x03B1: return 'a'; case 0x03B2: return 'b'; case 0x03B5: return 'e';
      case 0x03B6: return 'z'; case 0x03B7: return 'n'; case 0x03B9: return 'l';
      case 0x03BA: return 'k'; case 0x03BC: return 'm'; case 0x03BD: return 'v';
      case 0x03BF: return 'o'; case 0x03C1: return 'p'; case 0x03C4: return 't';
      case 0x03C5: return 'u'; case 0x03C7: return 'x';
      // Lunate sigma ϲ is written U+03F2, but NFKC rewrites it to final sigma U+03C2 before this
      // table is consulted - so the entry has to be on the normalised form. Determined by running
      // it, not by reading the tables.
      case 0x03C2: return 'c'; case 0x03F2: return 'c';
      case 0x03F3: return 'j';
      // Latin extended lookalikes
      case 0x0251: return 'a'; case 0x0131: return 'l'; case 0x026A: return 'l';
      case 0x029F: return 'l'; case 0x0261: return 'g';
      // Other scripts with Latin-identical glyphs
      // Cherokee is cased, so the uppercase forms never reach here - U+13AA lowercases to U+AB7A,
      // U+13A1 to U+AB71, U+13C0 to U+AB90. Both are listed so the table reads as what it covers.
      case 0xAB7A: case 0x13AA: return 'a';
      case 0xAB71: case 0x13A1: return 'l';
      case 0xAB90: case 0x13C0: return 'g';
      case 0x0585: return 'o';
      // ASCII digit/letter confusables - the commonest of all, and free for an attacker.
      // Applied after case folding, so one entry per class covers both cases. The i/l/1 class
      // collapses lowercase i too, because the capital-I attack ("AIice") only folds onto "Alice"
      // if that name's own l lands in the same class.
      case 'i': case '1': case 0x7C: return 'l';
      case '0': return 'o';
      default: return cp;
    }
  }



  /**
   * Whether the user previously un-pinned a key at this address after a mismatch.
   *
   * <p>A bundle arriving here is not a first sighting however empty the store looks: the user has
   * already reported an impersonation attempt at this address.
   */
  public static boolean wasKeyRejected(final SignalProtocolAddress address) {
    if (sInstance.mAccount == null || address == null) return false;
    return sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore().wasKeyRejected(address);
  }

  /**
   * Whether the contact list already holds someone under this display name at a different address.
   *
   * <p>The pin mechanism protects one address, and says nothing about a second contact at another
   * one. That is the cheapest way past the whole trust model: rather than substituting a key for an
   * existing contact - refused, recorded and warned about - a messenger fabricates a reinstall story
   * and invites the user to add a second contact with the same name at an address it controls. That
   * is a clean first sighting, so nothing fires. This is the only moment the app can notice.
   */
  public static boolean hasContactWithSameDisplayName(final String firstName,
      final String lastName, final SignalProtocolAddress excluding) {
    if (sInstance.mAccount == null || sInstance.mAccount.getContactList() == null) return false;
    for (final Contact existing : sInstance.mAccount.getContactList()) {
      if (existing.getSignalProtocolAddress().equals(excluding)) continue;
      if (displayNamesMatch(existing.getFirstName(), existing.getLastName(),
          firstName, lastName)) {
        return true;
      }
    }
    return hasRetiredDisplayName(firstName, lastName, excluding);
  }

  /**
   * Whether this name belonged to a contact the user deleted.
   *
   * <p>Deletion keeps the pinned key, which closes the same-address door. It cannot close the other
   * one: an attacker offering its OWN address under a deleted contact's name meets no pin at all,
   * and trust-on-first-use accepts it silently. Remembering the name is what keeps the duplicate
   * warning firing there.
   *
   * <p>The cost is a warning when a user deletes someone and legitimately re-adds them. That is the
   * right side to err on: re-adding a contact is precisely the moment where the app cannot tell the
   * real person from someone claiming to be them, and the warning routes the user to compare the
   * number - which is the only thing that ever could.
   */
  public static boolean hasRetiredDisplayName(final String firstName, final String lastName) {
    return hasRetiredDisplayName(firstName, lastName, null);
  }

  /**
   * @param excluding the address the name is being added at; a retirement from that same address is
   *     not a warning, because deletion kept the pin and a substituted bundle for it is refused
   */
  public static boolean hasRetiredDisplayName(final String firstName, final String lastName,
                                              final SignalProtocolAddress excluding) {
    if (sInstance.mAccount == null) return false;

    // The suppression's premise, enforced rather than assumed.
    //
    // It exists because "deletion keeps the pin, so a re-add at the SAME address is provably the
    // same identity". Two things were missing from the check. It compared address NAMES, but a name
    // is not an address - the device id beside it is the sender's to choose, so the attacker reused
    // the deleted contact's name at any other device id and landed on an address where nothing was
    // ever pinned, with the one control covering that door switched off. And it never asked whether
    // a pin actually survives: after rejectContactKey the pin is gone at the exact same address, and
    // that sequence is what the app's own advice produces - compare, mismatch, reject, delete, ask
    // for a fresh invite.
    //
    // So: the full address must match, AND there must still be a pinned key at it. With no pin
    // there is nothing that makes this "provably the same identity", and the warning is the only
    // thing left.
    final boolean excludedIsStillPinned = excluding != null
        && sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
            .getIdentity(excluding) != null;
    final String excludedAddress = ProtocolAddresses.key(excluding);

    for (final String[] retired : sInstance.mAccount.getRetiredDisplayNames()) {
      if (!displayNamesMatch(retired[0], retired[1], firstName, lastName)) continue;
      // Entries written before the address was recorded have length 2; treat those as matching
      // nothing in particular rather than silently suppressing.
      // The full address, and nothing looser. A migration arm here used to accept entries written
      // before the record held a rendered address, by comparing the bare address NAME - and it was
      // reachable: the attacker picks the deleted contact's address name for its own address, gets
      // a pin there the ordinary way (one accepted invite), and deletion deliberately keeps that
      // pin - so excludedIsStillPinned was satisfied by the attacker's own key and the duplicate
      // warning was suppressed. Those entries are re-keyed once at load instead; where the address
      // could not be identified their address element is blanked, which leaves the warning ON.
      if (excludedIsStillPinned && retired.length > 2
          && excludedAddress.equals(retired[2])) {
        continue;
      }
      return true;
    }
    return false;
  }

  /**
   * The short tag shown beside a contact's name so two contacts with one name can be told apart.
   *
   * <p><b>Keyed, not just hashed.</b> This used to be a plain truncated SHA-256 of the address,
   * computed inside {@code Contact}. That cannot work against this adversary: the messenger knows
   * the address of the contact being impersonated and chooses its own address freely, so it can
   * compute the victim's tag and grind until its own matches. Measured on a single JVM thread with
   * no GPU, matching the leading group took nine seconds; matching both ends is hours on rented
   * hardware. Widening the output made that worse rather than better, because a longer string is
   * one a user reads less of.
   *
   * <p>Keying with a per-install secret removes the ability to compute the target at all, so there
   * is nothing to grind towards. That also means the tag can be short enough to read completely,
   * which is the property that actually matters — security here is bounded by the characters a
   * person compares, not by the characters emitted.
   *
   * @return the tag, or an empty string when no account is loaded.
   */
  public static String displayTagFor(final Contact contact) {
    if (contact == null || sInstance.mAccount == null) return "";
    try {
      final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(
          sInstance.mAccount.getDisplayTagSecret(), "HmacSHA256"));
      final String name = contact.getSignalProtocolAddressName();
      mac.update((name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      mac.update((byte) 0);
      mac.update(String.valueOf(contact.getDeviceId())
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      final byte[] tag = mac.doFinal();

      // 40 bits, in two groups short enough to be read end to end. Keyed, so an attacker cannot
      // aim at it - 40 keyed bits are worth far more here than 96 unkeyed ones.
      return String.format("#%02x%02x-%02x%02x%02x", tag[0], tag[1], tag[2], tag[3], tag[4]);
    } catch (java.security.GeneralSecurityException e) {
      Log.e(TAG, "Could not derive a display tag", e);
      return "";
    }
  }

  /**
   * A name must not be able to imitate the address tag rendered beside it.
   *
   * <p>Checks the NFKC-normalised form, not the raw text. U+FF03 FULLWIDTH NUMBER SIGN and U+FE5F
   * SMALL NUMBER SIGN both render as a '#' and both fold to one under NFKC, so a raw
   * {@code indexOf('#')} lets them straight through — and the name is text the attacker wrote into
   * the invite for the user to copy. A handful of other sharp-like characters do not fold at all and
   * are listed explicitly.
   */
  public static boolean displayNameImitatesATag(final CharSequence name) {
    if (name == null) return false;
    final String normalized =
        java.text.Normalizer.normalize(name.toString(), java.text.Normalizer.Form.NFKC);
    if (normalized.indexOf('#') >= 0) return true;
    for (int i = 0; i < normalized.length(); ) {
      final int cp = normalized.codePointAt(i);
      i += Character.charCount(cp);
      switch (cp) {
        case 0x266F:  // MUSIC SHARP SIGN
        case 0x22D5:  // EQUAL AND PARALLEL TO
        case 0x2317:  // VIEWDATA SQUARE
        case 0x1F5E7: // THREE RAYS
          return true;
        default:
          break;
      }
    }
    return false;
  }
  /**
   * A contact's display label: the name, plus its address tag, safe to concatenate.
   *
   * <p>Lives here rather than in the view so every surface gets the same string — a previous version
   * had this logic in {@code E2EEStripView} and a toast in {@code E2EEStrip} was simply missed.
   *
   * <p>Two directionality problems, and only one of them is fixable by cleaning the name. Stripping
   * bidi control characters stops a {@code U+202E} in the name mirroring the tag after it. It does
   * nothing about a name whose <em>first strong character</em> is right-to-left: a Hebrew or Arabic
   * name flips the paragraph under {@code FIRST_STRONG} resolution, and the tag appended to it is
   * relocated to the other end with its {@code #} migrating across. That is not an attack requiring
   * exotic input — it happens for every Hebrew- and Arabic-named contact — and one leading RTL
   * letter is enough to trigger it deliberately.
   *
   * <p>So the tag is wrapped in a first-strong isolate ({@code U+2068}…{@code U+2069}). The
   * surrounding text cannot reorder it and it cannot reorder the surrounding text.
   */
  public static String displayLabelFor(final Contact contact) {
    if (contact == null) return "";
    final String name = (sanitizeForInlineDisplay(contact.getFirstName())
        + " " + sanitizeForInlineDisplay(contact.getLastName())).trim();
    if (contactCount() < 1) return name;
    return name + "  \u2068" + displayTagFor(contact) + "\u2069";
  }

  /**
   * Makes a name safe to place inside a single line of running text.
   *
   * <p>Two separate hazards, both driven by text the attacker writes into an invite for the user to
   * copy.
   *
   * <p><b>Bidi overrides</b> let a name reorder whatever follows it, so the tag appended after the
   * name renders mirrored. The full set of UBA explicit formatting characters is removed.
   *
   * <p><b>Line breaks</b> are the one the row layout already survives and the banners did not. The
   * info views are {@code wrap_content} with no {@code maxLines}, and both name fields declare
   * {@code inputType="textMultiLine"}, so newlines are ordinary input that the length cap counts as
   * ordinary characters. Twenty of them in a first name render
   * "Someone offered a different key for Bob" followed by twenty blank lines, with the refusal
   * notice, the instruction to compare by voice, and the tag itself all below the fold — on a
   * warning an attacker triggers with one forged bundle. Collapsed to spaces here rather than
   * dropped, so words on either side do not run together.
   */
  private static String sanitizeForInlineDisplay(final String value) {
    if (value == null) return "";
    final StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
          || c == 0x200E || c == 0x200F || c == 0x061C) {
        continue;
      }
      if (c == '\n' || c == '\r' || c == 0x0085 || c == 0x2028 || c == 0x2029) {
        out.append(' ');
        continue;
      }
      out.append(c);
    }
    // \\s+ alone is not enough: Java's \\s is ASCII-only, so it does not match U+2003 EM SPACE or
    // the other Unicode space separators - and EM SPACE padding is precisely how a name was made
    // wide enough to push the tag off its row. \\p{Zs} covers them. Unlike normalizeForDisplay this
    // path does not NFKC first (it must preserve the name as written), so the classes are needed
    // explicitly.
    return out.toString().replaceAll("[\\s\\p{Zs}]+", " ").trim();
  }

  /** Sanitises an arbitrary attacker-supplied string for use inside a warning. */
  public static String sanitizeForBanner(final CharSequence value) {
    return value == null ? "" : sanitizeForInlineDisplay(value.toString());
  }

  /** How many contacts the account holds; 0 when nothing is loaded. */
  public static int contactCount() {
    if (sInstance.mAccount == null || sInstance.mAccount.getContactList() == null) return 0;
    return sInstance.mAccount.getContactList().size();
  }

  /**
   * Whether a contact already exists at this exact address under a different name.
   *
   * <p>Unlike the display-name check this is <b>exact and unspoofable</b> — it compares addresses,
   * not a name heuristic a peer can dodge. There is no legitimate reason for two contact rows at one
   * address, and allowing it has real consequences: {@code getContactFromAddressInContactList} and
   * the message store both resolve an incoming envelope with {@code findFirst()}, so which row a
   * message is attributed to becomes list-order dependent.
   *
   * <p><b>Currently unreachable, and kept deliberately.</b> The add-contact screen is only shown on
   * the {@code sender == null} branch of the three envelope handlers — that is, only when no contact
   * was found at that exact address — and the Add button recomputes the same address from the same
   * envelope. So at the moment this runs, there is guaranteed to be nothing at that address and this
   * can never return non-null. An earlier version of this comment described the attack as live; the
   * dispatcher already prevents it, and such an envelope resolves to the existing contact instead.
   *
   * <p>It stays because the guarantee lives in a different file from the invariant, and a change to
   * how the add screen is reached would silently make two rows at one address possible again. Note
   * the polarity if that ever happens: the same-name case is treated as a re-add and skipped, so
   * every improvement to the name folding <em>widens</em> that exemption. A trailing space would be
   * enough to walk past it while {@code Contact.equals} still creates a second row.
   */
  public static Contact existingContactAtSameAddress(final SignalProtocolAddress address,
      final String firstName, final String lastName) {
    if (sInstance.mAccount == null || sInstance.mAccount.getContactList() == null
        || address == null) {
      return null;
    }
    for (final Contact existing : sInstance.mAccount.getContactList()) {
      if (!existing.getSignalProtocolAddress().equals(address)) continue;
      if (displayNamesMatch(existing.getFirstName(), existing.getLastName(), firstName, lastName)) {
        continue; // the same contact under the same name is a re-add, not a second identity
      }
      return existing;
    }
    return null;
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

    Log.d(TAG, "Deleting contact from contact list");
    ArrayList<Contact> newContacts = new ArrayList<>();
    for (Contact contact : contacts) {
      if (!contact.equals(contactToRemove)) {
        newContacts.add(contact);
      }
    }
    // BEFORE the list is pruned.
    //
    // removeAllUnencryptedMessages asks hasExactlyOneContactNamed to decide whether a legacy,
    // name-keyed message can be attributed to this contact. Asked after the removal, that question
    // is put to a list this contact has already left, and it answers wrongly in both directions:
    // deleting an impostor who shares an address name makes the name look unambiguous, so the
    // GENUINE contact's pre-upgrade history is deleted - which is verbatim the defect the keying
    // change was made to fix - and deleting a contact that is alone under its name makes the name
    // look ambiguous, so its own plaintext survives the only action a user has for erasing it, with
    // no row left to reach it from.
    Log.d(TAG, "Deleting unencrypted messages for contact");
    mAccount.removeAllUnencryptedMessages(contactToRemove);

    mAccount.setContactList(newContacts);

    // Remember the name, for the same reason the pin is kept.
    //
    // Keeping the pin closes the same-address door: a substituted bundle for this address still
    // fails. It does nothing about the other door - the attacker's OWN fresh address carrying this
    // contact's name, where nothing is pinned and trust-on-first-use accepts silently. With the
    // name gone from the contact list, hasContactWithSameDisplayName returns false and the
    // duplicate warning has nothing to fire on, so a warned attack becomes an unwarned one.
    //
    // rejectedAddresses was deliberately made to outlive removeIdentity on exactly this reasoning.
    // The display name got no such treatment and was erased by one tap, with no confirmation.
    // The FULL address, not the address name: the suppression that reads this back is what makes
    // a re-add at the same address unwarned, and a name shared with another device id is a
    // different identity that must not inherit that.
    mAccount.retireDisplayName(contactToRemove.getFirstName(), contactToRemove.getLastName(),
        ProtocolAddresses.key(contactToRemove.getSignalProtocolAddress()));

    Log.d(TAG, "Deleting session for contact");
    if (mAccount.getSignalProtocolStore().getSessionStore().containsSession(contactToRemove.getSignalProtocolAddress())) {
      mAccount.getSignalProtocolStore().getSessionStore().deleteSession(contactToRemove.getSignalProtocolAddress());
    }

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
    // The sanctioned way out of a pending change is therefore to dismiss it, keeping the pinned
    // key - never deletion.
    //
    // This used to offer "or acceptIdentityChange after comparing the offered number out of band"
    // as a second route. That instruction cannot be followed: the offered number is never displayed
    // anywhere. createFingerprint reads the PINNED key by design, and the verify screen says so
    // explicitly. It also contradicts acceptIdentityChange's own javadoc, which calls the method
    // deliberately unwired because a screen offering to adopt an offered key is an attack surface.
    // A comment that points a maintainer at wiring it up is pointing them at the thing the design
    // rejected.

    storeAllAccountInformationInSharedPreferences();
  }

  public static List<StorageMessage> getUnencryptedMessagesList(Contact contact) throws UnknownContactException {
    Log.d(TAG, "Getting unencrypted messages list...");
    return sInstance.getUnencryptedMessagesListFromAccount(contact);
  }

  private List<StorageMessage> getUnencryptedMessagesListFromAccount(Contact contact) throws UnknownContactException {
    if (mAccount != null && contact != null) {
      List<StorageMessage> messagesWithContact = mAccount.getUnencryptedMessages().stream()
          .filter(m -> m.belongsTo(contact.getSignalProtocolAddressName(), contact.getDeviceId()))
          .collect(Collectors.toList());
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
        Log.e(TAG, "Error: no session with the recipient" + ""
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
      Log.e(TAG, "Identity key mismatch while encrypting");
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

    // Filed under the full address. See StorageMessage.chatLogKey: the address NAME is public and
    // the device id beside it is the sender's to choose, so a name-keyed log put an impostor's
    // messages inside the genuine contact's conversation.
    final String logKey = StorageMessage.chatLogKey(
        signalProtocolAddress.getName(), signalProtocolAddress.getDeviceId());
    StorageMessage storageMessage;
    if (isFromOwnAccount) {
      storageMessage = new StorageMessage(logKey, account.getSignalProtocolAddress().getName(), signalProtocolAddress.getName(), timestamp, decryptedMessage);
    } else {
      storageMessage = new StorageMessage(logKey, signalProtocolAddress.getName(), account.getSignalProtocolAddress().getName(), timestamp, decryptedMessage);
    }

    recipient.ifPresent(contact -> account.addUnencryptedMessage(contact, storageMessage));
  }

  /**
   * Undo the chat-log entry written by a send that was never handed to the messenger.
   *
   * <p>{@code encryptMessage} records the plaintext in the user's history and persists it before
   * returning, and the encoder that runs afterwards can still refuse - a message can encode past
   * what the recipient will decode. The refused attempt therefore left a history entry for a
   * message nobody received, and pressing send again added a second one. Measured: one message
   * sent, two in the log.
   *
   * <p>Keyed on the exact envelope timestamp rather than "the last one", so a message that arrived
   * in between cannot be removed by mistake.
   *
   * @return whether an entry was found and removed
   */
  public static boolean discardRecordedMessage(final SignalProtocolAddress signalProtocolAddress,
                                               final Instant timestamp) {
    if (sInstance.mAccount == null || signalProtocolAddress == null || timestamp == null) {
      return false;
    }
    final boolean removed = sInstance.mAccount.removeUnencryptedMessage(
        signalProtocolAddress.getName(), timestamp);
    if (removed) sInstance.storeAllAccountInformationInSharedPreferences();
    return removed;
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
   * @return true only if the session was actually established and the bundle's signatures held.
   */
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
      Log.e(TAG, "The identity key for the recipient" + ""
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
    mStorageHelper = storageHelperFactory == null
        ? new StorageHelper(context) : storageHelperFactory.create(context);
  }

  /** How the storage helper is built. Overridable so persistence can be exercised on the JVM. */
  public interface StorageHelperFactory {
    StorageHelper create(Context context);
  }

  private static StorageHelperFactory storageHelperFactory;

  /**
   * Substitutes the storage helper, for tests only.
   *
   * <p>Every test in this class runs {@code initialize(null)}, which leaves {@code mStorageHelper}
   * null - so every {@code storeAllAccountInformationInSharedPreferences()} call inside verify,
   * dismiss, reject, delete and the send rollback is a silent no-op. The trust logic is covered;
   * the half that decides whether the result reaches disk is not, and a mutation deleting those
   * persist calls survives the whole suite.
   *
   * <p>A real context alone does not fix that: the production helper builds an Android Keystore
   * box, which has no JVM implementation. The seam is what makes the persisted half reachable.
   */
  public static void setStorageHelperFactoryForTest(final StorageHelperFactory factory) {
    storageHelperFactory = factory;
  }

  /**
   * Drops the singleton's account and storage helper, for tests only.
   *
   * <p>This is a static singleton and the JVM is shared across test classes, so a class that ran
   * earlier with a real context leaves a live {@code mStorageHelper} behind. The next class calling
   * {@code initialize(null)} does NOT get a fresh identity: {@code initializeStorageHelper} returns
   * early on a null context without clearing the field, so the stale helper answers
   * {@code hasExistingProtocolData()} and initialize takes its reload branch instead - handing back
   * a null account for reasons that have nothing to do with the test being run.
   *
   * <p>That has now produced two rounds of confusing failures. Making it explicit is cheaper than
   * each test class discovering it again.
   */
  public static void resetForTest() {
    storageStateForTest = null;
    sInstance.mStorageHelper = null;
    sInstance.mAccount = null;
    storageHelperFactory = null;
  }

  // needed for testing only
  public Account getAccount() {
    return mAccount;
  }

  // needed for testing only
  public void setAccount(final Account account) {
    this.mAccount = account;
  }

  /**
   * Injects a storage helper, for tests only.
   *
   * <p>{@code initialize()} contains the guard that refuses to generate a new identity over
   * existing data — the one standing between a lost "first run" flag and the irrecoverable
   * destruction of the user's identity key, every session and every verified contact. That guard
   * was unreachable from a JVM test, because reaching it needs a {@code StorageHelper} and the real
   * one is Keystore-backed. So it was never executed, and mutation testing found both of its
   * outcomes could be inverted with the whole suite still green.
   *
   * <p>A test seam is the honest price of covering it. The alternative was leaving the single most
   * destructive branch in the codebase untested because it was awkward to reach.
   */
  public void setStorageHelperForTest(final StorageHelper storageHelper) {
    this.mStorageHelper = storageHelper;
  }
}
