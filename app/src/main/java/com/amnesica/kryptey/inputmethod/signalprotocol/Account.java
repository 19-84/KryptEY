package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.DuplicateContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.time.Instant;

public class Account {

  private final String mName;
  private final int mDeviceId;
  private IdentityKeyPair mIdentityKeyPair;
  private PreKeyMetadataStore mMetadataStore;
  private SignalProtocolStoreImpl mSignalProtocolStore;
  private SignalProtocolAddress mSignalProtocolAddress;
  private ArrayList<StorageMessage> mUnencryptedMessages;
  /** Non-null exactly while the chat log is deferred. See {@link #setMessageLogLoader}. */
  private MessageLogLoader mMessageLogLoader;
  /** True only for the duration of a loader call, to refuse re-entrant loads. */
  private transient boolean mLoadingMessageLog;
  private ArrayList<Contact> contactList;

  /**
   * Per-install secret keying the contact display tags.
   *
   * <p>The tags exist so a user can tell two contacts with the same display name apart. An
   * <em>unkeyed</em> hash of the address cannot do that job against this adversary: the messenger
   * knows the address of the contact being impersonated and chooses its own freely, so it can
   * compute the target tag and grind an address whose tag matches wherever a person actually looks.
   * Measured on one JVM thread with no GPU, matching the leading group took nine seconds; more
   * output bits do not help, because they make partial reading more likely rather than less.
   *
   * <p>Keying removes the attacker's ability to compute the target at all. It never leaves the
   * device and is persisted in its own row of the same Keystore-encrypted store as the identity
   * key. The constructor mints one on every load — which is what made an earlier bug possible — and
   * the stored value then overwrites it, so a tag stays stable for the life of an install, which is
   * what makes it comparable between two rows.
   */
  private byte[] displayTagSecret;

  public Account(String name, int deviceId, IdentityKeyPair identityKeyPair, PreKeyMetadataStore metadataStore, SignalProtocolStoreImpl signalProtocolStore, SignalProtocolAddress signalProtocolAddress) {
    this.mName = name;
    this.mDeviceId = deviceId;
    this.mIdentityKeyPair = identityKeyPair;
    this.mMetadataStore = metadataStore;
    this.mSignalProtocolStore = signalProtocolStore;
    this.mSignalProtocolAddress = signalProtocolAddress;
    this.mUnencryptedMessages = new ArrayList<>();
    this.contactList = new ArrayList<>();
    this.displayTagSecret = newDisplayTagSecret();
  }

  private static byte[] newDisplayTagSecret() {
    final byte[] secret = new byte[32];
    new java.security.SecureRandom().nextBytes(secret);
    return secret;
  }

  public byte[] getDisplayTagSecret() {
    // Unreachable as the code stands, and kept anyway.
    //
    // The comment here used to say this is what keeps tags working after an upgrade from a store
    // predating the field. It is not: the constructor mints one unconditionally, StorageHelper
    // calls setDisplayTagSecret only when it has actually decoded a stored value, and no path
    // deserialises an Account around the constructor. So on any live account this field is already
    // set, and this branch never runs. The upgrade behaviour it claimed credit for is the
    // constructor's.
    //
    // Worth keeping as belt-and-braces - a null here would otherwise be an NPE inside tag
    // derivation, on the contact list, in the middle of the one screen a user checks when
    // something looks wrong. Worth NOT claiming to be load-bearing, because a reader deciding
    // whether the upgrade path is covered would stop here and conclude yes.
    if (displayTagSecret == null || displayTagSecret.length == 0) {
      displayTagSecret = newDisplayTagSecret();
    }
    return displayTagSecret;
  }

  public void setDisplayTagSecret(final byte[] secret) {
    this.displayTagSecret = secret;
  }

  // testing only
  public Account(String name, int mDeviceId) {
    this.mName = name;
    this.mDeviceId = mDeviceId;
  }

  public String getName() {
    return mName;
  }

  public IdentityKeyPair getIdentityKeyPair() {
    return mIdentityKeyPair;
  }

  public void setIdentityKeyPair(IdentityKeyPair identityKeyPair) {
    this.mIdentityKeyPair = identityKeyPair;
  }

  public PreKeyMetadataStore getMetadataStore() {
    return mMetadataStore;
  }

  public SignalProtocolStoreImpl getSignalProtocolStore() {
    return mSignalProtocolStore;
  }

