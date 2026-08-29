package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;

/**
 * The migration is irreversible, it runs exactly once, and it runs on whatever the load managed to
 * assemble.
 *
 * <p>{@code getAccountFromSharedPreferences} deliberately tolerates a contact list it cannot read -
 * "continuing with an empty list" - because the alternative used to be writing the string "null"
 * back over the user's data. That tolerance was safe while every reader matched at read time: the
 * next load read the list again. It is not safe now. {@code migrateLegacyKeys} runs at the end of
 * the same method, an empty contact list makes every legacy chat-log entry unattributable, and
 * unattributable entries are DELETED - after which the marker is sealed, so nothing will ever look
 * again, and {@code reloadAccount}'s write-back puts the emptied log on disk.
 *
 * <p>One unreadable value therefore destroys the user's entire pre-upgrade message history,
 * permanently and silently. The two decisions that make it permanent - delete rather than orphan,
 * and mark done - are individually defensible; taken together with a load path that reports failure
 * by substituting an empty list, they are not.
 */
@RunWith(RobolectricTestRunner.class)
public class MigrationOnPartialLoadTest {

  private static final String HISTORY = "everything we have ever said to each other";

  private Context context;
  private Account account;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", "bob-uuid", 5, false));
    account.setContactList(contacts);
    // The pre-upgrade chat log: keyed by the bare address name.
    account.getUnencryptedMessages().add(new StorageMessage("bob-uuid", "bob-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        HISTORY, false));
  }

  /**
   * A load that could not read the contact list must not be the load that decides, once and for
   * all, that nobody owns the user's history.
   */
  @Test
  public void anunreadableContactListMustNotDestroyTheChatLog() {
    final StorageHelper writer = new StorageHelper(context, workingBox());
    writer.storeAllInformationInSharedPreferences(account);
    // One value the load cannot make sense of. A truncated or half-written record reads exactly
    // like this: the envelope opens, and what comes out is not the shape the reader expects.
    writer.storeInSharedPreferences(ProtocolIdentifier.CONTACTS, "not-a-contact-list");

    final Account loaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull("precondition: the load must still produce an account", loaded);
    assertTrue("precondition: this must be the 'contacts could not be read' branch",
        loaded.getContactList().isEmpty());

    assertEquals("a transient read failure on ONE stored value has permanently deleted the whole "
            + "pre-upgrade message history, and sealed the marker that stops anything looking "
            + "again", 1, loaded.getUnencryptedMessages().size());
    assertEquals(HISTORY, loaded.getUnencryptedMessages().get(0).getUnencryptedMessage());
  }

  /**
   * The marker records that the migration happened before one byte of what it did is on disk.
   *
   * <p>The load mutates the account in memory and commits the marker; the write-back that persists
   * the re-keyed log is a separate commit made by the caller afterwards. Between the two the
   * migration has been promised and not delivered, and an IME process is killed as a matter of
   * routine. What survives is a store marked migrated whose chat log is still keyed by bare names:
   * every entry unreachable from every contact row for good - which is precisely the orphaned
   * plaintext the design says is worse than deletion, and which the user cannot erase, because
   * erasing a conversation means deleting a contact and no contact owns it.
   */
  @Test
  public void themarkerMustNotOutrunTheDataItDescribes() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(account);
    // A store written by the pre-upgrade binary carries no marker. Every store this code writes
    // does, so the fixture has to take it back off to stand in for one.
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit()
        .remove(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
            .KEY_SCHEMA_MIGRATED.toString())
        .commit();

    // A load whose result never reaches the write-back: the process died on the way there.
    final Account discarded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull(discarded);
    assertEquals("precondition: that load did migrate, in memory",
        StorageMessage.chatLogKey("bob-uuid", 5),
        discarded.getUnencryptedMessages().get(0).getContactUUID());

    final Account reloaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull(reloaded);
    assertEquals("precondition: the entry is still there", 1,
        reloaded.getUnencryptedMessages().size());
    assertEquals("the store is marked migrated and the chat log is not: this entry is now "
            + "unreachable from every contact row, and unerasable, for the life of the install",
        StorageMessage.chatLogKey("bob-uuid", 5),
        reloaded.getUnencryptedMessages().get(0).getContactUUID());
  }

