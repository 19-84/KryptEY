package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

/**
 * Covers the upgrade path for an installed KryptEY 0.1.5, whose SharedPreferences hold the identity
 * private key as cleartext JSON. Getting this wrong destroys every session the user has, so the
 * emphasis is on partial failures and on proving the cleartext is actually gone afterwards.
 */
public class EncryptedKeyValueStoreTest {

  private static final String LEGACY_STORE_JSON =
      "{\"identityKeyStore\":{\"identityKeyPair\":\"BASE64PRIVATEKEY\"}}";
  private static final String LEGACY_ADDRESS_JSON =
      "{\"name\":\"2aecc0a1-67ef-42c5-b2db-60f0bfdf2f33\",\"deviceId\":7296}";

  private static InMemoryKeyValueStore legacyStore() {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    raw.put("PROTOCOL_STORE", LEGACY_STORE_JSON);
    raw.put("PROTOCOL_ADDRESS", LEGACY_ADDRESS_JSON);
    raw.put("UNIQUE_USER_ID", "\"alice\"");
    raw.resetWriteCount(); // so failAfterWrites counts migration writes, not fixture setup
    return raw;
  }

  // ---------------------------------------------------------------- basics

  @Test
  public void roundTripsThroughEncryption() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    store.put("PROTOCOL_STORE", LEGACY_STORE_JSON);
    assertEquals(LEGACY_STORE_JSON, store.get("PROTOCOL_STORE"));
  }

  @Test
  public void whatLandsOnDiskIsNotTheCleartext() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    store.put("PROTOCOL_STORE", LEGACY_STORE_JSON);

    final String onDisk = raw.get("PROTOCOL_STORE");
    assertNotNull(onDisk);
    assertFalse("cleartext JSON still on disk", onDisk.contains("identityKeyStore"));
    assertFalse("private key still on disk", onDisk.contains("BASE64PRIVATEKEY"));
  }

  @Test
  public void missingKeyReadsAsNull() throws Exception {
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(new InMemoryKeyValueStore(), new JvmGcmCryptoBox());
    assertNull(store.get("NOT_PRESENT"));
  }

  // ------------------------------------------------------------- migration

  @Test
  public void detectsALegacyCleartextStore() {
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(legacyStore(), new JvmGcmCryptoBox());
    assertTrue(store.needsMigration());
  }

  @Test
  public void aFreshInstallDoesNotNeedMigration() {
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(new InMemoryKeyValueStore(), new JvmGcmCryptoBox());
    assertFalse("empty store misreported as legacy", store.needsMigration());
  }

  @Test
  public void migrationPreservesEveryValue() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    store.migrateToEncrypted();

    assertEquals(LEGACY_STORE_JSON, store.get("PROTOCOL_STORE"));
    assertEquals(LEGACY_ADDRESS_JSON, store.get("PROTOCOL_ADDRESS"));
    assertEquals("\"alice\"", store.get("UNIQUE_USER_ID"));
  }

  /**
   * Name is scoped deliberately: this asserts the migration replaces the cleartext <em>value</em> in
   * the store. It cannot speak to bytes left behind on a real filesystem — a SharedPreferences
   * commit leaves the previous file's blocks unlinked but not overwritten, which no unit test
   * against an in-memory map can observe.
   */
  @Test
  public void migrationReplacesTheCleartextValues() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox()).migrateToEncrypted();

    for (final String key : raw.keys()) {
      final String value = raw.get(key);
      assertFalse("cleartext survived migration under " + key,
          value.contains("identityKeyStore") || value.contains("BASE64PRIVATEKEY"));
    }
  }

  @Test
  public void migrationIsMarkedSoItDoesNotRunTwice() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    store.migrateToEncrypted();
    assertFalse(store.needsMigration());

    // Running it again must not double-encrypt: that would leave the value unreadable.
    store.migrateToEncrypted();
    assertEquals(LEGACY_STORE_JSON, store.get("PROTOCOL_STORE"));
  }

  @Test
  public void migrationIsIdempotentAcrossFreshInstances() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();

    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).migrateToEncrypted();
    final EncryptedKeyValueStore reopened =
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    reopened.migrateToEncrypted();

    assertEquals(LEGACY_STORE_JSON, reopened.get("PROTOCOL_STORE"));
  }

  /**
   * The migration rewrites values one at a time and only then sets the marker. A crash in the
   * middle must therefore leave a resumable store, not a half-readable one.
   */
  @Test
  public void aCrashMidMigrationIsRecoverable() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();

    raw.failAfterWrites = 3; // in-progress marker + two values, then die
    // Surfaces as StorageCryptoException: the store converts unchecked write failures at its
    // boundary so they cannot escape to LatinIME.setInputView(). What matters is the state left
    // behind, asserted below.
    assertThrows(StorageCryptoException.class,
        () -> new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).migrateToEncrypted());

    raw.failAfterWrites = -1;
    final EncryptedKeyValueStore resumed = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    assertTrue(resumed.needsMigration());
    resumed.migrateToEncrypted();

    assertEquals(LEGACY_STORE_JSON, resumed.get("PROTOCOL_STORE"));
    assertEquals(LEGACY_ADDRESS_JSON, resumed.get("PROTOCOL_ADDRESS"));
    assertEquals("\"alice\"", resumed.get("UNIQUE_USER_ID"));
  }

  @Test
  public void readsSucceedWhileAnInterruptedMigrationIsStillMixed() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();

    raw.failAfterWrites = 1;
    // Surfaces as StorageCryptoException: the store converts unchecked write failures at its
    // boundary so they cannot escape to LatinIME.setInputView(). What matters is the state left
    // behind, asserted below.
    assertThrows(StorageCryptoException.class,
        () -> new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).migrateToEncrypted());
    raw.failAfterWrites = -1;

    // Every value must still be readable, whichever form it happens to be in.
    final EncryptedKeyValueStore mixed = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    assertEquals(LEGACY_STORE_JSON, mixed.get("PROTOCOL_STORE"));
    assertEquals(LEGACY_ADDRESS_JSON, mixed.get("PROTOCOL_ADDRESS"));
    assertEquals("\"alice\"", mixed.get("UNIQUE_USER_ID"));
  }

  // ------------------------------------------------------- failure handling

  /**
   * A wrong key must surface as an error the caller has to handle, never as {@code null} — the
   * null-swallow is precisely what crashed the keyboard on every raise before this phase.
   */
  @Test
  public void aWrongMasterKeyThrowsRatherThanReturningNull() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox()).put("PROTOCOL_STORE", LEGACY_STORE_JSON);

    final EncryptedKeyValueStore withOtherKey =
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());
    assertThrows(StorageCryptoException.class, () -> withOtherKey.get("PROTOCOL_STORE"));
  }

  @Test
  public void tamperedStoredValueThrows() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    store.put("PROTOCOL_STORE", LEGACY_STORE_JSON);

    // Mutate a character in the body. Note the first base64 character cannot be used: the 0x01
    // version byte encodes to 'A', so overwriting index 0 with 'A' is a no-op.
    final String sealed = raw.get("PROTOCOL_STORE");
    final int at = sealed.length() / 2;
    final char original = sealed.charAt(at);
    final char flipped = original == 'A' ? 'B' : 'A';
    raw.put("PROTOCOL_STORE", sealed.substring(0, at) + flipped + sealed.substring(at + 1));

    assertThrows(StorageCryptoException.class,
        () -> new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).get("PROTOCOL_STORE"));
  }

  @Test
  public void garbageStoredValueThrows() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();
    // Reach the completed state legitimately so the sealed marker is valid.
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).put("PROTOCOL_STORE", LEGACY_STORE_JSON);
    raw.put("PROTOCOL_STORE", "!!! not base64 !!!");

    assertThrows(StorageCryptoException.class,
        () -> new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).get("PROTOCOL_STORE"));
  }

  /**
   * The laundering attack the sealed marker exists to stop: an attacker with filesystem write
   * access deletes the marker and substitutes their own cleartext JSON into a migrated store,
   * hoping the app will re-seal it under the legitimate Keystore key and thereafter trust it.
   */
  @Test
  public void refusesToEncryptCleartextInjectedIntoAMigratedStore() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).migrateToEncrypted();

    // Attacker: drop the marker, plant cleartext pinning their own identity key.
    raw.remove(EncryptedKeyValueStore.SCHEMA_KEY);
    raw.put("PROTOCOL_STORE", "{\"identityKeyStore\":{\"identityKeyPair\":\"ATTACKERKEY\"}}");

    final EncryptedKeyValueStore reopened =
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    assertThrows("attacker cleartext must not be sealed under the real master key",
        StorageCryptoException.class, reopened::migrateToEncrypted);
    assertThrows("attacker cleartext must not be readable either",
        StorageCryptoException.class, () -> reopened.get("PROTOCOL_STORE"));
  }

  /**
   * An envelope sealed under a master key we no longer hold must abort the migration, not be
   * skipped. Skipping it and marking the store complete would orphan it forever.
   */
  @Test
  public void refusesToMigrateAroundAnUndecryptableEnvelope() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox()).migrateToEncrypted();
    raw.remove(EncryptedKeyValueStore.SCHEMA_KEY);
    raw.put("NEW_CLEARTEXT_KEY", "{}");

    // A different master key: every existing envelope is now unreadable.
    final EncryptedKeyValueStore withLostKey =
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());
    assertThrows(StorageCryptoException.class, withLostKey::migrateToEncrypted);
  }

  @Test
  public void reportsWhetherEncryptedDataExists() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());
    assertFalse("empty store should not claim to hold encrypted data", store.hasEncryptedData());

    store.put("PROTOCOL_STORE", LEGACY_STORE_JSON);
    assertTrue(store.hasEncryptedData());
  }

  /**
   * A write that fails silently must abort the migration. If the schema marker were set while
   * cleartext survived on disk, the value would be permanently uninterpretable: the legacy-read
   * path is only reachable while the store is unmarked.
   */
  @Test
  public void aSilentlyDroppedWriteAbortsTheMigration() {
    final InMemoryKeyValueStore raw = legacyStore();
    raw.silentlyDropWrites.add("PROTOCOL_STORE");

    final SecretKey key = JvmGcmCryptoBox.newKey();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key));
    assertThrows(StorageCryptoException.class, store::migrateToEncrypted);

    // The in-progress marker is expected - it is what tells the next run this is a legitimate
    // resume. What must NOT happen is the store declaring itself finished.
    assertTrue("migration must not report completion after a failed write",
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).needsMigration());
    assertEquals("cleartext must remain readable so a later run can retry",
        LEGACY_STORE_JSON, raw.get("PROTOCOL_STORE"));
  }

  /**
   * An interrupted migration leaves real ciphertext with the marker deliberately unset. If that
   * did not count as "data at stake", a Keystore-backed box would conclude nothing was at risk and
   * mint a replacement master key, permanently orphaning what had already been converted.
   */
  @Test
  public void unmarkedCiphertextStillCountsAsEncryptedData() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    final SecretKey key = JvmGcmCryptoBox.newKey();

    raw.failAfterWrites = 2; // in-progress marker, then one value, then die
    // Surfaces as StorageCryptoException: the store converts unchecked write failures at its
    // boundary so they cannot escape to LatinIME.setInputView(). What matters is the state left
    // behind, asserted below.
    assertThrows(StorageCryptoException.class,
        () -> new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).migrateToEncrypted());
    raw.failAfterWrites = -1;

    assertTrue("a partially converted store must be recognised as holding data at stake",
        new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox(key)).hasEncryptedData());
  }

  /** Values with awkward encodings must survive the UTF-8 round trip intact. */
  @Test
  public void preservesNonAsciiAndControlCharacters() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    final String tricky = "{\"m\":\"日本語 · emoji 🔐 · \\u0000 · \\\"quoted\\\"\"}";
    store.put("UNENCRYPTED_MESSAGES", tricky);
    assertEquals(tricky, store.get("UNENCRYPTED_MESSAGES"));
  }
}
