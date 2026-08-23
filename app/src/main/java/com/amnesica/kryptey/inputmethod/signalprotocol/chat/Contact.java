package com.amnesica.kryptey.inputmethod.signalprotocol.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.Objects;

public class Contact {


  private String firstName;
  private String lastName;
  private int deviceId;
  private String signalProtocolAddressName;
  private boolean verified;


  @JsonProperty()
  private SignalProtocolAddress signalProtocolAddress;

  @JsonCreator
  public Contact(@JsonProperty("firstName") String firstName,
                 @JsonProperty("lastName") String lastName,
                 @JsonProperty("signalProtocolAddressName") String signalProtocolAddressName,
                 @JsonProperty("deviceId") int deviceId,
                 @JsonProperty("verified") boolean verified) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.signalProtocolAddressName = signalProtocolAddressName;
    this.deviceId = deviceId;
    this.signalProtocolAddress = com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.of(signalProtocolAddressName, deviceId);
    this.verified = verified;
  }


  public Contact() {
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
    return deviceId == contact.deviceId && Objects.equals(firstName, contact.firstName) && Objects.equals(lastName, contact.lastName) && Objects.equals(signalProtocolAddressName, contact.signalProtocolAddressName) && Objects.equals(signalProtocolAddress, contact.signalProtocolAddress);
  }

  @Override
  public int hashCode() {
    return Objects.hash(firstName, lastName, deviceId, signalProtocolAddressName, signalProtocolAddress);
  }

  @Override
  public String toString() {
    return firstName + '.' + lastName + '.' + deviceId + '.' + signalProtocolAddressName + '.' + signalProtocolAddress + '.' + verified;
  }

  /**
   * A short, stable tag for this contact's address, for telling apart two contacts a user has given
   * the same display name.
   *
   * <p>The pin mechanism protects one address. It does nothing about a second contact at a
   * <em>different</em> address carrying the same name, and the contact list renders names only - so
   * two rows both reading "Alice" are indistinguishable, and a messenger can sidestep every trust
   * control by getting the user to add a second Alice rather than by substituting a key for the
   * first. This is what makes those rows distinguishable.
   *
   * <p>Derived from the address, not the key: it must stay the same when a key is legitimately
   * rejected and re-pinned, or it would read as a change when nothing about the identity moved.
   */
  public String getAddressTag() {
    final String name = getSignalProtocolAddressName();
    final String head = name == null ? "?" : name.replace("-", "");
    return "#" + (head.length() >= 6 ? head.substring(0, 6) : head) + "." + getDeviceId();
  }
}