  public SignalProtocolAddress getSignalProtocolAddress() {
    return mSignalProtocolAddress;
  }

  public int getDeviceId() {
    return mDeviceId;
  }

  public void setMetadataStore(PreKeyMetadataStore metadataStore) {
    this.mMetadataStore = metadataStore;
  }

  public void setSignalProtocolStore(SignalProtocolStoreImpl signalProtocolStore) {
    this.mSignalProtocolStore = signalProtocolStore;
  }

  public void setSignalProtocolAddress(SignalProtocolAddress signalProtocolAddress) {
    this.mSignalProtocolAddress = signalProtocolAddress;
  }

  /**
   * Supplies the chat log the first time anything asks for it.
   *
   * <p>Exists so the log does not have to be parsed to load an account. See
   * {@link #setMessageLogLoader}.
   */
  public interface MessageLogLoader {
    ArrayList<StorageMessage> load();
  }

  /**
   * Defer the chat log: hand over how to read it rather than what it contains.
   *
   * <p>The log is the largest thing in the store and the only part of an account that grows without
   * bound - it has no size cap and no age cap, and a correspondent can add to it. Loading an account
   * happens on {@code setInputView}, which runs every time the keyboard is raised, in every app. So
   * the whole history was being parsed, and then re-serialised by the write-back, on every raise:
   * measured at 20,000 messages, 72 ms to read and 194 ms to write, for data the keyboard almost
   * never touches. Nothing on the raise path reads a message; only the message-log screen does.
   *
   * <p>Passing null loads nothing and leaves the log EMPTY, not null. An earlier version of this
   * said "empty" and set null, which is the same erasure this class must never allow: a null log
   * reports itself loaded, and {@code JsonUtil.toJson(null)} is the string "null", which seals and
   * commits perfectly well over the user's entire history.
   */
  public void setMessageLogLoader(final MessageLogLoader loader) {
    this.mMessageLogLoader = loader;
    this.mUnencryptedMessages = loader == null ? new ArrayList<>() : null;
  }

  /** Whether the log has actually been read, as opposed to merely being available. */
  public boolean messageLogIsLoaded() {
    return mMessageLogLoader == null;
  }

  public synchronized ArrayList<StorageMessage> getUnencryptedMessages() {
    if (mMessageLogLoader != null) {
      // Re-entrancy is refused rather than tolerated.
      //
      // An earlier comment here claimed the migration re-enters this method through
      // soleContactNamed. It does not: LegacyKeyMigration takes the list once and only then walks
      // it, and soleContactNamed reads the contact list. No production path is re-entrant, so the
      // honest thing is to say so loudly if one ever appears - during a load both fields are in
      // flux, and a re-entrant caller would silently receive something that is not the log.
      if (mLoadingMessageLog) {
        throw new IllegalStateException("re-entrant chat-log load: something called "
            + "getUnencryptedMessages() from inside the loader, which cannot return the log");
      }
      final MessageLogLoader loader = mMessageLogLoader;
      mLoadingMessageLog = true;
      try {
        final ArrayList<StorageMessage> loaded = loader.load();
        mUnencryptedMessages = loaded != null ? loaded : new ArrayList<>();
        mMessageLogLoader = null;
      } finally {
        // The loader is cleared only on success. If the read threw - one unparseable timestamp in
        // the log is enough, convertValue throws IllegalArgumentException - the account stays
        // DEFERRED. That is the difference between a bad day and an unrecoverable one: an account
        // that reports itself loaded while holding nothing gets that nothing written over the
        // user's history by the next ordinary save, and the decrypt path swallows
        // RuntimeException, so the process survives to do it.
        mLoadingMessageLog = false;
      }
    }
    return mUnencryptedMessages;
  }

  /**
   * Set when the stored contact list could not be read, to stop the next write destroying it.
   *
   * <p>The load substituted an empty list for an unreadable value and carried on. The very next
   * raise then wrote that empty list back over the ciphertext - every contact row, and with them
   * every verified badge, gone permanently even where the stored bytes were recoverable. The
   * migration beside it already guards against exactly this shape and says so in its own comment;
   * the contact list itself had no such guard.
   *
   * <p>Values are sealed per key, so one value can fail to open while the identity key, the address
   * and the protocol store all read fine - which is why the app looks healthy in this state and
   * reports READABLE storage.
   */
  private boolean contactsWereUnreadable = false;

