package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;

/**
 * The anti-laundering seal, against a real Android Keystore.
 *
 * <p>A mutation sweep found this was the one control in the storage layer enforced by nothing.
 * {@code legacyMigrationIsSealed} could be made to always answer false, {@code sealLegacyMigration}
 * could have its {@code generateKey()} deleted, and the fail-closed catch could be inverted - all
 * three at once, with 1455 JVM tests green. Every test that exercised the seal substituted a fake
 * box with a {@code boolean sealed} field, including the two whose names say they drive the real
 * wiring; and no instrumentation test named the alias at all. So what was measured was
 * {@code EncryptedKeyValueStore}'s use of the answer, never the Keystore code that produces it.
 *
 * <p>That matters because the seal is the whole answer to the laundering primitive: an attacker
 * with the data directory empties the store, plants a cleartext value of their own authorship, lets
 * one keyboard raise seal it under the real master key, then restores their snapshot with that
 * value swapped in. The refusal fires only if the box says this device has already converted. A
 * rename of the alias, a removed {@code generateKey()}, or an inverted catch retracts it silently -
 * nothing goes missing, so nothing looks wrong.
 *
 * <p><b>Cleanup is the reason this file can exist.</b> The seal alias is device-global and
 * production never removes it, so a class that seals and leaves it behind makes every later "fresh
 * device" case pass while measuring nothing - including the cases below. It is therefore destroyed
 * on both sides of every test, alongside the master key. {@code destroyMasterKey} deliberately does
 * NOT do this: the ladder clears half-created master keys between rungs through the same call, so
 * widening it would unseal the device on every step-down.
 *
 * <p>Runs only on a device or emulator, via {@code tools/test-on-emulator}.
 */
@RunWith(AndroidJUnit4.class)
public class TheMigrationSealIsRealOnDeviceTest {

  private Context context;

  /**
   * The seal alias, which {@code destroyMasterKey} deliberately leaves alone.
   *
   * <p>Both hooks call {@code destroyMasterKey} literally rather than through a wrapper. That is
   * not style: {@code InstrumentationTestsCleanUpTheKeystoreTest} reads the 400 characters after
   * each annotation looking for that call, so hiding it in a helper leaves the guard unable to see
   * it - and it caught this file doing exactly that.
   */
  private static void deleteSealAlias() throws Exception {
    final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    keyStore.deleteEntry(AndroidKeystoreCryptoBox.MIGRATION_SEALED_ALIAS);
  }

  private static boolean aliasExists(final String alias) throws Exception {
    final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    return keyStore.containsAlias(alias);
  }

