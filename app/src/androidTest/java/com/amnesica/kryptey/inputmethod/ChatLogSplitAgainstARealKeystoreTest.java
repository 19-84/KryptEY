package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.AndroidKeystoreCryptoBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.ArrayList;

/**
 * The chat-log split, run against a real Android Keystore rather than a fake box.
 *
 * <p>The JVM tests for this substitute a fixed AES key, which is the right thing there and leaves
 * one property untested: the split builds a <em>second</em> {@code EncryptedKeyValueStore}, and
 * both stores resolve the same Keystore alias. The hazard that makes the split more than a rename
 * is that the boolean handed to the crypto box — "does this device already hold data" — is the sole
 * input to the box's refusal to mint a replacement master key. Compute it from the log's own file,
 * which is empty on every device that has not yet moved its log, and the box is free to mint a new
 * key over the user's identity.
 *
 * <p>A fake box cannot show that, because a fake box has no key to lose. This can: it uses the real
 * {@code AndroidKeystoreCryptoBox}, and the symptom it looks for is the one a user would suffer —
 * the identity key changing across an ordinary reload.
 *
 * <p>The Keystore alias is device-global and shared with every other instrumentation class, so it
 * is destroyed on both sides of every test, in setUp and in tearDown - the discipline
 * {@code InstrumentationTestsCleanUpTheKeystoreTest} enforces. A class that seeds a master key and
 * leaves it behind turns a later class's "no existing key" case into a passing test of nothing.
 * (Written without naming the annotations: that guard scans for the first occurrence of each in
 * the file, so mentioning them in prose above the code makes it read the javadoc instead.)
 *
 * <p>Runs only on a device or emulator, via {@code tools/test-on-emulator}.
 */
@RunWith(AndroidJUnit4.class)
public class ChatLogSplitAgainstARealKeystoreTest {

  private static final String LOG_KEY = String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);

  private Context context;
  private SharedPreferences accountFile;
  private SharedPreferences messageFile;

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();   // shared alias; see javadoc

    accountFile = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    messageFile = context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE);
    accountFile.edit().clear().commit();
    messageFile.edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
  }

  @After
  public void tearDown() {
    accountFile.edit().clear().commit();
    messageFile.edit().clear().commit();
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = false;
  }

  /** Builds an account with one message and saves it through the real Keystore-backed store. */
  private Account seed() throws Exception {
    SignalProtocolMain.initialize(context);
    final Account account = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("the protocol must initialise against the real Keystore", account);

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", "bobAddress", 3, false));
    account.setContactList(contacts);

    final ArrayList<StorageMessage> messages = new ArrayList<>();
    messages.add(new StorageMessage(StorageMessage.chatLogKey("bobAddress", 3), "bobAddress", "me",
        Instant.now(), "the meeting is at nine"));
    account.setUnencryptedMessages(messages);

    new StorageHelper(context).storeAllInformationInSharedPreferences(account);
    return account;
  }

  /**
   * The identity survives building the log's second store, on a store shaped like a real upgrade.
   *
   * <p>The account file holds everything and the log's file does not exist — exactly the state of
   * every install at the moment this ships. Loading then forces the second store to be built. If it
   * told the Keystore box this was a fresh device, the box could mint a new master key, and the
   * identity key that comes back would not be the one that went in.
   */
  @Test
  public void theidentityKeySurvivesTheMoveToAsecondStore() throws Exception {
    final Account before = seed();
    final String identityBefore =
        before.getIdentityKeyPair().getPublicKey().getFingerprint();

    // Put the store back into its pre-split shape: log inside the account file, no log file at all.
    final String sealed = messageFile.getString(LOG_KEY, null);
    assertNotNull("precondition: the log must have been stored", sealed);
    accountFile.edit().putString(LOG_KEY, sealed).commit();
    messageFile.edit().clear().commit();
    assertTrue("precondition: a pre-split device has no log file", messageFile.getAll().isEmpty());

    final Account after = new StorageHelper(context).getAccountFromSharedPreferences();
    assertNotNull("the account must still load", after);
    after.getUnencryptedMessages();   // forces the log's store, and its crypto box, to be built

    assertEquals("the identity key changed across a reload that moved the chat log. The second "
            + "store told the Keystore box this device held no data, so it minted a replacement "
            + "master key - every session and every verified contact is gone.",
        identityBefore, after.getIdentityKeyPair().getPublicKey().getFingerprint());
  }

  /** And the move itself works against real encryption: the log arrives, and leaves the old file. */
  @Test
  public void thelogIsMovedAndStillReadableUnderTheRealKeystore() throws Exception {
    seed();
    final String sealed = messageFile.getString(LOG_KEY, null);
    assertNotNull(sealed);
    accountFile.edit().putString(LOG_KEY, sealed).commit();
    messageFile.edit().clear().commit();

    final Account loaded = new StorageHelper(context).getAccountFromSharedPreferences();
    assertNotNull(loaded);
    final ArrayList<StorageMessage> messages = loaded.getUnencryptedMessages();

    assertEquals("the message must survive a move through real seal and open", 1, messages.size());
    assertEquals("the meeting is at nine", messages.get(0).getUnencryptedMessage());
    assertNotNull("the log must now live in its own file", messageFile.getString(LOG_KEY, null));
    assertNull("and be gone from the account file", accountFile.getString(LOG_KEY, null));
  }

  /** A raise leaves the log's file byte-identical, under real encryption too. */
  @Test
  public void araiseDoesNotReSealTheLogUnderTheRealKeystore() throws Exception {
    seed();
    final String before = messageFile.getString(LOG_KEY, null);
    assertNotNull(before);

    final Account raised = new StorageHelper(context).getAccountFromSharedPreferences();
    assertNotNull(raised);
    new StorageHelper(context).storeAllInformationInSharedPreferences(raised);

    assertEquals("a raise re-sealed the chat log. Every seal draws a fresh nonce, so an unchanged "
        + "value is the proof it was not written.", before, messageFile.getString(LOG_KEY, null));
  }
}
