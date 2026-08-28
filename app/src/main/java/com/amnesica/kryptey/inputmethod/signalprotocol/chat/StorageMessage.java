package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

public class StorageMessage {
  // The rendered address of the contact this message belongs to, as ProtocolAddresses.key writes
  // it. Not a contact name and not a bare address name, whatever this comment used to say - see
  // belongsTo below, which is the only reader and compares the full rendered form.
  private String contactUUID;
  private String senderUUID;
  private String recipientUUID;
  private final Instant timestamp;
  private final String unencryptedMessage;

  /**
   * Whether the legacy migration has already asked who this entry belongs to.
   *
   * <p>The migration must run once per entry, and "once" could not be decided from anything that
   * existed. Not from the key's shape: a 0.1.5 store was never held to the wire's name rules, so a
   * messenger-supplied address name can be byte-for-byte a rendered key, and treating that as
   * "already done" is the smuggling hole {@code LegacySeparatorSmugglingTest} exists to keep shut.
   * Not from the schema marker either: the marker travels in the account batch while the log is
   * committed first, so the state "log re-keyed, marker missing" is one the write order
   * deliberately produces — and a second pass then re-evaluates a RENDERED key against the current
   * contact list. Measured: an entry correctly attributed to Bob on the first pass was re-filed
   * into an attacker's row on the second, and stopped belonging to Bob at all.
   *
   * <p>So the answer is recorded on the entry, in the same file and the same commit as the re-keying
   * it describes. If the log write lands, the flags land with it; if it does not, neither does the
   * re-keying. It is set even when the entry could not be attributed, because "I could not tell" is
   * an answer too, and asking again later means asking against a contact list the messenger has had
   * time to arrange.
   *
   * <p>Absent in a stored log written before this field existed, where it reads false — correct,
   * since those entries have not been asked.
   */
  private boolean legacyKeyResolved;

  @JsonCreator
  public StorageMessage(@JsonProperty("contactUUID") String contactUUID,
                        @JsonProperty("senderUUID") String senderUUID,
                        @JsonProperty("recipientUUID") String recipientUUID,
                        @JsonProperty("timestamp") Instant timestamp,
                        @JsonProperty("unencryptedMessage") String unencryptedMessage,
                        @JsonProperty("legacyKeyResolved") boolean legacyKeyResolved) {
    this.contactUUID = contactUUID;
    this.senderUUID = senderUUID;
    this.recipientUUID = recipientUUID;
    this.timestamp = timestamp;
    this.unencryptedMessage = unencryptedMessage;
    this.legacyKeyResolved = legacyKeyResolved;
  }

  /** The five-argument shape every caller but Jackson uses; a new message needs no migration. */
  public StorageMessage(final String contactUUID, final String senderUUID,
                        final String recipientUUID, final Instant timestamp,
                        final String unencryptedMessage) {
    this(contactUUID, senderUUID, recipientUUID, timestamp, unencryptedMessage, true);
  }

  public boolean isLegacyKeyResolved() {
    return legacyKeyResolved;
  }

  public void setLegacyKeyResolved(final boolean legacyKeyResolved) {
    this.legacyKeyResolved = legacyKeyResolved;
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