  private AndroidKeystoreCryptoBox box() {
    return new AndroidKeystoreCryptoBox(context, false);
  }

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
    deleteSealAlias();
  }

  @After
  public void tearDown() throws Exception {
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
    deleteSealAlias();
  }

  /**
   * A device that has never converted must read as not sealed.
   *
   * <p>The direction that matters least on its own and most as a precondition: every case below is
   * about a transition out of this state, and each would pass trivially if the device were already
   * sealed when the test began. This is what makes the cleanup above load-bearing rather than
   * tidy.
   */
  @Test
  public void afreshDeviceReadsAsNotSealed() throws Exception {
    assertFalse("precondition: the wipe must have removed the alias",
        aliasExists(AndroidKeystoreCryptoBox.MIGRATION_SEALED_ALIAS));
    assertFalse("a device that has never run the cleartext conversion must say so; answering true "
        + "here refuses a legitimate 0.1.5 upgrade", box().legacyMigrationIsSealed());
  }

  /**
   * Sealing writes something a different box instance can see.
   *
   * <p>Read back through a NEW box, which is the assertion. A field on the instance would satisfy
   * "seal then ask"; only the Keystore satisfies "seal, throw the object away, ask again" - and the
   * production caller is a fresh {@code AndroidKeystoreCryptoBox} on every keyboard raise.
   */
  @Test
  public void sealingIsVisibleToAlaterBoxOnTheSameDevice() throws Exception {
    assertFalse("precondition: not sealed yet", box().legacyMigrationIsSealed());

    box().sealLegacyMigration();

    assertTrue("the seal must land in the Keystore, not on the object that wrote it",
        aliasExists(AndroidKeystoreCryptoBox.MIGRATION_SEALED_ALIAS));
    assertTrue("and a box built later - which is what every keyboard raise builds - must see it. "
        + "Without this the refusal is re-armed on each raise and the attacker simply waits for "
        + "the next one", box().legacyMigrationIsSealed());
  }

  /**
   * Sealing an already-sealed device is a no-op, not a second key.
   *
   * <p>{@code ensureLegacyMigrationSealed} runs unconditionally on every raise, so this path is
   * taken thousands of times per install. It must not accumulate Keystore entries, and it must not
   * throw - the caller treats a failure here as non-fatal but logs it loudly, and a log line on
   * every raise is how a real failure stops being noticed.
   */
  @Test
  public void sealingAnAlreadySealedDeviceIsHarmless() throws Exception {
    box().sealLegacyMigration();
    assertTrue("precondition: sealed once", box().legacyMigrationIsSealed());

    box().sealLegacyMigration();
    box().sealLegacyMigration();

    assertTrue("repeated seals must leave the device sealed", box().legacyMigrationIsSealed());
  }

  /**
   * Destroying the master key must NOT unseal the device.
   *
   * <p>This pins a decision, not just behaviour. The tempting simplification is to have
   * {@code destroyMasterKey} clear both aliases so instrumentation gets one clean call. It clears
   * the master key alias through the same private helper the ladder uses between rungs - so a box
   * that steps down from a lock-bound key to an unbound one would also unseal the device, handing
   * back exactly the one free cleartext conversion the alias exists to spend. The wipe helper in
   * this file removes both deliberately and separately.
   */
  @Test
  public void destroyingTheMasterKeyLeavesTheDeviceSealed() throws Exception {
    box().sealLegacyMigration();
    assertTrue("precondition: sealed", box().legacyMigrationIsSealed());

    box().destroyMasterKey();

    assertTrue("destroying the master key must leave the seal alone. Clearing it here would unseal "
            + "the device on every ladder step-down, because the ladder clears half-created master "
            + "keys through the same call",
        box().legacyMigrationIsSealed());
    assertTrue("and the alias itself must still be there",
        aliasExists(AndroidKeystoreCryptoBox.MIGRATION_SEALED_ALIAS));
  }

  /**
   * The property the seal exists for, end to end on real hardware: cleartext planted after the
   * device has converted is refused rather than sealed.
   *
   * <p>The JVM has this case, and it is a good test - but it drives a fake box whose seal is a
   * boolean field, so it measures {@code EncryptedKeyValueStore}'s use of the answer and not the
   * Keystore that answers. This runs the same chain with the real box: seal the device the way a
   * fresh install does, plant an attacker's cleartext value, and require that the store refuses to
   * come up rather than sealing it under the real master key.
   */
  @Test
  public void cleartextPlantedAfterTheDeviceHasConvertedIsRefused() throws Exception {
    final android.content.SharedPreferences file =
        context.getSharedPreferences("seal_probe", Context.MODE_PRIVATE);
    file.edit().clear().commit();

    final AndroidKeystoreCryptoBox realBox = box();
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(new SharedPreferencesKeyValueStore(file), realBox);
    store.ensureLegacyMigrationSealed();
    assertTrue("precondition: a fresh install seals without ever converting", realBox
        .legacyMigrationIsSealed());

    final String planted = "{\"attacker\":\"identity store of my choosing\"}";
    file.edit().putString("PROTOCOL_STORE", planted).commit();

    final EncryptedKeyValueStore reopened =
        new EncryptedKeyValueStore(new SharedPreferencesKeyValueStore(file), box());
    try {
      reopened.migrateToEncrypted();
      throw new AssertionError("cleartext planted after this device converted was accepted for "
          + "conversion. It is now sealed under the real master key, and the AAD binds the key "
          + "name rather than the file - so it opens in the account's store as the user's identity");
    } catch (final StorageCryptoException expected) {
      // Refusing is the point.
    }

    assertEquals("and the planted value must be left exactly as found, not sealed on the "
        + "attacker's behalf", planted, file.getString("PROTOCOL_STORE", null));
    file.edit().clear().commit();
  }
}
