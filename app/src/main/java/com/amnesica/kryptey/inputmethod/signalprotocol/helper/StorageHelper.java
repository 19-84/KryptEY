package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.AndroidKeystoreCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.EncryptedKeyValueStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.KeyValueStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.SharedPreferencesKeyValueStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.StorageCryptoException;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class StorageHelper {
  static final String TAG = StorageHelper.class.getSimpleName();

  /**
   * Seam for tests. Production always supplies {@link AndroidKeystoreCryptoBox}; unit tests supply
   * an in-process key, because the Android Keystore cannot be exercised off a device and the
   * null-handling this class does is worth testing independently of it.
   */
  public interface CryptoBoxFactory {
    CryptoBox create(Context context, boolean hasExistingData);
  }

  private final Context mContext;
  private final String mSharedPreferenceName = "protocol";
  private final CryptoBoxFactory mCryptoBoxFactory;
  private EncryptedKeyValueStore mSecureStore;

  public StorageHelper(Context context) {
    this(context, AndroidKeystoreCryptoBox::new);
  }

  public StorageHelper(Context context, CryptoBoxFactory cryptoBoxFactory) {
    this.mContext = context;
    this.mCryptoBoxFactory = cryptoBoxFactory;
  }

  /**
   * Whether this device already holds protocol data, regardless of whether it can currently be
   * decrypted.
   *
   * <p>Exists to stop {@code initialize()} generating a fresh identity over an existing one. The
   * "is this a first run?" boolean lives in device-protected storage while the identity lives in
   * credential-protected storage — two directories that can be lost independently — so that boolean
   * cannot be trusted as the sole gate on destroying a user's keys.
   *
   * <p>Deliberately does not require the data to be readable: an undecryptable store still means
   * "this user had an identity", and overwriting it must not be silent.
   */
  public boolean hasExistingProtocolData() {
    if (mContext == null) return false;
    final SharedPreferences preferences =
        mContext.getSharedPreferences(mSharedPreferenceName, Context.MODE_PRIVATE);
    if (preferences == null) return false;
    return new SharedPreferencesKeyValueStore(preferences).contains(
        String.valueOf(ProtocolIdentifier.PROTOCOL_STORE));
  }

  /**
   * Loads the account, or returns {@code null} when there is nothing readable to load.
   *
   * <p>Previously this dereferenced the protocol store without checking it, so any storage read
   * failure became an NPE thrown out of {@code LatinIME.setInputView()} — the keyboard crashed
   * every time it was raised, in every app, with no way for the user to recover. Callers must now
   * handle {@code null} explicitly.
   */
  public Account getAccountFromSharedPreferences() {
    final String name = (String) getClassFromSharedPreferences(ProtocolIdentifier.UNIQUE_USER_ID);
    if (name == null) {
      Log.e(TAG, "Error: unique user id could not be read; not loading an account");
      return null;
    }
    final SignalProtocolStoreImpl signalProtocolStore = (SignalProtocolStoreImpl) getClassFromSharedPreferences(ProtocolIdentifier.PROTOCOL_STORE);
    if (signalProtocolStore == null) {
      Log.e(TAG, "Error: protocol store could not be read; not loading an account");
      return null;
    }
    final IdentityKeyPair identityKeyPair = signalProtocolStore.getIdentityKeyPair();
    if (identityKeyPair == null) {
      Log.e(TAG, "Error: protocol store has no identity key pair; not loading an account");
      return null;
    }

    final PreKeyMetadataStore metadataStore = (PreKeyMetadataStore) getClassFromSharedPreferences(ProtocolIdentifier.METADATA_STORE);
    final SignalProtocolAddress signalProtocolAddress = (SignalProtocolAddress) getClassFromSharedPreferences(ProtocolIdentifier.PROTOCOL_ADDRESS);
    if (metadataStore == null || signalProtocolAddress == null) {
      Log.e(TAG, "Error: metadata store or protocol address could not be read; not loading an account");
      return null;
    }

    // convertValue(null, ...) returns null, so a single failed read here would produce a non-null
    // account carrying null lists - which the write-back would then persist as the string "null",
    // erasing the user's entire contact list and message history. Fall back to empty instead.
    ArrayList<StorageMessage> unencryptedMessages = JsonUtil.convertUnencryptedMessagesList(
        (ArrayList<StorageMessage>) getClassFromSharedPreferences(ProtocolIdentifier.UNENCRYPTED_MESSAGES));
    if (unencryptedMessages == null) {
      Log.e(TAG, "Error: stored messages could not be read; continuing with an empty list");
      unencryptedMessages = new ArrayList<>();
    }
    ArrayList<Contact> contactList = JsonUtil.convertContactsList(
        (ArrayList<Contact>) getClassFromSharedPreferences(ProtocolIdentifier.CONTACTS));
    if (contactList == null) {
      Log.e(TAG, "Error: stored contacts could not be read; continuing with an empty list");
      contactList = new ArrayList<>();
    }

    Account account = new Account(name, signalProtocolAddress.getDeviceId(), identityKeyPair, metadataStore, signalProtocolStore, signalProtocolAddress); // deviceId is static
    account.setUnencryptedMessages(unencryptedMessages);
    account.setContactList(contactList);

    return account;
  }

  public void storeAllInformationInSharedPreferences(final Account account) {
    storeMetaDataStoreInSharedPreferences(account.getMetadataStore());
    storeUniqueUserIdInSharedPreferences(account.getName());
    storeSignalProtocolInSharedPreferences(account.getSignalProtocolStore()); // incl. registrationId + identityKeyPair
    storeSignalProtocolAddressInSharedPreferences(account.getSignalProtocolAddress());
    storeDeviceIdInSharedPreferences(account.getDeviceId());
    storeUnencryptedMessagesMapInSharedPreferences(account.getUnencryptedMessages());
    storeContactListInSharedPreferences(account.getContactList());
  }

  private void storeUnencryptedMessagesMapInSharedPreferences(List<StorageMessage> unencryptedMessages) {
    storeInSharedPreferences(ProtocolIdentifier.UNENCRYPTED_MESSAGES, unencryptedMessages);
  }

  public void storeMetaDataStoreInSharedPreferences(final PreKeyMetadataStore metadataStore) {
    storeInSharedPreferences(ProtocolIdentifier.METADATA_STORE, metadataStore);
  }

  public void storeUniqueUserIdInSharedPreferences(final String uniqueUserId) {
    storeInSharedPreferences(ProtocolIdentifier.UNIQUE_USER_ID, uniqueUserId);
  }

  public void storeSignalProtocolInSharedPreferences(final SignalProtocolStoreImpl signalProtocolStore) {
    storeInSharedPreferences(ProtocolIdentifier.PROTOCOL_STORE, signalProtocolStore);
  }

  public void storeSignalProtocolAddressInSharedPreferences(final SignalProtocolAddress signalProtocolAddress) {
    storeInSharedPreferences(ProtocolIdentifier.PROTOCOL_ADDRESS, signalProtocolAddress);
  }

  public void storeDeviceIdInSharedPreferences(final Integer deviceId) {
    storeInSharedPreferences(ProtocolIdentifier.DEVICE_ID, deviceId);
  }

  private void storeContactListInSharedPreferences(List<Contact> contactList) {
    storeInSharedPreferences(ProtocolIdentifier.CONTACTS, contactList);
  }

  public void storeInSharedPreferences(final ProtocolIdentifier protocolIdentifier, final Object objectToStore) {
    final EncryptedKeyValueStore store = secureStore();
    if (store == null) return;
    try {
      store.put(String.valueOf(protocolIdentifier), JsonUtil.toJson(objectToStore));
    } catch (StorageCryptoException e) {
      // Do not fall back to writing cleartext. Losing a write is recoverable; writing the identity
      // private key to disk unencrypted is not.
      Log.e(TAG, "Error: Could not store " + protocolIdentifier + " securely", e);
    }
  }

  public Object getClassFromSharedPreferences(final ProtocolIdentifier protocolIdentifier) {
    final EncryptedKeyValueStore store = secureStore();
    if (store == null) return null;
    try {
      final String json = store.get(String.valueOf(protocolIdentifier));
      if (json == null) {
        Log.w(TAG, "No stored content for " + protocolIdentifier);
        return null;
      }
      return JsonUtil.fromJson(json, protocolIdentifier.className);
    } catch (StorageCryptoException | IOException e) {
      Log.e(TAG, "Error: Could not process " + protocolIdentifier + " from secure storage", e);
      return null;
    } catch (RuntimeException e) {
      // Base64.decode throws IllegalArgumentException on short/!malformed input and ObjectMapper
      // throws IllegalArgumentException from convertValue. Unchecked, so without this they would
      // propagate out of LatinIME.setInputView() and crash the keyboard on every raise.
      Log.e(TAG, "Error: Unexpected failure reading " + protocolIdentifier, e);
      return null;
    }
  }

  /**
   * Builds the encrypted store, running the one-time cleartext migration if this is an upgrade from
   * 0.1.5. Returns {@code null} — never a cleartext fallback — when secure storage is unavailable.
   */
  private EncryptedKeyValueStore secureStore() {
    if (mSecureStore != null) return mSecureStore;
    if (mContext == null) {
      logError("mContext");
      return null;
    }
    final SharedPreferences sharedPreferences =
        mContext.getSharedPreferences(mSharedPreferenceName, Context.MODE_PRIVATE);
    if (sharedPreferences == null) {
      logError("sharedPreferences");
      return null;
    }

    final KeyValueStore raw = new SharedPreferencesKeyValueStore(sharedPreferences);
    final boolean alreadyEncrypted = EncryptedKeyValueStore.isEncrypted(raw);
    final CryptoBox cryptoBox = mCryptoBoxFactory.create(mContext, alreadyEncrypted);
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, cryptoBox);

    try {
      if (store.needsMigration()) {
        Log.i(TAG, "Migrating cleartext protocol storage to Keystore-backed encryption");
        store.migrateToEncrypted();
        Log.i(TAG, "Migration complete");
      }
    } catch (StorageCryptoException e) {
      Log.e(TAG, "Error: Could not migrate protocol storage to encrypted form", e);
      return null;
    }

    mSecureStore = store;
    return mSecureStore;
  }

  private void logError(final String nameObject) {
    Log.e(TAG, "Error: Possible null value for " + nameObject);
  }
}
