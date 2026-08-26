package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

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
}
