package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.Objects;

public class Contact {

  /**
   * How this contact's key reached us, which is the whole basis for trusting it.
   *
   * <p>{@link #IN_BAND} means the key bundle travelled through the same messenger the app is
   * defending against — a hostile messenger can substitute keys at first contact, and trust-on-first
   * -use cannot detect that, because there is no earlier key to have changed.
   *
   * <p>{@link #OUT_OF_BAND} means the user obtained the bundle through a channel they chose and
   * trust. That is what actually closes the first-contact gap, and it is worth recording, because
   * afterwards the two are indistinguishable from the stored key alone.
   */
  public enum KeyOrigin {
    IN_BAND,
    OUT_OF_BAND
  }

  private String firstName;
  private String lastName;
  private int deviceId;
  private String signalProtocolAddressName;
  private boolean verified;

  /**
   * Defaults to {@link KeyOrigin#IN_BAND}. A contact stored before this field existed came through
   * the messenger by definition, and absent-means-in-band is also the safe direction to guess: it
   * under-claims trust rather than over-claiming it.
   */
  private KeyOrigin keyOrigin = KeyOrigin.IN_BAND;

  @JsonProperty()
  private SignalProtocolAddress signalProtocolAddress;

  @JsonCreator
  public Contact(@JsonProperty("firstName") String firstName,
                 @JsonProperty("lastName") String lastName,
                 @JsonProperty("signalProtocolAddressName") String signalProtocolAddressName,
                 @JsonProperty("deviceId") int deviceId,
                 @JsonProperty("verified") boolean verified,
                 @JsonProperty("keyOrigin") KeyOrigin keyOrigin) {
    this.keyOrigin = keyOrigin == null ? KeyOrigin.IN_BAND : keyOrigin;
    this.firstName = firstName;
    this.lastName = lastName;
    this.signalProtocolAddressName = signalProtocolAddressName;
    this.deviceId = deviceId;
    this.signalProtocolAddress = com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.of(signalProtocolAddressName, deviceId);
    this.verified = verified;
  }

  /** Convenience for the common in-band case and for existing callers. */
  public Contact(String firstName, String lastName, String signalProtocolAddressName,
                 int deviceId, boolean verified) {
    this(firstName, lastName, signalProtocolAddressName, deviceId, verified, KeyOrigin.IN_BAND);
  }

  public Contact() {
  }

  public KeyOrigin getKeyOrigin() {
    return keyOrigin == null ? KeyOrigin.IN_BAND : keyOrigin;
  }

  public void setKeyOrigin(KeyOrigin keyOrigin) {
    this.keyOrigin = keyOrigin == null ? KeyOrigin.IN_BAND : keyOrigin;
  }

  /**
   * Whether this contact's key can be trusted without a further safety-number comparison.
   *
   * <p>True when the user explicitly verified it, or when the bundle never touched the messenger in
   * the first place. Those are two routes to the same assurance, and treating an out-of-band
   * exchange as still-unverified would ask the user to do the same work twice.
   */
  public boolean isTrustworthy() {
    return verified || getKeyOrigin() == KeyOrigin.OUT_OF_BAND;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public int getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(int deviceId) {
    this.deviceId = deviceId;
  }

  public String getSignalProtocolAddressName() {
    return signalProtocolAddressName;
  }

  public void setSignalProtocolAddressName(String signalProtocolAddressName) {
    this.signalProtocolAddressName = signalProtocolAddressName;
  }

  public SignalProtocolAddress getSignalProtocolAddress() {
    return signalProtocolAddress;
  }

  public void setSignalProtocolAddress(SignalProtocolAddress signalProtocolAddress) {
    this.signalProtocolAddress = signalProtocolAddress;
  }

  public boolean isVerified() {
    return verified;
  }

  public void setVerified(boolean verified) {
    this.verified = verified;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Contact contact = (Contact) o;
    return deviceId == contact.deviceId && verified == contact.verified && getKeyOrigin() == contact.getKeyOrigin() && Objects.equals(firstName, contact.firstName) && Objects.equals(lastName, contact.lastName) && Objects.equals(signalProtocolAddressName, contact.signalProtocolAddressName) && Objects.equals(signalProtocolAddress, contact.signalProtocolAddress);
  }

  @Override
  public int hashCode() {
    return Objects.hash(firstName, lastName, deviceId, signalProtocolAddressName, verified, getKeyOrigin(), signalProtocolAddress);
  }

  @Override
  public String toString() {
    return firstName + '.' + lastName + '.' + deviceId + '.' + signalProtocolAddressName + '.' + signalProtocolAddress + '.' + verified;
  }
}
