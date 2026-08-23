package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * {@link GcmCryptoBox} with an in-process AES key instead of a Keystore-held one.
 *
 * <p>This exercises the production framing and AES-GCM code paths for real — same class under test,
 * only the key source differs.
 *
 * <p><b>Known coverage gap.</b> Keystore key management — the fallback ladder,
 * unlocked-device-required, the absent-key refusal, and the self-test — is NOT covered by anything.
 * There is no {@code androidTest} source set in this project. Note also that a SunJCE key accepts a
 * caller-supplied IV while an Android Keystore key rejects one, so passing here does not by itself
 * prove the same code works on a device; that difference already hid one production-only failure.
 * These paths need instrumentation tests that do not yet exist.
 */
final class JvmGcmCryptoBox extends GcmCryptoBox {

  private final SecretKey key;

  JvmGcmCryptoBox() {
    this(newKey());
  }

  JvmGcmCryptoBox(final SecretKey key) {
    this.key = key;
  }

  static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  protected SecretKey key() {
    return key;
  }
}
