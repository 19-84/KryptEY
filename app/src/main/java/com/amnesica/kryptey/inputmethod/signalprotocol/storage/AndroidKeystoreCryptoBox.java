package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Supplies {@link GcmCryptoBox} with an AES-256 master key held in the Android Keystore, so the key
 * bytes never enter the app process.
 *
 * <p>Scope of that claim: it covers the <em>master key</em>. It does not cover cleartext written by
 * an earlier version of the app — a SharedPreferences commit renames the old file aside before
 * rewriting, and an unlinked file's blocks are not overwritten, so pre-migration cleartext can
 * survive in freed blocks until the filesystem reclaims them. An app cannot shred those.
 *
 * <p>Key generation walks a preference ladder, strongest first, self-testing each newly generated
 * candidate before accepting it. A key loaded from an existing alias is NOT self-tested:
 *
 * <ol>
 *   <li>StrongBox (dedicated secure element) + unlocked-device-required
 *   <li>TEE + unlocked-device-required
 *   <li>StrongBox alone
 *   <li>TEE alone
 * </ol>
 *
 * <p>{@code setUnlockedDeviceRequired} is only attempted when the device actually has a secure lock
 * screen; requesting it otherwise yields a key that can never be used. Vendor StrongBox
 * implementations are notoriously uneven, which is why each candidate is exercised with a real
 * encrypt/decrypt before being trusted rather than inferred from
 * {@code FEATURE_STRONGBOX_KEYSTORE}.
 */
public final class AndroidKeystoreCryptoBox extends GcmCryptoBox {

  private static final String TAG = AndroidKeystoreCryptoBox.class.getSimpleName();

  private static final String PROVIDER = "AndroidKeyStore";
  private static final String KEY_ALIAS = "kryptey.storage.master";

  private final Context context;
  /** Whether the caller has data on disk that a newly generated key could not possibly decrypt. */
  private final boolean hasExistingData;

  private volatile SecretKey cached;

  public AndroidKeystoreCryptoBox(final Context context, final boolean hasExistingData) {
    this.context = context.getApplicationContext();
    this.hasExistingData = hasExistingData;
  }

  @Override
  protected SecretKey key() throws StorageCryptoException {
    final SecretKey local = cached;
    if (local != null) return local;
    synchronized (this) {
      if (cached == null) cached = resolve();
      return cached;
    }
  }

  private SecretKey resolve() throws StorageCryptoException {
    final SecretKey existing = load();
    if (existing != null) return existing;

    // The alias is gone but ciphertext remains. This is the dangerous case: silently generating a
    // replacement would make every stored blob permanently undecryptable while looking like a
    // successful start-up, and the caller would then overwrite the user's identity keys with a
    // fresh account. Refuse, and let the caller decide.
    if (hasExistingData) {
      throw new StorageCryptoException(
          "Keystore master key is absent but encrypted data is present; refusing to generate a "
              + "replacement key that could not decrypt it");
    }

    final boolean deviceSecure = isDeviceSecure();
    StorageCryptoException last = null;
    for (final KeyCandidate candidate1 : candidateLadder(deviceSecure)) {
      {
        final boolean requireUnlocked = candidate1.requireUnlocked;
        final boolean strongBox = candidate1.strongBox;
        try {
          final SecretKey candidate = generate(strongBox, requireUnlocked);
          selfTestViaRealCallPath(candidate);
          Log.i(TAG, "master key created (strongBox=" + strongBox
              + ", unlockedDeviceRequired=" + requireUnlocked + ")");
          return candidate;
        } catch (Exception e) {
          last = new StorageCryptoException("key candidate rejected (strongBox=" + strongBox
              + ", unlockedDeviceRequired=" + requireUnlocked + ")", e);
          // A half-created alias would shadow the next attempt, so clear it before retrying.
          deleteAlias();
        }
      }
    }
    throw new StorageCryptoException("no usable Keystore configuration on this device", last);
  }

  /**
   * One rung of the key ladder: which protections to ask the Keystore for.
   *
   * <p>Extracted as data so the ORDER can be tested. The order is the security property here - ask
   * for the strongest protections first and only degrade when the device refuses - and it was
   * previously expressed as nested loops welded to the Keystore calls, so nothing could check it
   * without hardware. The Keystore calls themselves still need a device; this does not pretend
   * otherwise, it just stops the ordering from being untestable too.
   */
  static final class KeyCandidate {
    final boolean strongBox;
    final boolean requireUnlocked;

