package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * What {@code StorageHelper} tells the Keystore box about whether there is data at stake.
 *
 * <p>{@code AndroidKeystoreCryptoBox} refuses to mint a replacement master key when the alias is
 * gone but ciphertext remains - the guard that stands between a lost Keystore key and a fresh
 * identity written over the user's own. Its input is the single boolean {@code StorageHelper}
 * hands {@code mCryptoBoxFactory.create(context, alreadyEncrypted)}.
 *
 * <p>Both candidate predicates for that boolean are tested in {@link StorageSchemaStateTest}, and
 * they disagree. {@code isEncrypted} looks only for the schema marker.
 * {@code hasEncryptedData} also looks for envelope-shaped values, and its javadoc says why:
 * "an interrupted migration leaves real ciphertext with the marker deliberately unset, and
 * concluding 'nothing at stake' there would let a Keystore-backed box mint a replacement key and
 * orphan what had already been converted". {@code isEncrypted}'s own javadoc points at it -
 * "Callers deciding whether data is at stake should prefer {@code hasEncryptedData()}".
 *
 * <p>The call site used {@code isEncrypted}, and {@code hasEncryptedData} had no production caller
 * at all: one production method, five references, every one of them from a test. Measured at
 * 32df444 - replacing the whole expression with a constant {@code false}, so the refusal is
 * disarmed on every device, left the suite at 854 tests and 0 failures. The predicates were pinned;
 * the wiring between them and the box was pinned by nothing.
 *
 * <p>These tests assert the argument the production call site passes, not what either predicate
 * returns.
 */
@RunWith(RobolectricTestRunner.class)
public class KeystoreDataAtStakeWiringTest {

  private static final String PROTOCOL_STORE = String.valueOf(ProtocolIdentifier.PROTOCOL_STORE);
  private static final String IDENTITY =
      "{\"identityKeyStore\":{\"identityKeyPair\":\"BASE64PRIVATEKEY\"}}";

  private final List<Boolean> toldDataIsAtStake = new ArrayList<>();

  private Context context;
  private SharedPreferences preferences;
  private SecretKey key;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = JvmGcmCryptoBox.newKey();
    toldDataIsAtStake.clear();
  }

  /** A helper whose box factory records the one boolean the refusal depends on. */
  private StorageHelper recordingHelper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> {
      toldDataIsAtStake.add(hasExistingData);
      return new JvmGcmCryptoBox(key);
    });
  }

  private KeyValueStore raw() {
    return new SharedPreferencesKeyValueStore(preferences);
  }

  private void sealTheIdentity() throws Exception {
    new EncryptedKeyValueStore(raw(), new JvmGcmCryptoBox(key)).put(PROTOCOL_STORE, IDENTITY);
  }

  /** The ordinary case, which already worked - so a failure below is about the unmarked store. */
  @Test
  public void amarkedStoreTellsTheBoxThereIsDataAtStake() throws Exception {
    sealTheIdentity();
    assertTrue("precondition: a completed write leaves the schema marker behind",
        EncryptedKeyValueStore.isEncrypted(raw()));

    recordingHelper().storageState();

    assertEquals("the box must be built exactly once, from the production call site",
        1, toldDataIsAtStake.size());
    assertTrue("a marked store holds the user's identity under the master key",
        toldDataIsAtStake.get(0));
  }

  /**
   * The write completed; the marker did not.
   *
   * <p>{@code EncryptedKeyValueStore.put} and {@code putAll} seal the values, hand them to the
   * delegate, and only then write the completion marker - two separate durable commits. An IME is
   * killed routinely, and a kill between them leaves the identity key on disk as ciphertext with no
   * marker above it. {@code hasEncryptedData} was written for exactly this state and returns true
   * for it; the store the box is then handed must say so too, or the next start-up after the
   * Keystore key is invalidated mints a replacement over it.
   */
  @Test
  public void sealedValuesWithNoMarkerMustAlsoTellTheBoxThereIsDataAtStake() throws Exception {
    sealTheIdentity();
    preferences.edit().remove(EncryptedKeyValueStore.SCHEMA_KEY).commit();

    assertFalse("precondition: the marker is what did not reach disk",
        EncryptedKeyValueStore.isEncrypted(raw()));
    assertTrue("precondition: the identity key is still there, sealed",
        new EncryptedKeyValueStore(raw(), new JvmGcmCryptoBox(key)).hasEncryptedData());

    recordingHelper().storageState();

    assertEquals(1, toldDataIsAtStake.size());
    assertTrue("the marker is not the data. Ciphertext with no marker is still the user's "
            + "identity, and telling the box otherwise lets it mint a replacement master key over "
            + "it instead of refusing",
        toldDataIsAtStake.get(0));
  }
}
