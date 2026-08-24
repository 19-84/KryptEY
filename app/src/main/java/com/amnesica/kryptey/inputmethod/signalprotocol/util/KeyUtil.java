package com.amnesica.kryptey.inputmethod.signalprotocol.util;

import android.util.Log;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStore;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.signal.libsignal.protocol.util.Medium;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class KeyUtil {

  static final String TAG = KeyUtil.class.getSimpleName();

  public static final int BATCH_SIZE = 2; // 100 in Signal app
  private static final long SIGNED_PRE_KEY_MAX_DAYS = TimeUnit.DAYS.toMillis(30); // debug: TimeUnit.MINUTES.toMillis(3)
  private static final long SIGNED_PRE_KEY_ARCHIVE_AGE = TimeUnit.DAYS.toMillis(2); // debug: TimeUnit.SECONDS.toMillis(20)

  public static IdentityKeyPair generateIdentityKeyPair() {
    final ECKeyPair identityKeyPairKeys = ECKeyPair.generate();

    return new IdentityKeyPair(new IdentityKey(identityKeyPairKeys.getPublicKey()),
        identityKeyPairKeys.getPrivateKey());
  }

  public static int generateRegistrationId() {
    return KeyHelper.generateRegistrationId(false);
  }

  public synchronized static List<PreKeyRecord> generateAndStoreOneTimePreKeys(final SignalProtocolStoreImpl protocolStore, final PreKeyMetadataStore metadataStore) {
    Log.d(TAG, "Generating one-time prekeys...");

    List<PreKeyRecord> records = new LinkedList<>();
    int preKeyIdOffset = metadataStore.getNextOneTimePreKeyId();

    for (int i = 0; i < BATCH_SIZE; i++) {
      int preKeyId = (preKeyIdOffset + i) % Medium.MAX_VALUE;
      PreKeyRecord record = generateAndStoreOneTimePreKey(protocolStore, preKeyId);
      records.add(record);
    }

    return records;
  }

  public synchronized static PreKeyRecord generateAndStoreOneTimePreKey(final SignalProtocolStoreImpl protocolStore, final int preKeyId) {
    Log.d(TAG, "Generating one-time prekey with id: " + preKeyId + "...");
    ECKeyPair keyPair = ECKeyPair.generate();
    PreKeyRecord record = new PreKeyRecord(preKeyId, keyPair);

    protocolStore.storePreKey(preKeyId, record);
    return record;
  }

  public synchronized static SignedPreKeyRecord generateAndStoreSignedPreKey(final SignalProtocolStoreImpl protocolStore, final PreKeyMetadataStore metadataStore) {
    return generateAndStoreSignedPreKey(protocolStore, metadataStore, protocolStore.getIdentityKeyPair().getPrivateKey());
  }

  public synchronized static SignedPreKeyRecord generateAndStoreSignedPreKey(final SignalProtocolStoreImpl protocolStore,
                                                                             final PreKeyMetadataStore metadataStore,
                                                                             final ECPrivateKey privateKey) {
    Log.d(TAG, "Generating signed prekeys...");

    int signedPreKeyId = metadataStore.getNextSignedPreKeyId();
    SignedPreKeyRecord record = generateSignedPreKey(signedPreKeyId, privateKey, metadataStore);

    protocolStore.storeSignedPreKey(signedPreKeyId, record);
    metadataStore.setNextSignedPreKeyId((signedPreKeyId + 1) % Medium.MAX_VALUE);
    metadataStore.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() + SIGNED_PRE_KEY_MAX_DAYS);
    metadataStore.setOldSignedPreKeyDeletionTime(System.currentTimeMillis() + SIGNED_PRE_KEY_ARCHIVE_AGE);

    return record;
  }

  public synchronized static SignedPreKeyRecord generateSignedPreKey(final int signedPreKeyId, final ECPrivateKey privateKey, final PreKeyMetadataStore metadataStore) {
    // No try/catch: libsignal 0.86's ECPrivateKey.calculateSignature no longer declares
    // InvalidKeyException, so the old catch block became unreachable.
    final ECKeyPair keyPair = ECKeyPair.generate();
    final byte[] signature = privateKey.calculateSignature(keyPair.getPublicKey().serialize());

    return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
  }

  /**
   * Generates a Kyber-1024 pre-key, signs its public half with the identity key, and stores it.
   *
   * <p>The signature is what binds the post-quantum key to the identity: without it an attacker
   * could substitute their own Kyber key and PQXDH would agree on a shared secret with them.
   */
  public synchronized static KyberPreKeyRecord generateAndStoreKyberPreKey(
      final SignalProtocolStoreImpl protocolStore, final PreKeyMetadataStore metadataStore) {
    final int kyberPreKeyId = metadataStore.getNextKyberPreKeyId();
    final ECPrivateKey identityPrivateKey = protocolStore.getIdentityKeyPair().getPrivateKey();

    final KEMKeyPair keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
    final byte[] signature = identityPrivateKey.calculateSignature(keyPair.getPublicKey().serialize());

    final KyberPreKeyRecord record =
        new KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), keyPair, signature);

    protocolStore.storeKyberPreKey(kyberPreKeyId, record);
    metadataStore.setActiveKyberPreKeyId(kyberPreKeyId);
    metadataStore.setNextKyberPreKeyId((kyberPreKeyId + 1) % Medium.MAX_VALUE);

    Log.d(TAG, "Generated kyber pre key with id: " + kyberPreKeyId);
    return record;
  }

  private static void rotateSignedPreKey(SignalProtocolStoreImpl protocolStore, PreKeyMetadataStore metadataStore) {
    SignedPreKeyRecord signedPreKeyRecord = generateAndStoreSignedPreKey(protocolStore, metadataStore);
    metadataStore.setActiveSignedPreKeyId(signedPreKeyRecord.getId());
    metadataStore.setSignedPreKeyRegistered(true);
    metadataStore.setSignedPreKeyFailureCount(0);

    // Rotate the Kyber pre key on the same schedule. Both are signed by the identity key and both
    // feed the same PQXDH handshake, so leaving the post-quantum half pinned forever would mean a
    // single compromised Kyber key exposes every future initial message - which is the specific
    // thing rotating the classical half is meant to bound.
    generateAndStoreKyberPreKey(protocolStore, metadataStore);
  }

  /** How many consumed one-time pre-keys to retain so late first-messages still decrypt. */
  private static final int USED_PRE_KEY_RETENTION = 50;

  /**
   * Returns an unused one-time pre-key id, allocating a fresh one if the pool is empty.
   *
   * <p>This used to hard-code id 1 and regenerate <em>in place</em> whenever that id was consumed.
   * Handing out a second bundle therefore destroyed the key material the first invitee had already
   * been given: their opening message referenced pre-key 1 but pre-key 1 was now different key
   * material, so it could never be decrypted and there was no way to recover.
   *
   * <p>Now each bundle gets its own id, and consumed records are retained (bounded by
   * {@link #USED_PRE_KEY_RETENTION}) so a first message that arrives later still opens.
   */
  public static Integer getUnusedOneTimePreKeyId(final SignalProtocolStoreImpl protocolStore,
                                                 final PreKeyMetadataStore metadataStore) {
    if (protocolStore == null || metadataStore == null || protocolStore.getPreKeyStore() == null) {
      return null;
    }

    final Integer unused = protocolStore.getPreKeyStore().findUnusedPreKeyId();
    if (unused != null) {
      Log.d(TAG, "Reusing unused pre key with id " + unused);
      return unused;
    }

    int preKeyId = Math.floorMod(metadataStore.getNextOneTimePreKeyId(), Medium.MAX_VALUE);
    // Never overwrite an id that already holds key material a peer may be relying on.
    int guard = 0;
    while (protocolStore.getPreKeyStore().containsPreKey(preKeyId) && guard++ < Medium.MAX_VALUE) {
      preKeyId = Math.floorMod(preKeyId + 1, Medium.MAX_VALUE);
    }

    Log.d(TAG, "No unused prekey left. Generating new one time prekey with id " + preKeyId);
    generateAndStoreOneTimePreKey(protocolStore, preKeyId);
    metadataStore.setNextOneTimePreKeyId(Math.floorMod(preKeyId + 1, Medium.MAX_VALUE));

    protocolStore.getPreKeyStore().pruneUsedPreKeys(USED_PRE_KEY_RETENTION);
    return preKeyId;
  }

  public static boolean refreshSignedPreKeyIfNecessary(final SignalProtocolStoreImpl protocolStore, final PreKeyMetadataStore metadataStore) {
    if (protocolStore == null || metadataStore == null) return false;

    final boolean rotated = System.currentTimeMillis() > metadataStore.getNextSignedPreKeyRefreshTime();
    if (rotated) {
      Log.d(TAG, "Rotating signed prekey...");
      rotateSignedPreKey(protocolStore, metadataStore);
    } else {
      Log.d(TAG, "Rotation of signed prekey not necessary...");
    }
    // Runs on both paths: previously an early return meant retirement was only ever considered on
    // the calls that did NOT rotate, so keys retired by a rotation were never cleaned up by it.
    deleteOlderSignedPreKeysIfNecessary(protocolStore, metadataStore);
    return rotated;
  }

  private static void deleteOlderSignedPreKeysIfNecessary(final SignalProtocolStoreImpl protocolStore, final PreKeyMetadataStore metadataStore) {
    // Unreachable defensive guard, kept deliberately. This method is private with exactly one
    // caller, refreshSignedPreKeyIfNecessary, which returns before reaching it if either store is
    // null - so no test can distinguish this line from its absence, and mutation testing flags it
    // as a survivor. Recorded here rather than removed: it costs nothing and a second caller would
    // otherwise inherit the hazard silently.
    if (protocolStore == null || metadataStore == null) return;

    // Compare against the stored deletion *timestamp*, not against SIGNED_PRE_KEY_ARCHIVE_AGE.
    // That constant is a duration (2 days in millis, ~1.7e8); currentTimeMillis is ~1.7e12, so the
    // original comparison was unconditionally true and retired keys were dropped the instant they
    // were replaced. A peer still holding the previous bundle could then no longer be decrypted -
    // the archive window exists precisely to cover messages already in flight.
    if (System.currentTimeMillis() > metadataStore.getOldSignedPreKeyDeletionTime()) {
      Log.d(TAG, "Deleting old signed prekeys...");
      protocolStore.getSignedPreKeyStore().removeOldSignedPreKeys(metadataStore.getActiveSignedPreKeyId());
      protocolStore.getKyberPreKeyStore().removeOldKyberPreKeys(metadataStore.getActiveKyberPreKeyId());
    } else {
      Log.d(TAG, "Deletion of old signed prekeys not necessary...");
    }
  }
}
