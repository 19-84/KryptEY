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

  /**
   * Presence of this alias means the one-time cleartext conversion has already happened.
   *
   * <p>A separate alias rather than a flag inside the store, because the store is the thing the
   * attacker rewrites. Its key material is never used for anything — only whether it exists is
   * read — so it is generated with the cheapest parameters that will not prompt the user.
   *
   * <p>Package-private rather than private so instrumentation can name it for cleanup. That alias
   * is device-global and nothing in production ever removes it - deliberately, since removing it
   * restores exactly the one free laundering it exists to spend. {@code destroyMasterKey} is NOT
   * widened to clear it for the same reason and one more: the ladder calls the same
   * {@code deleteAlias} to clear a half-created master key between rungs, so a wider delete would
   * unseal the device on every step-down. An instrumentation class that seals and does not clean up
   * makes every later "fresh device" case pass without measuring anything, which is the state this
   * alias was in before {@code TheMigrationSealIsRealOnDeviceTest} existed.
   */
  static final String MIGRATION_SEALED_ALIAS = "kryptey.storage.migration.sealed";

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

  /**
   * The device-dependent operations {@link #resolve} needs, behind a seam.
   *
   * <p>Every one of these requires an Android Keystore, which needs hardware or a KVM runner, so
   * for as long as they were called directly the DECISIONS around them could not be executed by any
   * test either. The decisions are the security-critical part: refusing to generate a replacement
   * key when ciphertext already exists, walking the ladder strongest-first, and clearing a
   * half-created alias before the next attempt. This does not pretend the Keystore calls themselves
   * are tested - it stops them from making everything around them untestable too.
   */
  interface KeystoreOps {
    /** The existing master key, or null if the alias is absent. */
    SecretKey load() throws StorageCryptoException;

    boolean isDeviceSecure();

    /**
     * Whether the screen is locked RIGHT NOW, as distinct from whether a lock exists at all.
     *
     * <p>The two rungs that bind the key to the screen lock are certified by a real seal/open, and
     * that is exactly the operation such a key refuses while the device is locked. Without this
     * question the ladder reads "this device cannot" from an answer that only means "not right
     * now", and steps down permanently.
     */
    boolean isDeviceLocked();

    SecretKey generate(boolean strongBox, boolean requireUnlocked) throws Exception;

    /** Must exercise the same seal/open path production uses. */
    void selfTest(SecretKey candidate) throws Exception;

    void deleteAlias();
  }

  private SecretKey resolve() throws StorageCryptoException {
    return resolve(new KeystoreOps() {
      @Override
      public SecretKey load() throws StorageCryptoException {
        return AndroidKeystoreCryptoBox.this.load();
      }

      @Override
      public boolean isDeviceSecure() {
        return AndroidKeystoreCryptoBox.this.isDeviceSecure();
      }

      @Override
      public boolean isDeviceLocked() {
        return AndroidKeystoreCryptoBox.this.isDeviceLocked();
      }

      @Override
      public SecretKey generate(final boolean strongBox, final boolean requireUnlocked)
          throws Exception {
        return AndroidKeystoreCryptoBox.this.generate(strongBox, requireUnlocked);
      }

      @Override
      public void selfTest(final SecretKey candidate) throws Exception {
        selfTestViaRealCallPath(candidate);
      }

      @Override
      public void deleteAlias() {
        AndroidKeystoreCryptoBox.this.deleteAlias();
      }
    }, hasExistingData);
  }

  static SecretKey resolve(final KeystoreOps ops, final boolean hasExistingData)
      throws StorageCryptoException {
    final SecretKey existing = ops.load();
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

    final boolean deviceSecure = ops.isDeviceSecure();
    StorageCryptoException last = null;
    for (final KeyCandidate candidate1 : candidateLadder(deviceSecure)) {
      final boolean requireUnlocked = candidate1.requireUnlocked;
      final boolean strongBox = candidate1.strongBox;
      try {
        final SecretKey candidate = ops.generate(strongBox, requireUnlocked);
        ops.selfTest(candidate);
        Log.i(TAG, "master key created (strongBox=" + strongBox
            + ", unlockedDeviceRequired=" + requireUnlocked + ")");
        return candidate;
      } catch (Exception e) {
        last = new StorageCryptoException("key candidate rejected (strongBox=" + strongBox
            + ", unlockedDeviceRequired=" + requireUnlocked + ")", e);
        // A half-created alias would shadow the next attempt, so clear it before retrying.
        ops.deleteAlias();

        // "Cannot" and "cannot right now" are different answers, and the ladder read them the same.
        //
        // The self-test is a real seal/open - deliberately, so that a key which cannot actually be
        // used is never accepted - and a seal/open is precisely what an unlocked-device-required key
        // refuses while the device is locked. So generating the first key while the screen is locked
        // made both lock-bound rungs fail, and the ladder stepped down to one with no lock binding
        // AT ALL, permanently: the ladder is walked once, at generation, and never revisited. A
        // device that fully supports lock-bound storage would spend the rest of the install with
        // storage readable whenever the app runs, and the only trace was one log line.
        //
        // Refusing is safe HERE and nowhere else: this loop is reached only when the alias is absent
        // and hasExistingData is false, so there is nothing to lose by deferring. The caller already
        // renders the state - storage unreadable, "this clears when the device can read its own
        // storage again, usually after an unlock" - and the next raise on an unlocked device gets
        // the strong key. Regenerating or deleting to repair a weak key later would be the opposite
        // of this: destructive, with no way back.
        if (requireUnlocked && ops.isDeviceLocked()) {
          throw new StorageCryptoException("the device is locked, so a key bound to the lock cannot "
              + "be certified right now; deferring rather than falling back to one that is not "
              + "bound to it", last);
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

  private boolean isDeviceLocked() {
    try {
      final KeyguardManager km =
          (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
      return km != null && km.isDeviceLocked();
    } catch (Exception e) {
      // Unknown reads as locked, which defers rather than degrades. Deferring costs a raise; the
      // other direction costs the lock binding for the life of the install.
      return true;
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

    applyApi28Protections(spec, strongBox, requireUnlocked);

    generator.init(spec.build());
    return generator.generateKey();
  }

  /**
   * Ask the spec for whatever this API level can actually grant, or refuse the candidate.
   *
   * <p>Split out of {@link #generate} because that method cannot run in a test: it calls the real
   * Keystore provider, and every ladder test therefore drives a fake {@link KeystoreOps}. That left
   * this branch - the only place {@link #needsApi28} is consulted in production - executed by no
   * test at all. It was measured rather than assumed: deleting the refusal outright, so a pre-28
   * device silently received a key with none of the protections the ladder had asked for, kept all
   * 843 tests green and left {@code needsApi28} called only from tests.
   *
   * <p>Taking a builder rather than a boolean is the point. A predicate can be tested and still be
   * wired to nothing; this is the wiring, and it needs no Keystore to run, so a Robolectric test at
   * a chosen SDK level can execute the real thing on both sides of the branch.
   */
  static void applyApi28Protections(final KeyGenParameterSpec.Builder spec, final boolean strongBox,
      final boolean requireUnlocked) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (requireUnlocked) spec.setUnlockedDeviceRequired(true);
      if (strongBox) spec.setIsStrongBoxBacked(true);
    } else if (needsApi28(strongBox, requireUnlocked)) {
      throw new IllegalStateException("StrongBox / unlocked-device-required need API 28");
    }
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

  @Override
  public boolean legacyMigrationIsSealed() {
    try {
      final KeyStore ks = KeyStore.getInstance(PROVIDER);
      ks.load(null);
      return ks.containsAlias(MIGRATION_SEALED_ALIAS);
    } catch (final Exception e) {
      // Fail CLOSED. An unreadable Keystore must not read as "conversion never happened", because
      // that is the answer that lets an attacker's cleartext be sealed. Refusing a legitimate
      // upgrade on a broken Keystore costs the user an error; the other way round costs them their
      // identity key.
      Log.w(TAG, "could not read the migration seal; treating the conversion as already done", e);
      return true;
    }
  }

  @Override
  public void sealLegacyMigration() {
    try {
      final KeyStore ks = KeyStore.getInstance(PROVIDER);
      ks.load(null);
      if (ks.containsAlias(MIGRATION_SEALED_ALIAS)) return;
      final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
      generator.init(new KeyGenParameterSpec.Builder(MIGRATION_SEALED_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build());
      generator.generateKey();
    } catch (final Exception e) {
      // Not fatal: the conversion itself succeeded and the data is sealed. What is lost is the
      // protection against a SECOND conversion, so it is logged loudly rather than swallowed.
      Log.e(TAG, "could not record the migration seal; a future cleartext conversion will not be "
          + "refused on this device", e);
    }
  }
}
