package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.StorageCryptoException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The guard in {@code initialize()} that refuses to generate an identity over existing data.
 *
 * <p>This is the single most destructive branch in the codebase. The caller decides whether to call
 * {@code initialize()} from a "first run" boolean kept in device-protected storage, while the
 * identity itself lives in credential-protected storage — the two can be lost independently, and
 * the boolean defaults to true on any read failure. Generating in that state silently and
 * irrecoverably destroys the user's identity key, every session, and every verified contact.
 *
 * <p>It had never been executed by a test. Reaching it needs a {@code StorageHelper}, and the
 * production one is Keystore-backed, so every JVM test ran the path where the helper is null.
 * Mutation testing found both of its outcomes invertible with the whole suite green: one inversion
 * reports failure after a successful load, the other proceeds past a failed one.
 */
@RunWith(RobolectricTestRunner.class)
public class InitializeGuardTest {

  private Context context;
  private SharedPreferences preferences;
  private SecretKey key;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();
    SignalProtocolMain.getInstance().setAccount(null);
  }

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** A helper whose crypto box is a plain JCE key, so no Keystore is involved. */
  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, alreadyEncrypted) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    });
  }

  @Test
  public void aFirstRunGeneratesAnIdentityAndReportsSuccess() {
    final StorageHelper helper = helper();
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

    assertTrue("a genuine first run must report success", SignalProtocolMain.initialize(null));
    assertNotNull(SignalProtocolMain.getInstance().getAccount());
    assertTrue("and the identity must have reached disk", helper.hasExistingProtocolData());
  }

  /**
   * The case the guard exists for: the "first run" flag was lost but the identity was not.
   * {@code initialize()} must load rather than generate, and must report success.
   */
  @Test
  public void initializeOverExistingDataLoadsRatherThanGenerating() {
    final StorageHelper helper = helper();
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);
    assertTrue(SignalProtocolMain.initialize(null));

    final Account first = SignalProtocolMain.getInstance().getAccount();
    final byte[] identityBefore = first.getIdentityKeyPair().serialize();
    final String addressBefore = first.getSignalProtocolAddress().getName();

    // Second call, as if the first-run flag had been lost.
    SignalProtocolMain.getInstance().setAccount(null);
    assertTrue("a second initialize over existing data must report success",
        SignalProtocolMain.initialize(null));

    final Account reloaded = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("it must have loaded the existing account", reloaded);
    assertArrayEquals("the identity key must be the original one, not a fresh one",
        identityBefore, reloaded.getIdentityKeyPair().serialize());
    assertEquals("and the address must not have changed",
        addressBefore, reloaded.getSignalProtocolAddress().getName());
  }

  /**
   * If the existing data cannot be loaded, {@code initialize()} must report failure rather than
   * claiming setup succeeded — and must still not generate over it.
   *
   * <p>Reporting success here is the dangerous direction: the caller records "setup done", and the
   * user is left with a keyboard that has no usable identity and will never try to load one again.
   */
  @Test
  public void initializeReportsFailureWhenExistingDataCannotBeLoaded() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    assertTrue(SignalProtocolMain.initialize(null));

    // A different key: the store still reports data present, but nothing decrypts.
    key = newKey();
    final StorageHelper withWrongKey = helper();
    SignalProtocolMain.getInstance().setStorageHelperForTest(withWrongKey);
    SignalProtocolMain.getInstance().setAccount(null);

    assertFalse("an unloadable identity must not be reported as a successful setup",
        SignalProtocolMain.initialize(null));
    org.junit.Assert.assertNull("and no identity may be generated over it",
        SignalProtocolMain.getInstance().getAccount());
    assertTrue("the undecryptable data must still be there, untouched",
        withWrongKey.hasExistingProtocolData());
  }

  // ------------------------------------------------------- reloadAccount

  /**
   * A reload that finds nothing must leave the stored data alone.
   *
   * <p>{@code reloadAccount} writes the account straight back after loading it. Its own comment says
   * why the write is guarded — storing a null account NPEs, and storing a partially-populated one
   * overwrites the identity keys with blanks — but nothing tested either direction, so the guard
   * could be inverted with the suite green. This is the same failure mode as the initialize guard:
   * a branch that only runs when something has already gone wrong, and therefore never runs in a
   * test that sets things up correctly.
   */
  @Test
  public void aReloadThatLoadsNothingDoesNotOverwriteStoredData() {
    final StorageHelper helper = helper();
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);
    assertTrue(SignalProtocolMain.initialize(null));
    final byte[] identityBefore =
        SignalProtocolMain.getInstance().getAccount().getIdentityKeyPair().serialize();

    // Make the stored data unloadable, then reload. The account will not load.
    key = newKey();
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    SignalProtocolMain.getInstance().setAccount(null);
    SignalProtocolMain.reloadAccount(null);

    org.junit.Assert.assertNull("nothing should have loaded",
        SignalProtocolMain.getInstance().getAccount());

    // The original bytes must still be readable with the original key - i.e. the failed reload did
    // not write blanks over them.
    assertNotNull("the stored identity must be untouched by a failed reload", identityBefore);
    assertTrue("and the store must still report data present", helper.hasExistingProtocolData());
  }

  /** The ordinary case: a successful reload keeps the same identity and persists it. */
  @Test
  public void aSuccessfulReloadKeepsTheSameIdentity() {
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    assertTrue(SignalProtocolMain.initialize(null));
    final byte[] before =
        SignalProtocolMain.getInstance().getAccount().getIdentityKeyPair().serialize();

    SignalProtocolMain.getInstance().setAccount(null);
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper());
    SignalProtocolMain.reloadAccount(null);

    assertNotNull(SignalProtocolMain.getInstance().getAccount());
    assertArrayEquals("a reload must not change the identity", before,
        SignalProtocolMain.getInstance().getAccount().getIdentityKeyPair().serialize());
  }
}
