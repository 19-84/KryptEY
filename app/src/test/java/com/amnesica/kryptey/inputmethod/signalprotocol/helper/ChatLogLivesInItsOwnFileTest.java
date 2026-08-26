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
