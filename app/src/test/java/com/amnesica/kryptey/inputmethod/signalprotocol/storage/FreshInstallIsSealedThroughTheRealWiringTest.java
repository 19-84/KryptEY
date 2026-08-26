package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The seal is written on a fresh install, checked through the wiring production actually uses.
 *
 * <p>Written because the first version of this claim was tested by calling
 * {@code migrateToEncrypted()} directly on a store that had nothing to migrate — a call
 * {@code StorageHelper} cannot make, because it gates that method on {@code needsMigration()} and a
 * fresh install never needs one. The test passed, the property was false, and every device that had
 * no 0.1.5 store to convert was left with exactly one free laundering: the population the commit
 * message singled out as protected.
 *
 * <p>So this drives {@link StorageHelper} end to end. The distinction it exists to hold is between
 * "the store would refuse this if asked" and "the app asks".
 */
@RunWith(RobolectricTestRunner.class)
public class FreshInstallIsSealedThroughTheRealWiringTest {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
  private static final String STORE_KEY = String.valueOf(ProtocolIdentifier.PROTOCOL_STORE);
  private static final String ATTACKER_STORE =
      "{\"identityKeyStore\":{\"identityKeyPair\":\"MINE\"}}";

  private Context context;
  private SharedPreferences accountFile;

  /** One seal for the whole test, the way one Keystore alias serves one device. */
  private boolean sealed;

  private StorageHelper.CryptoBoxFactory box() {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
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
    };
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    accountFile = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    accountFile.edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    sealed = false;
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** A brand-new install that stores an account, exactly as the app does on first use. */
  private Account freshInstall() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertTrue("precondition: the account must reach disk",
        new StorageHelper(context, box()).storeAllInformationInSharedPreferences(account));
    return account;
  }

  @Test
  public void afreshInstallSealsWithoutEverMigrating() throws Exception {
    freshInstall();

    // The load path is what production runs on every raise, and it is where the seal must happen.
    assertNotNull("precondition: the store must load back",
        new StorageHelper(context, box()).getAccountFromSharedPreferences());

    assertTrue("a device that never had a 0.1.5 store must still record that it has nothing left "
        + "to convert. Sealing only from inside the migration means this device never seals, "
        + "because it never migrates - and it is then the one device left open forever.", sealed);
  }

  /**
   * And the sealing is what stops the laundering, through the same wiring.
   *
   * <p>The attacker needs no Keystore access: write the app's own private file, raise the keyboard
   * once, take the sealed row.
   */
  @Test
  public void cleartextInjectedAfterAfreshInstallIsNotSealed() throws Exception {
    freshInstall();
    assertNotNull(new StorageHelper(context, box()).getAccountFromSharedPreferences());

    // The attacker replaces the whole file with one cleartext row of their own.
    final SharedPreferences.Editor editor = accountFile.edit();
    editor.clear();
    editor.putString(STORE_KEY, ATTACKER_STORE);
    editor.commit();

    // One raise.
    final Account loaded = new StorageHelper(context, box()).getAccountFromSharedPreferences();

    final String after = accountFile.getString(STORE_KEY, null);
    assertNotNull("the attacker's row must still be there to inspect", after);
    assertTrue("the app sealed attacker-authored cleartext under the real master key. Sealed, it "
            + "is indistinguishable from the user's own data, so restoring a snapshot of the "
            + "original file with this one row swapped in puts the account on the attacker's "
            + "identity key with nothing missing. Row is now: " + after,
        after.equals(ATTACKER_STORE));
    assertNull("and the load must refuse rather than hand back an account built from it", loaded);
  }
}
