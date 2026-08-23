package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.prekey.KyberPreKeyEntity;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.KyberPreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;

import java.io.IOException;

/**
 * Covers the Kyber-1024 pre-key machinery PQXDH adds.
 *
 * <p>Two things here are easy to get wrong in ways nothing else would notice. The whole protocol
 * store is Jackson-serialized into SharedPreferences after essentially every message, so a Kyber
 * record that does not survive that round trip breaks the handshake only on the next app start.
 * And the signature over the Kyber public key is the only thing binding it to the sender's
 * identity — without it, an attacker substituting their own Kyber key agrees a shared secret with
 * the victim.
 */
public class KyberPreKeyTest {

  private static SignalProtocolStoreImpl newStore() {
    SignalProtocolMain.testIsRunning = true;
    final IdentityKeyPair identityKeyPair = KeyUtil.generateIdentityKeyPair();
    return new SignalProtocolStoreImpl(identityKeyPair, KeyUtil.generateRegistrationId());
  }

  // ------------------------------------------------------------------ generation

  @Test
  public void generatesAStoredKyberPreKey() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final PreKeyMetadataStoreImpl metadata = new PreKeyMetadataStoreImpl();

    final KyberPreKeyRecord record = KeyUtil.generateAndStoreKyberPreKey(store, metadata);

