package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;

/**
 * The harness behind the raise-cost table in REVIVAL.md. Not an assertion — a measurement.
 *
 * <pre>
 *   tools/build-in-docker testDebugUnitTest --tests '*ChatLogRaiseCostHarness' -Pmeasure
 * </pre>
 *
 * <p>Both methods are {@code @Ignore}d individually rather than the class, because
 * {@code IgnoredTestsAreAccountedForTest} scans for the annotation on a method and a class-level
 * one is invisible to it — which would leave two disabled measurements unaccounted for in exactly
 * the way that guard exists to prevent. They are registered there. Ignored deliberately: Timings are not a property to assert: a threshold tight enough
 * to mean anything is flaky on a loaded machine, and one loose enough to be stable asserts nothing.
 * What guards the behaviour is {@link ChatLogLoadsLazilyTest}, which pins the structural facts —
 * the log is not read to load an account, and a raise leaves its stored bytes untouched. This file
 * exists so the numbers quoted in the document can be re-derived rather than taken on trust, which
 * a reviewer correctly pointed out was not previously possible from anything in the tree.
 *
 * <p>Numbers recorded on a desktop JVM under Robolectric, after the log moved to its own file:
 * empty 31 ms, 1,000 messages 42 ms, 20,000 messages 25 ms - a raise no longer scales with the log
 * at all, which is the point of the split. Before the split the same measurements were 36 ms,
 * 42 ms and 199 ms. The "eager" column, which forces the log to be read, still scales and should:
 * 366 ms at 20,000. The file-layer probe explains why the split was needed at all - committing one
 * unrelated key costs ~13-40 ms against a small preferences file and ~146-166 ms when a 5.35 MB
 * sibling value shares it.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatLogRaiseCostHarness {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

  private static final StorageHelper.CryptoBoxFactory BOX = (ctx, has) -> new GcmCryptoBox() {
    @Override protected javax.crypto.SecretKey key() { return KEY; }
  };

  /** One raise: load the account, then write it back. Exactly what setInputView causes. */
  private long raise(final Context context, final boolean touchLog) {
    long total = 0;
    for (int run = 0; run < 5; run++) {
      final long start = System.nanoTime();
      final Account raised = new StorageHelper(context, BOX).getAccountFromSharedPreferences();
      if (touchLog) raised.getUnencryptedMessages();
      new StorageHelper(context, BOX).storeAllInformationInSharedPreferences(raised);
      total += System.nanoTime() - start;
    }
    return total / 5 / 1_000_000;
  }

  private void seed(final Context context, final int count) throws Exception {
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", "bobAddress", 3, false));
    account.setContactList(contacts);
    final String key = StorageMessage.chatLogKey("bobAddress", 3);
    final ArrayList<StorageMessage> messages = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      messages.add(new StorageMessage(key, "bobAddress", "me", Instant.now(),
          "message number " + i + " with some ordinary sentence length to it"));
    }
    account.setUnencryptedMessages(messages);
    new StorageHelper(context, BOX).storeAllInformationInSharedPreferences(account);
  }

  @Ignore("measurement harness, not a test - see the class javadoc")
  @Test
  public void raiseCost() throws Exception {
    final Context context = RuntimeEnvironment.getApplication();
    for (final int size : new int[] {0, 1000, 20000}) {
      seed(context, size);
      final long lazy = raise(context, false);
      final long eager = raise(context, true);
      // The log's own file, not the account's - it moved, and reading the old location here
      // reported "0 bytes" for a 5 MB log, which is the kind of number that quietly makes a table
      // look wrong.
      System.out.println("RAISE " + size + " messages: lazy " + lazy + " ms, eager " + eager
          + " ms, stored " + context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE)
          .getString("UNENCRYPTED_MESSAGES", "").length() + " bytes");
    }
  }

  /**
   * Where the cost that laziness does NOT remove actually lives.
   *
   * <p>Commits one unrelated key against a preferences file with and without a large sibling value.
   * SharedPreferences serialises its whole in-memory map on every commit, so the log is paid for by
   * any write to the file it shares, whoever made the write and whatever it changed.
   */
  @Ignore("measurement harness, not a test - see the class javadoc")
  @Test
  public void fileLayerCost() {
    final Context context = RuntimeEnvironment.getApplication();
    System.out.println("COMMIT small file: " + commitOneKey(context, "probe_small", 0) + " ms");
    System.out.println("COMMIT with 5.35 MB sibling: "
        + commitOneKey(context, "probe_big", 5_345_228) + " ms");
  }

  private long commitOneKey(final Context context, final String name, final int siblingBytes) {
    final SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
    if (siblingBytes > 0) {
      final StringBuilder blob = new StringBuilder();
      while (blob.length() < siblingBytes) blob.append("abcdefghij");
      prefs.edit().putString("BIG", blob.toString()).commit();
    }
    long total = 0;
    for (int i = 0; i < 5; i++) {
      final long start = System.nanoTime();
      prefs.edit().putString("UNRELATED", "value" + i).commit();
      total += System.nanoTime() - start;
    }
    return total / 5 / 1_000_000;
  }

  /**
   * What a cap would actually buy, at sizes a person could reach.
   *
   * <p>The chat-log cap is a deferred product decision, and it has been deferred without numbers:
   * the trade is between losing old history and paying for it on every message. This measures the
   * paying half at several sizes, so the choice can be made against evidence rather than intuition.
   *
   * <p>Measured on the DECRYPT path, not the raise path, because that is where the cost lands: the
   * raise is lazy and the first decrypt of a session forces the load, after which every message
   * re-serialises and re-seals the whole log.
   */
  @Ignore("measurement harness, not a test - see the class javadoc")
  @Test
  public void whatAcapWouldBuy() throws Exception {
    final Context context = RuntimeEnvironment.getApplication();
    for (final int size : new int[] {0, 500, 2000, 10000, 20000, 50000}) {
      seed(context, size);
      final long firstWrite = writeOnce(context);
      final long steadyWrite = writeOnce(context);
      final int stored = context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE)
          .getString("UNENCRYPTED_MESSAGES", "").length();
      System.out.println("CAP " + size + " messages: first save " + firstWrite
          + " ms, next save " + steadyWrite + " ms, stored " + stored + " bytes");
    }
  }

  /** One account save with the log loaded, which is what a decrypt or a send performs. */
  private long writeOnce(final Context context) {
    final StorageHelper helper = new StorageHelper(context, BOX);
    final Account account = helper.getAccountFromSharedPreferences();
    if (account == null) return -1;
    account.getUnencryptedMessages();   // force the lazy load, as a decrypt does
    final long start = System.nanoTime();
    helper.storeAllInformationInSharedPreferences(account);
    return (System.nanoTime() - start) / 1_000_000;
  }
}
