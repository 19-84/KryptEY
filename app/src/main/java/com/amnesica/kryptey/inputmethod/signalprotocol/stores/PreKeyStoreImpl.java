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

      store.put(preKeyId, new PreKeyWithStatus(Objects.requireNonNull(store.get(preKeyId)).getSerializedPreKeyRecord(), true));
      Log.d(TAG, "Setting PreKeyRecord with id " + preKeyId + " to used");

      return new PreKeyRecord(Objects.requireNonNull(store.get(preKeyId)).getSerializedPreKeyRecord());
    } catch (InvalidMessageException e) {
      throw new AssertionError(e);
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
    final java.util.List<Integer> used = store.entrySet().stream()
        .filter(e -> e.getValue().isUsed())
        .map(Map.Entry::getKey)
        .sorted()
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

