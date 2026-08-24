package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** {@link KeyValueStore} over Android SharedPreferences. */
public final class SharedPreferencesKeyValueStore implements KeyValueStore {

  private final SharedPreferences preferences;

  public SharedPreferencesKeyValueStore(final SharedPreferences preferences) {
    this.preferences = preferences;
  }

  @Override
  public String get(final String key) {
    return preferences.getString(key, null);
  }

  @Override
  public void put(final String key, final String value) {
    // commit() rather than apply(): this holds key material, and the migration must be durable
    // before the plaintext copy is deleted. An apply() that had not yet reached disk when the
    // process died would lose the user's identity key.
    //
    // The return value MUST be checked. On a full disk or any I/O error, SharedPreferences deletes
    // the partial file, restores the previous contents from its .bak on next load, and returns
    // false - while the in-memory map keeps the new value, so the running process looks healthy.
    // Discarding this would let the migration mark the store "encrypted" while cleartext survived
    // on disk, which is unrecoverable: the legacy-read path is only reachable while unmarked.
    if (!preferences.edit().putString(key, value).commit()) {
      throw new StorageWriteException("could not persist '" + key + "' to SharedPreferences");
    }
  }

  /**
   * One editor, one commit - so the whole batch reaches disk or none of it does.
   *
   * <p>SharedPreferences writes the file by rename, keeping the previous contents in a .bak until
   * the new file is complete. That is what makes a single commit atomic against process death, and
   * it is exactly the property eight separate commits do not have.
   *
   * <p>The return value is checked for the same reason it is checked in {@link #put}: on an I/O
   * failure the on-disk state rolls back while the in-memory map keeps the new values, so a caller
   * that ignored it would carry on with an account that does not exist on disk.
   */
  @Override
  public void putAll(final Map<String, String> entries) {
    final SharedPreferences.Editor editor = preferences.edit();
    for (final Map.Entry<String, String> entry : entries.entrySet()) {
      editor.putString(entry.getKey(), entry.getValue());
    }
    if (!editor.commit()) {
      throw new StorageWriteException(
          "could not persist " + entries.size() + " entries to SharedPreferences");
    }
  }

  @Override
  public void remove(final String key) {
    if (!preferences.edit().remove(key).commit()) {
      throw new StorageWriteException("could not remove '" + key + "' from SharedPreferences");
    }
  }

  /** Unchecked so it aborts the migration rather than being quietly absorbed part-way through. */
  public static final class StorageWriteException extends RuntimeException {
    StorageWriteException(final String message) {
      super(message);
    }
  }

  @Override
  public boolean contains(final String key) {
    return preferences.contains(key);
  }

  @Override
  public Set<String> keys() {
    return new HashSet<>(preferences.getAll().keySet());
  }
}
