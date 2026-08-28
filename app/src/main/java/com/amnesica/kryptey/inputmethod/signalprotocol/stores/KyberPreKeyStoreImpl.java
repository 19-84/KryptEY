package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import android.util.Log;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores the Kyber-1024 pre-keys that PQXDH adds to the initial agreement.
 *
 * <p>Deliberately a separate store rather than a few extra methods on {@link PreKeyStoreImpl}:
 * Kyber records are a different type with different lifecycle rules, and folding them into the
 * classical pre-key map — as at least one fork of this project did — silently mixes post-quantum
 * and Curve25519 key material in one namespace.
 *
 * <p>Note the one-time semantics. libsignal calls {@link #markKyberPreKeyUsed} rather than a
 * remove: a last-resort Kyber pre-key may legitimately be reused across sessions, so the record is
 * retained and only the (base key, pre-key) pairing is burned. Reusing the <em>same</em> base key
 * against an already-used pre-key indicates a replayed handshake and must be rejected.
 */
public class KyberPreKeyStoreImpl implements KyberPreKeyStore {
  static final String TAG = KyberPreKeyStoreImpl.class.getSimpleName();

  @JsonProperty
  private final Map<Integer, byte[]> store = new HashMap<>();

  /**
   * Base keys already seen against a given Kyber pre-key id, as base64. Persisted so that a replay
   * is still detected after the keyboard process is restarted.
   */
  @JsonProperty
  private final Map<Integer, Set<String>> usedBaseKeys = new HashMap<>();

  public KyberPreKeyStoreImpl() {
  }

  @Override
  public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
    Log.d(TAG, "Loading KyberPreKeyRecord with id: " + kyberPreKeyId);
    try {
      if (!store.containsKey(kyberPreKeyId)) {
        throw new InvalidKeyIdException("No such kyber prekeyrecord! " + kyberPreKeyId);
      }
      return new KyberPreKeyRecord(store.get(kyberPreKeyId));
    } catch (InvalidMessageException e) {
      throw new StoredRecordUnreadableException(
          "a stored kyberprekey record could not be read back", e);
    }
  }

  @Override
  public List<KyberPreKeyRecord> loadKyberPreKeys() {
    Log.d(TAG, "Loading all KyberPreKeyRecords");
    try {
      final List<KyberPreKeyRecord> results = new LinkedList<>();
      for (final byte[] serialized : store.values()) {
        results.add(new KyberPreKeyRecord(serialized));
      }
      return results;
    } catch (InvalidMessageException e) {
      throw new StoredRecordUnreadableException(
          "a stored kyberprekey record could not be read back", e);
    }
  }

  @Override
  public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
    Log.d(TAG, "Storing KyberPreKeyRecord with id: " + kyberPreKeyId);
    store.put(kyberPreKeyId, record.serialize());
  }

  @Override
  public boolean containsKyberPreKey(int kyberPreKeyId) {
    return store.containsKey(kyberPreKeyId);
  }

  /**
   * How many distinct base keys one Kyber pre-key may burn before it is retired.
   *
   * <p>Generous against legitimate use, because this counts incoming <em>session establishments</em>
   * rather than messages: measured, fifty first-messages from one peer session burn one base key,
   * since libsignal stops re-deriving a session it already holds. A pre-key rotates on the signed
   * pre-key schedule long before a real correspondent approaches this.
   */
  static final int USED_BASE_KEY_LIMIT = 256;

  @Override
  public void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey)
      throws ReusedBaseKeyException {
    final String encoded = com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64
        .encodeBytesWithoutPadding(baseKey.serialize());
    final Set<String> seen = usedBaseKeys.computeIfAbsent(kyberPreKeyId, id -> new HashSet<>());
    if (!seen.add(encoded)) {
      // Same base key against the same Kyber pre-key: a replayed initial message. Rejecting here is
      // what stops an attacker re-driving a captured handshake.
      Log.w(TAG, "Rejecting reused base key for kyber pre key id: " + kyberPreKeyId);
      throw new ReusedBaseKeyException("base key already used with kyber pre key " + kyberPreKeyId);
    }
    Log.d(TAG, "Marked kyber pre key " + kyberPreKeyId + " used");

    // Bound the replay set by RETIRING the pre-key, never by forgetting a base key.
    //
    // This set only ever grew: one entry per incoming session establishment, kept for the life of
    // the pre-key, persisted, and re-encrypted into the Keystore box on every save. Measured on the
    // real path: 200 handshakes left 200 entries under a single pre-key id, and nothing below the
    // active id - which is all removeOldKyberPreKeys reaches - was ever involved.
    //
    // Evicting the oldest entry would be the obvious bound and is the wrong one: a forgotten base
    // key is a base key that can be replayed, which is exactly what this map exists to deny.
    // Dropping the RECORD instead is strictly stronger than remembering the keys. Once
    // loadKyberPreKey throws for this id every handshake against it is refused, replays included,
    // and ids are handed out monotonically so a retired id never returns to have a forgotten key
    // replayed against it. getPreKeyBundle already mints a fresh Kyber pre-key when the active one
    // is missing, so the next invite the user exports heals this without anyone noticing.
    //
    // Retire AFTER recording, so the handshake that crossed the limit still completes.
    if (seen.size() > USED_BASE_KEY_LIMIT) {
      Log.w(TAG, "Retiring kyber pre key " + kyberPreKeyId + " after " + seen.size()
          + " distinct base keys");
      removeKyberPreKey(kyberPreKeyId);
    }
  }

  public void removeKyberPreKey(int kyberPreKeyId) {
    Log.d(TAG, "Removing KyberPreKeyRecord with id: " + kyberPreKeyId);
    store.remove(kyberPreKeyId);
    usedBaseKeys.remove(kyberPreKeyId);
  }

  /** Mirrors {@code SignedPreKeyStoreImpl.removeOldSignedPreKeys} so rotation can retire pairs. */
  public void removeOldKyberPreKeys(int activeKyberPreKeyId) {
    Log.d(TAG, "Removing old KyberPreKeyRecords below id: " + activeKyberPreKeyId);
    for (int i = 0; i < activeKyberPreKeyId; i++) {
      if (containsKyberPreKey(i)) {
        removeKyberPreKey(i);
      }
    }
  }

  public int getSize() {
    return store.size();
  }
}
