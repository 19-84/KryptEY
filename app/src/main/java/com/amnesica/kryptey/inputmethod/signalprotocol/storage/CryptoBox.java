package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

/**
 * Authenticated encryption with associated data, for data at rest.
 *
 * <p>Split out from the Keystore so the wire framing and AES-GCM usage can be exercised by ordinary
 * JVM unit tests; only key <em>management</em> is device-only.
 */
public interface CryptoBox {

  /**
   * Encrypts and authenticates {@code plaintext}, binding {@code aad} into the authentication tag.
   *
   * @param aad context the ciphertext is only valid in. Not stored in the envelope; the reader must
   *     supply the same bytes. Binding the storage key name here is what stops an attacker moving a
   *     valid envelope from one preference key to another.
   */
  byte[] seal(byte[] plaintext, byte[] aad) throws StorageCryptoException;

  /**
   * Decrypts and verifies an envelope produced by {@link #seal}.
   *
   * @throws StorageCryptoException if the envelope is malformed, truncated, was produced under a
   *     different key, carries a different {@code aad}, or fails its authentication tag.
   */
  byte[] open(byte[] envelope, byte[] aad) throws StorageCryptoException;

  /**
   * Whether this device has already completed the one-time conversion of a cleartext 0.1.5 store.
   *
   * <p>The fact belongs OUT here, away from the file being converted, and that is the whole point.
   * The conversion is a laundering primitive — it takes bytes an attacker wrote and hands them back
   * sealed under the real master key — and every guard on it lived inside the same file the
   * attacker can rewrite. A Phase 1 sweep showed both guards fall to the same move: snapshot the
   * file, empty it so nothing decryptable remains, let the keyboard raise once, and restore the
   * snapshot with the freshly sealed row swapped in. Emptying the file costs nothing, because
   * {@code hasEncryptedData} gates only whether a master key is MINTED, not whether it is used.
   *
   * <p>Answered from the Keystore, which an attacker holding the app's private files cannot edit.
   * They can destroy the alias, but that destroys the master key with it and the sealed data
   * becomes unreadable — which leaves them nothing to launder into.
   */
  default boolean legacyMigrationIsSealed() {
    return false;
  }

  /** Records that the conversion has happened and must never be offered again. */
  default void sealLegacyMigration() {
  }
}
