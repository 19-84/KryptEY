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
  /**
   * The chat log lives in its own file, and that is a performance property, not tidiness.
   *
   * <p>{@code SharedPreferencesImpl} serialises its entire in-memory map to XML and fsyncs on every
   * {@code commit()}. While the log shared a file with the account, every write to the account -
   * and a raise writes the account - rewrote the whole message history too, whatever had actually
   * changed. Measured: committing one unrelated key costs 13 ms against a small file and 146 ms
   * when a 5.35 MB sibling shares it. Deferring the parse did not touch that; only moving the bytes
   * does.
   */
  private final String mMessageStoreName = "protocol_messages";
  private final CryptoBoxFactory mCryptoBoxFactory;
  private EncryptedKeyValueStore mSecureStore;
  private EncryptedKeyValueStore mMessageStore;

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
   *
   * <p><b>Both files, since the chat log moved into its own.</b> The two can be lost independently -
   * {@code SharedPreferencesImpl.loadFromDisk} swallows a parse failure and installs an empty map,
   * so a corrupt {@code protocol.xml} reads as "no data" while its sibling is untouched. Asking only
   * the account's file then answers "fresh install", a new identity is generated, and that new
   * account's empty log is written straight over the history that survived. Before the split the log
   * lived in the file that was lost, so there was nothing left to destroy; the split created the
   * survivor, and this is the gate learning about it.
   */
  public boolean hasExistingProtocolData() {
    if (mContext == null) return false;
    final SharedPreferences accountFile = preferencesNamed(mSharedPreferenceName);
    if (accountFile != null && new SharedPreferencesKeyValueStore(accountFile).contains(
        String.valueOf(ProtocolIdentifier.PROTOCOL_STORE))) {
      return true;
    }
    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    return messageFile != null && new SharedPreferencesKeyValueStore(messageFile).contains(
        String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES));
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

    ArrayList<Contact> contactList = JsonUtil.convertContactsList(
        (ArrayList<Contact>) getClassFromSharedPreferences(ProtocolIdentifier.CONTACTS));
    final boolean contactsWereReadable = contactList != null;
    if (contactList == null) {
      Log.e(TAG, "Error: stored contacts could not be read; continuing with an empty list");
      contactList = new ArrayList<>();
    }

    Account account = new Account(name, signalProtocolAddress.getDeviceId(), identityKeyPair, metadataStore, signalProtocolStore, signalProtocolAddress); // deviceId is static
    // The chat log is handed over as a way to read it, not as its contents.
    //
    // This is the whole point of the change: loading an account happens on setInputView, which runs
    // every time the keyboard is raised in any app, and the log is the one part of the store that
    // grows without bound. Parsing it here meant every raise paid for the user's entire history -
    // and the write-back that follows a reload re-serialised it too. Nothing on the raise path
    // reads a message; only the message-log screen does, and it can afford the read.
    account.setMessageLogLoader(this::readMessageLog);
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

    migrateLegacyKeys(account, contactsWereReadable);
    return account;
  }

  /**
   * Reads the chat log, refusing to turn a failed read into an empty history.
   *
   * <p>This javadoc used to say the opposite - that it "falls back to empty, deliberately" - and a
   * reviewer caught it still saying so after the body had changed. That is the description a
   * maintainer reads before deciding whether a caller needs a catch, so it saying "nothing to
   * catch" was worse than saying nothing.
   *
   * <p>An absent key still yields an empty list: a new account, or a log never written. A key that
   * is present but unreadable throws {@link
   * com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException}, which leaves the
   * account deferred so no save can write over what could not be read. Callers must survive it -
   * see that class for why an input method may not let it escape.
   */
  private ArrayList<StorageMessage> readMessageLog() {
    final String key = String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);
    final SharedPreferences accountFile = preferencesNamed(mSharedPreferenceName);
    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    if (accountFile == null || messageFile == null) {
      throw new com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException(
          "no preferences available to read the chat log from");
    }

    final boolean inItsOwnFile = messageFile.contains(key);
    final boolean inTheAccountFile = accountFile.contains(key);

    if (inItsOwnFile) {
      final ArrayList<StorageMessage> messages = readLogFrom(messageStore(), key);
      if (inTheAccountFile) {
        // A move that was interrupted after the copy. Finishing it here rather than at the point of
        // the move is what makes the move idempotent instead of one-shot: whatever killed the
        // process last time, the next load lands in a consistent place.
        accountFile.edit().remove(key).commit();
        Log.i(TAG, "Finished moving the chat log out of the account file");
      }
      return messages;
    }

    if (inTheAccountFile) {
      final ArrayList<StorageMessage> messages = readLogFrom(secureStore(), key);
      moveMessageLogToItsOwnFile(messages, accountFile, key);
      return messages;
    }

    // Genuinely nothing stored: a new account, or a log never written. Distinguishing this from
    // "stored but unreadable" is the whole reason both branches above test key presence rather than
    // trusting a null read - only the key's presence tells them apart, and keys are stored in the
    // clear while values are sealed.
    return new ArrayList<>();
  }

  private SharedPreferences preferencesNamed(final String name) {
    if (mContext == null) {
      logError("mContext");
      return null;
    }
    return mContext.getSharedPreferences(name, Context.MODE_PRIVATE);
  }

  /**
   * Deserialises the log out of one store, refusing to turn an unreadable value into an empty one.
   *
   * <p>An account that believes the user has no history gets that belief written to disk by the
   * next ordinary save, and the log is plaintext the user cannot recover from anywhere else. So a
   * present-but-unreadable value throws, which leaves the account deferred and the save skipping
   * the key. Callers must survive that - see {@code ChatLogUnavailableException} for why an input
   * method may not let it escape a click listener.
   */
  private ArrayList<StorageMessage> readLogFrom(final EncryptedKeyValueStore store,
      final String key) {
    ArrayList<StorageMessage> messages = null;
    if (store != null) {
      try {
        final String json = store.get(key);
        if (json != null) {
          messages = JsonUtil.convertUnencryptedMessagesList(
              (ArrayList<StorageMessage>) JsonUtil.fromJson(
                  json, ProtocolIdentifier.UNENCRYPTED_MESSAGES.className));
        }
      } catch (StorageCryptoException | IOException | RuntimeException e) {
        Log.e(TAG, "Error: could not read the stored chat log", e);
      }
    }
    if (messages != null) return messages;

    throw new com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException(
        "the stored chat log exists but could not be read; refusing to present it as empty");
  }

  /**
   * Moves the log out of the account's file: copy, verify, and only then delete.
   *
   * <p>The order is the safety. A kill after the copy leaves the log in both files, which costs
   * disk and nothing else - the next load prefers the new copy and clears the old. The other order
   * round loses the history outright. Verifying before deleting covers the case where the write
   * appeared to succeed and did not survive: a store that cannot read back what it just wrote is
   * not one to delete the original on the strength of.
   */
  private void moveMessageLogToItsOwnFile(final ArrayList<StorageMessage> messages,
      final SharedPreferences accountFile, final String key) {
    final EncryptedKeyValueStore store = messageStore();
    if (store == null || messages == null) return;
    try {
      store.put(key, JsonUtil.toJson(messages));
      if (store.get(key) == null) {
        Log.e(TAG, "Not removing the old chat log: the new copy could not be read back");
        return;
      }
      accountFile.edit().remove(key).commit();
      Log.i(TAG, "Moved the chat log into its own file; a keyboard raise no longer rewrites it");
    } catch (StorageCryptoException | RuntimeException e) {
      // The original is untouched, so this is a retry next load rather than a loss.
      Log.e(TAG, "Error: could not move the chat log to its own file; leaving it where it is", e);
    }
  }

  /**
   * The store the chat log lives in, on its own preferences file.
   *
   * <p><b>{@code hasExistingData} is computed across BOTH files, and that is the whole safety of
   * this method.</b> That boolean is the sole input to the Keystore box's refusal to mint a
   * replacement master key, and both stores share one Keystore alias. Asking only about this file -
   * which is empty on every device that has not yet moved its log - would answer "no existing
   * data" and authorise minting a fresh key over the user's identity, every session, on every
   * device with history. The question is "does this user have an identity", and that is not a
   * per-file question.
   */
  private EncryptedKeyValueStore messageStore() {
    if (mMessageStore != null) return mMessageStore;
    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    final SharedPreferences accountFile = preferencesNamed(mSharedPreferenceName);
    if (messageFile == null || accountFile == null) return null;

    final KeyValueStore rawMessages = new SharedPreferencesKeyValueStore(messageFile);
    final boolean alreadyEncrypted =
        EncryptedKeyValueStore.hasEncryptedData(rawMessages)
            || EncryptedKeyValueStore.hasEncryptedData(
                new SharedPreferencesKeyValueStore(accountFile));

    final CryptoBox cryptoBox = mCryptoBoxFactory.create(mContext, alreadyEncrypted);
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(rawMessages, cryptoBox);
    try {
      if (store.needsMigration()) store.migrateToEncrypted();
    } catch (StorageCryptoException e) {
      Log.e(TAG, "Error: could not prepare the chat log's store", e);
      return null;
    }
    mMessageStore = store;
    return store;
  }

  /**
   * Writes the chat log to its own file, if anything has actually read it.
   *
   * <p>Two conditions, not one. "Loaded" alone was enough to write once, and a null log reports
   * itself loaded - so a failed read produced a save of JSON "null" over the whole history. The
   * account no longer reaches that state, and this is the second lock on the same door.
   */
  private void storeMessageLog(final Account account) {
    if (account == null || !account.messageLogIsLoaded()) return;
    final ArrayList<StorageMessage> messages = account.getUnencryptedMessages();
    if (messages == null) return;

    final EncryptedKeyValueStore store = messageStore();
    if (store == null) {
      Log.e(TAG, "Error: no store for the chat log; it was not saved");
      return;
    }
    try {
      store.put(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES), JsonUtil.toJson(messages));
    } catch (StorageCryptoException | RuntimeException e) {
      Log.e(TAG, "Error: could not store the chat log", e);
    }
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
  private void migrateLegacyKeys(final Account account, final boolean contactsWereReadable) {
    if (account == null) return;
    if (getClassFromSharedPreferences(ProtocolIdentifier.KEY_SCHEMA_MIGRATED) != null) {
      account.setKeysAreRendered(true);   // the store already said so
      return;
    }

    // Not against a contact list that failed to load.
    //
    // The load deliberately tolerates an unreadable contact list and continues with an empty one,
    // which was harmless while readers matched at read time. It is not harmless for a one-shot
    // irreversible pass: every legacy entry would be unattributable against an empty list, the
    // marker would seal that answer, and the next write-back would persist it. One unreadable
    // value would have cost the user their entire pre-upgrade history, permanently and silently.
    if (!contactsWereReadable) {
      Log.w(TAG, "Not migrating legacy keys: the contact list could not be read, and the answer "
          + "this migration gives is not reversible");
      return;
    }

    // This forces the chat log to be read, and that is correct rather than a regression of the
    // laziness above. The marker check at the top of this method means we only get here on the ONE
    // load that actually performs the migration - once per install, ever. Every subsequent raise
    // returns at that check without touching the log. Deferring the migration itself was the other
    // option and it is not worth it: LegacyKeyMigration is the component on this branch with the
    // worst record for being changed, and one parse once is not a cost worth that risk.
    try {
      LegacyKeyMigration.apply(account);
    } catch (final RuntimeException e) {
      // Not sealing an answer we could not compute.
      //
      // The symmetric case to contactsWereReadable above, and it was missing: this pass is one-shot
      // and irreversible, so running it against a log we failed to read - and then writing the
      // marker beside that result - would classify the user's entire pre-upgrade history as
      // unattributable, permanently. Returning without the marker means the next raise tries again.
      Log.w(TAG, "Not migrating legacy keys: the chat log could not be read, and the answer this "
          + "migration gives is not reversible", e);
      return;
    }
    account.setKeysAreRendered(true);

    // The marker is NOT written here - it travels in the same batch as the data it describes, in
    // storeAllInformationInSharedPreferences. See the comment there.
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

    // The log first, then the account batch, and the order is load-bearing.
    //
    // Two files means two commits, so a kill can land between them. The batch carries
    // KEY_SCHEMA_MIGRATED, which asserts "every key in the log is a rendered address". Batch first,
    // a kill seals that marker over a log still holding pre-upgrade keys: the migration never runs
    // again and those entries are unattributable for good. This way round the surviving state is a
    // re-keyed log with no marker, which the next load simply migrates again - re-keying is
    // idempotent.
    storeMessageLog(account);

    final Map<String, String> batch = new LinkedHashMap<>();
    put(batch, ProtocolIdentifier.METADATA_STORE, account.getMetadataStore());
    put(batch, ProtocolIdentifier.UNIQUE_USER_ID, account.getName());
    // Protocol store carries the registrationId and the identity key pair.
    put(batch, ProtocolIdentifier.PROTOCOL_STORE, account.getSignalProtocolStore());
    put(batch, ProtocolIdentifier.PROTOCOL_ADDRESS, account.getSignalProtocolAddress());
    put(batch, ProtocolIdentifier.DEVICE_ID, account.getDeviceId());
    // Written only if something actually read it. A log that was never loaded cannot have changed,
    // and the stored value is already what we would write - so writing it would mean parsing the
    // whole history in order to serialise it back byte for byte. putAll writes the keys it is
    // given and clears nothing, so omitting this leaves the stored log exactly as it was.
    // The chat log is NOT in this batch. It lives in its own file and was written just above, in
    // storeMessageLog - see there for why that order round.
    put(batch, ProtocolIdentifier.CONTACTS, account.getContactList());

    put(batch, ProtocolIdentifier.RETIRED_DISPLAY_NAMES, account.getRetiredDisplayNames());

    final byte[] secret = account.getDisplayTagSecret();
    if (secret != null) {
      batch.put(String.valueOf(ProtocolIdentifier.DISPLAY_TAG_SECRET),
          JsonUtil.toJson(com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
              .encodeBytes(secret)));
    }

    // The marker means "every key in this store is a rendered address", so it is written when this
    // account is known to satisfy that and not otherwise. See Account.keysAreRendered for the three
    // cases and for the two ways of getting this wrong that came before it.
    //
    // In the batch, not before it: as its own durable commit it outran the data, and a kill in
    // between - routine for an IME - left a store marked migrated whose log was still bare-keyed,
    // unreachable from every contact row for the life of the install and unerasable, since erasing
    // a conversation means deleting a contact and no contact owns those entries.
    if (account.keysAreRendered()) {
      batch.put(String.valueOf(ProtocolIdentifier.KEY_SCHEMA_MIGRATED), JsonUtil.toJson("1"));
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
    // hasEncryptedData, not isEncrypted. This boolean is the sole input to the Keystore box's
    // refusal to mint a replacement master key, so it must mean "is any of this unreadable without
    // the existing key", not "did a marker survive". put() and putAll() write the values and the
    // completion marker as two separate durable commits, and this process is killed routinely - so
    // ciphertext with no marker above it is an ordinary state, not a corrupt one, and it is still
    // the user's identity.
    // Asked of both files, for the same reason messageStore() asks of both: the two stores share
    // one Keystore alias, and this store is always built first - so if it minted a replacement key
    // here, the log's store would find that fresh alias and its own refusal would never be reached.
    // The question is "does this user have an identity", and it is not per-file.
    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    final boolean alreadyEncrypted = EncryptedKeyValueStore.hasEncryptedData(raw)
        || (messageFile != null && EncryptedKeyValueStore.hasEncryptedData(
            new SharedPreferencesKeyValueStore(messageFile)));
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
