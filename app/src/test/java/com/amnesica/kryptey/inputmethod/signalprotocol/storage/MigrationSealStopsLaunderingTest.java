package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The one-time cleartext conversion cannot be made to happen a second time.
 *
 * <p>Converting cleartext found on disk is a laundering primitive: it takes bytes an attacker wrote
 * and hands them back sealed under the real master key, at which point they are indistinguishable
 * from the user's own data. Two guards existed for it and a Phase 1 sweep showed both fall, because
 * both live inside the file the attacker rewrites.
 *
 * <p>The recorded residual was that emptying the file at least costs the attacker stealth — "the
 * user's contact list visibly disappears". That was wrong, and the tests below are the reason it is
 * no longer possible to believe it: the attacker never loses a row, because they put the file back.
 *
 * <p>The fact now lives in the Keystore, which someone holding the app's private files cannot edit.
 * They can destroy the alias, but that destroys the master key with it, and the sealed data they
 * wanted to launder into becomes unreadable.
 */
public class MigrationSealStopsLaunderingTest {

  private static final String VICTIM_STORE = "{\"identityKeyStore\":{\"identityKeyPair\":\"THEIRS\"}}";
  private static final String ATTACKER_STORE = "{\"identityKeyStore\":{\"identityKeyPair\":\"MINE\"}}";

  /**
   * A box whose seal outlives the store, the way a Keystore alias outlives a file.
   *
   * <p>The distinction this whole fix turns on, so the double has to model it: everything else in
   * these tests can be rewritten by the attacker, and this cannot.
   */
  private static final class SealedBox extends GcmCryptoBox {
    private boolean sealed;

    private static final javax.crypto.SecretKey KEY =
        new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

    @Override
    protected javax.crypto.SecretKey key() {
      return KEY;
    }

    @Override
    public boolean legacyMigrationIsSealed() {
      return sealed;
    }

    @Override
    public void sealLegacyMigration() {
      sealed = true;
    }
  }

  private static InMemoryKeyValueStore legacyStore() {
    final InMemoryKeyValueStore raw = new InMemoryKeyValueStore();
    raw.put("PROTOCOL_STORE", VICTIM_STORE);
    raw.put("UNIQUE_USER_ID", "\"alice\"");
    return raw;
  }

  /** The genuine upgrade still works, or everything below is about a broken app. */
  @Test
  public void thelegitimateUpgradeStillConverts() throws Exception {
    final SealedBox box = new SealedBox();
    final InMemoryKeyValueStore raw = legacyStore();
    final EncryptedKeyValueStore store = new EncryptedKeyValueStore(raw, box);

    assertTrue("precondition: a 0.1.5 store needs converting", store.needsMigration());
    store.migrateToEncrypted();

    assertEquals("the user's own data must survive the conversion", VICTIM_STORE,
        store.get("PROTOCOL_STORE"));
    assertTrue("and the device must record that it has now happened", box.legacyMigrationIsSealed());
  }

  /**
   * The snapshot attack: empty the file, let one raise seal the attacker's row, put the file back.
   *
   * <p>This is the sequence the old guard could not see, because the guard only refuses cleartext
   * that sits BESIDE decryptable data — and step two removes the decryptable data.
   */
  @Test
  public void asecondConversionIsRefusedEvenWhenNothingDecryptableIsLeftBehind() throws Exception {
    final SealedBox box = new SealedBox();

    // The user's real install, converted once.
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, box).migrateToEncrypted();
    assertTrue(box.legacyMigrationIsSealed());

    // The attacker replaces the whole file with one cleartext row of their own. Emptying it costs
    // them nothing: the master key is untouched, because hasExistingData gates only MINTING.
    final InMemoryKeyValueStore replaced = new InMemoryKeyValueStore();
    replaced.put("PROTOCOL_STORE", ATTACKER_STORE);
    final EncryptedKeyValueStore reopened = new EncryptedKeyValueStore(replaced, box);

