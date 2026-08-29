package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The help text promises that deleting a contact deletes their message history. The chat log lives
 * in two files during a move, and this is the state where that promise is easiest to break.
 *
 * <p>{@code moveMessageLogToItsOwnFile} is copy-verify-delete, and a process kill between the copy
 * and the delete leaves the log in <em>both</em> files. The account-file copy is then unreachable
 * through the reader, which prefers the new location — so a deletion sweep operating on the loaded
 * list would write the pruned log to the message store and leave the un-pruned copy sitting in
 * {@code protocol.xml}. An input-method process is killed as a matter of routine, so the
 * interrupted state is ordinary rather than exotic.
 *
 * <p>A review round flagged this as the one row of its deletion table it could not settle by
 * reading: it read the mechanism and did not trace a trigger. The mechanism is sound — the next
 * read finishes the move before returning — but "sound by comment" is what this branch keeps
 * finding wrong, so it is measured here through the real deletion path.
 */
@RunWith(RobolectricTestRunner.class)
public class AninterruptedLogMoveStillHonoursAdeletionTest {

  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
  private static final String SECRET = "the whole conversation, in the clear";

  private Context context;
  private Contact bob;

  private StorageHelper helper() {
    return new StorageHelper(context, (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return KEY;
      }
    });
  }

  private SharedPreferences accountFile() {
    return context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
  }

  private SharedPreferences messageFile() {
    return context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE);
  }

  private static String logKey() {
    return String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    accountFile().edit().clear().commit();
    messageFile().edit().clear().commit();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.setStorageHelperFactoryForTest(ctx -> helper());
    SignalProtocolMain.initialize(context);

    bob = new Contact("Bob", "Jones", "bob-uuid", 5, false);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /**
   * Seeds the state a kill between the copy and the delete leaves: the log in both files.
   */
  private void thelogIsInBothFiles() {
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);

    final ArrayList<StorageMessage> log = new ArrayList<>();
    log.add(new StorageMessage(StorageMessage.chatLogKey("bob-uuid", 5), "bob-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        SECRET, true));
    account.setUnencryptedMessages(log);
    assertTrue(helper().storeAllInformationInSharedPreferences(account));

    // The copy landed in the message file. Put it back in the account file too, which is exactly
    // what the interrupted move leaves behind.
    assertTrue("fixture: the log must be in its own file", messageFile().contains(logKey()));
    accountFile().edit()
        .putString(logKey(), messageFile().getString(logKey(), null)).commit();
    assertTrue("fixture: and in the account file", accountFile().contains(logKey()));

    // And the account is reloaded, so its log is DEFERRED - which is the state a process kill
    // actually leaves. Written first without this, and the test failed: setUnencryptedMessages
    // marks the log loaded, so getUnencryptedMessages never calls the loader, and the cleanup that
    // finishes an interrupted move lives inside the loader. The fixture has to reach the boundary
    // or it is measuring a state production never has.
    final Account reloaded = helper().getAccountFromSharedPreferences();
    assertNotNull("fixture: the store must reload", reloaded);
    assertFalse("fixture: the reloaded log must be deferred, or the loader is never entered",
        reloaded.messageLogIsLoaded());
    SignalProtocolMain.getInstance().setAccount(reloaded);
  }

  /**
   * Deleting the contact must erase the plaintext from BOTH files, not just the live one.
   *
   * <p>The account-file copy is unreachable through the reader once the message file has one, so a
   * sweep that ran on the loaded list and wrote only the message store would leave it behind — the
   * user having been told, in the help text, that it was gone.
   */
  @Test
  public void deletingAcontactErasesThePlaintextFromBothFiles() {
    thelogIsInBothFiles();

    assertTrue("precondition: the deletion must actually happen",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));

    final String stillInTheAccountFile = accountFile().getString(logKey(), null);
    assertFalse("the account file still holds a chat-log value after the contact was deleted. It "
            + "is unreachable through the reader, which prefers the new location, so nothing will "
            + "ever prune it - and the help text says the history is gone",
        stillInTheAccountFile != null);

    final String stillInTheMessageFile = messageFile().getString(logKey(), null);
    assertNotNull("the message file must still exist - its presence is what says this device holds "
        + "data, and losing that lets a later load generate over the identity", stillInTheMessageFile);
    assertFalse("and the sealed value must no longer contain the plaintext",
        stillInTheMessageFile.contains(SECRET));
  }

  /**
   * And the contact's messages really are the thing being erased, not the whole log.
   *
   * <p>Without this the assertion above passes just as well against a deletion that erased
   * everything, which would be a different defect wearing the same green tick.
   */
  @Test
  public void anothercontactsMessagesSurviveTheDeletion() {
    final Contact carol = new Contact("Carol", "Danvers", "carol-uuid", 6, false);
    thelogIsInBothFiles();

    // The reloaded account, whose log is deferred - getUnencryptedMessages below is what loads it.
    final Account account = SignalProtocolMain.getInstance().getAccount();
    final ArrayList<Contact> contacts = new ArrayList<>(account.getContactList());
    contacts.add(carol);
    account.setContactList(contacts);
    account.getUnencryptedMessages().add(new StorageMessage(
        StorageMessage.chatLogKey("carol-uuid", 6), "carol-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_100L),
        "carol's words", true));
    assertTrue(helper().storeAllInformationInSharedPreferences(account));

    assertTrue(SignalProtocolMain.removeContactFromContactListAndProtocol(bob));

    final Account reloaded = helper().getAccountFromSharedPreferences();
    assertNotNull(reloaded);
    assertEquals("exactly one message must survive, and it must be Carol's", 1,
        reloaded.getUnencryptedMessages().size());
    assertEquals("carol's words",
        reloaded.getUnencryptedMessages().get(0).getUnencryptedMessage());
  }
}
