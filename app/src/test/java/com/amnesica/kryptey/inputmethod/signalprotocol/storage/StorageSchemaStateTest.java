package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The schema-state predicates, which decide whether a store's contents are at stake.
 *
 * <p>Written from mutation-testing survivors in {@code EncryptedKeyValueStore}: four boolean
 * operators could be inverted with the whole suite still green. Each one gates a decision that
 * destroys or exposes the user's identity key if taken the wrong way, and the reason they survived
 * is the same in every case — the existing tests reach these predicates through stores that already
 * carry a schema marker, so the branches that run on an <em>unmarked</em> store were never executed.
 */
public class StorageSchemaStateTest {

  private static final String LEGACY_JSON =
      "{\"identityKeyStore\":{\"identityKeyPair\":\"BASE64PRIVATEKEY\"}}";

  private static InMemoryKeyValueStore legacyStore() {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    raw.put("PROTOCOL_STORE", LEGACY_JSON);
    raw.put("UNIQUE_USER_ID", "\"alice\"");
    raw.resetWriteCount();
    return raw;
  }

  /**
   * Note each {@link JvmGcmCryptoBox} mints its own key, so a store migrated here is NOT readable
   * by a box created later. Tests that need to read the migrated values back must keep the box
   * that did the migration - see {@link #migratedWith}.
   */
  private static InMemoryKeyValueStore migratedStore() throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox()).migrateToEncrypted();
    return raw;
  }

  private static InMemoryKeyValueStore migratedWith(final JvmGcmCryptoBox box) throws Exception {
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, box).migrateToEncrypted();
    return raw;
  }

  // ------------------------------------------------- isEncrypted / key minting

  /**
   * {@code isEncrypted} is the input to the most destructive decision in the app.
   *
   * <p>{@code StorageHelper:220} passes it to {@code mCryptoBoxFactory.create(context,
   * alreadyEncrypted)}, which decides whether the Keystore box <b>loads the existing master key or
   * mints a new one</b>. Answer "false" for a migrated store and the box mints a fresh key over
   * data sealed under the old one: every session, and the user's own identity key, becomes
   * permanently unreadable. Nothing exercised this — inverting the comparison left the suite green.
   */
  @Test
  public void isEncryptedDistinguishesAMigratedStoreFromALegacyOne() throws Exception {
    assertFalse("a legacy cleartext store must not be reported as encrypted",
        EncryptedKeyValueStore.isEncrypted(legacyStore()));
    assertFalse("nor an empty one", EncryptedKeyValueStore.isEncrypted(new InMemoryKeyValueStore()));
    assertTrue("a migrated store must be reported as encrypted",
        EncryptedKeyValueStore.isEncrypted(migratedStore()));
  }

  /**
   * The consequence, made concrete: this is what "mint a new key" does to a migrated store. The
   * test exists so that the cost of getting {@code isEncrypted} wrong is visible in the suite
   * rather than only in the javadoc.
   */
  @Test
  public void aFreshMasterKeyOverAMigratedStoreFailsLoudlyRatherThanSilently() throws Exception {
    final InMemoryKeyValueStore raw = migratedStore();

    // A different box stands in for a freshly minted Keystore key.
    final EncryptedKeyValueStore wrongKey = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());

    assertThrows("data sealed under another key must never read back as null or garbage",
        StorageCryptoException.class, () -> wrongKey.get("PROTOCOL_STORE"));
  }

  // --------------------------------------------------------- hasEncryptedData

  /**
   * A purely legacy store holds nothing a fresh key could not read, so nothing is at stake.
   *
   * <p>Inverting the {@code &&} at the envelope check makes this return true for any non-empty
   * value, cleartext included. That is the answer that says "there is encrypted data here",
   * which is what stops a replacement key being minted — so on a legacy store the mutant blocks
   * the migration that is supposed to happen.
   */
  @Test
  public void aLegacyCleartextStoreHoldsNothingAtStake() {
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(legacyStore(), new JvmGcmCryptoBox());
    assertFalse("cleartext is readable without any key, so nothing is at stake",
        store.hasEncryptedData());
  }

  /**
   * Ciphertext with no marker at all must still count.
   *
   * <p>{@code unmarkedCiphertextStillCountsAsEncryptedData} covers the interrupted-migration case,
   * but that leaves the in-progress marker behind — so it returns at the marker check and never
   * reaches the loop over payload values. Deleting the marker outright, which is exactly what an
   * attacker laundering cleartext into a migrated store would do, is what reaches it.
   */
  @Test
  public void ciphertextWithTheMarkerDeletedStillCountsAsEncryptedData() throws Exception {
    final InMemoryKeyValueStore raw = migratedStore();
    raw.remove(EncryptedKeyValueStore.SCHEMA_KEY);

    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, new JvmGcmCryptoBox());
    assertTrue("sealed values are at stake whether or not the marker survived",
        store.hasEncryptedData());
  }

  // ------------------------------------------------------------- legacy reads

  /**
   * The plain legacy read: no marker, no envelopes, cleartext comes back as-is.
   *
   * <p>This is the state of every 0.1.5 install on first launch after the update, and it was
   * untested — inverting the {@code &&} to {@code ||} makes the very first read throw, which would
   * brick the migration for every existing user. Every other read test reaches this line with a
   * marker present.
   */
  @Test
  public void aLegacyValueReadsBackUnchangedWithNoMarkerPresent() throws Exception {
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(legacyStore(), new JvmGcmCryptoBox());

    assertEquals("legacy cleartext must read back verbatim before migration",
        LEGACY_JSON, store.get("PROTOCOL_STORE"));
  }

  /** And the guard it sits behind still fires: cleartext beside readable ciphertext is refused. */
  @Test
  public void cleartextBesideReadableCiphertextIsStillRefused() throws Exception {
    // Same box throughout: the guard turns on whether the OTHER values decrypt, so a fresh box
    // (which cannot read them) would make containsReadableEnvelope false and the guard silent.
    final JvmGcmCryptoBox box = new JvmGcmCryptoBox();
    final InMemoryKeyValueStore raw = migratedWith(box);
    raw.remove(EncryptedKeyValueStore.SCHEMA_KEY);
    raw.put("PROTOCOL_STORE", LEGACY_JSON); // attacker plants cleartext

    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, box);
    assertThrows("planted cleartext must not be readable in a store that holds sealed values",
        StorageCryptoException.class, () -> store.get("PROTOCOL_STORE"));
  }

  // ------------------------------------------------------- in-progress marker

  /**
   * A store with nothing to convert still ends in the completed state.
   *
   * <p>Inverting the {@code &&} that guards the in-progress marker (so a fresh store also writes
   * one) is an <b>equivalent mutant</b>, and this test does not try to kill it. There is no early
   * return in {@code migrateToEncryptedInternal}: {@code writeMarker(MARKER_COMPLETE)} always runs
   * at the end, so the mutant only adds a redundant write of a marker that is immediately replaced.
   * A crash in the extra window leaves {@code MARKER_MIGRATING} on an empty store, which the next
   * run resolves to complete anyway. Recording that judgement here rather than manufacturing an
   * assertion for it - the observable end state is what matters, and that is what this asserts.
   */
  @Test
  public void migratingAStoreWithNothingToConvertEndsInTheCompletedState() throws Exception {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    final JvmGcmCryptoBox box = new JvmGcmCryptoBox();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, box);

    assertFalse("an empty store needs no migration", store.needsMigration());
    store.migrateToEncrypted();

    assertFalse("migration must not leave the store looking unmigrated", store.needsMigration());
    assertTrue("and it must be recognisable as an encrypted store afterwards",
        EncryptedKeyValueStore.isEncrypted(raw));

    // Values written afterwards are sealed and read back through the same box.
    store.put("PROTOCOL_STORE", LEGACY_JSON);
    assertEquals(LEGACY_JSON, store.get("PROTOCOL_STORE"));
    assertNotNull(raw.get("PROTOCOL_STORE"));
    assertFalse("the value on disk must not be the cleartext",
        LEGACY_JSON.equals(raw.get("PROTOCOL_STORE")));
  }
}
