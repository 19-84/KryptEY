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

  public ArrayList<StorageMessage> getUnencryptedMessages() {
    return mUnencryptedMessages;
  }

  public void setUnencryptedMessages(ArrayList<StorageMessage> unencryptedMessages) {
    this.mUnencryptedMessages = unencryptedMessages;
  }

  public void addUnencryptedMessage(Contact contact, StorageMessage storageMessage) throws RuntimeException {
    if (mUnencryptedMessages == null)
      throw new RuntimeException("Error: UnencryptedMessage could not be saved. mUnencryptedMessages is null");
    mUnencryptedMessages.add(storageMessage);
  }

  /**
   * Remove one message this account sent, identified by recipient and exact timestamp.
   *
   * <p>Exists so a send that was refused after the plaintext had already been recorded can be
   * undone. Matching on the timestamp rather than removing "the last" entry matters: a message from
   * the contact can arrive between the two, and removing that instead would delete something the
   * user actually received.
   */
  public boolean removeUnencryptedMessage(final String recipientUUID, final Instant timestamp) {
    if (mUnencryptedMessages == null || recipientUUID == null || timestamp == null) return false;
    for (int i = mUnencryptedMessages.size() - 1; i >= 0; i--) {
      final StorageMessage candidate = mUnencryptedMessages.get(i);
      if (recipientUUID.equals(candidate.getRecipientUUID())
          && timestamp.equals(candidate.getTimestamp())) {
        mUnencryptedMessages.remove(i);
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
   * <p>De-duplicated, so the bound counts DISTINCT names. Without that, a hundred delete-and-re-add
   * cycles of one unrelated contact evict the name an attacker cares about - user work rather than
   * attacker work, but it is the loop the app's advice creates.
   */
  public void retireDisplayName(final String firstName, final String lastName,
                                final String addressName) {
    if (firstName == null && lastName == null) return;
    final String first = firstName == null ? "" : firstName;
    final String last = lastName == null ? "" : lastName;
    final String address = addressName == null ? "" : addressName;

    final LinkedList<String[]> retired = getRetiredDisplayNames();
    retired.removeIf(entry -> entry.length > 2
        && first.equals(entry[0]) && last.equals(entry[1]) && address.equals(entry[2]));
    retired.addLast(new String[] {first, last, address});
    while (retired.size() > RETIRED_DISPLAY_NAME_LIMIT) retired.removeFirst();
  }

  public void removeAllUnencryptedMessages(Contact contact) {
    final boolean unambiguous = hasExactlyOneContactNamed(contact.getSignalProtocolAddressName());
    List<StorageMessage> operatedList = new ArrayList<>();
    mUnencryptedMessages.stream()
        .filter(m -> m.belongsTo(contact.getSignalProtocolAddressName(), contact.getDeviceId(),
            unambiguous))
        .forEach(operatedList::add);
    mUnencryptedMessages.removeAll(operatedList);
  }

  /**
   * Whether one address name identifies a single contact.
   *
   * <p>Gates the legacy chat-log key. Two contacts sharing an address name is the impostor case, and
   * in it no legacy message can be attributed to either without guessing.
   */
  public boolean hasExactlyOneContactNamed(final String addressName) {
    if (contactList == null || addressName == null) return false;
    int matches = 0;
    for (final Contact other : contactList) {
      if (addressName.equals(other.getSignalProtocolAddressName())) matches++;
    }
    return matches == 1;
  }

  public ArrayList<Contact> getContactList() {
    return contactList;
  }

  public void setContactList(ArrayList<Contact> contactList) {
    this.contactList = contactList;
  }

  public void addContactToContactList(Contact contact) throws DuplicateContactException {
    if (this.contactList.contains(contact))
      throw new DuplicateContactException("Error: Contact " + contact.getFirstName() + " " + contact.getLastName() + " already exists in contact list and will not be saved!");
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
    return mDeviceId == account.mDeviceId && Objects.equals(mName, account.mName) && Objects.equals(mIdentityKeyPair, account.mIdentityKeyPair) && Objects.equals(mMetadataStore, account.mMetadataStore) && Objects.equals(mSignalProtocolStore, account.mSignalProtocolStore) && Objects.equals(mSignalProtocolAddress, account.mSignalProtocolAddress) && Objects.equals(mUnencryptedMessages, account.mUnencryptedMessages) && Objects.equals(contactList, account.contactList);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mName, mDeviceId, mIdentityKeyPair, mMetadataStore, mSignalProtocolStore, mSignalProtocolAddress, mUnencryptedMessages, contactList);
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
        ", mUnencryptedMessages=" + mUnencryptedMessages +
        ", contactList=" + contactList +
        '}';
  }
}

