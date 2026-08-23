package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class GcmCryptoBoxTest {

  private final GcmCryptoBox box = new JvmGcmCryptoBox();

  /** Every value in the real store is bound to its own key name; tests use one fixed context. */
  private static final byte[] AAD = "kryptey/storage/v2/PROTOCOL_STORE".getBytes(StandardCharsets.UTF_8);

  private static byte[] utf8(final String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] sealed(final byte[] plaintext) throws Exception {
    return box.seal(plaintext, AAD);
  }

  private byte[] opened(final byte[] envelope) throws Exception {
    return box.open(envelope, AAD);
  }

  private static byte[] sealedWith(final GcmCryptoBox b, final byte[] plaintext) throws Exception {
    return b.seal(plaintext, AAD);
  }

  @Test
  public void roundTripsPlaintext() throws Exception {
    final byte[] plaintext = utf8("{\"identityKeyPair\":\"secret\"}");
    assertArrayEquals(plaintext, opened(sealed(plaintext)));
  }

  @Test
  public void roundTripsEmptyInput() throws Exception {
    assertArrayEquals(new byte[0], opened(sealed(new byte[0])));
  }

  @Test
  public void roundTripsLargePayload() throws Exception {
    // The real protocol store grows with the pre-key count; make sure nothing assumes a small blob.
    final byte[] big = new byte[512 * 1024];
    for (int i = 0; i < big.length; i++) big[i] = (byte) i;
    assertArrayEquals(big, opened(sealed(big)));
  }

  /**
   * Weak on its own — a substring check passes for ECB or a fixed keystream too — so it is paired
   * with a check that the ciphertext body differs in essentially every byte from the plaintext.
   */
  @Test
  public void ciphertextDoesNotResembleThePlaintext() throws Exception {
    final byte[] plaintext = utf8("BEGIN_IDENTITY_KEY_MATERIAL_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    final byte[] sealed = sealed(plaintext);

    assertFalse("plaintext leaked into the envelope",
        new String(sealed, StandardCharsets.ISO_8859_1).contains("BEGIN_IDENTITY"));

    final byte[] body = Arrays.copyOfRange(sealed, 1 + GcmCryptoBox.NONCE_BYTES,
        1 + GcmCryptoBox.NONCE_BYTES + plaintext.length);
    int identical = 0;
    for (int i = 0; i < plaintext.length; i++) {
      if (body[i] == plaintext[i]) identical++;
    }
    // The plaintext deliberately contains a long run of one byte: a fixed keystream or any
    // non-randomising mode would reproduce that structure in the ciphertext.
    assertTrue("ciphertext tracks the plaintext byte-for-byte in " + identical + " positions",
        identical < plaintext.length / 4);
  }

  /**
   * The store is rewritten on essentially every message under one long-lived key, so a repeated
   * GCM nonce is a realistic risk rather than a theoretical one — and it would be catastrophic.
   */
  @Test
  public void usesAFreshNoncePerSeal() throws Exception {
    final byte[] plaintext = utf8("same input every time");
    final Set<String> nonces = new HashSet<>();
    for (int i = 0; i < 512; i++) {
      final byte[] sealed = sealed(plaintext);
      final byte[] nonce = Arrays.copyOfRange(sealed, 1, 1 + GcmCryptoBox.NONCE_BYTES);
      assertTrue("GCM nonce repeated after " + i + " seals",
          nonces.add(Arrays.toString(nonce)));
    }
  }

  /**
   * The single-instance test above is not sufficient on its own: a per-instance counter starting at
   * zero would satisfy it while reusing nonces 0,1,2... under the same long-lived Keystore key on
   * every keyboard restart. The box is short-lived; the master key is not.
   */
  @Test
  public void noncesDoNotRepeatAcrossBoxInstancesSharingAKey() throws Exception {
    final javax.crypto.SecretKey shared = JvmGcmCryptoBox.newKey();
    final Set<String> nonces = new HashSet<>();
    for (int instance = 0; instance < 64; instance++) {
      final GcmCryptoBox freshBox = new JvmGcmCryptoBox(shared);
      for (int i = 0; i < 8; i++) {
        final byte[] sealed = sealedWith(freshBox, utf8("restart " + instance));
        final byte[] nonce = Arrays.copyOfRange(sealed, 1, 1 + GcmCryptoBox.NONCE_BYTES);
        assertTrue("nonce repeated across box instances sharing one key (instance " + instance + ")",
            nonces.add(Arrays.toString(nonce)));
      }
    }
  }

  @Test
  public void identicalPlaintextsProduceDifferentEnvelopes() throws Exception {
    final byte[] a = sealed(utf8("x"));
    final byte[] b = sealed(utf8("x"));
    assertNotEquals(Arrays.toString(a), Arrays.toString(b));
  }

  @Test
  public void stampsTheFormatVersion() throws Exception {
    assertEquals(GcmCryptoBox.VERSION, sealed(utf8("x"))[0]);
  }

  @Test
  public void rejectsAnUnknownFormatVersion() throws Exception {
    final byte[] sealed = sealed(utf8("x"));
    sealed[0] = 0x7F;
    final StorageCryptoException e =
        assertThrows(StorageCryptoException.class, () -> opened(sealed));
    assertTrue(e.getMessage().contains("unsupported storage envelope version"));
  }

  @Test
  public void rejectsATamperedCiphertextByte() throws Exception {
    final byte[] sealed = sealed(utf8("the quick brown fox"));
    sealed[sealed.length - 1] ^= 0x01; // last byte is inside the GCM tag
    assertThrows(StorageCryptoException.class, () -> opened(sealed));
  }

  @Test
  public void rejectsATamperedNonce() throws Exception {
    final byte[] sealed = sealed(utf8("the quick brown fox"));
    sealed[3] ^= 0x01;
    assertThrows(StorageCryptoException.class, () -> opened(sealed));
  }

  @Test
  public void rejectsATamperedPayloadBody() throws Exception {
    final byte[] sealed = sealed(utf8("a somewhat longer message so the body has room"));
    sealed[1 + GcmCryptoBox.NONCE_BYTES + 2] ^= 0x08;
    assertThrows(StorageCryptoException.class, () -> opened(sealed));
  }

  @Test
  public void rejectsATruncatedEnvelope() throws Exception {
    final byte[] sealed = sealed(utf8("x"));
    for (int len = 0; len < sealed.length; len++) {
      final byte[] truncated = Arrays.copyOf(sealed, len);
      assertThrows("accepted a " + len + "-byte envelope",
          StorageCryptoException.class, () -> opened(truncated));
    }
  }

  /** The whole point of Keystore backing: another key's envelope must not open. */
  @Test
  public void rejectsAnEnvelopeSealedUnderADifferentKey() throws Exception {
    final byte[] sealed = sealedWith(new JvmGcmCryptoBox(), utf8("someone else's data"));
    assertThrows(StorageCryptoException.class, () -> opened(sealed));
  }

  /**
   * The AAD binds each envelope to the storage key it belongs to. Without this an attacker with
   * filesystem write access could move a valid envelope between preference keys — CONTACTS and
   * UNENCRYPTED_MESSAGES are both plain ArrayList, so the transplant would deserialize cleanly and
   * silently erase one of them.
   */
  @Test
  public void rejectsAnEnvelopeTransplantedToADifferentStorageKey() throws Exception {
    final byte[] forContacts =
        box.seal(utf8("[{\"firstName\":\"Ada\"}]"), utf8("kryptey/storage/v2/CONTACTS"));

    assertThrows("envelope opened under a different storage key",
        StorageCryptoException.class,
        () -> box.open(forContacts, utf8("kryptey/storage/v2/UNENCRYPTED_MESSAGES")));

    // ...and still opens under its own key, so the binding is not just breaking everything.
    assertArrayEquals(utf8("[{\"firstName\":\"Ada\"}]"),
        box.open(forContacts, utf8("kryptey/storage/v2/CONTACTS")));
  }

  @Test
  public void rejectsNullAad() throws Exception {
    assertThrows(StorageCryptoException.class, () -> box.seal(utf8("x"), null));
    assertThrows(StorageCryptoException.class, () -> box.open(sealed(utf8("x")), null));
  }

  @Test
  public void rejectsNullInputs() {
    assertThrows(StorageCryptoException.class, () -> sealed(null));
    assertThrows(StorageCryptoException.class, () -> opened(null));
  }
}
