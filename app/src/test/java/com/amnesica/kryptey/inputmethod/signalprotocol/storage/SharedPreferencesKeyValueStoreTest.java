package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Set;

import javax.crypto.SecretKey;

/**
 * Exercises the real Android SharedPreferences implementation, which the in-memory test double
 * cannot stand in for.
 *
 * <p>Two things here are only meaningful against the real thing: that {@code keys()} hands back a
 * snapshot rather than a live view — {@code migrateToEncrypted} mutates the store while iterating
 * it, so a live view would throw {@code ConcurrentModificationException} in production with nothing
 * to catch it — and that the whole encrypted store works end to end on top of actual preferences.
 */
@RunWith(RobolectricTestRunner.class)
public class SharedPreferencesKeyValueStoreTest {

  private SharedPreferences preferences;
  private SharedPreferencesKeyValueStore store;

  @Before
  public void setUp() {
    final Context context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    store = new SharedPreferencesKeyValueStore(preferences);
  }

  @Test
  public void putAndGetRoundTrip() {
    store.put("PROTOCOL_STORE", "value");
    assertEquals("value", store.get("PROTOCOL_STORE"));
  }

  @Test
  public void missingKeyIsNull() {
    assertNull(store.get("ABSENT"));
  }

  @Test
  public void containsReflectsWhatWasWritten() {
    assertFalse(store.contains("PROTOCOL_STORE"));
    store.put("PROTOCOL_STORE", "value");
    assertTrue(store.contains("PROTOCOL_STORE"));
  }

  @Test
  public void removeDeletesTheEntry() {
    store.put("PROTOCOL_STORE", "value");
    store.remove("PROTOCOL_STORE");
    assertFalse(store.contains("PROTOCOL_STORE"));
    assertNull(store.get("PROTOCOL_STORE"));
  }

  @Test
  public void writesAreVisibleThroughTheUnderlyingPreferences() {
    store.put("PROTOCOL_STORE", "value");
    assertEquals("value", preferences.getString("PROTOCOL_STORE", null));
  }

  /**
   * {@code KeyValueStore} declares this mandatory. Mutating a live keySet while iterating it is
   * exactly what the migration does.
   */
  @Test
  public void keysReturnsASnapshotThatSurvivesConcurrentMutation() {
    store.put("A", "1");
    store.put("B", "2");

    final Set<String> keys = store.keys();
    for (final String key : keys) {
      store.put(key, "rewritten"); // would blow up on a live view
    }

    assertEquals(2, keys.size());
    assertEquals("rewritten", store.get("A"));
    assertEquals("rewritten", store.get("B"));
  }

  /** The real point of the class: the whole encrypted store works on actual SharedPreferences. */
  @Test
  public void encryptedStoreWorksOnRealPreferences() throws Exception {
    final SecretKey key = JvmGcmCryptoBox.newKey();
    final EncryptedKeyValueStore encrypted =
        new EncryptedKeyValueStore(store, new JvmGcmCryptoBox(key));

    encrypted.put("PROTOCOL_STORE", "{\"identityKeyPair\":\"SECRET\"}");

    assertEquals("{\"identityKeyPair\":\"SECRET\"}", encrypted.get("PROTOCOL_STORE"));
    final String onDisk = preferences.getString("PROTOCOL_STORE", null);
    assertFalse("cleartext reached SharedPreferences", onDisk.contains("SECRET"));
  }

  /** The 0.1.5 upgrade, end to end, against real preferences rather than a HashMap. */
  @Test
  public void migratesALegacyCleartextPreferencesFile() throws Exception {
    preferences.edit()
        .putString("PROTOCOL_STORE", "{\"identityKeyPair\":\"LEGACYSECRET\"}")
        .putString("UNIQUE_USER_ID", "\"alice\"")
        .commit();

    final SecretKey key = JvmGcmCryptoBox.newKey();
    final EncryptedKeyValueStore encrypted =
        new EncryptedKeyValueStore(store, new JvmGcmCryptoBox(key));
    assertTrue(encrypted.needsMigration());
    encrypted.migrateToEncrypted();

    assertEquals("{\"identityKeyPair\":\"LEGACYSECRET\"}", encrypted.get("PROTOCOL_STORE"));
    assertEquals("\"alice\"", encrypted.get("UNIQUE_USER_ID"));

    for (final String k : preferences.getAll().keySet()) {
      final Object v = preferences.getAll().get(k);
      assertFalse("legacy cleartext survived under " + k,
          v instanceof String && ((String) v).contains("LEGACYSECRET"));
    }

    // Reopening with the same key must not re-migrate.
    assertFalse(new EncryptedKeyValueStore(store, new JvmGcmCryptoBox(key)).needsMigration());
  }
}
