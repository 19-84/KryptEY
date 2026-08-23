package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import com.amnesica.kryptey.inputmethod.signalprotocol.TrustedKey;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.IdentityKeyStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class IdentityKeyStoreImpl implements IdentityKeyStore {

  @JsonProperty
  private List<TrustedKey> trustedKeys = new ArrayList<>();

  /**
   * Addresses whose identity key changed and whose change the user has not yet accepted. Persisted,
   * so a warning is not lost when the keyboard process restarts.
   */
  @JsonProperty
  private List<String> changedIdentities = new ArrayList<>();

  @JsonProperty
  @JsonSerialize(using = JsonUtil.IdentityKeyPairSerializer.class)
  @JsonDeserialize(using = JsonUtil.IdentityKeyPairDeserializer.class)
  private IdentityKeyPair identityKeyPair;

  @JsonProperty
  private int localRegistrationId;

  public IdentityKeyStoreImpl(IdentityKeyPair identityKeyPair, int localRegistrationId) {
    this.identityKeyPair = identityKeyPair;
    this.localRegistrationId = localRegistrationId;
  }

  public IdentityKeyStoreImpl() {
  }

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return identityKeyPair;
  }

  @Override
  public int getLocalRegistrationId() {
    return localRegistrationId;
  }

  /**
   * Records the identity for {@code address}, replacing any previous entry.
   *
   * <p>This used to <em>append</em> while the lookup returned the first match, so a changed key was
   * stored and then permanently ignored: the store kept reporting the old identity,
   * {@link #isTrustedIdentity} refused the new one forever, and the contact became unreachable with
   * no recovery — deleting them did not help, because contact removal never touched this list.
   *
   * <p>Replacing is not the same as trusting. A displaced key is remembered in
   * {@code changedIdentities} and {@link #isTrustedIdentity} keeps refusing to <em>send</em> to it
   * until {@link #acceptIdentityChange} is called, so an identity swap cannot be silently accepted.
   */
  @Override
  public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    final IdentityKey existing = getIdentityKeyFromEntryInList(address);

    if (identityKey.equals(existing)) return IdentityChange.NEW_OR_UNCHANGED;

    trustedKeys.removeIf(k -> k != null && k.getSignalProtocolAddress().equals(address));
    trustedKeys.add(new TrustedKey(address, identityKey));

    if (existing == null) return IdentityChange.NEW_OR_UNCHANGED;

    // A previously known identity was displaced. Remember that until the user accepts it.
    final String key = addressKey(address);
    if (!changedIdentities.contains(key)) changedIdentities.add(key);
    return IdentityChange.REPLACED_EXISTING;
  }

  /**
   * Trust-on-first-use, but not trust-on-change.
   *
   * <p>A first sighting is trusted. An unchanged key is trusted. A key that displaced a previously
   * known one is refused for {@code SENDING} until the user accepts it — otherwise a man in the
   * middle who substitutes their own identity is accepted silently, which is the single worst
   * outcome this protocol can produce. {@code RECEIVING} is still allowed so the message can be
   * decrypted and shown alongside a warning rather than vanishing.
   */
  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    final IdentityKey trusted = getIdentityKeyFromEntryInList(address);
    if (trusted == null) return true;
    if (!trusted.equals(identityKey)) return false;
    if (direction == Direction.SENDING && hasUnacceptedIdentityChange(address)) return false;
    return true;
  }

  /** True when this address's identity changed and the user has not acknowledged it. */
  public boolean hasUnacceptedIdentityChange(final SignalProtocolAddress address) {
    return changedIdentities.contains(addressKey(address));
  }

  /** Records that the user has seen and accepted an identity change for {@code address}. */
  public void acceptIdentityChange(final SignalProtocolAddress address) {
    changedIdentities.remove(addressKey(address));
  }

  /**
   * Forgets everything known about {@code address}.
   *
   * <p>Contact removal must reach this, or the app's own advice — delete the contact and ask for a
   * new invite — cannot work: the stale identity would survive and keep refusing the new one.
   */
  public void removeIdentity(final SignalProtocolAddress address) {
    trustedKeys.removeIf(k -> k != null && k.getSignalProtocolAddress().equals(address));
    changedIdentities.remove(addressKey(address));
  }

  private static String addressKey(final SignalProtocolAddress address) {
    return address.getName() + "." + address.getDeviceId();
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return getIdentityKeyFromEntryInList(address);
  }

  private IdentityKey getIdentityKeyFromEntryInList(SignalProtocolAddress address) {
    for (TrustedKey trustedKey : trustedKeys) {
      if (trustedKey != null && trustedKey.getSignalProtocolAddress().equals(address))
        return trustedKey.getIdentityKey();
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    IdentityKeyStoreImpl that = (IdentityKeyStoreImpl) o;
    return localRegistrationId == that.localRegistrationId && Objects.equals(trustedKeys, that.trustedKeys) && Objects.equals(identityKeyPair, that.identityKeyPair);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trustedKeys, identityKeyPair, localRegistrationId);
  }

  public List<TrustedKey> getTrustedKeys() {
    return trustedKeys;
  }
}
