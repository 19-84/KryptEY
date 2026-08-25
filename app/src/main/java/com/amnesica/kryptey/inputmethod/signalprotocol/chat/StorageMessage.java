package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

public class StorageMessage {
  private String contactUUID; // contact name or address name (uuid)
  private String senderUUID;
  private String recipientUUID;
  private final Instant timestamp;
  private final String unencryptedMessage;

  @JsonCreator
  public StorageMessage(@JsonProperty("contactUUID") String contactUUID,
                        @JsonProperty("senderUUID") String senderUUID,
                        @JsonProperty("recipientUUID") String recipientUUID,
                        @JsonProperty("timestamp") Instant timestamp,
                        @JsonProperty("unencryptedMessage") String unencryptedMessage) {
    this.contactUUID = contactUUID;
    this.senderUUID = senderUUID;
    this.recipientUUID = recipientUUID;
    this.timestamp = timestamp;
    this.unencryptedMessage = unencryptedMessage;
  }

  public String getSenderUUID() {
    return senderUUID;
  }

  public void setSenderUUID(String senderUUID) {
    this.senderUUID = senderUUID;
  }

  public String getRecipientUUID() {
    return recipientUUID;
  }

  public void setRecipientUUID(String recipientUUID) {
    this.recipientUUID = recipientUUID;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public String getUnencryptedMessage() {
    return unencryptedMessage;
  }

  /**
   * The key a chat log is filed under: the FULL address, not the address name.
   *
   * <p>The address name alone is not an identity. It is public - it travels in every envelope the
   * messenger relays - and the device id beside it is one byte the sender chooses. Filing messages
   * by the name alone meant two contacts differing only in device id shared one thread, so an
   * impostor's words rendered inside the genuine contact's conversation, under their name, their
   * tag and their badge. That is the substitution the pin refuses, achieved without touching a key.
   * Deleting the impostor - the action the duplicate-name warning steers the user towards - took
   * the genuine contact's history with it.
   */
  public static String chatLogKey(final String addressName, final int deviceId) {
    return com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.key(
        com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.of(
            addressName, deviceId));
  }

  /**
   * Whether this message belongs to the given contact's log.
   *
   * <p>One comparison, against the full address. There used to be a second arm matching a bare
   * address name, for messages written before the device id joined the key, gated on that name
   * identifying exactly one contact. It produced two HIGH findings in consecutive review rounds and
   * could not be fixed where it lived: the gate asks a question about the contact list at the moment
   * it is asked, and the messenger moves the contact list. Legacy entries are now re-keyed once, at
   * the first load after the upgrade, by {@code StorageHelper.migrateLegacyKeys} - which is the only
   * moment that question has an answer the messenger has not had a chance to influence.
   */
  public boolean belongsTo(final String addressName, final int deviceId) {
    if (contactUUID == null) return false;
    return contactUUID.equals(chatLogKey(addressName, deviceId));
  }

  public String getContactUUID() {
    return contactUUID;
  }

  public void setContactUUID(String contactUUID) {
    this.contactUUID = contactUUID;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StorageMessage message = (StorageMessage) o;
    return Objects.equals(contactUUID, message.contactUUID) && Objects.equals(senderUUID, message.senderUUID) && Objects.equals(recipientUUID, message.recipientUUID) && Objects.equals(timestamp, message.timestamp) && Objects.equals(unencryptedMessage, message.unencryptedMessage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(contactUUID, senderUUID, recipientUUID, timestamp, unencryptedMessage);
  }

  @Override
  public String toString() {
    return "StorageMessage{" +
        "contactUUID='" + contactUUID + '\'' +
        ", senderUUID='" + senderUUID + '\'' +
        ", recipientUUID='" + recipientUUID + '\'' +
        ", timestamp=" + timestamp +
        ", unencryptedMessage='" + unencryptedMessage + '\'' +
        '}';
  }
}
