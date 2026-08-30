package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyMetadataStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.PreKeyStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;

import java.util.HashSet;
import java.util.Set;

/**
 * One-time pre-key allocation.
 *
 * <p>The bug this guards: the id was hard-coded to 1 and regenerated <em>in place</em> whenever it
 * was consumed. Inviting a second contact therefore overwrote the key material the first contact
 * had already been handed. Their opening message referenced pre-key 1, pre-key 1 was now a
 * different key, and the message could never be decrypted — with no recovery and no diagnostic.
 */
public class OneTimePreKeyTest {

  private SignalProtocolStoreImpl store;
  private PreKeyMetadataStoreImpl metadata;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    final IdentityKeyPair identityKeyPair = KeyUtil.generateIdentityKeyPair();
    store = new SignalProtocolStoreImpl(identityKeyPair, KeyUtil.generateRegistrationId());
    metadata = new PreKeyMetadataStoreImpl();
  }

  private int allocate() {
    final Integer id = KeyUtil.getUnusedOneTimePreKeyId(store, metadata);
    assertNotNull("allocation returned null", id);
    return id;
  }

  /** Consuming a pre-key is what libsignal does when a peer actually uses the bundle. */
  private void consume(final int id) throws Exception {
    store.loadPreKey(id);
  }

  @Test
  public void anUnusedPreKeyIsHandedOutAgainRatherThanRegenerated() {
    final int first = allocate();
    assertEquals("an unconsumed pre key should be reused, not replaced", first, allocate());
  }

  /** The core regression: a second bundle must not destroy the first bundle's key. */
  @Test
  public void issuingASecondBundleDoesNotDestroyTheFirstBundlesKey() throws Exception {
    final int alice = allocate();
    final byte[] aliceKey = store.loadPreKey(alice).getKeyPair().getPublicKey().serialize();
    consume(alice);

    final int carol = allocate();
    assertFalse("the second bundle reused the first bundle's pre key id", alice == carol);

    // Alice's opening message arrives now, after Carol was invited.
    assertTrue("Alice's pre key was deleted", store.containsPreKey(alice));
    assertArrayEquals("Alice's pre key material was overwritten by Carol's bundle",
        aliceKey, store.loadPreKey(alice).getKeyPair().getPublicKey().serialize());
  }

  @Test
  public void manySuccessiveBundlesAllGetDistinctIds() throws Exception {
    final Set<Integer> ids = new HashSet<>();
    for (int i = 0; i < 25; i++) {
      final int id = allocate();
      assertTrue("pre key id " + id + " was handed out twice", ids.add(id));
      consume(id);
    }
  }

  @Test
  public void allocationNeverOverwritesAnExistingRecord() throws Exception {
    // Pre-seed the id the allocator would naturally reach for.
    KeyUtil.generateAndStoreOneTimePreKey(store, 0);
    final byte[] seeded = store.loadPreKey(0).getKeyPair().getPublicKey().serialize();
    consume(0);
    metadata.setNextOneTimePreKeyId(0);

    final int allocated = allocate();

    assertFalse("allocator reused an occupied id", allocated == 0);
    assertArrayEquals("pre-seeded key material was clobbered",
        seeded, store.loadPreKey(0).getKeyPair().getPublicKey().serialize());
  }

  @Test
  public void nullStoresYieldNullRatherThanThrowing() {
    assertNull(KeyUtil.getUnusedOneTimePreKeyId(null, metadata));
    assertNull(KeyUtil.getUnusedOneTimePreKeyId(store, null));
  }

  // ------------------------------------------------------------- retention

  /**
   * Consumed keys are retained so a late first message still decrypts — but not forever: the whole
   * store is serialized into SharedPreferences on every message.
   */
  @Test
  public void consumedPreKeysAreRetainedButBounded() throws Exception {
    for (int i = 0; i < 80; i++) {
      consume(allocate());
    }
    // One more allocation triggers the prune.
    allocate();

    final int size = store.getPreKeyStore().getSize();
    assertTrue("used pre keys grow without bound: " + size, size <= 60);
    assertTrue("pruning removed everything, including recent keys", size > 10);
  }

  @Test
  public void pruningKeepsTheMostRecentUsedKeysAndDropsTheOldest() throws Exception {
    final PreKeyStoreImpl preKeys = new PreKeyStoreImpl();
    for (int id = 0; id < 10; id++) {
      KeyUtil.generateAndStoreOneTimePreKey(store, id);
      preKeys.storePreKey(id, store.loadPreKey(id));
      preKeys.loadPreKey(id); // mark used
    }

    preKeys.pruneUsedPreKeys(3);

    assertEquals(3, preKeys.getSize());
    assertFalse("oldest used key survived pruning", preKeys.containsPreKey(0));
    assertTrue("newest used key was pruned", preKeys.containsPreKey(9));
  }

  @Test
  public void pruningNeverTouchesUnusedKeys() throws Exception {
    final PreKeyStoreImpl preKeys = new PreKeyStoreImpl();
    for (int id = 0; id < 10; id++) {
      KeyUtil.generateAndStoreOneTimePreKey(store, id);
      preKeys.storePreKey(id, store.loadPreKey(id));
    }

    preKeys.pruneUsedPreKeys(0);

    assertEquals("unused pre keys must never be pruned", 10, preKeys.getSize());
  }

  @Test
  public void findUnusedReturnsNullOnceEverythingIsConsumed() throws Exception {
    final PreKeyStoreImpl preKeys = new PreKeyStoreImpl();
    KeyUtil.generateAndStoreOneTimePreKey(store, 5);
    preKeys.storePreKey(5, store.loadPreKey(5));

    assertEquals(Integer.valueOf(5), preKeys.findUnusedPreKeyId());
    preKeys.loadPreKey(5);
    assertNull(preKeys.findUnusedPreKeyId());
  }

  /**
   * Asking for a pre-key that is not there is a refusal, not a crash.
   *
   * <p>{@code loadPreKey} declares {@link org.signal.libsignal.protocol.InvalidKeyIdException} and
   * libsignal calls it from inside the decrypt path. Delete the {@code containsKey} guard and
   * control falls into the {@code Objects.requireNonNull} on the next line, which raises an
   * unchecked {@code NullPointerException} out of a store callback whose signature promised a
   * checked one - the crash class {@code StoredRecordUnreadableException} exists to prevent. That
   * deletion was measured: it survives the whole suite, while the identical guard in
   * {@link com.amnesica.kryptey.inputmethod.signalprotocol.stores.KyberPreKeyStoreImpl} is killed
   * by {@code KyberPreKeyTest::missingKyberPreKeyRaisesInvalidKeyId}. The classical half simply had
   * no equivalent.
   *
   * <p>Reached in production when a peer's opening message names a one-time pre-key that
   * {@code pruneUsedPreKeys} has since dropped. {@code PreKeyRotationTest} already catches this
   * exception type around this call, so the contract was depended on before it was asserted.
   *
   * <p>The two assertions before it are not decoration. Without them this case would also pass on a
   * store where {@code loadPreKey} throws for every id, which is a store that has stopped working
   * entirely - an absence that holds in the broken world as well as the correct one is not evidence.
   */
  @Test
  public void amissingPreKeyRaisesTheDeclaredTypeRatherThanCrashing() throws Exception {
    final PreKeyStoreImpl preKeys = new PreKeyStoreImpl();
    KeyUtil.generateAndStoreOneTimePreKey(store, 5);
    preKeys.storePreKey(5, store.loadPreKey(5));

    assertNotNull("precondition: a pre-key that IS present must load", preKeys.loadPreKey(5));
    assertFalse("precondition: and the id under test must genuinely be absent",
        preKeys.containsPreKey(99));

    assertThrows("a pre-key id the store does not hold must raise the checked type libsignal's "
            + "callback declares. Without the containsKey guard this is a NullPointerException out "
            + "of the decrypt path instead, which no caller can handle and which reaches the user "
            + "as a dead keyboard rather than as one unreadable message",
        org.signal.libsignal.protocol.InvalidKeyIdException.class, () -> preKeys.loadPreKey(99));
  }
}