  /**
   * Stored values that were present and could not be opened, and so must not be written over.
   *
   * <p>A sealed value that will not decrypt reads back as null, which is indistinguishable from one
   * that was never stored - so the loader substituted a default and the write-back that follows
   * every load persisted the substitution over ciphertext that may well have been recoverable. The
   * contact list has had its own guard against exactly this since a review round found it; these
   * are the two that did not.
   *
   * <p>Per key rather than a whole-store refusal, deliberately. Copying the contact list's
   * treatment - refuse every write while any value is unreadable - would turn one corrupt row into
   * a permanently read-only app: no contact added, no key pinned, no rejection recorded, and
   * nothing to repair it. {@code putAll} clears nothing, so omitting one key leaves its stored
   * bytes exactly as they were, and the cost is that the value stays wrong for the session instead
   * of being made wrong for good.
   */
  private final java.util.Set<String> unreadableValues = new java.util.HashSet<>();

  /** Marks a stored value as present-but-unopenable, so the next save leaves it alone. */
  public void markValueUnreadable(final String key) {
    if (key != null) unreadableValues.add(key);
  }

  /** @return whether this value must be left on disk untouched. */
  public boolean valueWasUnreadable(final String key) {
    return unreadableValues.contains(key);
  }

  public void markContactsUnreadable() {
    this.contactsWereUnreadable = true;
  }

  public boolean contactsWereUnreadable() {
    return contactsWereUnreadable;
  }

  public void setUnencryptedMessages(ArrayList<StorageMessage> unencryptedMessages) {
    this.mMessageLogLoader = null;
    this.mUnencryptedMessages = unencryptedMessages;
  }

  public void addUnencryptedMessage(Contact contact, StorageMessage storageMessage) throws RuntimeException {
    final ArrayList<StorageMessage> messages = getUnencryptedMessages();
    if (messages == null)
      throw new RuntimeException("Error: UnencryptedMessage could not be saved. mUnencryptedMessages is null");
    messages.add(storageMessage);
  }

  /**
   * Remove one message this account sent, identified by recipient and exact timestamp.
   *
   * <p>Exists so a send that was refused after the plaintext had already been recorded can be
   * undone. Matching on the timestamp rather than removing "the last" entry matters: a message from
   * the contact can arrive between the two, and removing that instead would delete something the
   * user actually received.
   */
  /**
   * Removes one logged message, identified by the full address rather than the bare name.
   *
   * <p>The device id is load-bearing here, and its absence was the odd one out: every other log
   * operation identifies an entry by the rendered address through {@code belongsTo}, and only this
   * one matched on the address NAME. Two contacts can share a name and differ by device id - that is
   * the whole reason the chat log is keyed the way it is - so a rollback of a failed send could
   * erase a delivered message belonging to the other one.
   */
  public boolean removeUnencryptedMessage(final String recipientUUID, final int deviceId,
      final Instant timestamp) {
    if (recipientUUID == null || timestamp == null) return false;
    final ArrayList<StorageMessage> messages = getUnencryptedMessages();
    if (messages == null) return false;
    for (int i = messages.size() - 1; i >= 0; i--) {
      final StorageMessage candidate = messages.get(i);
      if (recipientUUID.equals(candidate.getRecipientUUID())
          && candidate.belongsTo(recipientUUID, deviceId)
          && timestamp.equals(candidate.getTimestamp())) {
        messages.remove(i);
        return true;
      }
    }
    return false;
  }

  /**
   * Display names of contacts the user has deleted, most recent last.
   *
   * <p>Deleting a contact used to erase the only defence against the cheapest impersonation there
   * is. The pin deliberately outlives the contact, which closes the SAME-address door - a
   * substituted bundle for that address still fails. The attacker simply uses the other door: its
   * own fresh address, with the deleted contact's name. Nothing is pinned there, so
   * trust-on-first-use accepts it silently, and with the name gone from the contact list the
   * duplicate-name warning has nothing to fire on. A warned attack became an unwarned one.
   *
   * <p>That matters because deletion is one tap with no confirmation, and a hostile messenger can
   * drive a user to it: replay any message, the decrypt fails, repeat until they delete the contact
   * and start over.
   *
   * <p>Bounded, because it is a warning aid rather than a security record - unlike
   * {@code rejectedAddresses}, which must never be forgotten.
   *
   * <p><b>What this does NOT cover.</b> It matches the folded name exactly. For a LIVE duplicate
   * that is enough, because a name the fold misses still leaves two rows the user can tell apart by
   * their address tags - the argument {@code ListAdapterContacts} makes. After a deletion there is
   * no second row, so a dodge costs the attacker nothing: measured, "Bob", "Bob J", "Bob Jones Jr",
   * "Robert Jones" and "Bob Jones (new phone)" all pass without a warning after "Bob Jones" is
   * deleted. Matching more loosely would fire on ordinary names and habituate the user, which is
   * the failure mode this whole control is trying to avoid. So the gap is real and stated rather
   * than papered over - the address tag remains the thing that distinguishes, and comparing the
   * safety number remains the only thing that proves anything.
   */
  private LinkedList<String[]> retiredDisplayNames = new LinkedList<>();

