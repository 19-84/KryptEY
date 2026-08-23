package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
}