  /**
   * A load that could not read the contacts must leave the store un-migrated, so a healthy one can
   * still do the job.
   *
   * <p>With ambiguous entries now kept rather than deleted, running the migration against an empty
   * contact list no longer destroys anything — which is why the sibling test above passes with the
   * guard removed. What it would still do is seal the marker over a log that never got re-keyed,
   * and a bare-keyed entry matches no contact row: unreachable for the life of the install, and
   * unerasable, because erasing a conversation means deleting a contact and no contact owns it.
   *
   * <p>So the assertion is about the marker, not the messages. This is the case the guard exists
   * for, and it had no test until the control showed the guard could be deleted unnoticed.
   */
  @Test
  public void aloadThatCouldNotReadContactsMustNotMarkTheStoreMigrated() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(account);
    final android.content.SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().remove(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
        .KEY_SCHEMA_MIGRATED.toString()).commit();

    // The contact list becomes unreadable; the load tolerates that and continues with an empty one.
    preferences.edit().putString(
        com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier.CONTACTS.toString(),
        "not decipherable as a contact list").commit();

    final Account loaded =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull(loaded);
    assertTrue("precondition: the contact list must have come back empty for this to be the case "
        + "the guard is about", loaded.getContactList().isEmpty());

    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(loaded);

    // Contacts readable again, marker absent, so the migration gets its proper chance.
    assertEquals("the store must not have been marked migrated by a load that could not see the "
            + "contacts - sealing that answer strands every entry, unreachable and unerasable",
        "bob-uuid", loaded.getUnencryptedMessages().get(0).getContactUUID());
    assertTrue("and the marker must still be absent, so a healthy load can still re-key",
        preferences.getString(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
            .KEY_SCHEMA_MIGRATED.toString(), null) == null);
  }

  /** A working box with a fixed key, as StorageHelperTest uses. */
  private StorageHelper.CryptoBoxFactory workingBox() {
    return (ctx, hasExistingData)
        -> new com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox() {
          @Override
          protected javax.crypto.SecretKey key() {
            return KEY;
          }
        };
  }

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

  /**
   * A chat-log write that FAILS must not let the marker through either.
   *
   * <p>The write order was chosen against a process kill between the two commits: the log first, so
   * that what survives a kill is a re-keyed log with no marker, which the next load simply migrates
   * again. It says nothing about the log commit returning false — an ordinary outcome on a full
   * disk, and the reason {@code lastMessageLogWriteSucceeded()} exists at all. In that case the
   * account batch was still committed, marker and all, and the result is the state the ordering
   * exists to prevent: a store asserting every key in its log is a rendered address, over a log
   * that never received the re-keying. Unreachable from every contact row for the life of the
   * install, and unerasable, because erasing a conversation means deleting a contact and no contact
   * owns those entries.
   *
   * <p>The whole save is refused rather than the one key dropped. Dropping the marker alone leaves
   * the account batch landing over entries still flagged unresolved, and the next load re-asks
   * "which single contact bears this address name?" against a contact list that landed batch has
   * had a raise to change — the measured pass-two substitution this file records elsewhere.
   */
  @Test
  public void alostChatLogWriteMustNotLetTheMarkerThrough() {
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(account);
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit()
        .remove(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
            .KEY_SCHEMA_MIGRATED.toString())
        .commit();

    // The load migrates in memory. Then the log write fails - through the real mechanism, not an
    // override: a stray cleartext key in the message file makes requireEncryptedOnly throw, so
    // messageStore() returns null and storeMessageLog returns false. That file is written only by
    // this branch and only through the encrypted store, so cleartext in it is never legitimate.
    final Account migrated =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE).edit()
        .putString("something-this-app-never-wrote", "in the clear").commit();
    final StorageHelper helper = new StorageHelper(context, workingBox());
    assertNotNull(migrated);
    assertTrue("precondition: the load must have migrated in memory", migrated.keysAreRendered());

    final boolean saved = helper.storeAllInformationInSharedPreferences(migrated);

    assertFalse("the save must be refused outright, not partly committed", saved);
    assertNull("and the marker must not be on disk: it would assert the log had been re-keyed when "
            + "the write that would have re-keyed it failed",
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE).getString(
            com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
                .KEY_SCHEMA_MIGRATED.toString(), null));
  }
}