  /** How many deleted names to remember. Enough to cover a user tidying their list. */
  private static final int RETIRED_DISPLAY_NAME_LIMIT = 100;

  /**
   * How many addresses one retired name remembers.
   *
   * <p>The list is bounded by NAME, so a name's addresses cannot be the thing that fills it. This
   * second bound only stops one name growing without limit.
   *
   * <p><b>The direction this trim drops in is not what makes it safe, and the first version of this
   * comment had that backwards.</b> It said dropping an address "loses silence, not a warning". The
   * reader settles it the other way: {@code everyAddressIs} refuses to suppress on the FIRST
   * address that differs from the one being asked about, so every address in the set is a reason
   * not to suppress, and removing one can only make suppression easier to satisfy.
   *
   * <p>What makes the trim safe is the SIZE it leaves. Suppression needs a set whose addresses are
   * all the excluded one, and the merge de-duplicates, so a trimmed set holds eight DISTINCT
   * addresses and cannot satisfy that. The de-duplication is load-bearing for this, not tidiness:
   * without it a messenger could drive eight deletions at its own address, fill the set with eight
   * copies of it, push the genuine address out and be suppressed permanently. Lowering this bound
   * toward one is therefore the dangerous direction, and nothing else in the file says so.
   */
  public static final int RETIRED_ADDRESSES_PER_NAME = 8;

  public LinkedList<String[]> getRetiredDisplayNames() {
    if (retiredDisplayNames == null) retiredDisplayNames = new LinkedList<>();
    return retiredDisplayNames;
  }

  public void setRetiredDisplayNames(final LinkedList<String[]> retiredDisplayNames) {
    this.retiredDisplayNames = retiredDisplayNames;
  }

