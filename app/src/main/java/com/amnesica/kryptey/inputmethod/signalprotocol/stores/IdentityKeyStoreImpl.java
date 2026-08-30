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

  /**
   * Addresses whose pinned key was obtained out of band.
   *
   * <p>Provenance lives here, next to the key it describes, rather than on the {@code Contact} row.
   * On the row it was settable by anyone holding a Contact object — including from a plain
   * constructor — which made the strongest trust signal in the app assertable rather than earned.
   * Here it can only be set by the import path that actually observed the out-of-band transfer.
   */
  @JsonProperty
  private java.util.List<String> outOfBandAddresses = new java.util.ArrayList<>();

  /**
   * Addresses whose key the user un-pinned after a safety-number mismatch.
   *
   * <p>Survives {@link #removeIdentity}, deliberately — it is the one thing that must not be
   * forgotten when the key is. Without it a reject leaves the address indistinguishable from one
   * never seen, so the very next bundle to arrive is a silent trust-on-first-use, including the
   * forged bundle that provoked the rejection in the first place. That is the same substitution
   * window contact deletion was reverted for, entered through a different door.
   *
   * <p>Cleared only by a fresh comparison (the user confirming a number), never by anything an
   * attacker can trigger.
   */
  @JsonProperty
  private java.util.List<String> rejectedAddresses = new java.util.ArrayList<>();

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
   * {@code pendingIdentities} and {@link #isTrustedIdentity} keeps refusing to <em>send</em> to it
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

  /** Records that this address's pinned key arrived through a channel outside the messenger. */
  public void markKeyOutOfBand(final SignalProtocolAddress address) {
    if (address == null) return;
    final String key = addressKey(address);
    if (!outOfBandAddresses.contains(key)) outOfBandAddresses.add(key);
  }

  /** True when the pinned key for this address was obtained out of band. */
  public boolean isKeyOutOfBand(final SignalProtocolAddress address) {
    return address != null && outOfBandAddresses.contains(addressKey(address));
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
   * Discards a pending identity change and keeps the pinned key.
   *
   * <p>This is the safe exit from a pending change, and the only one a user should normally need.
   *
   * <p>Entry into the pending state is controlled by an attacker: one forged bundle to an address
   * the messenger sees in every envelope records a change and drops the contact's verified badge.
   * Without an exit that state is permanent, and a permanent remotely-triggerable DoS on the badge
   * teaches the user to disregard it long before a real substitution arrives.
   *
   * <p>Deleting the contact was tried as that exit and was worse: it surrendered the pin, which
   * turns every attacker-inducible decryption failure - a replay, a flipped bit - into a
   * key-substitution window, because the app's generic failure advice tells the user to delete and
   * re-invite. Dismissing creates no such window. The pinned key is untouched, so
   * {@code createFingerprint} still shows the number the user compared before, and re-comparing it
   * confirms a key that never moved.
   *
   * <p>Deliberately does not restore {@code Contact.verified} by itself - the user re-runs the
   * comparison, which is cheap because the number is unchanged, and that keeps "verified" meaning
   * "somebody compared this" rather than "some code decided it was fine".
   *
   * @return true if a change was pending and has been discarded.
   */
  public boolean dismissIdentityChange(final SignalProtocolAddress address) {
    if (address == null) return false;
    return pendingIdentities.remove(addressKey(address)) != null;
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
    // The new key did not come through the original trusted channel, so the provenance does not
    // carry over to it.
    outOfBandAddresses.remove(addressKey(address));
    return true;
  }

  /**
   * Records that the user un-pinned this address after comparing numbers and finding a mismatch.
   * Kept separately from the pin so it outlives {@link #removeIdentity}.
   *
   * <p>This javadoc was orphaned: a second one was inserted between it and this method, so it
   * documented nothing and {@code markKeyRejected} had none at all.
   */
  public void markKeyRejected(final SignalProtocolAddress address) {
    if (address == null) return;
    final String key = addressKey(address);
    if (!rejectedAddresses.contains(key)) rejectedAddresses.add(key);
  }

  /**
   * Whether a key at this address was previously rejected by the user.
   *
   * <p>A pin arriving at such an address is not a first sighting, whatever the empty store suggests
   * — the user has already told us that somebody was impersonating this contact here.
   */
  public boolean wasKeyRejected(final SignalProtocolAddress address) {
    return address != null && rejectedAddresses.contains(addressKey(address));
  }

  /** Cleared by a fresh comparison, which is the only thing that can retire the warning. */
  public boolean clearRejection(final SignalProtocolAddress address) {
    return address != null && rejectedAddresses.remove(addressKey(address));
  }

  /**
   * Forgets everything known about {@code address}.
   *
   * <p>Reached only from {@code SignalProtocolMain.rejectContactKey}, i.e. only when the user
   * compared safety numbers on the verify screen and reported that they did NOT match. Contact
   * removal deliberately does not reach it — a pin must outlive the contact row, or the app's
   * attacker-inducible failure advice becomes a route to substituting a key.
   *
   * <p>An earlier revision of this comment described contact removal as the caller and as "the sole
   * exit from a pending identity change". Both were true only of a design that was reverted; a
   * maintainer acting on that text would reintroduce the substitution window. The exit from a
   * pending change is {@code dismissIdentityChange}, which keeps the pin. This method is for the
   * opposite case: the pin itself is wrong.
   */
  public void removeIdentity(final SignalProtocolAddress address) {
    if (address == null) return;
    trustedKeys.removeIf(k -> k != null && k.getSignalProtocolAddress().equals(address));
    pendingIdentities.remove(addressKey(address));
    outOfBandAddresses.remove(addressKey(address));
  }

  /**
   * The map key for an address.
   *
   * <p>Null-tolerant on purpose. Every method here reaches this, and several used to do so without
   * a guard, so a null address became an NPE thrown out of the trust store — on this codebase that
   * means a keyboard that dies mid-message rather than a refusal.
   *
   * <p>The sentinel cannot collide with a real key, and this used to give the wrong reason: "address
   * names are UUIDs and the separator is a dot". Peer address names are not UUIDs. Only the LOCAL
   * account's name is one; a peer's is whatever the sender wrote, and the only validation on the
   * wire is {@code BinaryEnvelope}'s printable-ASCII check, which permits dots. That is the same
   * premise {@code ProtocolAddresses} spends a paragraph refuting, and it is worth correcting
   * rather than leaving because it is load-bearing-looking: a maintainer either trusts it and
   * widens name validation, or "harmonises" the separator - and these three collections are
   * persisted strings, so re-keying them with no migration makes {@code wasKeyRejected} answer
   * false at every address the user has ever rejected.
   *
   * <p>The reason that actually holds: every rendered key ends in {@code .&lt;digits&gt;} because a
   * device id is a dot-free integer in {@code [1,127]}, and the sentinel contains no dot at all. So
   * it is unreachable whatever a name contains - including a name planted in a pre-0.1.5 store the
   * deserializer never validated. Note {@code saveIdentity} does NOT null-check — libsignal always supplies an
   * address, so it is unreachable, but an earlier version of this sentence claimed every mutating
   * method refuses a null and that was not true.
   */
  private static String addressKey(final SignalProtocolAddress address) {
    if (address == null) return "\u0000-no-address";
    return address.getName() + "." + address.getDeviceId();
  }

  /**
   * Every address this store already pins the given key at, other than the one asked about.
   *
   * <p>The key dual of {@code existingContactAtSameAddress}, which its own comment calls "exact and
   * unspoofable: one address is one identity". The same is true the other way and had no reader:
   * one identity key belongs to one address, because {@code initializeProtocol} mints a fresh UUID,
   * a fresh device id and a fresh identity key pair together — so an honest peer cannot produce two
   * addresses sharing a key, not even by reinstalling, which mints a new key as well.
   *
   * <p>What can produce it is a relay. The envelope's sender name and device id are written outside
   * the bundle signature, so re-delivering a genuine, correctly signed invite under another address
   * yields bytes that verify perfectly and pin the peer's real key somewhere the peer never chose.
   * Measured: the second row shows the same safety number as the first, because the number is a
   * function of the two identity keys and the address was deliberately removed from it — so reading
   * the digits aloud has the real peer confirm a row the messenger created.
   *
   * <p>Answered from the pinned keys rather than from the contact list on purpose. The contact list
   * is the messenger's to influence, by arranging adds and deletes; the pins are what the app itself
   * recorded.
   */
  public java.util.List<SignalProtocolAddress> addressesAlreadyPinning(final IdentityKey key,
      final SignalProtocolAddress excluding) {
    final java.util.List<SignalProtocolAddress> found = new ArrayList<>();
    if (key == null) return found;
    for (final TrustedKey trustedKey : trustedKeys) {
      if (trustedKey == null || trustedKey.getIdentityKey() == null) continue;
      final SignalProtocolAddress at = trustedKey.getSignalProtocolAddress();
      if (at == null || at.equals(excluding)) continue;
      if (key.equals(trustedKey.getIdentityKey())) found.add(at);
    }
    return found;
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
    // All six persisted fields, not three. This used to compare only the registration id, the
    // pinned keys and the key pair - omitting the pending changes, the out-of-band provenance and
    // the rejection records, every one of which is @JsonProperty state that survives a restart. A
    // round trip that silently dropped all three still compared equal, so any test asserting
    // "the store survived serialisation" was blind to exactly the trust state this class exists to
    // hold.
    return localRegistrationId == that.localRegistrationId
        && Objects.equals(trustedKeys, that.trustedKeys)
        && Objects.equals(identityKeyPair, that.identityKeyPair)
        && Objects.equals(pendingIdentities, that.pendingIdentities)
        && Objects.equals(outOfBandAddresses, that.outOfBandAddresses)
        && Objects.equals(rejectedAddresses, that.rejectedAddresses);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trustedKeys, identityKeyPair, localRegistrationId, pendingIdentities,
        outOfBandAddresses, rejectedAddresses);
  }

  public List<TrustedKey> getTrustedKeys() {
    return trustedKeys;
  }
}
