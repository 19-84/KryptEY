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
   * cannot be read out to a peer, because it is keyed with a secret only this install holds. It
   * renders whenever the account has more than one contact — deliberately not gated on the name
   * comparison, so a name that dodges the folding still produces two visibly different rows.
   */
  public String getAddressTag() {
    // Derived by SignalProtocolMain, which holds the per-install secret this is keyed with. Kept as
    // a method on Contact because that is where every render site reaches for it.
    return com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain.displayTagFor(this);
  }


}
