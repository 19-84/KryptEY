package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.stores.SignalProtocolStoreImpl;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.KeyUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Retention keeps the most recently used pre-keys. "Most recent" was read off the id, and the
 * allocator makes that wrong.
 *
 * <p>Used records are kept rather than deleted, because a peer's first message arrives after the
 * invite was handed over, so the key must still be there to open it. Fifty are kept. Which fifty was
 * decided by sorting ids and dropping the lowest — which reads as oldest-first and is not:
 * {@code findUnusedPreKeyId} hands out the <em>lowest free</em> id, and when a peer's first message
 * arrives libsignal removes that id and the app regenerates it in place, unused. The next invite
 * therefore carries a low id, and the invite after that prunes it.
 *
 * <p>Measured before the fix, on the real invite path: fifty-five invites, id 3 recycled by a
 * delivered first message, and the invite handed id 3 was destroyed by the very next invite while
 * fifty older keys were kept. The peer holding that invite could never be decrypted, and nothing
 * said so — their messages simply stopped working.
 *
 * <p>The relay does not have to wait for any of this. An invite it discards still spent an id on the
 * sender's side, so making invites fail makes the user press Invite again, which is what mints and
 * prunes.
 */
public class TheNewestInviteIsNotTheFirstPrunedTest {

  private Account account;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  private SignalProtocolStoreImpl store() {
    return account.getSignalProtocolStore();
  }

  private int lowestIdInTheStore() {
    for (int id = 0; id < 4000; id++) {
      if (store().getPreKeyStore().containsPreKey(id)) return id;
    }
    throw new AssertionError("fixture: the store must hold pre-keys");
  }

  /** Fills the retention window the way a user does: by inviting people. */
  private void handOutInvites(final int count) throws Exception {
    for (int i = 0; i < count; i++) {
      assertNotNull("fixture: every invite must carry a bundle", SignalProtocolMain.exportOwnKeyBundle());
    }
  }

  /**
   * What a delivered first message leaves behind: the id gone, then regenerated in place, unused.
   * That is what {@code decrypt} does after libsignal consumes the record.
   */
  private void aPeersFirstMessageArrivesFor(final int preKeyId) {
    assertTrue("fixture: the id must be in the store before it is consumed",
        store().getPreKeyStore().containsPreKey(preKeyId));
    store().getPreKeyStore().removePreKey(preKeyId);
    KeyUtil.generateAndStoreOneTimePreKey(store(), preKeyId);
  }

  @Test
  public void arecycledIdDoesNotMakeTheNewestInviteThefirstPruned() throws Exception {
    handOutInvites(55);
    // The oldest invite still inside the retention window is finally answered. That is the ordinary
    // case: an invite handed over weeks ago, delivered whenever the recipient gets round to it.
    final int oldestRetained = lowestIdInTheStore();
    aPeersFirstMessageArrivesFor(oldestRetained);

    final Integer recycled = store().getPreKeyStore().findUnusedPreKeyId();
    assertEquals("precondition: the allocator must hand the recycled id to the next invite - that "
        + "is what makes the newest invite carry a low id",
        Integer.valueOf(oldestRetained), recycled);

    handOutInvites(1);   // the invite that takes the recycled id
    handOutInvites(1);   // the invite after it, which mints and prunes

    assertTrue("the key for the most recently issued invite was pruned while fifty older ones were "
            + "kept. The peer holding that invite can never be decrypted, and nothing tells either "
            + "of them: retention has to count consumption order, not id order",
        store().getPreKeyStore().containsPreKey(recycled));
  }

  /**
   * And retention still bounds the store, so the fix is not "keep everything".
   *
   * <p>The whole store is serialized into SharedPreferences on every message, which is why used
   * records cannot simply accumulate. Without this floor the test above would pass against a build
   * that had deleted the prune altogether.
   */
  @Test
  public void retentionStillBoundsTheStore() throws Exception {
    handOutInvites(120);

    int used = 0;
    for (int id = 0; id < 400; id++) {
      if (store().getPreKeyStore().containsPreKey(id)) used++;
    }
    assertTrue("used pre-key records must stay bounded; found " + used, used <= 60);
  }

  /**
   * The oldest consumption really is the one dropped.
   *
   * <p>Stated on the store rather than through the allocator, because it is the ordering itself
   * that is being pinned: the earliest-consumed record goes, whatever its id happens to be.
   */
  @Test
  public void theOldestConsumptionIsWhatGoes() throws Exception {
    handOutInvites(55);
    // The first invite of all took the lowest id and was consumed first; after 55 invites it is
    // long gone. The most recent one must still be here.
    final int newest = 54;
    assertTrue("the most recently consumed record must survive a prune",
        store().getPreKeyStore().containsPreKey(newest));
    assertTrue("and the earliest consumed must not: it is what the bound is spending",
        !store().getPreKeyStore().containsPreKey(0));
  }
}
