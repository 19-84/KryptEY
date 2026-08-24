package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import java.util.Map;
import java.util.Set;

/**
 * The minimal slice of SharedPreferences that {@link EncryptedKeyValueStore} needs.
 *
 * <p>Exists so the plaintext-to-encrypted migration can be tested on a JVM. That migration is the
 * one operation in this codebase that can permanently destroy a user's identity key and every
 * session they have, so testing it against a real implementation rather than a mock matters.
 */
public interface KeyValueStore {

  String get(String key);

  void put(String key, String value);

  /**
   * Write several entries as one unit.
   *
   * <p>The account save writes eight values. Done one at a time that is eight separate durable
   * commits on the IME main thread, and - worse - eight separate opportunities to stop half way: a
   * process death or an I/O failure after the fourth leaves a store holding a new protocol store
   * beside an old contact list, which is a torn account that both halves believe is consistent.
   *
   * <p>The default here keeps every existing implementation working and is NOT atomic. An
   * implementation that can do better should say so by overriding.
   */
  default void putAll(Map<String, String> entries) {
    for (final Map.Entry<String, String> entry : entries.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  void remove(String key);

  boolean contains(String key);

  /** Every key currently held. Must be a snapshot: callers mutate the store while iterating. */
  Set<String> keys();
}