  /**
   * Records a deleted contact's name and the address it was deleted from.
   *
   * <p>The address matters as much as the name. Deletion keeps the pin, so a re-add at the SAME
   * address is provably the same identity - a substituted bundle for it is still refused. Warning
   * there is a false alarm, and it is the commonest firing of this control, because the app's own
   * decryption-failure advice sends users round exactly that loop.
   *
   * <p>Merged by name, so the bound counts DISTINCT names. Without that, a hundred delete-and-re-add
   * cycles of one unrelated contact evict the name an attacker cares about - user work rather than
   * attacker work, but it is the loop the app's advice creates.
   *
   * <p><b>De-duplicated the way the READER matches</b>, which it was not. The reader folds:
   * {@code hasRetiredDisplayName} goes through {@code displayNamesMatch}, which NFKC-folds, strips
   * invisibles and maps confusables, so "Bob Jones", "Bob Jones " and a Cyrillic-B "Вob Jones"
   * are one name to it. The writer compared raw strings, so those occupied three slots and answered
   * one query - and a messenger that can drive deletions (replay a message, the decrypt fails, the
   * app's own advice is delete-and-re-invite) could vary the name each cycle so the dedup never
   * collapsed, pressing a real entry out of the bound with a hundred variants instead of a hundred
   * repeats. Writer and reader now fold identically, which is the only way a bound on "distinct
   * names" means anything.
   */
  public void retireDisplayName(final String firstName, final String lastName,
                                final String addressName) {
    if (firstName == null && lastName == null) return;
    final String first = firstName == null ? "" : firstName;
    final String last = lastName == null ? "" : lastName;
    final String address = addressName == null ? "" : addressName;

    final LinkedList<String[]> retired = getRetiredDisplayNames();

    // One entry per folded NAME, carrying the set of addresses that name has been deleted from.
    //
    // Keying was name-only for one round and name-and-address for the next, and each shape bought
    // its own attack, because the two things being asked of this list are different questions:
    //
    //   the BOUND asks "how many names do we remember", and the reader matches on the name - so an
    //   entry per address let the attacker mint entries for free by varying the address, pressing a
    //   real name out of a hundred-entry list without the user ever typing the impostor's name;
    //
    //   the SUPPRESSION asks "was this exact address deleted", because deletion keeps the pin and a
    //   re-add there is provably the same identity - so collapsing to the name alone made the
    //   deletion of an impostor "Bob" EVICT the genuine "Bob", and the impostor's next invite at
    //   its own address arrived silent. One attacker-driven cycle, using the impersonated name as
    //   the eviction key.
    //
    // Neither is a keying problem. They are two facts about one name, and an entry that holds the
    // name once and its addresses as a set answers both: the bound counts names, so varying the
    // address merges instead of accumulating, and the suppression still needs an exact address that
    // is in the set, so deleting the impostor ADDS its address rather than removing the genuine
    // one. Merging is also why this cannot be written as removeIf-then-add: the entry being
    // replaced carries addresses the new one must keep.
    String[] merged = null;
    for (final java.util.Iterator<String[]> it = retired.iterator(); it.hasNext(); ) {
      final String[] entry = it.next();
      if (entry.length < 2
          || !SignalProtocolMain.displayNamesMatch(entry[0], entry[1], first, last)) {
        continue;
      }
      it.remove();
      merged = entry;
      break;
    }

    if (merged == null) {
      retired.addLast(new String[] {first, last, address});
    } else {
      final java.util.LinkedList<String> addresses = new java.util.LinkedList<>();
      for (int i = 2; i < merged.length; i++) {
        // De-duplicated, and this is the clause the whole thing rests on rather than a tidiness:
        // repeats of one address are what would let a messenger fill the set with eight copies of
        // its own, push everything else out, and be suppressed permanently. Deletions are
        // attacker-drivable - replay a message, the decrypt fails, the app's own advice is delete
        // and re-invite - so eight cycles at one address is a cheap sequence to drive.
        if (merged[i] != null && !merged[i].equals(address)) addresses.addLast(merged[i]);
      }
      addresses.addLast(address);
      // Oldest address first, so the trim drops the oldest. Nothing observable turns on which end
      // it drops - see RETIRED_ADDRESSES_PER_NAME for why it is the size that matters - and an
      // entry written before the record held an address contributes an empty element, which is the
      // oldest thing in it and so the first thing a trim removes. That costs a legacy entry its
      // "warn about everything" element, which the eight distinct addresses replacing it supply
      // anyway.
      while (addresses.size() > RETIRED_ADDRESSES_PER_NAME) addresses.removeFirst();

      final String[] replacement = new String[2 + addresses.size()];
      // The name is re-recorded from the CURRENT deletion rather than kept from the old entry. The
      // two fold to the same thing by construction; the newer spelling is the one the user last
      // saw, and the warning quotes it back to them.
      replacement[0] = first;
      replacement[1] = last;
      int i = 2;
      for (final String each : addresses) replacement[i++] = each;
      retired.addLast(replacement);
    }

    while (retired.size() > RETIRED_DISPLAY_NAME_LIMIT) retired.removeFirst();
  }

  public void removeAllUnencryptedMessages(Contact contact) {
    final ArrayList<StorageMessage> messages = getUnencryptedMessages();
    if (messages == null) return;
    List<StorageMessage> operatedList = new ArrayList<>();
    messages.stream()
        .filter(m -> m.belongsTo(contact.getSignalProtocolAddressName(), contact.getDeviceId()))
        .forEach(operatedList::add);
    messages.removeAll(operatedList);
  }

  /**
   * The single contact bearing this address name, or null if none or more than one does.
   *
   * <p>Used ONCE, by the one-time key migration, and deliberately nowhere else. Asked at read time
   * this is a question the messenger can change the answer to, by adding or removing a contact
   * between the write and the read; asked at the first load after upgrade it is a question about a
   * contact list the pre-upgrade binary wrote, which nothing since has been able to touch.
   */
  /**
   * Whether every key in this account is known to be a rendered full address.
   *
   * <p>Decides whether the schema marker is written when the account is saved, and it has to be a
   * property of THIS account rather than a constant, which cost two attempts to get right. Writing
   * the marker only after a migration meant a fresh install never wrote one, so its next load
   * treated its own modern retirements as pre-upgrade. Writing it unconditionally meant a load that
   * could not read the contact list - which the loader deliberately tolerates - sealed the marker
   * over a log it had not re-keyed, stranding every entry unreachable and unerasable.
   *
   * <p>True in exactly three cases: the store already said so, the migration just ran against a
   * contact list it could actually read, or the account is new and has no legacy data to hold.
   */
  private transient boolean keysAreRendered;