    try {
      reopened.migrateToEncrypted();
      fail("the store sealed attacker-supplied cleartext under the user's master key. Sealed, it "
          + "is indistinguishable from the user's own data, so the attacker restores their "
          + "snapshot of the original file with this one row swapped in: the contact list, the "
          + "retired names and the display tags all come back untouched, and the account comes up "
          + "with the attacker's identity key. Nothing is missing and there is nothing to see.");
    } catch (final StorageCryptoException expected) {
      assertTrue("the refusal must say why: " + expected.getMessage(),
          expected.getMessage().contains("already completed"));
    }
  }

  /**
   * The marker-replay attack, which needs no emptying and no race.
   *
   * <p>{@code MARKER_MIGRATING} is committed as its own durable write before anything is sealed,
   * and nothing invalidates it except a SUCCESSFUL migration. So it can be harvested from a
   * deliberately interrupted run and presented later, in one file, alongside the user's genuine
   * sealed rows and one cleartext row of the attacker's — which disarmed the beside-decryptable
   * guard by construction. The class javadoc's claim that the marker "cannot be faked" was true of
   * forgery and silent about retention.
   */
  @Test
  public void areplayedInProgressMarkerDoesNotReOpenTheConversion() throws Exception {
    final SealedBox box = new SealedBox();

    // A deliberately interrupted run, which is where the attacker harvests the marker.
    final InMemoryKeyValueStore interrupted = legacyStore();
    // After the marker write, before the payloads: the state a real crash leaves behind. The
    // marker is the first write the migration makes, so failing on the second is what preserves it.
    interrupted.resetWriteCount();
    interrupted.failAfterWrites = 1;
    try {
      new EncryptedKeyValueStore(interrupted, box).migrateToEncrypted();
    } catch (final Exception expected) {
      // The point of the interruption is the marker it leaves behind.
    }
    final String harvestedMarker = interrupted.get("__kryptey_storage_schema");
    assertNotNull("this test needs the in-progress marker to exist, or it proves nothing about "
        + "replaying it", harvestedMarker);
    interrupted.failAfterWrites = -1;

    // The user's real install completes normally on another run.
    final InMemoryKeyValueStore raw = legacyStore();
    new EncryptedKeyValueStore(raw, box).migrateToEncrypted();
    assertTrue(box.legacyMigrationIsSealed());

    // Now the composed file: the user's genuine sealed rows, the harvested marker, one cleartext
    // row. No row goes missing at any point.
    final InMemoryKeyValueStore composed = new InMemoryKeyValueStore();
    composed.put("UNIQUE_USER_ID", raw.get("UNIQUE_USER_ID"));
    composed.put("__kryptey_storage_schema", harvestedMarker);
    composed.put("PROTOCOL_STORE", ATTACKER_STORE);

    try {
      new EncryptedKeyValueStore(composed, box).migrateToEncrypted();
      fail("a replayed in-progress marker re-opened the conversion, so the attacker's cleartext "
          + "was sealed under the user's master key - with the file never losing a row");
    } catch (final StorageCryptoException expected) {
      assertTrue("the refusal must say why: " + expected.getMessage(),
          expected.getMessage().contains("already completed"));
    }
  }

  /** And a device that never had a 0.1.5 store is sealed from its first run. */
  @Test
  public void afreshInstallIsSealedWithoutEverConverting() throws Exception {
    final SealedBox box = new SealedBox();
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(new InMemoryKeyValueStore(), box);
    store.put("PROTOCOL_STORE", VICTIM_STORE);
    store.migrateToEncrypted();

    assertTrue("an install with no legacy data must still record the seal - otherwise the one "
        + "device that never needed the conversion is the one that stays open to it forever",
        box.legacyMigrationIsSealed());

    final InMemoryKeyValueStore injected = new InMemoryKeyValueStore();
    injected.put("PROTOCOL_STORE", ATTACKER_STORE);
    try {
      new EncryptedKeyValueStore(injected, box).migrateToEncrypted();
      fail("cleartext was sealed on a device that never had a legacy store");
    } catch (final StorageCryptoException expected) {
      assertFalse(expected.getMessage().isEmpty());
    }
  }
}
