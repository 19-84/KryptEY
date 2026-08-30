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
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The chat log lives in its own preferences file, and getting there must not cost an identity.
 *
 * <p>Why it moved: {@code SharedPreferencesImpl} serialises its whole in-memory map to XML and
 * fsyncs on every {@code commit()}. While the log shared a file with the account, every write to
 * the account — and raising the keyboard writes the account — rewrote the entire message history
 * too, whatever had actually changed. Measured: one unrelated key costs 13 ms against a small file
 * and 146 ms with a 5.35 MB sibling. Deferring the parse, done earlier, did not touch that; only
 * moving the bytes does.
 *
 * <p>The move is the risky part and most of this file is about it rather than about the speed.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatLogLivesInItsOwnFileTest {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
  private static final String LOG_KEY = String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);

  private Context context;
  private SharedPreferences accountFile;
  private SharedPreferences messageFile;
  private Contact bob;

  /** Records what each store was told about whether this device already holds data. */
  private final List<Boolean> hasExistingDataAnswers = new ArrayList<>();

  private StorageHelper.CryptoBoxFactory box() {
    return (ctx, hasExistingData) -> {
      hasExistingDataAnswers.add(hasExistingData);
      return new GcmCryptoBox() {
        @Override
        protected javax.crypto.SecretKey key() {
          return KEY;
        }
      };
    };
  }

  private StorageHelper helper() {
    return new StorageHelper(context, box());
  }

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    accountFile = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    messageFile = context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE);
    accountFile.edit().clear().commit();
    messageFile.edit().clear().commit();
    hasExistingDataAnswers.clear();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();

    bob = new Contact("Bob", "Jones", "bobAddress", 3, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);

    final ArrayList<StorageMessage> messages = new ArrayList<>();
    messages.add(new StorageMessage(
        StorageMessage.chatLogKey("bobAddress", 3), "bobAddress", "me", Instant.now(),
        "the meeting is at nine"));
    account.setUnencryptedMessages(messages);
    helper().storeAllInformationInSharedPreferences(account);
  }

  /**
   * Writes a store shaped the way every existing install is: the log inside the account's file, and
   * <b>no log file at all</b>.
   *
   * <p>Clearing the whole file rather than just the log key is load-bearing, and a control run
   * proved it: {@code EncryptedKeyValueStore} leaves a schema row behind, and
   * {@code hasEncryptedData} returns true on the strength of that row alone. Removing only the log
   * key left a file that still looked like it held encrypted data, so the identity test below
   * passed even with the two-file check reduced to one. A device that has never moved its log has
   * no such file, and that is the state worth testing.
   */
  private void putTheLogBackInTheAccountFile() {
    final String sealed = messageFile.getString(LOG_KEY, null);
    assertNotNull("precondition: the log must have been written somewhere", sealed);
    accountFile.edit().putString(LOG_KEY, sealed).commit();
    messageFile.edit().clear().commit();
    assertTrue("precondition: the log's file must look untouched, as on a pre-split install",
        messageFile.getAll().isEmpty());
  }

  /**
   * <b>The hazard.</b> Building the log's store must not tell the Keystore box this is a new device.
   *
   * <p>That boolean is the sole input to the box's refusal to mint a replacement master key, and
   * both stores share one Keystore alias. The log's file is empty on every device that has not yet
   * moved its log — which is every device in existence at the moment this ships. If the answer were
   * computed from that file alone it would be "no existing data", and on any device whose Keystore
   * key had gone the box would mint a fresh one <em>over the user's identity</em>: identity key,
   * every session, every verified contact. Silently, on an ordinary keyboard raise.
   *
   * <p>So the question is asked of both files, because it means "does this user have an identity"
   * and that is not a per-file question. This test is the one that would catch it going back.
   */
  @Test
  public void abuildingTheLogsStoreNeverClaimsAdeviceWithHistoryIsNew() {
    putTheLogBackInTheAccountFile();
    hasExistingDataAnswers.clear();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    loaded.getUnencryptedMessages();   // forces the log's store to be built

    assertFalse("no crypto box was built, so this test checked nothing",
        hasExistingDataAnswers.isEmpty());
    for (final Boolean answer : hasExistingDataAnswers) {
      assertTrue("a crypto box was told this device holds no existing data while the account file "
              + "is full of it. That boolean is what stops the Keystore box minting a replacement "
              + "master key, and both stores share one alias - so this is not a storage bug, it is "
              + "the user's identity.", answer);
    }
  }

  /**
   * And the mirror: building the ACCOUNT's store must not claim a device with a log is new.
   *
   * <p>The same disjunction, the other way round, and only one half of it was ever tested.
   * {@code secureStore} asks {@code hasEncryptedData} of the account file OR the log's file, and a
   * sweep narrowed it to the account file alone: the whole suite stayed green, while the identical
   * deletion in {@code messageStore} is killed instantly by the case above. The reason is visible
   * in that case's fixture - it sets up account file full, log's store built. Nobody had set up the
   * reverse, which is the only state where this half of the disjunction changes the answer.
   *
   * <p>The state is not hypothetical. {@code SharedPreferencesImpl.loadFromDisk} swallows a parse
   * failure and installs an empty map, so a corrupt {@code protocol.xml} reads as "no data" while
   * {@code protocol_messages.xml} is untouched and still holds the user's history. With the second
   * disjunct gone the box is told nothing is at stake, mints a replacement master key instead of
   * refusing - and because this store is always built first and both stores share one alias, the
   * log's own refusal then finds a live alias and never fires. The surviving history becomes
   * permanently unreadable, and the user is shown a working, empty keyboard rather than told
   * anything is wrong.
   *
   * <p>The whole account file is cleared rather than just its keys, for the reason
   * {@link #putTheLogBackInTheAccountFile} documents in reverse: a leftover schema row would make
   * {@code hasEncryptedData} answer true on its own strength, and the case would pass with the
   * disjunct deleted.
   */
  @Test
  public void abuildingTheAccountsStoreNeverClaimsAdeviceWithAlogIsNew() {
    assertNotNull("precondition: the log must be in its own file",
        messageFile.getString(LOG_KEY, null));
    accountFile.edit().clear().commit();
    assertTrue("precondition: the account file must look like a device that lost it - empty, with "
        + "no schema row to answer on its own", accountFile.getAll().isEmpty());
    hasExistingDataAnswers.clear();

    helper().getAccountFromSharedPreferences();   // forces the account's store to be built

    assertFalse("no crypto box was built, so this test checked nothing",
        hasExistingDataAnswers.isEmpty());
    for (final Boolean answer : hasExistingDataAnswers) {
      assertTrue("a crypto box was told this device holds no existing data while the log's file "
              + "still holds the user's history. This store is built first and both stores share "
              + "one Keystore alias, so a replacement key minted here is one the log's store then "
              + "finds already live - its own refusal never runs, and the history is unreadable "
              + "for good.", answer);
    }
  }

  /** An existing install's log is moved out of the account file, intact. */
  @Test
  public void thelogIsMovedOutOfTheAccountFileOnTheNextLoad() {
    putTheLogBackInTheAccountFile();
    assertNotNull("precondition: the log starts in the account file",
        accountFile.getString(LOG_KEY, null));

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    final ArrayList<StorageMessage> messages = loaded.getUnencryptedMessages();

    assertEquals("the move must not lose messages", 1, messages.size());
    assertEquals("the meeting is at nine", messages.get(0).getUnencryptedMessage());
    assertNotNull("the log must now be in its own file", messageFile.getString(LOG_KEY, null));
    assertNull("and it must be gone from the account file, or the raise still rewrites it",
        accountFile.getString(LOG_KEY, null));
  }

  /**
   * A move interrupted after the copy finishes on the next load rather than staying half-done.
   *
   * <p>The copy-verify-delete order means a kill can leave the log in both files. That costs disk
   * and nothing else, but only because the next load is expected to clean it up — so that is
   * asserted rather than assumed, and it makes the move idempotent instead of one-shot.
   */
  @Test
  public void aninterruptedMoveIsFinishedByTheNextLoad() {
    // Exactly the state a kill between the copy and the delete leaves behind.
    final String sealed = messageFile.getString(LOG_KEY, null);
    assertNotNull(sealed);
    accountFile.edit().putString(LOG_KEY, sealed).commit();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    assertEquals("the surviving copy must be readable", 1, loaded.getUnencryptedMessages().size());
    assertNull("the stale copy in the account file must be cleared, or it is rewritten on every "
        + "raise forever", accountFile.getString(LOG_KEY, null));
  }

  /**
   * A surviving chat log is by itself enough to say this device is not a fresh install.
   *
   * <p>The split created a survivor, and the fresh-install gate did not know about it. That gate
   * exists because the "is this a first run?" boolean lives in device-protected storage while the
   * identity lives in credential-protected storage, and the two can be lost independently — so it
   * asks the store instead. It asked only the account's file.
   *
   * <p>{@code SharedPreferencesImpl.loadFromDisk} swallows a parse failure and installs an empty
   * map, so a corrupt {@code protocol.xml} reads as "no data" while its sibling is untouched. The
   * gate then says fresh install, a new account is generated, and its empty log — a brand-new
   * account reports its log loaded — is written straight over the history that survived. Before the
   * split the log lived in the file that was lost, so there was nothing left to destroy; afterwards
   * there is.
   */
  @Test
  public void asurvivingChatLogMeansThisDeviceIsNotAfreshInstall() {
    assertNotNull("precondition: the log must be stored in its own file",
        messageFile.getString(LOG_KEY, null));

    // Exactly what a corrupt or lost protocol.xml looks like to the framework.
    accountFile.edit().clear().commit();

    assertTrue("a chat log survived while the account file did not, and the gate called this a "
            + "fresh install. The next save writes a new account's empty log over the history that "
            + "survived, silently.",
        helper().hasExistingProtocolData());
  }

  /** And the user is told the store is unreadable rather than shown fresh-install text. */
  @Test
  public void asurvivingChatLogIsReportedAsUnreadableRatherThanAbsent() {
    accountFile.edit().clear().commit();

    assertEquals("with history on disk that cannot be loaded, the strip must say the store is "
            + "unreadable - showing the ordinary first-run text invites the user to start again "
            + "over their own data", StorageHelper.StorageState.UNREADABLE, helper().storageState());
  }

  /**
   * "Must not overwrite" and "did the identity persist" are different questions.
   *
   * <p>They were briefly the same method. The chat log is written before the account batch, so an
   * install whose log commit succeeded and whose account batch failed satisfied the combined
   * predicate — {@code initialize()} reported success, the caller recorded "setup done"
   * permanently, and the keyboard was left with no identity and no way back.
   */
  @Test
  public void theoverwriteCheckAndTheIdentityCheckAreDifferentQuestions() {
    // Exactly the state a first save leaves when the log lands and the account batch does not.
    accountFile.edit().clear().commit();
    assertNotNull("precondition: the log must be stored", messageFile.getString(LOG_KEY, null));

    assertTrue("there is data on this device, so nothing may overwrite it",
        helper().hasExistingProtocolData());
    assertFalse("but the identity did NOT reach disk, and initialize() must not report success - "
            + "the caller would record 'setup done' over a device with no identity",
        helper().identityReachedDisk());
  }

  /**
   * An install that has never sent a message does not create the log's file at all.
   *
   * <p>Every save used to write the log, and a brand-new account reports its empty log as loaded -
   * so every install created {@code protocol_messages} on its first save. That file's existence is
   * now one of the things that says "this device holds data", so an install with no history at all
   * looked like one worth protecting: lose the account file afterwards and the keyboard refuses to
   * re-initialise, permanently, in order to preserve nothing.
   */
  @Test
  public void afreshInstallWithNoMessagesDoesNotCreateTheLogsFile() throws Exception {
    accountFile.edit().clear().commit();
    messageFile.edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account fresh = SignalProtocolMain.getInstance().getAccount();
    helper().storeAllInformationInSharedPreferences(fresh);

    assertNull("a device that has never stored a message must not have a chat-log entry",
        messageFile.getString(LOG_KEY, null));

    // The previous assertion here was dead: it ANDed "has data" with "identity did not reach disk"
    // just after a successful store, so the conjunction was false for any implementation and could
    // not fail. What is worth asserting is the thing that would actually brick the device - that
    // losing the account file afterwards does not leave something claiming history to preserve.
    accountFile.edit().clear().commit();
    assertFalse("with no messages ever stored and the account file gone, nothing should claim this "
            + "device holds data worth refusing to re-initialise over",
        new StorageHelper(context, box()).hasExistingProtocolData());
  }

  /**
   * But clearing a history that exists does persist as an empty log.
   *
   * <p>The skip above is "nothing to say and nothing already said". Once something has been stored,
   * an empty list is a user deleting their history and must survive - otherwise the next load
   * brings it all back.
   */
  @Test
  public void clearingAstoredHistoryPersistsAsAnEmptyLog() {
    assertNotNull("precondition: there is a stored history", messageFile.getString(LOG_KEY, null));

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    loaded.getUnencryptedMessages().clear();
    helper().storeAllInformationInSharedPreferences(loaded);

    final Account afterwards = helper().getAccountFromSharedPreferences();
    assertNotNull(afterwards);
    assertEquals("clearing a stored history must persist, not be skipped as 'nothing to say'",
        0, afterwards.getUnencryptedMessages().size());
  }

  /**
   * An upgrade whose log is empty does not create the log's file just to say so.
   *
   * <p>This is the whole existing user base, not an edge case: every install predating this branch
   * has an {@code UNENCRYPTED_MESSAGES} value even with no messages, because the old batch wrote it
   * whenever the log was loaded and a fresh account reports its empty log loaded. So the first
   * keyboard raise after upgrading runs the move with {@code "[]"}. Copying that would create the
   * log's file on every one of those devices — and that file's existence is what says "this device
   * holds data", so losing the account file afterwards would leave the keyboard refusing to
   * re-initialise, permanently, to preserve an empty list.
   */
  @Test
  public void anupgradeWithAnEmptyLogDoesNotCreateTheLogsFile() {
    // A pre-split install that never sent a message: "[]" sitting in the account file.
    final Account emptied = helper().getAccountFromSharedPreferences();
    assertNotNull(emptied);
    emptied.getUnencryptedMessages().clear();
    helper().storeAllInformationInSharedPreferences(emptied);
    final String sealedEmpty = messageFile.getString(LOG_KEY, null);
    assertNotNull("precondition: an empty log must have been stored", sealedEmpty);
    accountFile.edit().putString(LOG_KEY, sealedEmpty).commit();
    messageFile.edit().clear().commit();

    final Account loaded = new StorageHelper(context, box()).getAccountFromSharedPreferences();
    assertNotNull(loaded);
    assertEquals("the empty log must read back as empty", 0, loaded.getUnencryptedMessages().size());

    assertNull("moving an empty log must not create the log's file - that file's existence is what "
        + "makes a device look like it has history worth refusing to re-initialise over",
        messageFile.getString(LOG_KEY, null));
    assertNull("and the empty value must be dropped from the account file, not left to be "
        + "rewritten on every raise", accountFile.getString(LOG_KEY, null));
  }

  /**
   * Deleting history persists even while the log still lives in the account file.
   *
   * <p>Reachable whenever a move did not complete — no message store, a failed write, a failed
   * read-back — and {@code readMessageLog} serves the log from the account file in exactly that
   * state. The "nothing to say and nothing already said" skip has to consider both files, or a user
   * who deletes their last contact gets the empty list skipped while their full plaintext history
   * sits in the account file and returns on the next raise.
   */
  @Test
  public void clearingHistoryPersistsEvenWhileTheLogIsStillInTheAccountFile() {
    // Load FIRST, then put the files into the failed-move state.
    //
    // Doing it the other way round does not work and a control run proved it: any load of a log
    // sitting in the account file moves it, so by the time the save happens the log is in its own
    // file and the single-file check answers correctly. The state this test is about is one where
    // the move did NOT complete while the account is already loaded in memory, so it has to be
    // built after the load and before the save.
    final Account loaded = new StorageHelper(context, box()).getAccountFromSharedPreferences();
    assertNotNull(loaded);
    assertEquals("precondition: the history must be readable", 1,
        loaded.getUnencryptedMessages().size());

    final String sealed = messageFile.getString(LOG_KEY, null);
    assertNotNull(sealed);
    accountFile.edit().putString(LOG_KEY, sealed).commit();
    messageFile.edit().clear().commit();

    loaded.getUnencryptedMessages().clear();
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(loaded);

    final Account afterwards = new StorageHelper(context, box()).getAccountFromSharedPreferences();
    assertNotNull(afterwards);
    assertEquals("the user deleted their history and it came back - the skip looked only at the "
        + "log's own file while the history was in the account file",
        0, afterwards.getUnencryptedMessages().size());
  }

  /** And after the move, a raise leaves the log's file untouched. */
  @Test
  public void araiseDoesNotRewriteTheLogsFile() {
    final String before = messageFile.getString(LOG_KEY, null);
    assertNotNull("precondition: there must be a stored log", before);

    final Account raised = helper().getAccountFromSharedPreferences();
    assertNotNull(raised);
    helper().storeAllInformationInSharedPreferences(raised);

    assertEquals("a raise re-sealed the chat log. Every seal draws a fresh nonce, so an identical "
            + "value is the proof it was not written - which is the whole point of the split.",
        before, messageFile.getString(LOG_KEY, null));
    assertNull("and the account file must not have acquired a copy",
        accountFile.getString(LOG_KEY, null));
  }
}
