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
   * Whether this contact's key can be relied on without a further safety-number comparison: either
   * the user verified it, or it never travelled through the messenger.
   */
  public static boolean isContactKeyTrustworthy(final Contact contact) {
    if (contact == null) return false;
    if (contact.isVerified()) return true;
    if (sInstance.mAccount == null) return false;
    return sInstance.mAccount.getSignalProtocolStore().getIdentityKeyStore()
        .isKeyOutOfBand(contact.getSignalProtocolAddress());
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

  public static void verifyContact(Contact contact) throws UnknownContactException {
    Log.d(TAG, "Verifying contact...");
    sInstance.verifyContactInContactList(contact);
  }

  private void verifyContactInContactList(Contact contact) throws UnknownContactException {
    if (contact == null || mAccount == null) return;
    contact.setVerified(true);
    mAccount.updateContactInContactList(contact);

    // Deliberately does NOT clear a pending identity change. The fingerprint the user compared is
    // built from the *pinned* key, so accepting a different, pending key on the strength of it
    // would have them confirm one key and trust another. Accepting a change is its own action,
    // against the key actually displayed.

    storeAllAccountInformationInSharedPreferences();
  }

  private Fingerprint createFingerprint(Contact contact) {
    if (contact == null || getAccount() == null) return null;

    final IdentityKey localIdentity = getAccount().getIdentityKeyPair().getPublicKey();

    // Prefer the session's copy, but fall back to the stored identity. Verification should be
    // possible as soon as a contact's bundle has been processed - before any message is exchanged -
    // because that is exactly when a user ought to compare safety numbers, and requiring a session
    // first means the check happens only after they have already sent something.
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
    // Clearing it here opened a fail-open path: an attacker substitutes their key, libsignal
    // refuses, the user is shown generic "delete the contact and ask for a new invite" advice,
    // follows it, and the attacker's key is then accepted as a clean first sighting. The pin
    // surviving deletion is what makes that attack fail closed. The sanctioned way to move to a new
    // key is acceptIdentityChange, after comparing safety numbers out of band.

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

    // update session with new signed pre key from recipient
    if (messageEnvelope.getCiphertextMessage() != null && messageEnvelope.getPreKeyResponse() != null) {
      Log.d(TAG, "Message with cipherText and updated preKeyResponse received...");
      processPreKeyResponseMessage(messageEnvelope, signalProtocolAddress);
    }

    logMessageType(messageEnvelope.getCiphertextType());

    byte[] plaintext;
    if (messageEnvelope.getCiphertextType() == CiphertextMessage.PREKEY_TYPE) {
      // decrypting message and storing session with preKeySignalMessage
      final PreKeySignalMessage preKeySignalMessage = new PreKeySignalMessage(messageEnvelope.getCiphertextMessage());

      Log.d(TAG, "PreKeySignalMessage: Used signed prekey id: " + preKeySignalMessage.getSignedPreKeyId());

      plaintext = sessionCipher.decrypt(preKeySignalMessage);
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
