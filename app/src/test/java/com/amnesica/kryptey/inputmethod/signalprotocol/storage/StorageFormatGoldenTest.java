package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.util.Base64;

import org.junit.Test;

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The on-disk storage format, frozen the way {@code WireFormatGoldenTest} freezes the wire format.
 *
 * <p>Nothing pinned any of it. {@code GcmCryptoBox.VERSION}, the nonce length, the GCM tag length,
 * the schema key and the schema marker could each be changed with the whole suite green - and every
 * one of them makes every already-installed store unreadable. That failure does not appear in
 * testing: it appears on upgrade, on a user's phone, as a keyboard that has lost their identity key
 * and every verified contact.
 *
 * <p>It is a stronger constraint than the wire format, because the wire only has to agree with the
 * other person's current install. This has to agree with what THIS install wrote months ago, and
 * there is no way to renegotiate with a file.
 *
 * <p>Changing any of these is a decision to migrate, not an implementation detail. Failing here is
 * the reminder to write the migration.
 */
public class StorageFormatGoldenTest {

  private static SecretKey aKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static GcmCryptoBox boxWith(final SecretKey key) {
    return new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
  }

  @Test
  public void theEnvelopeConstantsAreUnchanged() {
    assertEquals("the storage envelope version byte is what every stored blob starts with; "
        + "changing it makes every installed store unreadable", (byte) 0x01, GcmCryptoBox.VERSION);
    assertEquals("the nonce is a fixed-width field in that envelope", 12, GcmCryptoBox.NONCE_BYTES);
    assertEquals("and the GCM tag length is baked into every blob already written",
        128, GcmCryptoBox.TAG_BITS);
  }

  @Test
  public void theSchemaMarkerIsUnchanged() {
    assertEquals("the schema key is how an installed store is recognised as encrypted at all",
        "__kryptey_storage_schema", EncryptedKeyValueStore.SCHEMA_KEY);
    assertEquals("and the schema value decides whether the cleartext migration runs again",
        "2", EncryptedKeyValueStore.SCHEMA_ENCRYPTED);
  }

  /**
   * The layout, measured rather than described: version byte, then the nonce, then ciphertext and
   * tag. A blob written by an older install has exactly this shape.
   */
  @Test
  public void asealedBlobHasTheDocumentedLayout() throws Exception {
    final SecretKey key = aKey();
    final byte[] plaintext = "the quick brown fox".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final byte[] aad = "kryptey/storage/v2/test".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    final byte[] sealed = boxWith(key).seal(plaintext, aad);

    assertEquals("the first byte is the version", GcmCryptoBox.VERSION, sealed[0]);
    assertEquals("then the nonce, then ciphertext plus a 16-byte tag",
        1 + GcmCryptoBox.NONCE_BYTES + plaintext.length + GcmCryptoBox.TAG_BITS / 8, sealed.length);
  }

  /**
   * And a blob sealed under one key opens again under the same key with the same AAD - the property
   * an upgrade has to preserve. If the format moves, this is what stops working.
   */
  @Test
  public void ablobOpensAgainUnderTheSameKeyAndAad() throws Exception {
    final SecretKey key = aKey();
    final byte[] plaintext = "identity key material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final byte[] aad = "kryptey/storage/v2/PROTOCOL_STORE"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    final byte[] sealed = boxWith(key).seal(plaintext, aad);
    assertArrayEquals("a blob must reopen under the key that sealed it",
        plaintext, boxWith(key).open(sealed, aad));

    // Base64 is how it actually reaches SharedPreferences, so the round trip has to survive that
    // too - this is the exact path EncryptedKeyValueStore takes.
    final byte[] viaText = Base64.decode(Base64.encodeBytes(sealed));
    assertArrayEquals("and after the base64 round trip the store performs",
        plaintext, boxWith(key).open(viaText, aad));
  }

  /**
   * The AAD binds a blob to its key name. A value moved from one preference key to another must not
   * open - otherwise an attacker with write access to the store could swap the contact list for the
   * identity key and neither would notice.
   */
  @Test
  public void ablobDoesNotOpenUnderAdifferentAad() throws Exception {
    final SecretKey key = aKey();
    final byte[] plaintext = "bound to its key".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    final byte[] sealed = boxWith(key).seal(plaintext,
        "kryptey/storage/v2/CONTACTS".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    final StorageCryptoException refused = org.junit.Assert.assertThrows(
        "a blob must not open under another key's AAD", StorageCryptoException.class,
        () -> boxWith(key).open(sealed,
            "kryptey/storage/v2/PROTOCOL_STORE".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertNotNull(refused.getMessage());
  }

  /** A version byte the reader does not know must be refused, not guessed at. */
  @Test
  public void anunknownVersionByteIsRefused() throws Exception {
    final SecretKey key = aKey();
    final byte[] aad = "kryptey/storage/v2/test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final byte[] sealed = boxWith(key).seal("x".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        aad);

    sealed[0] = (byte) 0x02;
    assertTrue("a future version must be refused rather than parsed as the current one",
        org.junit.Assert.assertThrows(StorageCryptoException.class,
            () -> boxWith(key).open(sealed, aad)).getMessage() != null);
  }
}
