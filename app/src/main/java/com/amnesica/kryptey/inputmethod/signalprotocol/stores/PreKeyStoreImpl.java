package com.amnesica.kryptey.inputmethod.signalprotocol.stores;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.PreKeyWithStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class PreKeyStoreImpl implements PreKeyStore {
  static final String TAG = PreKeyStoreImpl.class.getSimpleName();

  @JsonProperty
  private final Map<Integer, PreKeyWithStatus> store = new HashMap<>();

  public PreKeyStoreImpl() {
  }

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    Log.d(TAG, "Loading PreKeyRecord with id: " + preKeyId);
    try {
      if (!store.containsKey(preKeyId)) {
        throw new InvalidKeyIdException("No such PreKeyRecord! (id = " + preKeyId + ")");
      }

      store.put(preKeyId, new PreKeyWithStatus(
          Objects.requireNonNull(store.get(preKeyId)).getSerializedPreKeyRecord(), true,
          nextConsumptionSequence()));
      Log.d(TAG, "Setting PreKeyRecord with id " + preKeyId + " to used");

      return new PreKeyRecord(Objects.requireNonNull(store.get(preKeyId)).getSerializedPreKeyRecord());
    } catch (InvalidMessageException e) {
      throw new StoredRecordUnreadableException(
          "a stored prekey record could not be read back", e);
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    Log.d(TAG, "Storing PreKeyRecord with id: " + preKeyId);
    store.put(preKeyId, new PreKeyWithStatus(record.serialize(), false));
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return store.containsKey(preKeyId);
  }

  @Override
  public void removePreKey(int preKeyId) {
    Log.d(TAG, "Removing PreKeyRecord with id: " + preKeyId);
    store.remove(preKeyId);
  }

  public void removeAllPreKeys() {
    Log.d(TAG, "Removing all PreKeyRecords");
    store.clear();
  }

  // Count of currently available (eg. unused) prekeys
  public int getAvailablePreKeys() {
    return (int) store.entrySet().stream()
        .filter(p -> !p.getValue().isUsed()).count();
  }

  /**
   * One past the highest consumption sequence in the store.
   *
   * <p>Derived rather than persisted separately, so a reload cannot restart it at zero and make
   * every record consumed after the reload look older than everything before it - which would prune
   * the newest invites first, the exact defect this ordering exists to fix. Fifty-odd entries, read
   * once per invite.
   */
  private long nextConsumptionSequence() {
    long highest = 0;
    for (final PreKeyWithStatus each : store.values()) {
      if (each.getUsedAt() > highest) highest = each.getUsedAt();
    }
    return highest + 1;
  }

  /** Lowest id still unused, or null when every stored pre-key has been consumed. */
  public Integer findUnusedPreKeyId() {
    return store.entrySet().stream()
        .filter(e -> !e.getValue().isUsed())
        .map(Map.Entry::getKey)
        .min(Integer::compareTo)
        .orElse(null);
  }

  /**
   * Drops the oldest <em>used</em> pre-keys, keeping the most recent {@code keep}.
   *
   * <p>Used records are deliberately retained rather than deleted on use: a peer's first message
   * arrives after the bundle was handed out, so the key must still be there to decrypt it. But they
   * cannot be kept forever - the whole store is serialized into SharedPreferences on every message.
   */
  public void pruneUsedPreKeys(final int keep) {
    // By WHEN each was consumed, not by id.
    //
    // Sorting by id reads as "oldest first" and is not: findUnusedPreKeyId hands out the lowest
    // free id, and a peer's first message removes their id so the app regenerates it in place. The
    // next invite therefore carries a LOW id and is the first thing this drops. Measured on the
    // real path: fifty used records, id 3 recycled, and the invite handed id 3 was destroyed by the
    // very next invite while fifty older keys were kept - so the peer holding that invite could
    // never be decrypted, and the app said nothing. Ties go to the lower id, which only matters for
    // records stored by an older build, all of which carry sequence zero.
    final java.util.List<Integer> used = store.entrySet().stream()
        .filter(e -> e.getValue().isUsed())
        .sorted(java.util.Comparator
            .<Map.Entry<Integer, PreKeyWithStatus>>comparingLong(e -> e.getValue().getUsedAt())
            .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toList());
    for (int i = 0; i < used.size() - keep; i++) {
      Log.d(TAG, "Pruning used PreKeyRecord with id: " + used.get(i));
      store.remove(used.get(i));
    }
  }

  public Boolean checkPreKeyAvailable(final int preKeyId) {
    return store.containsKey(preKeyId) ? Objects.requireNonNull(store.get(preKeyId)).isUsed() : null;
  }

  public int getSize() {
    return store.size();
  }
}

