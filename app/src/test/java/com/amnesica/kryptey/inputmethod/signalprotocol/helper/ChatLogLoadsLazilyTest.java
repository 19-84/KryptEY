package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The plaintext chat log is no longer parsed to raise the keyboard.
 *
 * <p>REVIVAL.md carried this as a deferred defect with its numbers attached: the log has no size cap
 * and no age cap, entries leave only when a contact is deleted, and loading an account happens on
 * {@code setInputView} — which runs every time the keyboard is raised, in every app. Measured at
 * ~358 characters per message, 20,000 messages is 7.16 MB, 72 ms to read and 194 ms to write. The
 * write matters as much as the read and the original entry understated it: a reload is followed by a
 * write-back, so a raise was paying for a full parse <em>and</em> a full re-serialisation of history
 * the keyboard never looks at. It is also peer-paced — a correspondent can send messages, and each
 * one is kept forever.
 *
 * <p>The entry deferred the fix because {@code LegacyKeyMigration.apply} iterates the message list
 * during the account load, so "a lazy load either skips that migration or forces the read it was
 * meant to avoid". That turned out to be a smaller problem than it looked: the migration is gated on
 * a marker, and when the marker is present it does not run at all. So the read is forced on exactly
 * one load per install — the one that actually migrates — and never again. The migration itself is
 * untouched, which matters, because it is the component on this branch with the worst record for
 * being changed.
 *
 * <p>The dangerous half of this change is not the read, it is the write: the log is now omitted from
 * the save batch when nothing has loaded it. If that were wrong it would erase the user's entire
 * history on the next raise, silently. {@code EncryptedKeyValueStore.putAll} writes the keys it is
 * given and clears nothing — and {@link #asaveThatNeverTouchedTheLogLeavesTheStoredLogIntact} is the
 * test that says so rather than the comment.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatLogLoadsLazilyTest {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

  private Context context;
  private SharedPreferences preferences;
  private Account account;
  private Contact bob;

  private StorageHelper.CryptoBoxFactory box() {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected javax.crypto.SecretKey key() {
        return KEY;
      }
    };
  }

  private StorageHelper helper() {
    return new StorageHelper(context, box());
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    bob = new Contact("Bob", "Jones", "bobAddress", 3, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);

    final ArrayList<StorageMessage> messages = new ArrayList<>();
    messages.add(new StorageMessage(
        StorageMessage.chatLogKey(bob.getSignalProtocolAddressName(), bob.getDeviceId()),
        "bobAddress", "me", Instant.now(), "the meeting is at nine"));
    account.setUnencryptedMessages(messages);

    helper().storeAllInformationInSharedPreferences(account);
  }

  /** The property the whole change exists for. */
  @Test
  public void anaccountLoadsWithoutReadingTheChatLog() {
    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("precondition: the store must reload", loaded);

    assertFalse("loading an account read the chat log. This runs on setInputView - every keyboard "
            + "raise, in every app - and the log is the one part of the store that grows without "
            + "bound and is paced by the peer, not the user.",
        loaded.messageLogIsLoaded());
  }

  /** And the deferral does not lose anything: asked for, the log is exactly what was stored. */
  @Test
  public void thelogIsStillCorrectWhenSomethingActuallyAsksForIt() {
    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);

    final ArrayList<StorageMessage> messages = loaded.getUnencryptedMessages();
    assertEquals("the deferred log must come back with exactly what was stored", 1, messages.size());
    assertEquals("the meeting is at nine", messages.get(0).getUnencryptedMessage());
    assertTrue("asking for it must mark it loaded", loaded.messageLogIsLoaded());
  }

  /**
   * The dangerous one: a save that never touched the log must not erase it.
   *
   * <p>This is the exact shape of a raise. The keyboard loads an account, writes it back, and never
   * looks at a message. If omitting the log from the batch cleared the stored value instead of
   * leaving it alone, the user's entire history would be gone after one raise, with no error.
   */
  @Test
  public void asaveThatNeverTouchedTheLogLeavesTheStoredLogIntact() {
    final Account raised = helper().getAccountFromSharedPreferences();
    assertNotNull(raised);
    assertFalse("precondition: this test is about a save that never read the log",
        raised.messageLogIsLoaded());

    // Exactly what reloadAccount does after a raise.
    helper().storeAllInformationInSharedPreferences(raised);

    final Account afterwards = helper().getAccountFromSharedPreferences();
    assertNotNull(afterwards);
    assertEquals("a raise erased the user's message history. The log is omitted from the save "
            + "batch when nothing loaded it, which is only safe because putAll writes the keys it "
            + "is given and clears nothing.",
        1, afterwards.getUnencryptedMessages().size());
    assertEquals("the meeting is at nine",
        afterwards.getUnencryptedMessages().get(0).getUnencryptedMessage());
  }

  /** A log that WAS touched is still written, or edits would be silently dropped. */
  @Test
  public void alogThatWasTouchedIsWrittenBack() {
    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);

    loaded.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey(bob.getSignalProtocolAddressName(), bob.getDeviceId()),
        "me", "bobAddress", Instant.now(), "and bring the folder"));
    helper().storeAllInformationInSharedPreferences(loaded);

    final Account afterwards = helper().getAccountFromSharedPreferences();
    assertNotNull(afterwards);
    assertEquals("a message added after the account was loaded must survive the save",
        2, afterwards.getUnencryptedMessages().size());
  }

  /**
   * The loader runs once, however many times the log is asked for.
   *
   * <p>Not a performance nicety. The loader is cleared before it is invoked precisely because the
   * migration reaches back into the account while loading, and a re-entrant call would run the read
   * twice and let the second result win — discarding whatever the first pass had already changed.
   */
  @Test
  public void theloaderRunsAtMostOnceEvenUnderReEntrantAccess() {
    final AtomicInteger calls = new AtomicInteger();
    final Account subject = SignalProtocolMain.getInstance().getAccount();
    subject.setMessageLogLoader(() -> {
      calls.incrementAndGet();
      // Re-entrant on purpose: this is what the migration does through soleContactNamed.
      subject.getUnencryptedMessages();
      final ArrayList<StorageMessage> loaded = new ArrayList<>();
      loaded.add(new StorageMessage("k", "bobAddress", "me", Instant.now(), "loaded once"));
      return loaded;
    });

    assertEquals(1, subject.getUnencryptedMessages().size());
    subject.getUnencryptedMessages();
    subject.getUnencryptedMessages();

    assertEquals("the loader must run exactly once; a re-entrant call running it twice would let "
        + "the second result overwrite work the first pass had already done", 1, calls.get());
  }

  /**
   * The one load that migrates still reads the log, and still re-keys it.
   *
   * <p>This is the case the deferral entry was worried about, and the reason the fix is safe: the
   * migration is gated on a marker, so it runs on exactly one load per install. On that load the
   * read is forced — deliberately — and the entry is re-keyed as before. If laziness ever caused
   * the migration to run against an empty log instead, the marker would then be written and that
   * answer sealed, and the user's pre-upgrade history would be permanently unattributable. So this
   * asserts both halves: that the log was read, and that the re-key actually happened.
   */
  @Test
  public void themigratingLoadStillReadsAndReKeysTheLog() {
    // A pre-upgrade store: the chat log keyed by a bare address name, and no schema marker.
    final ArrayList<StorageMessage> legacy = new ArrayList<>();
    legacy.add(new StorageMessage(bob.getSignalProtocolAddressName(), "bobAddress", "me",
        Instant.now(), "sent before the upgrade"));
    account.setUnencryptedMessages(legacy);
    account.setKeysAreRendered(false);
    helper().storeAllInformationInSharedPreferences(account);
    preferences.edit()
        .remove(String.valueOf(
            com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.KEY_SCHEMA_MIGRATED))
        .commit();

    final Account migrated = helper().getAccountFromSharedPreferences();
    assertNotNull(migrated);

    assertTrue("the migrating load must have read the log - if it migrated an empty list the "
            + "marker would seal that answer and the pre-upgrade history would be lost",
        migrated.messageLogIsLoaded());
    assertEquals(1, migrated.getUnencryptedMessages().size());
    assertEquals("the legacy entry must have been re-keyed onto the contact's full address",
        StorageMessage.chatLogKey(bob.getSignalProtocolAddressName(), bob.getDeviceId()),
        migrated.getUnencryptedMessages().get(0).getContactUUID());
  }

  /** And a fresh account, which has no store behind it, still starts with an empty log. */
  @Test
  public void afreshAccountHasAnEmptyLogRatherThanANullOne() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();

    assertNotNull("a fresh account must not carry a null log", fresh.getUnencryptedMessages());
    assertEquals(0, fresh.getUnencryptedMessages().size());
  }
}
