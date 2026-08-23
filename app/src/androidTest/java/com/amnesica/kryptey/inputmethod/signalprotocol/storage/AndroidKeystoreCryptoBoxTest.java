package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Instrumentation tests for the Keystore-backed crypto box. These require a device or emulator.
 *
 * <p>They exist because JVM tests provably cannot substitute for them. {@code JvmGcmCryptoBox} uses
 * a SunJCE key, which accepts a caller-supplied IV; an Android Keystore key rejects one with
 * {@code CALLER_NONCE_PROHIBITED}. That single difference already hid a bug that would have made
 * {@code seal()} throw on every call on every real device while all 17 JVM crypto tests passed —
 * and the original self-test missed it too, because it used a call shape the production code never
 * uses.
 *
 * <p>So the theme here is: exercise the real call path, on a real Keystore key, and assert the
 * things that only differ on hardware.
 *
 * <p>Run with: {@code ./gradlew connectedDebugAndroidTest}
 */
@RunWith(AndroidJUnit4.class)
public class AndroidKeystoreCryptoBoxTest {

  private static final byte[] AAD =
      "kryptey/storage/v2/PROTOCOL_STORE".getBytes(StandardCharsets.UTF_8);

  private Context context;

  private static byte[] utf8(final String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    // Start from a known state; these tests create and destroy the real master key alias.
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
  }

  @After
  public void tearDown() {
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
  }

  /**
   * The regression test for the bug that motivated this whole file: a Keystore key forbids a
   * caller-supplied IV, so {@code seal()} must let the provider generate the nonce.
   */
  @Test
  public void sealAndOpenRoundTripAgainstARealKeystoreKey() throws Exception {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
    final byte[] plaintext = utf8("{\"identityKeyPair\":\"SECRET\"}");

    assertArrayEquals(plaintext, box.open(box.seal(plaintext, AAD), AAD));
  }

  @Test
  public void theEnvelopeCarriesTheExpectedNonceWidth() throws Exception {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
    final byte[] sealed = box.seal(utf8("x"), AAD);

    assertTrue(sealed.length > 1 + GcmCryptoBox.NONCE_BYTES);
    // seal() rejects a provider that picks a different nonce length; prove the real provider agrees.
    assertTrue(sealed[0] == GcmCryptoBox.VERSION);
  }

  /** On hardware the master key is long-lived while box instances are not. */
  @Test
  public void noncesDoNotRepeatAcrossInstancesSharingTheKeystoreKey() throws Exception {
    final Set<String> nonces = new HashSet<>();
    for (int instance = 0; instance < 16; instance++) {
      final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
      for (int i = 0; i < 8; i++) {
        final byte[] sealed = box.seal(utf8("restart " + instance), AAD);
        final byte[] nonce = Arrays.copyOfRange(sealed, 1, 1 + GcmCryptoBox.NONCE_BYTES);
        assertTrue("Keystore nonce repeated across instances", nonces.add(Arrays.toString(nonce)));
      }
    }
  }

  @Test
  public void theSameAliasIsReusedAcrossInstances() throws Exception {
    final byte[] sealed = new AndroidKeystoreCryptoBox(context, false).seal(utf8("payload"), AAD);
    // A second instance must resolve the same alias, not mint a new key.
    assertArrayEquals(utf8("payload"),
        new AndroidKeystoreCryptoBox(context, true).open(sealed, AAD));
  }

  @Test
  public void aadIsEnforcedByTheKeystoreKeyToo() throws Exception {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
    final byte[] sealed = box.seal(utf8("payload"), AAD);

    assertThrows(StorageCryptoException.class,
        () -> box.open(sealed, utf8("kryptey/storage/v2/CONTACTS")));
  }

  @Test
  public void tamperedCiphertextIsRejected() throws Exception {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, false);
    final byte[] sealed = box.seal(utf8("the quick brown fox"), AAD);
    sealed[sealed.length - 1] ^= 0x01;

    assertThrows(StorageCryptoException.class, () -> box.open(sealed, AAD));
  }

  /**
   * The guard that stands between a lost Keystore key and a destroyed identity: with data at stake
   * and no alias, it must refuse rather than mint a replacement that could not decrypt anything.
   */
  @Test
  public void refusesToGenerateAReplacementKeyWhenDataIsAtStake() {
    final AndroidKeystoreCryptoBox box = new AndroidKeystoreCryptoBox(context, true);

    final StorageCryptoException e =
        assertThrows(StorageCryptoException.class, () -> box.seal(utf8("x"), AAD));
    assertTrue("wrong failure: " + e.getMessage(),
        e.getMessage().contains("refusing to generate"));
  }

  @Test
  public void generatesFreelyWhenThereIsNothingAtStake() throws Exception {
    assertNotNull(new AndroidKeystoreCryptoBox(context, false).seal(utf8("x"), AAD));
  }

  /** After a wipe the old ciphertext must be permanently unreadable, not silently re-readable. */
  @Test
  public void destroyingTheMasterKeyOrphansExistingCiphertext() throws Exception {
    final byte[] sealed = new AndroidKeystoreCryptoBox(context, false).seal(utf8("secret"), AAD);

    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();

    final AndroidKeystoreCryptoBox afterWipe = new AndroidKeystoreCryptoBox(context, false);
    assertThrows("old ciphertext opened under a freshly generated key",
        StorageCryptoException.class, () -> afterWipe.open(sealed, AAD));
  }

  @Test
  public void aFreshKeyCannotOpenAnotherKeysEnvelope() throws Exception {
    final byte[] sealed = new AndroidKeystoreCryptoBox(context, false).seal(utf8("secret"), AAD);
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();

    final AndroidKeystoreCryptoBox regenerated = new AndroidKeystoreCryptoBox(context, false);
    regenerated.seal(utf8("forces key generation"), AAD);

    assertThrows(StorageCryptoException.class, () -> regenerated.open(sealed, AAD));
  }

  /** Exercises the whole stack against a real Keystore key and real SharedPreferences. */
  @Test
  public void theEncryptedStoreWorksEndToEndOnDevice() throws Exception {
    final android.content.SharedPreferences preferences =
        context.getSharedPreferences("kryptey-instrumentation", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    final KeyValueStore raw = new SharedPreferencesKeyValueStore(preferences);
    final EncryptedKeyValueStore store =
        new EncryptedKeyValueStore(raw, new AndroidKeystoreCryptoBox(context, false));

    store.put("PROTOCOL_STORE", "{\"identityKeyPair\":\"ONDEVICE\"}");
    assertEquals("{\"identityKeyPair\":\"ONDEVICE\"}", store.get("PROTOCOL_STORE"));

    final String onDisk = preferences.getString("PROTOCOL_STORE", null);
    assertNotNull(onDisk);
    assertFalse("cleartext reached SharedPreferences on device", onDisk.contains("ONDEVICE"));

    preferences.edit().clear().commit();
  }

  private static void assertEquals(final String expected, final String actual) {
    org.junit.Assert.assertEquals(expected, actual);
  }
}