    KeyCandidate(final boolean strongBox, final boolean requireUnlocked) {
      this.strongBox = strongBox;
      this.requireUnlocked = requireUnlocked;
    }

    @Override
    public String toString() {
      return "strongBox=" + strongBox + ",requireUnlocked=" + requireUnlocked;
    }
  }

  /**
   * The candidates to try, strongest first.
   *
   * <p>{@code setUnlockedDeviceRequired} is only meaningful on a device with a secure lock screen,
   * so on one without it those rungs are skipped rather than attempted and failed - attempting them
   * would delete and recreate the alias for nothing.
   */
  static java.util.List<KeyCandidate> candidateLadder(final boolean deviceSecure) {
    final java.util.List<KeyCandidate> ladder = new java.util.ArrayList<>();
    for (final boolean requireUnlocked : new boolean[] {true, false}) {
      if (requireUnlocked && !deviceSecure) continue;
      for (final boolean strongBox : new boolean[] {true, false}) {
        ladder.add(new KeyCandidate(strongBox, requireUnlocked));
      }
    }
    return ladder;
  }

  private SecretKey load() throws StorageCryptoException {
    try {
      final KeyStore ks = KeyStore.getInstance(PROVIDER);
      ks.load(null);
      return (SecretKey) ks.getKey(KEY_ALIAS, null);
    } catch (Exception e) {
      throw new StorageCryptoException("could not read the Android Keystore", e);
    }
  }

  private boolean isDeviceSecure() {
    try {
      final KeyguardManager km =
          (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
      return km != null && km.isDeviceSecure();
    } catch (Exception e) {
      return false;
    }
  }

  private SecretKey generate(final boolean strongBox, final boolean requireUnlocked)
      throws Exception {
    final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
        PROVIDER);
    final KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (requireUnlocked) spec.setUnlockedDeviceRequired(true);
      if (strongBox) spec.setIsStrongBoxBacked(true);
    } else if (needsApi28(strongBox, requireUnlocked)) {
      throw new IllegalStateException("StrongBox / unlocked-device-required need API 28");
    }

    generator.init(spec.build());
    return generator.generateKey();
  }

  /**
   * Whether this candidate asks for anything only API 28 can grant.
   *
   * <p>Extracted so the refusal can be tested. It has to be a refusal, not a silent downgrade: if a
   * pre-28 device quietly produced a key without the protections requested, the ladder's first rung
   * would report success and the log would claim StrongBox and unlocked-device-required for a key
   * that had neither. Failing sends the ladder down to a candidate that is honest about what it is.
   */
  static boolean needsApi28(final boolean strongBox, final boolean requireUnlocked) {
    return strongBox || requireUnlocked;
  }

  /**
   * Proves the key can round-trip <em>through this class's own seal/open path</em> before we commit
   * user data to it.
   *
   * <p>Going through {@link GcmCryptoBox#seal} rather than hand-rolling a Cipher matters: an
   * earlier version of this self-test called {@code init(ENCRYPT_MODE, key)} directly, which is the
   * one form a Keystore key accepts, and so it passed while certifying keys that could never
   * satisfy a real {@code seal()}. A self-test that exercises a different call shape from
   * production proves nothing.
   */
  private void selfTestViaRealCallPath(final SecretKey candidate) throws Exception {
    final GcmCryptoBox probe = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return candidate;
      }
    };
    final byte[] sample = "kryptey-keystore-selftest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final byte[] aad = "kryptey/storage/selftest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (!java.util.Arrays.equals(sample, probe.open(probe.seal(sample, aad), aad))) {
      throw new IllegalStateException("Keystore key failed a seal/open round trip");
    }
  }

  /**
   * Destroys the master key, rendering every blob it protected permanently unreadable. Intended for
   * a wipe; it is not a way to recover from a decryption failure.
   */
  public synchronized void destroyMasterKey() {
    cached = null;
    deleteAlias();
  }

  private void deleteAlias() {
    try {
      final KeyStore ks = KeyStore.getInstance(PROVIDER);
      ks.load(null);
      ks.deleteEntry(KEY_ALIAS);
    } catch (Exception e) {
      Log.w(TAG, "could not delete Keystore alias", e);
    }
  }
}
