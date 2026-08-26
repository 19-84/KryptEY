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

  /** The sealed value exactly as it sits in SharedPreferences, without going through the store. */
  private String rawStoredLog() {
    return preferences.getString(String.valueOf(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.UNENCRYPTED_MESSAGES),
        null);
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

    final String storedBefore = rawStoredLog();
    assertNotNull("precondition: there must be a stored log to compare", storedBefore);

    // Exactly what reloadAccount does after a raise.
    helper().storeAllInformationInSharedPreferences(raised);

    // The stored bytes, not just the message count.
    //
    // Comparing counts only catches the log being CLEARED. It says nothing about it being
    // rewritten, which is the half this change exists for - and a review pointed out that the
    // unconditional save passes every count-based assertion here, because it simply re-serialises
    // the same message. Ciphertext is the discriminator: every seal draws a fresh GCM nonce, so a
    // value that was written again cannot be byte-identical to the one that was not.
    assertEquals("the stored chat log was rewritten by a raise that never read it. It is still "
            + "correct, but the whole point of deferring it is that raising the keyboard should "
            + "not re-serialise and re-seal the user's entire history.",
        storedBefore, rawStoredLog());

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

  /** The loader runs once, however many times the log is asked for. */
  @Test
  public void theloaderRunsOnceHoweverOftenTheLogIsAskedFor() {
    final AtomicInteger calls = new AtomicInteger();
    final Account subject = SignalProtocolMain.getInstance().getAccount();
    subject.setMessageLogLoader(() -> {
      calls.incrementAndGet();
      final ArrayList<StorageMessage> loaded = new ArrayList<>();
      loaded.add(new StorageMessage("k", "bobAddress", "me", Instant.now(), "loaded once"));
      return loaded;
    });

    assertEquals(1, subject.getUnencryptedMessages().size());
    subject.getUnencryptedMessages();
    subject.getUnencryptedMessages();

    assertEquals("the loader must run exactly once", 1, calls.get());
  }

  /**
   * A re-entrant load is refused loudly rather than handed something that is not the log.
   *
   * <p>The first version of this file asserted that re-entrancy was <em>tolerated</em>, and
   * justified it by claiming the migration re-enters through {@code soleContactNamed}. A review
   * checked and it does not: {@code LegacyKeyMigration} takes the list once and only then walks it,
   * and {@code soleContactNamed} reads the contact list. So the test was exercising a hazard that
   * does not exist, and describing the wrong failure mode for it — mid-load both fields are in
   * flux, so a re-entrant caller would have received null and thrown a {@code NullPointerException}
   * somewhere else entirely, not "let the second result win".
   *
   * <p>What is kept is the refusal, because "no production path does this today" is a fact about
   * today. If one ever appears it should say so at the point of the mistake.
   */
  @Test
  public void arereEntrantLoadIsRefusedRatherThanReturningSomethingElse() {
    final Account subject = SignalProtocolMain.getInstance().getAccount();
    subject.setMessageLogLoader(() -> {
      subject.getUnencryptedMessages();   // re-entrant, from inside the loader
      return new ArrayList<>();
    });

    try {
      subject.getUnencryptedMessages();
      throw new AssertionError("a re-entrant load must be refused, not silently served");
    } catch (final IllegalStateException expected) {
      assertTrue("the refusal must say what happened",
          expected.getMessage().contains("re-entrant"));
    }
  }

  /**
   * A loader that throws must not leave an account that looks loaded and holds nothing.
   *
   * <p>This is the erasure path the first version of this change opened, and it needed no exotic
   * conditions: {@code readMessageLog} calls {@code JsonUtil.convertUnencryptedMessagesList}, which
   * is {@code convertValue} and throws {@code IllegalArgumentException} on any binding failure —
   * one unparseable {@code Instant} in the whole log is enough. The loader was cleared before being
   * invoked, so a throw left {@code mMessageLogLoader} null AND {@code mUnencryptedMessages} null:
   * {@code messageLogIsLoaded()} then answered true, and the next ordinary save — verifying a
   * contact, adding one, any of a dozen sites — serialised a null root as the literal string
   * {@code "null"} and committed it over the user's entire history. The decrypt path swallows
   * {@code RuntimeException}, so the process survived to do it.
   *
   * <p>The account must stay deferred instead. A store that cannot be parsed is a bad day; a store
   * that gets overwritten because it could not be parsed is unrecoverable.
   */
  @Test
  public void aloaderThatThrowsLeavesTheAccountDeferredRatherThanEmpty() {
    final Account subject = helper().getAccountFromSharedPreferences();
    assertNotNull(subject);
    subject.setMessageLogLoader(() -> {
      throw new IllegalArgumentException("one unparseable timestamp in the log");
    });

    try {
      subject.getUnencryptedMessages();
      throw new AssertionError("the loader's failure must not be swallowed");
    } catch (final IllegalArgumentException expected) {
      // The point is what the account looks like afterwards.
    }

    assertFalse("after a failed load the account must still be deferred. If it reports itself "
            + "loaded while holding nothing, the next save writes that nothing over the stored log.",
        subject.messageLogIsLoaded());
  }

  /**
   * And the whole chain: an unreadable log must survive a save, not be replaced by it.
   *
   * <p>Asserted end to end through the store rather than on the flag, because the flag is an
   * implementation detail and the property that matters is that the bytes on disk are still there.
   */
  @Test
  public void asaveAfterAfailedLogReadLeavesTheStoredLogIntact() {
    final Account subject = helper().getAccountFromSharedPreferences();
    assertNotNull(subject);
    subject.setMessageLogLoader(() -> {
      throw new IllegalArgumentException("unreadable");
    });
    try {
      subject.getUnencryptedMessages();
    } catch (final IllegalArgumentException expected) {
      // expected
    }

    helper().storeAllInformationInSharedPreferences(subject);

    final Account afterwards = helper().getAccountFromSharedPreferences();
    assertNotNull(afterwards);
    assertEquals("a failed read followed by an ordinary save destroyed the user's history",
        1, afterwards.getUnencryptedMessages().size());
    assertEquals("the meeting is at nine",
        afterwards.getUnencryptedMessages().get(0).getUnencryptedMessage());
  }

  /**
   * A stored log that cannot be read is refused, not presented as an empty history.
   *
   * <p>{@code getClassFromSharedPreferences} returns null both when nothing is stored and when what
   * is stored could not be decrypted or parsed — it catches {@code RuntimeException} deliberately,
   * so a corrupt value cannot crash the keyboard on every raise. Collapsing those two into "empty"
   * is how a transient read failure turns into permanent deletion: the account believes there is no
   * history, and the next save makes that true. Only the key's presence separates them, and keys
   * are stored in the clear.
   */
  @Test
  public void anunreadableStoredLogIsRefusedRatherThanPresentedAsEmpty() {
    preferences.edit().putString(String.valueOf(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.UNENCRYPTED_MESSAGES),
        "not a sealed envelope").commit();

    final Account subject = helper().getAccountFromSharedPreferences();
    assertNotNull(subject);
    try {
      subject.getUnencryptedMessages();
      throw new AssertionError("an unreadable log must not come back as an empty one");
    } catch (final IllegalStateException expected) {
      // The account must also still be deferred, so a save cannot write over the stored value.
    }
    assertFalse("after refusing the read the account must stay deferred",
        subject.messageLogIsLoaded());

    helper().storeAllInformationInSharedPreferences(subject);
    assertEquals("the unreadable value must still be there - overwriting what we could not read is "
            + "the destruction this refusal exists to prevent",
        "not a sealed envelope", rawStoredLog());
  }

  /**
   * And the migration will not seal an answer it computed from a log it could not read.
   *
   * <p>The symmetric case to the existing {@code contactsWereReadable} guard, which was missing.
   * This pass is one-shot and irreversible: running it against an unreadable log and then writing
   * {@code KEY_SCHEMA_MIGRATED} beside the result would classify the user's entire pre-upgrade
   * history as unattributable, permanently, on the strength of one bad read.
   */
  @Test
  public void amigrationDoesNotSealAnAnswerComputedFromAnUnreadableLog() {
    final String marker = String.valueOf(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.KEY_SCHEMA_MIGRATED);
    preferences.edit()
        .remove(marker)
        .putString(String.valueOf(com.amnesica.kryptey.inputmethod.signalprotocol
            .ProtocolIdentifier.UNENCRYPTED_MESSAGES), "not a sealed envelope")
        .commit();

    final Account subject = helper().getAccountFromSharedPreferences();
    assertNotNull("a bad chat log must not stop the account loading", subject);
    helper().storeAllInformationInSharedPreferences(subject);

    assertFalse("the migration marker was written after a failed read. The next load would then "
            + "treat the unreadable log as already migrated and never try again.",
        preferences.contains(marker));
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
