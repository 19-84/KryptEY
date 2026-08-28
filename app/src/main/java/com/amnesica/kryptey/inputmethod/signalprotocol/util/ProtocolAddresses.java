package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import android.util.Log;

import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.Random;

/**
 * Builds {@link SignalProtocolAddress} values that libsignal will actually accept.
 *
 * <p>libsignal 0.86 validates device ids and rejects anything outside {@code [1, 127]}; 0.21 did
 * not. KryptEY 0.1.5 generated them with {@code new Random().nextInt(10000)}, so the overwhelming
 * majority of existing installs hold a device id the current library refuses to construct — the
 * stored address cannot be deserialized at all, which takes the whole account down with it.
 *
 * <p>Legacy ids are therefore folded back into the valid range, deterministically, so the same
 * stored value always maps to the same address.
 *
 * <p>An earlier version of this note claimed the fold costs the user their sessions but preserves
 * everything else. That was wrong in both directions, so to be precise about what actually happens:
 *
 * <ul>
 *   <li><b>Preserved — identity keys and safety numbers.</b> Fingerprints derive from the two identity keys alone, deliberately not from the address - the address name is peer-supplied and unauthenticated.
 *   <li><b>Preserved — local sessions and trusted identities.</b> Both are stored keyed by address
 *       and read back through the same folding deserializer, so lookups still match. Nothing local
 *       is lost.
 *   <li><b>Lost — the user's public address, permanently.</b> The folded value is written back on
 *       first load and the original is gone. Every peer still on 0.1.5 has this user recorded under
 *       the old id, so their envelopes no longer match a known contact and re-adding produces a
 *       duplicate entry with no session. This breaks mixed-version pairs in both directions until
 *       both sides upgrade; upgraded-to-upgraded is self-consistent.
 * </ul>
 */
public final class ProtocolAddresses {

  private static final String TAG = ProtocolAddresses.class.getSimpleName();

  public static final int MIN_DEVICE_ID = 1;
  public static final int MAX_DEVICE_ID = 127;

  private ProtocolAddresses() {
  }

  /** A device id libsignal will accept, for a newly created account. */
  public static int generateDeviceId() {
    return MIN_DEVICE_ID + new Random().nextInt(MAX_DEVICE_ID);
  }

  public static boolean isValidDeviceId(final int deviceId) {
    return deviceId >= MIN_DEVICE_ID && deviceId <= MAX_DEVICE_ID;
  }

  /**
   * Folds an out-of-range legacy device id into {@code [1, 127]}. Deterministic, so a stored
   * address and every contact referring to it land on the same value.
   */
  public static int sanitizeDeviceId(final int deviceId) {
    if (isValidDeviceId(deviceId)) return deviceId;
    final int mapped = MIN_DEVICE_ID + Math.floorMod(deviceId - MIN_DEVICE_ID, MAX_DEVICE_ID);
    Log.w(TAG, "Legacy device id " + deviceId + " is outside libsignal's valid range; "
        + "mapping to " + mapped + ". Existing sessions for this address are not recoverable.");
    return mapped;
  }

  /** Constructs an address, folding a legacy device id into range if necessary. */
  public static SignalProtocolAddress of(final String name, final int deviceId) {
    return new SignalProtocolAddress(name, sanitizeDeviceId(deviceId));
  }

  /**
   * The canonical string form of a full address: {@code name.deviceId}.
   *
   * <p>One definition, because two records key off it - the chat log and the retired display name -
   * and both were keyed by the address NAME alone. A name is not an identity: it travels in every
   * envelope the messenger relays, and the device id beside it is one byte the sender chooses. Any
   * record keyed by the name is a record two different peers share.
   */
  public static String key(final SignalProtocolAddress address) {
    if (address == null) return "";
    return address.getName() + SEPARATOR + address.getDeviceId();
  }

  /**
   * A separator no address name can contain.
   *
   * <p>A dot was wrong, and the way it was wrong is worth keeping. Two keys built this way never
   * collide with each other - device ids are dot-free integers, so the last dot splits uniquely -
   * and that is the collision the obvious analysis looks for. The live one is between a key and a
   * BARE address name, because the retired-name migration arm still compares against bare names for
   * upgrade compatibility. (The chat log's legacy arm was named here too and is gone: belongsTo
   * makes one comparison, against the full rendered address. The conclusion is unchanged - one
   * bare-name comparison is enough to need this - but a reader deciding whether the separator must
   * still be non-printable should count the real arms.) The messenger writes address names:
   * it picks {@code bobsName.7}, which is a perfectly ordinary printable name at a fresh unpinned
   * address that collides with no contact and triggers no duplicate warning - and every message
   * filed for the real Bob at device 7 then matches it.
   *
   * <p>{@code BinaryEnvelope.requireDisplaySafeName} refuses any sender name outside printable
   * ASCII, on every envelope, in both directions. A character below 0x20 therefore cannot occur in
   * any address name this app will accept, so a rendered key and a bare name live in provably
   * disjoint spaces. If that validation is ever widened, this is the other place to change.
   */
  public static final char SEPARATOR = '\u001f';
}
