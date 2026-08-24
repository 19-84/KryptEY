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
import java.util.List;
import java.util.Objects;

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
    // Older stores predate this field. Minting one lazily keeps tags working after an upgrade; it
    // changes every existing tag once, which is harmless - they are only ever compared with each
    // other, never carried between devices or read out to a peer.
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

  public void removeAllUnencryptedMessages(Contact contact) {
    List<StorageMessage> operatedList = new ArrayList<>();
    mUnencryptedMessages.stream()
        .filter(m -> m.getContactUUID().equals(contact.getSignalProtocolAddressName()))
        .forEach(operatedList::add);
    mUnencryptedMessages.removeAll(operatedList);
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

