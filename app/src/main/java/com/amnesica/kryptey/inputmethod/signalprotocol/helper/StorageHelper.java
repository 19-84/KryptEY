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
import java.util.Map;
import java.util.LinkedHashMap;

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
  /**
   * Why there is no account, when there is no account.
   *
   * <p>These three look identical to every caller today, and two of them mean opposite things.
   * {@code NONE} is a fresh install and the right response is to generate an identity.
   * {@code UNREADABLE} means the user's identity is still on disk and the key that protects it is
   * gone - generating anything would destroy it - and the right response is to tell them, because
   * nothing the app can do recovers it. {@code READABLE} means the load failed for some other
   * reason and is worth retrying.
   *
   * <p>Recorded in REVIVAL.md as a known-deferred defect: "no user-visible signal when the Keystore
   * key is gone - it currently looks identical to no data". This is the half of it that can be
   * decided here; showing it is the UI's job.
   */
  public enum StorageState {
    /** No protocol data at all. A first run. */
    NONE,
    /** Protocol data exists and the master key can open it. */
    READABLE,
    /** Protocol data exists and cannot be decrypted - typically a lost Keystore key. */
    UNREADABLE
  }

  /**
   * Distinguishes "nothing stored" from "stored and unreadable".
   *
   * <p>The distinction is not cosmetic. A lost Keystore key leaves the identity key, every session
   * and every verified contact on disk, encrypted under a key that no longer exists. Presenting
   * that as a fresh install invites the user to re-invite all their contacts, which silently
   * discards every pin they had already verified - the trust-on-first-use window reopens for every
   * one of them, at a moment when an attacker who caused the key loss knows exactly when to strike.
   */
  public StorageState storageState() {
    if (!hasExistingProtocolData()) return StorageState.NONE;

    final EncryptedKeyValueStore store = secureStore();
    if (store == null) return StorageState.UNREADABLE;

    // Trial decryption, not a flag: the flag says what was written, and the question here is what
    // can still be read.
    try {
      return store.get(String.valueOf(ProtocolIdentifier.PROTOCOL_STORE)) != null
          ? StorageState.READABLE : StorageState.UNREADABLE;
    } catch (Exception e) {
      return StorageState.UNREADABLE;
    }
  }

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

    // Restore the display-tag secret, or every contact tag changes on this load.
    //
    // The constructor mints a fresh one, which is right for a new account and wrong for a reload -
    // and reloadAccount runs on every setInputView, so without this the tags changed on every
    // rotation, every theme flip, and every IME restart. That is worse than cosmetic: a tag is only
    // useful because it is the same next time you look, and constant churn is exactly the
    // cries-wolf failure this codebase argues against elsewhere.
    final String storedSecret = (String) getClassFromSharedPreferences(
        ProtocolIdentifier.DISPLAY_TAG_SECRET);
    if (storedSecret != null && !storedSecret.isEmpty()) {
      try {
        account.setDisplayTagSecret(com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
            .decode(storedSecret));
      } catch (java.io.IOException e) {
        Log.w(TAG, "Stored display-tag secret is unreadable; a new one will be minted");
      }
    }

    // Names of deleted contacts, for the duplicate warning.
    //
    // Persisted for the same reason the tag secret is: a defence that resets on every reload is not
    // a defence. reloadAccount runs on every setInputView, so an in-memory-only list would be empty
    // by the time the attacker's invite arrives - which is after the user has put the keyboard away
    // and picked it up again, every time.
    final Object storedRetired = getClassFromSharedPreferences(
        ProtocolIdentifier.RETIRED_DISPLAY_NAMES);
    if (storedRetired instanceof java.util.List) {
      final java.util.LinkedList<String[]> retired = new java.util.LinkedList<>();
      for (final Object entry : (java.util.List<?>) storedRetired) {
        if (entry instanceof java.util.List) {
          final java.util.List<?> pair = (java.util.List<?>) entry;
          // Three elements, not two. retireDisplayName writes {first, last, addressName} and both
          // the suppression in hasRetiredDisplayName and the de-duplication in retireDisplayName
          // are gated on length > 2 - so dropping the address here did not lose a nicety, it made
          // both of them dead code. reloadAccount runs on every setInputView, so the reloaded
          // shape is the only shape production ever sees: the suppression never fired, and every
          // delete-and-re-add appended another copy to a bounded list whose oldest entry is the one
          // an attacker wants evicted.
          retired.add(new String[] {
              pair.size() > 0 && pair.get(0) != null ? String.valueOf(pair.get(0)) : "",
              pair.size() > 1 && pair.get(1) != null ? String.valueOf(pair.get(1)) : "",
              pair.size() > 2 && pair.get(2) != null ? String.valueOf(pair.get(2)) : ""});
        } else if (entry instanceof String[]) {
          retired.add((String[]) entry);
        }
      }
      account.setRetiredDisplayNames(retired);
    }

    migrateLegacyKeys(account);
    return account;
  }

  /**
   * Runs the one-time key migration, once, at the first load after the upgrade.
   *
   * <p>The transformation itself lives in {@link LegacyKeyMigration}; this decides whether it has
   * already happened. The marker is an efficiency guard, not the safety property - a first version
   * of this comment claimed it was what made the load-time answer sound, and a test written against
   * that claim passed with the check removed. What actually makes it safe is that re-keying is
   * idempotent: a re-keyed entry carries the separator and is skipped, so a second pass has nothing
   * to act on however the contact list has changed. The marker saves rescanning the whole log on
   * every setInputView, which happens a great deal.
   */
  private void migrateLegacyKeys(final Account account) {
    if (account == null) return;
    if (getClassFromSharedPreferences(ProtocolIdentifier.KEY_SCHEMA_MIGRATED) != null) return;

    LegacyKeyMigration.apply(account);
    storeInSharedPreferences(ProtocolIdentifier.KEY_SCHEMA_MIGRATED, "1");
  }

  /**
   * One write, not eight.
   *
   * <p>This used to call the eight single-value setters in turn, each of which is a durable
   * {@code commit()}. That is eight fsyncs on the IME main thread per save, and eight places for a
   * save to stop half way: a process death or an I/O failure after the fourth left a store holding
   * a new protocol store beside an old contact list, with nothing recording that the two no longer
   * described the same moment. Reload has no way to detect that - both halves are individually
   * well-formed - so the account came back subtly wrong rather than obviously broken.
   *
   * <p>Sealing happens for the whole batch before anything is handed to the delegate, so a failure
   * to encrypt any one value writes none of them.
   */
  public void storeAllInformationInSharedPreferences(final Account account) {
    final EncryptedKeyValueStore store = secureStore();
    if (store == null) return;

    final Map<String, String> batch = new LinkedHashMap<>();
    put(batch, ProtocolIdentifier.METADATA_STORE, account.getMetadataStore());
    put(batch, ProtocolIdentifier.UNIQUE_USER_ID, account.getName());
    // Protocol store carries the registrationId and the identity key pair.
    put(batch, ProtocolIdentifier.PROTOCOL_STORE, account.getSignalProtocolStore());
    put(batch, ProtocolIdentifier.PROTOCOL_ADDRESS, account.getSignalProtocolAddress());
    put(batch, ProtocolIdentifier.DEVICE_ID, account.getDeviceId());
    put(batch, ProtocolIdentifier.UNENCRYPTED_MESSAGES, account.getUnencryptedMessages());
    put(batch, ProtocolIdentifier.CONTACTS, account.getContactList());

    put(batch, ProtocolIdentifier.RETIRED_DISPLAY_NAMES, account.getRetiredDisplayNames());

    final byte[] secret = account.getDisplayTagSecret();
    if (secret != null) {
      batch.put(String.valueOf(ProtocolIdentifier.DISPLAY_TAG_SECRET),
          JsonUtil.toJson(com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
              .encodeBytes(secret)));
    }

    try {
      store.putAll(batch);
    } catch (StorageCryptoException e) {
      // Same rule as the single-value path: never fall back to cleartext. Losing a write is
      // recoverable; writing the identity private key to disk unencrypted is not.
      Log.e(TAG, "Error: could not store account information", e);
    }
  }

  private static void put(final Map<String, String> batch, final ProtocolIdentifier identifier,
                          final Object value) {
    batch.put(String.valueOf(identifier), JsonUtil.toJson(value));
  }

  private void storeDisplayTagSecretInSharedPreferences(final byte[] secret) {
    if (secret == null) return;
    storeInSharedPreferences(ProtocolIdentifier.DISPLAY_TAG_SECRET,
        com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64.encodeBytes(secret));
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
