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
}
