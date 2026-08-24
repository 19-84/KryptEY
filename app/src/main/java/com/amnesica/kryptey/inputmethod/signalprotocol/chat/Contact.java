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
    // Hash the WHOLE address, do not take a prefix of it. The first version returned the first six
    // characters of the address name plus the device id - but the address name is chosen by the
    // peer and the messenger knows every contact's, so an impostor only had to pick a name agreeing
    // with the victim's in its first six characters and reuse the device id. No grinding, no cost,
    // and the tag rendered identically for both contacts. A tag that an adversary can make collide
    // is worse than none, because it looks like a check.
    final byte[] digest;
    try {
      final java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
      sha.update((name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      sha.update((byte) 0);  // separator, so name+deviceId cannot be repartitioned
      sha.update(String.valueOf(getDeviceId())
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest = sha.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }

    // 40 bits, grouped for reading aloud. Not a safety number - it identifies the address, not the
    // key - but enough that producing a collision is not something a messenger does incidentally.
    final StringBuilder tag = new StringBuilder("#");
    for (int i = 0; i < 5; i++) {
      if (i == 2 || i == 4) tag.append('-');
      tag.append(String.format("%02x", digest[i]));
    }
    return tag.toString();
  }
}