    assertNotNull(record);
    assertTrue(store.containsKyberPreKey(record.getId()));
    assertEquals(record.getId(), metadata.getActiveKyberPreKeyId());
    assertNotNull(store.loadKyberPreKey(record.getId()));
  }

  /**
   * The signature is what binds the post-quantum key to the identity. Verify it against the
   * identity public key rather than merely asserting it is non-empty.
   */
  @Test
  public void theKyberPublicKeyIsSignedByTheIdentityKey() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final KyberPreKeyRecord record =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());

    final byte[] signedMaterial = record.getKeyPair().getPublicKey().serialize();
    assertTrue("kyber pre key signature does not verify against the identity key",
        store.getIdentityKeyPair().getPublicKey().getPublicKey()
            .verifySignature(signedMaterial, record.getSignature()));
  }

  @Test
  public void aTamperedKyberSignatureDoesNotVerify() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final KyberPreKeyRecord record =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());

    final byte[] signature = record.getSignature().clone();
    signature[signature.length - 1] ^= 0x01;

    assertFalse("a corrupted signature still verified",
        store.getIdentityKeyPair().getPublicKey().getPublicKey()
            .verifySignature(record.getKeyPair().getPublicKey().serialize(), signature));
  }

  /** A different identity's signature must not pass — this is the substitution attack. */
  @Test
  public void anotherIdentitysSignatureDoesNotVerify() throws Exception {
    final SignalProtocolStoreImpl mine = newStore();
    final SignalProtocolStoreImpl attacker = newStore();

    final KyberPreKeyRecord attackerKey =
        KeyUtil.generateAndStoreKyberPreKey(attacker, new PreKeyMetadataStoreImpl());

    assertFalse("an attacker's kyber key verified against our identity",
        mine.getIdentityKeyPair().getPublicKey().getPublicKey()
            .verifySignature(attackerKey.getKeyPair().getPublicKey().serialize(),
                attackerKey.getSignature()));
  }

  @Test
  public void successiveGenerationsUseDistinctIdsAndKeys() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final PreKeyMetadataStoreImpl metadata = new PreKeyMetadataStoreImpl();

    final KyberPreKeyRecord first = KeyUtil.generateAndStoreKyberPreKey(store, metadata);
    final KyberPreKeyRecord second = KeyUtil.generateAndStoreKyberPreKey(store, metadata);

    assertFalse("kyber pre key id was reused", first.getId() == second.getId());
    assertFalse("the same kyber key material was generated twice",
        java.util.Arrays.equals(first.getKeyPair().getPublicKey().serialize(),
            second.getKeyPair().getPublicKey().serialize()));
  }

  // ------------------------------------------------------------- replay rejection

  /**
   * libsignal calls {@code markKyberPreKeyUsed} rather than removing the record, because a
   * last-resort key may legitimately serve several sessions. Reusing the same base key against the
   * same pre-key is a replayed handshake and must be refused.
   */
  @Test
  public void reusingABaseKeyAgainstOneKyberPreKeyIsRejected() throws Exception {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    final ECKeyPair baseKey = ECKeyPair.generate();

    store.markKyberPreKeyUsed(1, 1, baseKey.getPublicKey());

    assertThrows("a replayed base key was accepted", ReusedBaseKeyException.class,
        () -> store.markKyberPreKeyUsed(1, 1, baseKey.getPublicKey()));
  }

  @Test
  public void differentBaseKeysAgainstOneKyberPreKeyAreAccepted() throws Exception {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    store.markKyberPreKeyUsed(1, 1, ECKeyPair.generate().getPublicKey());
    store.markKyberPreKeyUsed(1, 1, ECKeyPair.generate().getPublicKey());
  }

  @Test
  public void theSameBaseKeyAgainstDifferentKyberPreKeysIsAccepted() throws Exception {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    final ECKeyPair baseKey = ECKeyPair.generate();
    store.markKyberPreKeyUsed(1, 1, baseKey.getPublicKey());
    store.markKyberPreKeyUsed(2, 1, baseKey.getPublicKey());
  }

  @Test
  public void missingKyberPreKeyRaisesInvalidKeyId() {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    assertThrows(InvalidKeyIdException.class, () -> store.loadKyberPreKey(99));
  }

  // ----------------------------------------------------------- persistence layer

  /**
   * The whole store is Jackson-serialized into SharedPreferences after nearly every message. A
   * Kyber record that does not survive that round trip would break the handshake on next launch,
   * not at the point the bug was introduced.
   */
  @Test
  public void kyberRecordsSurviveTheProtocolStoreRoundTrip() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final KyberPreKeyRecord original =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());

    final SignalProtocolStoreImpl reloaded =
        JsonUtil.fromJson(JsonUtil.toJson(store), SignalProtocolStoreImpl.class);

    assertTrue("kyber pre key vanished across serialization",
        reloaded.containsKyberPreKey(original.getId()));
    final KyberPreKeyRecord restored = reloaded.loadKyberPreKey(original.getId());
    assertArrayEquals("kyber public key changed across serialization",
        original.getKeyPair().getPublicKey().serialize(),
        restored.getKeyPair().getPublicKey().serialize());
    assertArrayEquals("kyber signature changed across serialization",
        original.getSignature(), restored.getSignature());
    assertEquals(original.getId(), restored.getId());
  }

  /** Replay state must persist too, or restarting the process reopens the replay window. */
  @Test
  public void usedBaseKeysSurviveSerialization() throws Exception {
    final KyberPreKeyStoreImpl store = new KyberPreKeyStoreImpl();
    final ECKeyPair baseKey = ECKeyPair.generate();
    store.markKyberPreKeyUsed(1, 1, baseKey.getPublicKey());

    final KyberPreKeyStoreImpl reloaded =
        JsonUtil.fromJson(JsonUtil.toJson(store), KyberPreKeyStoreImpl.class);

    assertThrows("replay protection was lost across a restart", ReusedBaseKeyException.class,
        () -> reloaded.markKyberPreKeyUsed(1, 1, baseKey.getPublicKey()));
  }

  /** The Kyber key travels to the peer through this entity; losing any field breaks the handshake. */
  @Test
  public void kyberPreKeyEntitySurvivesTheWireRoundTrip() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final KyberPreKeyRecord record =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());

    final KyberPreKeyEntity original = new KyberPreKeyEntity(
        record.getId(), record.getKeyPair().getPublicKey(), record.getSignature());

    final KyberPreKeyEntity restored =
        JsonUtil.fromJson(JsonUtil.toJson(original), KyberPreKeyEntity.class);

    assertEquals(original.getKeyId(), restored.getKeyId());
    assertArrayEquals("signature lost on the wire",
        original.getSignature(), restored.getSignature());
    assertArrayEquals("kyber public key lost on the wire",
        original.getPublicKey().serialize(), restored.getPublicKey().serialize());
    assertEquals(original, restored);
  }

  @Test
  public void kyberEntityEqualityDistinguishesDifferentKeys() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    final KyberPreKeyRecord a =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());
    final KyberPreKeyRecord b =
        KeyUtil.generateAndStoreKyberPreKey(store, new PreKeyMetadataStoreImpl());

    assertFalse(new KyberPreKeyEntity(a.getId(), a.getKeyPair().getPublicKey(), a.getSignature())
        .equals(new KyberPreKeyEntity(b.getId(), b.getKeyPair().getPublicKey(), b.getSignature())));
  }

  /**
   * A legacy store predates the Kyber field entirely, so its JSON has no {@code kyberPreKeyStore}
   * at all. It must still deserialize, and come back empty rather than null — the lazy generation
   * in {@code getPreKeyBundle} depends on being able to ask.
   */
  @Test
  public void aLegacyStoreWithoutKyberFieldsStillDeserializes() throws Exception {
    final SignalProtocolStoreImpl store = newStore();
    String json = JsonUtil.toJson(store);
    // Strip the kyber field the way a 0.1.5-era store would simply not have it.
    json = json.replaceAll(",\"kyberPreKeyStore\":\\{.*?\\}\\}", "");

    final SignalProtocolStoreImpl legacy;
    try {
      legacy = JsonUtil.fromJson(json, SignalProtocolStoreImpl.class);
    } catch (IOException e) {
      throw new AssertionError("a legacy store without kyber fields failed to load", e);
    }

    assertNotNull(legacy);
    assertFalse("a legacy store should report no kyber pre key for the default id",
        legacy.containsKyberPreKey(0));
  }
}
