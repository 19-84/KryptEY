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
   * Address -> the base64 identity key that was offered for it and refused. Persisted, so the
   * warning is not lost when the keyboard process restarts.
   */
  @JsonProperty
  private java.util.Map<String, String> pendingIdentities = new java.util.HashMap<>();

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

    // Reached only when the caller already decided to trust the new key (libsignal refuses to get
    // here otherwise, since isTrustedIdentity runs first). The pending record, if any, is now
    // satisfied.
    pendingIdentities.remove(addressKey(address));
    return IdentityChange.REPLACED_EXISTING;
  }

  /**
   * Trust-on-first-use, and never trust-on-change.
   *
   * <p>A first sighting is trusted; an unchanged key is trusted; a key that displaces a pinned one
   * is refused in <em>both</em> directions, so libsignal aborts and nothing is sent to or accepted
   * from the new key.
   *
   * <p>An earlier version tried to allow RECEIVING so a message could be shown with a warning. That
   * did not work and could not: libsignal calls this method <em>before</em> {@code saveIdentity},
   * so refusing here means {@code saveIdentity} never runs — which meant the pending-change flag
   * was never set, {@code REPLACED_EXISTING} was unreachable, and the whole warning mechanism was
   * dead code. The flag is now recorded from the {@code UntrustedIdentityException} path instead,
   * via {@link #recordIdentityChange}, which is the only place it can be reached.
   */
  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    final IdentityKey trusted = getIdentityKeyFromEntryInList(address);
    if (trusted == null) return true;
    return trusted.equals(identityKey);
  }

  /**
   * Records that {@code offered} was presented for an address that is already pinned to a different
   * key, without trusting it.
   *
   * <p>Called when libsignal raises {@code UntrustedIdentityException}. The offered key is kept so
   * the user can be shown what changed and can accept it deliberately; it is deliberately NOT
   * written into {@code trustedKeys}, so until the user acts the old pin stands and everything
   * fails closed.
   */
  public void recordIdentityChange(final SignalProtocolAddress address, final IdentityKey offered) {
    if (address == null || offered == null) return;
    final IdentityKey pinned = getIdentityKeyFromEntryInList(address);
    if (pinned == null || pinned.equals(offered)) return; // nothing displaced
    pendingIdentities.put(addressKey(address),
        com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
            .encodeBytesWithoutPadding(offered.serialize()));
  }

  /** True when a different identity key has been offered for this address and not yet accepted. */
  public boolean hasUnacceptedIdentityChange(final SignalProtocolAddress address) {
    return address != null && pendingIdentities.containsKey(addressKey(address));
  }

  /** The key that was offered for this address, or null if no change is pending. */
  public IdentityKey getPendingIdentity(final SignalProtocolAddress address) {
    final String encoded = address == null ? null : pendingIdentities.get(addressKey(address));
    if (encoded == null) return null;
    try {
      return new IdentityKey(com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
          .decodeWithoutPadding(encoded), 0);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Accepts the pending identity for {@code address}, replacing the pin.
   *
   * <p>Takes the key the user was actually shown and refuses if it no longer matches what is
   * pending, so a change that arrives between display and confirmation cannot be accepted by
   * mistake.
   *
   * @return true if the pin was replaced.
   */
  public boolean acceptIdentityChange(final SignalProtocolAddress address, final IdentityKey shown) {
    final IdentityKey pending = getPendingIdentity(address);
    if (pending == null || shown == null || !pending.equals(shown)) return false;

    trustedKeys.removeIf(k -> k != null && k.getSignalProtocolAddress().equals(address));
    trustedKeys.add(new TrustedKey(address, pending));
    pendingIdentities.remove(addressKey(address));
    return true;
  }

  /**
   * Forgets everything known about {@code address}.
   *
   * <p>Contact removal must reach this, or the app's own advice — delete the contact and ask for a
   * new invite — cannot work: the stale identity would survive and keep refusing the new one.
   */
  public void removeIdentity(final SignalProtocolAddress address) {
    trustedKeys.removeIf(k -> k != null && k.getSignalProtocolAddress().equals(address));
    pendingIdentities.remove(addressKey(address));
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
