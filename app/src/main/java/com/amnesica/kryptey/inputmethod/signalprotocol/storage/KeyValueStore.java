package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

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

  void remove(String key);

  boolean contains(String key);

  /** Every key currently held. Must be a snapshot: callers mutate the store while iterating. */
  Set<String> keys();
}
