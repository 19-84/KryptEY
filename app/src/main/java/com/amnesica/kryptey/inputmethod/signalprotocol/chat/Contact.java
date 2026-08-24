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
    this.signalProtocolAddress = com.amnesica.kryptey.inputmethod.signalprotocol.util
        .ProtocolAddresses.of(signalProtocolAddressName, deviceId);
    // Store the FOLDED id, not the raw argument. ProtocolAddresses.of() folds a legacy device id
    // into libsignal's [1,127]; keeping the raw value here left this field and the assembled
    // address disagreeing for every 0.1.5 peer, and different parts of the app key off different
    // ones - the contact list matches on this scalar, the identity and session stores on the
    // address. The add path was fixed to pass an already-folded id, but deserialising a stored
    // contact comes straight through here with the raw value, so every upgrading user hit it.
    this.deviceId = this.signalProtocolAddress.getDeviceId();
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

  /**
   * Keeps the three views of one address consistent.
   *
   * <p>A contact carries the address three ways — {@code signalProtocolAddressName},
   * {@code deviceId}, and the assembled {@code signalProtocolAddress} — and different parts of the
   * app key off different ones: the contact list matches on the first two, the identity store and
   * session store on the third. Letting a setter move one without the others makes those disagree,
   * and that exact desync has already shipped once, when a raw peer-supplied device id was stored
   * while the address held the folded value. No production code calls these setters today (they
   * exist for Jackson), so this is here to stop the next caller reintroducing it rather than to fix
   * a live bug.
   */
  public void setDeviceId(int deviceId) {
    this.deviceId = deviceId;
    resyncAddress();
  }

  private void resyncAddress() {
    if (signalProtocolAddressName == null) return;
    this.signalProtocolAddress = com.amnesica.kryptey.inputmethod.signalprotocol.util
        .ProtocolAddresses.of(signalProtocolAddressName, deviceId);
    // of() folds the device id into libsignal's range; keep the scalar in step with it, or the two
    // still disagree for any legacy value.
    this.deviceId = this.signalProtocolAddress.getDeviceId();
  }

  public String getSignalProtocolAddressName() {
    return signalProtocolAddressName;
  }

  public void setSignalProtocolAddressName(String signalProtocolAddressName) {
    this.signalProtocolAddressName = signalProtocolAddressName;
    resyncAddress();
  }

  public SignalProtocolAddress getSignalProtocolAddress() {
    return signalProtocolAddress;
  }

  public void setSignalProtocolAddress(SignalProtocolAddress signalProtocolAddress) {
    this.signalProtocolAddress = signalProtocolAddress;
    if (signalProtocolAddress != null) {
      this.signalProtocolAddressName = signalProtocolAddress.getName();
      this.deviceId = signalProtocolAddress.getDeviceId();
    }
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
   *
   * <p>Note what this is not. It disambiguates two rows on one screen; it is not a safety number and
   * cannot be read out to a peer, because it is computed from the address the messenger delivered
   * rather than from anything the peer independently knows. And it only renders when the app has
   * decided two contacts share a display name — so it cannot be the answer to a name comparison
   * that failed, which an earlier version of this comment wrongly claimed it was.
   */
  public String getAddressTag() {
    final String name = getSignalProtocolAddressName();
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

    // 96 bits, not 40.
    //
    // The first version emitted 40, which is not a security margin against this adversary: the
    // hashed input is the address name, the peer chooses it freely, and the messenger knows the
    // address of the contact being impersonated. Grinding a full 40-bit collision is minutes on a
    // GPU and a couple of core-hours for the 32 bits a user is least likely to read carefully - so
    // an attacker could present a second contact whose tag matched the genuine one exactly, and the
    // tag then actively made things worse by looking like a check that had passed.
    //
    // 96 bits puts a targeted collision out of reach. It is longer to read, which is the right
    // trade: this string exists to be compared against another row on the same screen, so it has to
    // survive an adversary aiming at it, and it is not something anyone reads aloud - unlike a
    // safety number, it is derived from the address the messenger delivered, not from anything the
    // peer independently knows.
    final StringBuilder tag = new StringBuilder("#");
    for (int i = 0; i < 12; i++) {
      if (i > 0 && i % 3 == 0) tag.append('-');
      tag.append(String.format("%02x", digest[i]));
    }
    return tag.toString();
  }

}