  public void setKeysAreRendered(final boolean rendered) {
    this.keysAreRendered = rendered;
  }

  public boolean keysAreRendered() {
    return keysAreRendered;
  }

  /**
   * Whether the one-time key migration actually ran on this load.
   *
   * <p>Narrower than {@code keysAreRendered}, and the difference is the whole point: that is true
   * in three cases, and only one of them means a chat log has just been re-keyed in memory and not
   * yet written. A fresh install has it true from construction, so a save that keys off it alone
   * refuses the first save of every new install whose log write happens to fail - which protects
   * nothing, because there is no legacy log to protect.
   */
  private transient boolean keysWereJustMigrated;

  public void markKeysWereJustMigrated() {
    this.keysWereJustMigrated = true;
  }

  public boolean keysWereJustMigrated() {
    return keysWereJustMigrated;
  }

  public Contact soleContactNamed(final String addressName) {
    if (contactList == null || addressName == null) return null;
    Contact found = null;
    for (final Contact other : contactList) {
      if (addressName.equals(other.getSignalProtocolAddressName())) {
        if (found != null) return null;
        found = other;
      }
    }
    return found;
  }

  public ArrayList<Contact> getContactList() {
    return contactList;
  }

  public void setContactList(ArrayList<Contact> contactList) {
    this.contactList = contactList;
  }

  public void addContactToContactList(Contact contact) throws DuplicateContactException {
    if (this.contactList.contains(contact))
      // Without the name. Every catch of this ends in printStackTrace, which on Android goes to
      // logcat - a sink neither logging guard inspects, because they match Log.* call sites. The
      // caller already knows which contact it passed; a reader of the log does not need to.
      throw new DuplicateContactException(
          "Error: that contact already exists in the contact list and will not be saved");
    this.contactList.add(contact);
  }

  public void updateContactInContactList(Contact contact) throws UnknownContactException {
    // Match on the full address, not the name alone. Two entries can share a name and differ by
    // device id - exactly what a legacy peer's folded device id produces - and matching by name
    // took the last one, so verifying one contact overwrote the other.
    for (int i = 0; i < contactList.size(); i++) {
      final Contact candidate = contactList.get(i);
      if (candidate.getSignalProtocolAddressName().equals(contact.getSignalProtocolAddressName())
          && candidate.getDeviceId() == contact.getDeviceId()) {
        this.contactList.set(i, contact);
        return;
      }
    }
    throw new UnknownContactException("Contact does not exist in contact list");
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Account account = (Account) o;
    return mDeviceId == account.mDeviceId && Objects.equals(mName, account.mName) && Objects.equals(mIdentityKeyPair, account.mIdentityKeyPair) && Objects.equals(mMetadataStore, account.mMetadataStore) && Objects.equals(mSignalProtocolStore, account.mSignalProtocolStore) && Objects.equals(mSignalProtocolAddress, account.mSignalProtocolAddress) && Objects.equals(getUnencryptedMessages(), account.getUnencryptedMessages()) && Objects.equals(contactList, account.contactList);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mName, mDeviceId, mIdentityKeyPair, mMetadataStore, mSignalProtocolStore, mSignalProtocolAddress, getUnencryptedMessages(), contactList);
  }

  @Override
  public String toString() {
    return "Account{" +
        "mName='" + mName + '\'' +
        ", mDeviceId=" + mDeviceId +
        ", mIdentityKeyPair=" + mIdentityKeyPair +
        ", mMetadataStore=" + mMetadataStore +
        ", mSignalProtocolStore=" + mSignalProtocolStore +
        ", mSignalProtocolAddress=" + mSignalProtocolAddress +
        ", mUnencryptedMessages=" + (messageLogIsLoaded() ? mUnencryptedMessages : "<deferred>") +
        ", contactList=" + contactList +
        '}';
  }
}

