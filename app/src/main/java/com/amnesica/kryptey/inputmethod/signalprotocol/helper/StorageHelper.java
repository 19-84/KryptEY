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
    if (identityReachedDisk()) return true;
    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    return messageFile != null && new SharedPreferencesKeyValueStore(messageFile).contains(
        String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES));
  }

  /**
   * Whether the <em>identity</em> specifically is on disk, in the account's own file.
   *
   * <p>A different question from {@link #hasExistingProtocolData()}, and separating them is the
   * point. "Is there something here I must not overwrite" is answered by either file. "Did the
   * identity I just generated actually persist" is answered by one, and only one.
   *
   * <p>They were briefly the same method, and it was a bad merge: the chat log is written before the
   * account batch, so an install whose log commit succeeded and whose account batch failed - a full
   * disk, one unserialisable value - satisfied the combined predicate. {@code initialize()} then
   * reported success, the caller recorded "setup done" permanently, and the keyboard was left with
   * no identity and no way back, while the strip told the user their contacts were safe but locked.
   */
  public boolean identityReachedDisk() {
    if (mContext == null) return false;
    final SharedPreferences accountFile = preferencesNamed(mSharedPreferenceName);
    return accountFile != null && new SharedPreferencesKeyValueStore(accountFile).contains(
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
  @android.annotation.SuppressLint("ApplySharedPref")   // durable by design; see the call site
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
        //
        // commit(), not apply(), and lint is told so below: apply() is asynchronous, so the removal
        // could still be in flight when the process dies. That is survivable here - the log would
        // simply be found in both files again next time - but every other durable write in this
        // class is a commit for the same reason, and a storage layer that is sometimes eventual is
        // harder to reason about than one that is never eventual.
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
  @android.annotation.SuppressLint("ApplySharedPref")   // durable by design; see the call site
  private void moveMessageLogToItsOwnFile(final ArrayList<StorageMessage> messages,
      final SharedPreferences accountFile, final String key) {
    if (messages == null) return;

    if (messages.isEmpty()) {
      // Every install that predates this branch has an UNENCRYPTED_MESSAGES value even with no
      // messages - the old batch wrote it whenever the log was loaded, and a fresh account reports
      // its empty log loaded - so on upgrade this path runs with "[]" for the entire existing user
      // base. Copying that would create the log's file on every one of those devices, and that
      // file's existence is what says "this device holds data": lose the account file afterwards
      // and the keyboard refuses to re-initialise, permanently, to preserve an empty list. Drop it
      // instead; there is nothing to carry forward.
      accountFile.edit().remove(key).commit();
      Log.i(TAG, "Dropped an empty chat log from the account file rather than moving it");
      return;
    }

    final EncryptedKeyValueStore store = messageStore();
    if (store == null) return;
    try {
      store.put(key, JsonUtil.toJson(messages));
      if (store.get(key) == null) {
        Log.e(TAG, "Not removing the old chat log: the new copy could not be read back");
        return;
      }
      // commit(), not apply(): this is the delete half of copy-verify-delete, and it must be
      // durable before anything reports the move done.
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
    if (cryptoBox == null) {
      // The same rule as secureStore, which had it and this did not. The chat log's store is fed by
      // the same factory, and its NPE would escape further: readMessageLog is the deferred loader,
      // so an unchecked throw here travels out through Account.getUnencryptedMessages and past a
      // click listener that catches only three checked types.
      Log.e(TAG, "no crypto box; the chat log store cannot be opened");
      return null;
    }
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(rawMessages, cryptoBox);
    try {
      // NOT migrateToEncrypted. This file was created by this branch, after encryption existed, and
      // every write to it goes through EncryptedKeyValueStore.put - so cleartext here is never
      // legitimate, and running the cleartext migration on it turns it into a laundering oracle for
      // the account's file. See requireEncryptedOnly for the chain.
      store.requireEncryptedOnly(
          java.util.Collections.singleton(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES)));
    } catch (StorageCryptoException e) {
      Log.e(TAG, "Error: the chat log's store is not in a state this app wrote", e);
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
  /**
   * @return whether the log reached disk, or there was nothing to write.
   *
   * <p>It used to return nothing and swallow the failure, and the caller then returned true on the
   * strength of the account batch alone. So a failed log commit produced exactly the outcome
   * {@code mLastChatLogWriteFailed} exists to report — the message delivered and absent from the
   * history — with the notice never firing, because the flag only ever covered the log being
   * unREADable.
   */
  /** Whether the last {@code storeAllInformationInSharedPreferences} got the log to disk. */
  private boolean mLastMessageLogWriteSucceeded = true;

  public boolean lastMessageLogWriteSucceeded() {
    return mLastMessageLogWriteSucceeded;
  }

  private boolean storeMessageLog(final Account account) {
    // Nothing to write is not a failure: a log that was never loaded cannot have changed, and the
    // stored value is already what would be written.
    if (account == null || !account.messageLogIsLoaded()) return true;
    final ArrayList<StorageMessage> messages = account.getUnencryptedMessages();
    if (messages == null) return true;

    final SharedPreferences messageFile = preferencesNamed(mMessageStoreName);
    final SharedPreferences accountFile = preferencesNamed(mSharedPreferenceName);
    final String logKey = String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);
    // BOTH files. The log is still in the account file whenever a move failed - no message store,
    // a failed write, a failed read-back - and readMessageLog happily serves it from there. Asking
    // only the message file then means a user who deletes their last contact gets the empty list
    // skipped while their full plaintext history sits in the account file and comes back on the
    // next raise. They asked for it to be gone.
    final boolean storedAnywhere = (messageFile != null && messageFile.contains(logKey))
        || (accountFile != null && accountFile.contains(logKey));
    if (messages.isEmpty() && !storedAnywhere) {
      // Nothing to say and nothing already said. Writing an empty list here would create the log's
      // file on every install that has never sent a message, and that file's existence is now one
      // of the things that says "this device holds data" - so an install with no history at all
      // would look like one worth protecting, and a later loss of the account file would leave the
      // keyboard refusing to re-initialise in order to preserve nothing.
      //
      // An empty list IS written once something has been stored: that is a user clearing history,
      // and it must persist.
      return true;
    }

    final EncryptedKeyValueStore store = messageStore();
    if (store == null) {
      Log.e(TAG, "Error: no store for the chat log; it was not saved");
      return false;
    }
    try {
      store.put(String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES), JsonUtil.toJson(messages));
      return true;
    } catch (StorageCryptoException | RuntimeException e) {
      Log.e(TAG, "Error: could not store the chat log", e);
      return false;
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
  public boolean storeAllInformationInSharedPreferences(final Account account) {
    final EncryptedKeyValueStore store = secureStore();
    if (store == null) {
      // Nothing is written here, INCLUDING the log - so the log flag must not keep saying the last
      // write succeeded. It is only ever assigned further down, and its initialiser is true, so a
      // device whose secureStore never resolves (a Keystore key invalidated by a credential change,
      // a migration that threw) reported a healthy log write forever while writing nothing at all.
      mLastMessageLogWriteSucceeded = false;
      return false;
    }

    // The log first, then the account batch, and the order is load-bearing.
    //
    // Two files means two commits, so a kill can land between them. The batch carries
    // KEY_SCHEMA_MIGRATED, which asserts "every key in the log is a rendered address". Batch first,
    // a kill seals that marker over a log still holding pre-upgrade keys: the migration never runs
    // again and those entries are unattributable for good. This way round the surviving state is a
    // re-keyed log with no marker, which the next load simply migrates again - re-keying is
    // idempotent.
    // Kept, and reported through its own accessor rather than folded into the account batch's
    // result. They are different facts: a lost account write costs a trust decision, a lost log
    // write costs a message from the history, and the app says different things about each.
    mLastMessageLogWriteSucceeded = storeMessageLog(account);

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
      return true;
    } catch (StorageCryptoException e) {
      // Same rule as the single-value path: never fall back to cleartext. Losing a write is
      // recoverable; writing the identity private key to disk unencrypted is not.
      //
      // The return value matters as much as the log line. Asking the preferences afterwards whether
      // the identity is there does NOT answer this: SharedPreferences commits to its in-memory map
      // before writing, and on a full disk it deletes the partial file, restores the previous
      // contents on next load, and returns false - while the running process keeps the new value
      // and looks perfectly healthy. That is written up in SharedPreferencesKeyValueStore's javadoc
      // and it is exactly the case initialize() has to be able to see.
      Log.e(TAG, "Error: could not store account information", e);
      return false;
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
   *
   * <p><b>This file runs that migration and the chat log's file does not, deliberately.</b> Sealing
   * cleartext found on disk is a laundering primitive: it takes bytes an attacker wrote and returns
   * them sealed under the real master key, and the AAD binds the key name rather than the file, so
   * the result is portable between the two. The log's file was created by this branch, after
   * encryption existed, so cleartext in it is never legitimate and {@code requireEncryptedOnly}
   * refuses it outright. This file cannot take that position: accepting 0.1.5 cleartext is the
   * whole upgrade path, and refusing it would strand every existing install.
   *
   * <p>So the primitive is still here, and the guard on it — refusing cleartext that sits beside
   * decryptable data — is weaker than it looks, because an attacker with the data directory can
   * simply empty the file first.
   *
   * <p><b>This paragraph used to claim that costs them stealth</b> — that emptying the file makes
   * the user's contact list visibly disappear. A Phase 1 sweep showed the claim is wrong, and it is
   * corrected here rather than left standing, because a residual nobody re-derives is how the size
   * of an accepted risk drifts. The attacker <em>snapshots this file first</em>: empty it, let the
   * keyboard raise once to seal their row, then restore the snapshot with only that row swapped in.
   * The AAD binds the key name, which is unchanged, so it opens. Nothing goes missing and the
   * display tags are untouched. Recorded in REVIVAL.md under "Two storage findings that make a
   * recorded residual wrong", together with the second route — {@code MARKER_MIGRATING} is durable
   * and is invalidated only by a SUCCESSFUL migration, so it can be harvested from an interrupted
   * run and replayed to disarm the guard without emptying anything at all.
   *
   * <p>Both need write access to the app's private storage, which is strictly more than the
   * messenger has. That is why they are deferred rather than urgent — not because they cost the
   * attacker anything.
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
    if (cryptoBox == null) {
      // No box, no store. Guarding each use instead would mean a null check before every seal and
      // open in the class, and the first one missed is an unchecked throw out of the storage path -
      // which this codebase names as its worst crash mode. A missing box is exactly the state
      // storageState() reports as UNREADABLE, and returning null here is how it gets there.
      Log.e(TAG, "no crypto box; the protocol store cannot be opened");
      return null;
    }
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, cryptoBox);

    try {
      if (store.needsMigration()) {
        Log.i(TAG, "Migrating cleartext protocol storage to Keystore-backed encryption");
        store.migrateToEncrypted();
        Log.i(TAG, "Migration complete");
      }
      // Unconditionally, and outside the branch above. A fresh install never needs a migration, so
      // gating the seal on one meant it was never written on exactly the devices that had no legacy
      // store - leaving each of them one free laundering. This also retries a seal that was
      // interrupted or failed on an earlier run.
      store.ensureLegacyMigrationSealed();
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
