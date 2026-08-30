package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The real log-write flag, exercised rather than overridden.
 *
 * <p>The two tests that read {@code lastMessageLogWriteSucceeded} both subclass {@code StorageHelper}
 * and override it with a constant, so they pin the wiring in {@code SignalProtocolMain} and never
 * run the field's own logic. Neither the assignment on the write path nor the one added for the
 * null-store path had any coverage: deleting either left the suite green, which in a repo that
 * tracks surviving mutants deliberately is a gap worth closing rather than noting.
 */
@RunWith(RobolectricTestRunner.class)
public class MessageLogWriteFlagTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** A helper whose box works: the log write lands and the flag says so. */
  @Test
  public void aworkingStoreReportsTheLogWriteSucceeded() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();

    // A message in the log, which is what makes this test about the write at all.
    //
    // A fresh account's log is loaded and EMPTY, and storeMessageLog's "nothing to say and nothing
    // already said" arm returns true without touching the store - so the first version of this
    // asserted the flag's own initialiser and would have stayed green with the write-path
    // assignment deleted outright. It covered the null-store path and nothing else, while claiming
    // both.
    account.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey("bob-address", 1), "me", "bob-address",
        java.time.Instant.ofEpochMilli(4000), "something to write"));

    final StorageHelper helper = new StorageHelper(context, (ctx, hasExistingData) ->
        new GcmCryptoBox() {
          @Override
          protected javax.crypto.SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }
        });

    assertTrue("precondition: the account must reach disk",
        helper.storeAllInformationInSharedPreferences(account));
    assertTrue("a store that works must report the log write as succeeded",
        helper.lastMessageLogWriteSucceeded());
    // And the log really reached its own file, so the flag is reporting a write rather than a
    // skipped one.
    assertTrue("the log must actually be on disk for that report to mean anything",
        context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE)
            .contains(String.valueOf(
                com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
                    .UNENCRYPTED_MESSAGES)));
  }

  /**
   * And a helper with no usable box reports failure rather than keeping its initialiser.
   *
   * <p>This is the path the flag used to lie about: the method returns before ever reaching the
   * assignment, having written NOTHING - including the log - so the field kept its initialiser,
   * {@code true}. A device whose store never resolves reported a healthy log write forever.
   */
  @Test
  public void anunusableStoreReportsTheLogWriteFailed() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();

    final StorageHelper helper = new StorageHelper(context, (ctx, hasExistingData) -> null);

    assertFalse("precondition: with no box there is nothing to write with",
        helper.storeAllInformationInSharedPreferences(account));
    assertFalse("a store that could not be resolved must not report a healthy log write - nothing "
            + "was written at all, and the flag's initialiser says the opposite",
        helper.lastMessageLogWriteSucceeded());
  }

  /**
   * The discriminating case: the log write fails while the account batch succeeds.
   *
   * <p>Asserting {@code true} on a healthy store cannot discriminate, because {@code true} is also
   * the field's initialiser — a control proved that deleting the assignment left the healthy case
   * green. What kills that mutant is a store where the log write is the only thing that fails, and
   * the AAD makes it constructible: {@code EncryptedKeyValueStore} binds the key NAME into it, so a
   * box can refuse exactly the log's key and seal everything else.
   */
  @Test
  public void alogWriteThatFailsAloneIsReported() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    account.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey("bob-address", 1), "me", "bob-address",
        java.time.Instant.ofEpochMilli(4000), "something to write"));

    final String logKey = String.valueOf(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.UNENCRYPTED_MESSAGES);
    final StorageHelper helper = new StorageHelper(context, (ctx, hasExistingData) ->
        new GcmCryptoBox() {
          @Override
          protected javax.crypto.SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }

          @Override
          public byte[] seal(final byte[] plaintext, final byte[] aad)
              throws StorageCryptoException {
            if (new String(aad, java.nio.charset.StandardCharsets.UTF_8).endsWith(logKey)) {
              throw new StorageCryptoException("refusing to seal the chat log");
            }
            return super.seal(plaintext, aad);
          }
        });

    assertTrue("precondition: the account batch itself must still succeed, or this is not the case "
        + "under test", helper.storeAllInformationInSharedPreferences(account));
    assertFalse("a log write that failed on its own must be reported, or the message is delivered "
            + "and silently missing from the history while everything else looks healthy",
        helper.lastMessageLogWriteSucceeded());
  }

  /**
   * A rollback whose log write is lost must say so, and it is the LOG's write that decides.
   *
   * <p>{@code discardRecordedMessage} removes one chat-log entry — the rollback for a message the
   * encoder refused, which exists because {@code encrypt} writes the plaintext to the log before the
   * encoder runs. It reported its outcome by returning
   * {@code storeAllAccountInformationInSharedPreferences()}, which is the <b>account batch</b>. The
   * chat log is deliberately not in that batch: it lives in its own file, is written first, and is
   * reported by {@code lastMessageLogWriteSucceeded}.
   *
   * <p>So it answered about the wrong file. In the one failure that matters — the log commit fails
   * while the account commit succeeds, which {@code removeContact}'s own comment calls ordinary on a
   * nearly full disk — it returned {@code true} with the entry still on disk, under a javadoc
   * promising it reported the removal reaching disk. That is worse than the bare call it replaced:
   * silent became wrong.
   *
   * <p>Uses this file's log-refusing box, which is the only fixture that can tell the two files
   * apart: a healthy store returns true either way, so a test on one cannot discriminate — the trap
   * this class's own javadoc names.
   */
  @Test
  public void adiscardThatLosesTheLogWriteIsReported() throws Exception {
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final java.time.Instant when = java.time.Instant.ofEpochMilli(9000);
    account.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey("bob-address", 1), "me", "bob-address", when,
        "the draft the encoder refused"));
    // A second entry, so the log is non-empty AFTER the rollback. storeMessageLog returns true for
    // "nothing to write", so a log emptied by the removal is never sealed at all and the refusing
    // box is never reached - the test would then measure a write that did not happen and read its
    // success as the store being healthy.
    account.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey("bob-address", 1), "me", "bob-address",
        java.time.Instant.ofEpochMilli(9500), "a message that stays"));

    final String logKey = String.valueOf(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.UNENCRYPTED_MESSAGES);
    final StorageHelper helper =
        new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
          @Override
          protected javax.crypto.SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }

          @Override
          public byte[] seal(final byte[] plaintext, final byte[] aad)
              throws StorageCryptoException {
            if (new String(aad, java.nio.charset.StandardCharsets.UTF_8).endsWith(logKey)) {
              throw new StorageCryptoException("refusing to seal the chat log");
            }
            return super.seal(plaintext, aad);
          }
        });
    SignalProtocolMain.getInstance().setStorageHelperForTest(helper);

    final int before = account.getUnencryptedMessages().size();
    final boolean reported = SignalProtocolMain.discardRecordedMessage(
        com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.of("bob-address", 1),
        when);

    assertEquals("precondition: the rollback must actually have removed the entry, or nothing was "
            + "written and there is no write outcome to report. before=" + before + " after="
            + account.getUnencryptedMessages().size(),
        before - 1, account.getUnencryptedMessages().size());
    assertFalse("precondition: the log write must actually have failed, or this measures a healthy "
            + "store and cannot tell the two files apart", helper.lastMessageLogWriteSucceeded());
    assertFalse("the rollback removed the entry from memory but its own file's write failed, so the "
            + "entry is still on disk - a log entry for a message nobody received, and the plaintext "
            + "of a draft the user was told could not be sent. Reporting the account batch's success "
            + "here answers about a file this operation never touched",
        reported);
  }
}
