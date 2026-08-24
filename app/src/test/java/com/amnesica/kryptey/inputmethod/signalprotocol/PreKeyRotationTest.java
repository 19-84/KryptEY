package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

/**
 * Pre-key rotation and retirement.
 *
 * <p>Both halves of a PQXDH bundle are signed by the identity key and feed the same handshake, so
 * they have to age out together — pinning the Kyber half forever would mean one compromised
 * post-quantum key exposes every future initial message, which is exactly what rotating the
 * classical half is meant to bound.
 *
 * <p>The retirement window matters for a subtler reason: a peer may still be holding the previous
 * bundle. Dropping retired keys immediately makes messages already in flight undecryptable.
 */
public class PreKeyRotationTest {

  private SignalProtocolStoreImpl store;
  private PreKeyMetadataStoreImpl metadata;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    final IdentityKeyPair identityKeyPair = KeyUtil.generateIdentityKeyPair();
    store = new SignalProtocolStoreImpl(identityKeyPair, KeyUtil.generateRegistrationId());
    metadata = new PreKeyMetadataStoreImpl();

    KeyUtil.generateAndStoreOneTimePreKeys(store, metadata);
    final SignedPreKeyRecord signed = KeyUtil.generateAndStoreSignedPreKey(store, metadata);
    metadata.setActiveSignedPreKeyId(signed.getId());
    metadata.setSignedPreKeyRegistered(true);
    KeyUtil.generateAndStoreKyberPreKey(store, metadata);
  }

  @Test
  public void nothingRotatesBeforeTheRefreshTime() {
    final int signedBefore = metadata.getActiveSignedPreKeyId();
    final int kyberBefore = metadata.getActiveKyberPreKeyId();

    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() + 60_000);
    assertFalse(KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata));

    assertEquals(signedBefore, metadata.getActiveSignedPreKeyId());
    assertEquals(kyberBefore, metadata.getActiveKyberPreKeyId());
  }

  /** The gap this test exists for: the Kyber half used never to rotate at all. */
  @Test
  public void theKyberPreKeyRotatesAlongsideTheSignedPreKey() throws Exception {
    final int signedBefore = metadata.getActiveSignedPreKeyId();
    final int kyberBefore = metadata.getActiveKyberPreKeyId();
    final byte[] kyberKeyBefore =
        store.loadKyberPreKey(kyberBefore).getKeyPair().getPublicKey().serialize();

    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    assertTrue(KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata));

    assertNotEquals("signed pre key did not rotate",
        signedBefore, metadata.getActiveSignedPreKeyId());
    assertNotEquals("kyber pre key did not rotate", kyberBefore, metadata.getActiveKyberPreKeyId());

    final byte[] kyberKeyAfter = store
        .loadKyberPreKey(metadata.getActiveKyberPreKeyId()).getKeyPair().getPublicKey().serialize();
    assertFalse("kyber key material was reused across a rotation",
        java.util.Arrays.equals(kyberKeyBefore, kyberKeyAfter));
  }

  @Test
  public void aRotatedKyberPreKeyIsStillSignedByTheIdentityKey() throws Exception {
    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata);

    final var rotated = store.loadKyberPreKey(metadata.getActiveKyberPreKeyId());
    assertTrue("rotated kyber pre key is not correctly signed",
        store.getIdentityKeyPair().getPublicKey().getPublicKey().verifySignature(
            rotated.getKeyPair().getPublicKey().serialize(), rotated.getSignature()));
  }

  /**
   * Regression test. {@code deleteOlderSignedPreKeysIfNecessary} used to compare
   * {@code currentTimeMillis()} against {@code SIGNED_PRE_KEY_ARCHIVE_AGE} — a duration (~1.7e8),
   * not a timestamp (~1.7e12) — so the comparison was unconditionally true and retired keys were
   * dropped the moment they were replaced, breaking any peer still holding the previous bundle.
   */
  @Test
  public void retiredKeysAreKeptUntilTheArchiveWindowExpires() throws Exception {
    final int oldSignedId = metadata.getActiveSignedPreKeyId();

    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata);
    assertNotEquals(oldSignedId, metadata.getActiveSignedPreKeyId());

    // Rotation set a deletion time in the future, so the superseded key must survive.
    assertTrue("deletion time was not pushed into the future by rotation",
        metadata.getOldSignedPreKeyDeletionTime() > System.currentTimeMillis());
    assertTrue("a retired signed pre key was deleted inside its archive window",
        store.containsSignedPreKey(oldSignedId));
  }

  @Test
  public void retiredKeysAreRemovedOnceTheWindowHasPassed() throws Exception {
    final int oldSignedId = metadata.getActiveSignedPreKeyId();
    final int oldKyberId = metadata.getActiveKyberPreKeyId();

    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata);

    // Wind the archive window into the past and run the maintenance pass again.
    metadata.setOldSignedPreKeyDeletionTime(System.currentTimeMillis() - 1);
    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() + 60_000);
    KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata);

    assertFalse("retired signed pre key was never cleaned up",
        store.containsSignedPreKey(oldSignedId));
    assertFalse("retired kyber pre key was never cleaned up",
        store.containsKyberPreKey(oldKyberId));
    assertTrue("the active signed pre key must survive retirement",
        store.containsSignedPreKey(metadata.getActiveSignedPreKeyId()));
    assertTrue("the active kyber pre key must survive retirement",
        store.containsKyberPreKey(metadata.getActiveKyberPreKeyId()));
  }

  /**
   * Documents the actual interaction between rotation and retirement, which is easy to misread.
   *
   * <p>There is a single deletion timestamp, and rotating <em>resets</em> it — deliberately, since
   * the key just retired is the one that needs the archive window. So a rotation never also retires
   * in the same pass, even when the previous window had already expired. Retirement always happens
   * on a later call. This is correct, but it means "retirement runs on the rotating path" is not a
   * property this design has, and a test asserting it would be asserting a bug.
   */
  @Test
  public void rotatingResetsTheArchiveWindowRatherThanRetiringImmediately() throws Exception {
    final int oldSignedId = metadata.getActiveSignedPreKeyId();

    metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
    metadata.setOldSignedPreKeyDeletionTime(System.currentTimeMillis() - 1);
    assertTrue(KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata));

    assertTrue("rotation must push the deletion time forward for the key it just retired",
        metadata.getOldSignedPreKeyDeletionTime() > System.currentTimeMillis());
    assertTrue("the just-retired key must survive its fresh archive window",
        store.containsSignedPreKey(oldSignedId));
  }

  @Test
  public void repeatedRotationsKeepProducingDistinctIds() {
    final java.util.Set<Integer> signedIds = new java.util.HashSet<>();
    final java.util.Set<Integer> kyberIds = new java.util.HashSet<>();

    for (int i = 0; i < 10; i++) {
      metadata.setNextSignedPreKeyRefreshTime(System.currentTimeMillis() - 1);
      KeyUtil.refreshSignedPreKeyIfNecessary(store, metadata);
      signedIds.add(metadata.getActiveSignedPreKeyId());
      kyberIds.add(metadata.getActiveKyberPreKeyId());
    }

    assertEquals("signed pre key ids collided across rotations", 10, signedIds.size());
    assertEquals("kyber pre key ids collided across rotations", 10, kyberIds.size());
  }

  // -------------------------------------------------------------- null guards

  /**
   * Both arms of each null guard, which no test covered.
   *
   * <p>These are written {@code a == null || b == null}. Weakening the {@code ||} to {@code &&}
   * survived the whole suite, because every existing test passes two real stores — so only the
   * both-non-null path ran, and the guard could have been anything. With {@code &&}, one null
   * slips through into an NPE on the IME main thread, which is a crash on every keyboard raise
   * rather than a logged refusal.
   */
  @Test
  public void refreshRefusesWhenEitherStoreIsMissing() {
    assertFalse("a null protocol store must be refused, not dereferenced",
        KeyUtil.refreshSignedPreKeyIfNecessary(null, metadata));
    assertFalse("a null metadata store must be refused",
        KeyUtil.refreshSignedPreKeyIfNecessary(store, null));
    assertFalse(KeyUtil.refreshSignedPreKeyIfNecessary(null, null));
  }

  @Test
  public void allocationRefusesWhenEitherStoreIsMissing() {
    org.junit.Assert.assertNull(KeyUtil.getUnusedOneTimePreKeyId(null, metadata));
    org.junit.Assert.assertNull(KeyUtil.getUnusedOneTimePreKeyId(store, null));
  }

  // ------------------------------------------------- the allocator's search

  /**
   * When the next id is taken, the allocator must take the very next free one.
   *
   * <p>The search is {@code while (containsPreKey(id) && guard++ < MAX)}. Weakening that
   * {@code &&} to {@code ||} keeps the loop running past ids that are free, walking the whole
   * space until the guard trips — an unbounded-looking scan on the IME main thread that also
   * hands out an id far from the expected one. Nothing asserted <em>which</em> id came back, so
   * the mutation was invisible.
   */
  @Test
  public void allocationTakesTheNextFreeIdWhenTheObviousOneIsTaken() {
    // Consume every pre-key the fixture minted, so the fast path finds nothing unused.
    // loadPreKey is what marks one used, so draining through findUnusedPreKeyId uses the real path.
    Integer unused;
    while ((unused = store.getPreKeyStore().findUnusedPreKeyId()) != null) {
      try {
        store.getPreKeyStore().loadPreKey(unused);
      } catch (org.signal.libsignal.protocol.InvalidKeyIdException e) {
        throw new AssertionError(e);
      }
    }

    // Occupy exactly the id the allocator will try first, and consume it - a key that is present
    // but unused would be handed straight back by the fast path, and the scan would never run.
    final int next = metadata.getNextOneTimePreKeyId();
    KeyUtil.generateAndStoreOneTimePreKey(store, next);
    try {
      store.getPreKeyStore().loadPreKey(next);
    } catch (org.signal.libsignal.protocol.InvalidKeyIdException e) {
      throw new AssertionError(e);
    }
    org.junit.Assert.assertNull("the fast path must find nothing, or the scan is not exercised",
        store.getPreKeyStore().findUnusedPreKeyId());

    // The lowest id the store does not already hold. Computed rather than assumed: the fixture's
    // batch already occupies the first few, so "next + 1" is not it - an earlier version of this
    // test asserted that and was wrong about the code, not the other way round.
    int expected = next;
    while (store.getPreKeyStore().containsPreKey(expected)) expected++;

    final Integer allocated = KeyUtil.getUnusedOneTimePreKeyId(store, metadata);

    org.junit.Assert.assertNotNull(allocated);
    assertEquals("the allocator must stop at the first free id, not keep scanning past it",
        expected, allocated.intValue());
  }
}
