package com.amnesica.kryptey.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.security.KeyStore;
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

  /** Whether the shared master key exists in the platform Keystore right now. */
  private boolean masterKeyExists() throws Exception {
    final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    return keyStore.containsAlias("kryptey.storage.master");
  }

  /**
   * Loading a store whose master key is gone neither mints a replacement nor destroys the data.
   *
   * <p><b>What this does not do, stated so nobody later believes otherwise: it does not
   * discriminate the cross-file {@code hasExistingData} check.</b> Two earlier versions of this
   * test claimed to and did not, and the second failure is the more interesting one. Seeding
   * through {@code SignalProtocolMain.initialize} mints the key, so
   * {@code AndroidKeystoreCryptoBox.resolve} finds the alias and returns before the refusal is
   * consulted at all. Clearing the account file to get past that produces a state where nothing
   * ever decrypts — an empty file means {@code needsMigration()} is false and every {@code get}
   * returns null before touching the key — so no key is resolved and nothing could mint either
   * way. Run with the cross-file check reduced to one file, this test still passes.
   *
   * <p>The contract that check exists to keep is pinned by the JVM test
   * {@code ChatLogLivesInItsOwnFileTest#abuildingTheLogsStoreNeverClaimsAdeviceWithHistoryIsNew},
   * which captures the boolean actually handed to the box and does fail when it is wrong. That is
   * the right level for it: the downstream consequence turns out not to be reachable in any state
   * this test can construct.
   *
   * <p>What this <em>does</em> establish, on real hardware, is worth keeping: a device whose key
   * has gone — a changed lock-screen credential does exactly this — comes back with its data
   * untouched and no new key quietly standing in for the old one. A silent recovery-by-destruction
   * here would look like a working keyboard and be the loss of every session and verified contact.
   */
  @Test
  public void aloadWithNokeyNeitherMintsOneNorDestroysTheData() throws Exception {
    seed();
    assertTrue("precondition: seeding must have created the master key", masterKeyExists());

    // The state that actually exercises the refusal: the account's file is gone - a corrupt
    // protocol.xml reads as an empty map - and only the chat log survives. Asking that one file
    // "does this device hold data" answers no, and the box is then free to mint. With the account
    // file still populated, both the one-file and two-file answers are "yes" and the test would
    // pass either way, which is a test of nothing.
    accountFile.edit().clear().commit();

    // And the user changes their lock-screen credential: the key is gone, the data is not.
    new AndroidKeystoreCryptoBox(context, false).destroyMasterKey();
    assertFalse("precondition: the key must be gone", masterKeyExists());
    assertNotNull("precondition: the surviving data must still be there",
        messageFile.getString(LOG_KEY, null));

    final StorageHelper helper = new StorageHelper(context);
    assertTrue("the store must still report that this device holds data",
        helper.hasExistingProtocolData());
    helper.getAccountFromSharedPreferences();   // builds both stores, and their crypto boxes

    assertFalse("a replacement master key was minted while the user's data was still on disk. "
        + "Their identity key, every session and every verified contact are now sealed under a key "
        + "that no longer exists, and the new one can read none of it.", masterKeyExists());
    assertNotNull("and the surviving data must still be on disk afterwards - a load that cannot "
        + "read anything must not tidy up after itself", messageFile.getString(LOG_KEY, null));
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
