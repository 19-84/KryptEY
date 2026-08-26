package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
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
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;

/**
 * The chat log's file must not seal anything on an attacker's behalf.
 *
 * <p>Splitting the store into two files created an oracle, and the two halves only combine because
 * of the split. The cleartext migration seals whatever key names it finds under the real master
 * key; and the AAD binds the format version and the storage KEY name, not which file the value came
 * from. So a value sealed in one file opens in the other.
 *
 * <p>The anti-laundering check in the migration does not stop it. That check fires only when the
 * <em>same</em> file also holds a decryptable envelope — and the log's file legitimately holds
 * exactly one payload key, which an attacker with write access to the data directory can simply
 * remove first.
 *
 * <p>The chain: plant a cleartext {@code PROTOCOL_STORE} of your own authorship in
 * {@code protocol_messages}, let the app seal it, copy the sealed value into {@code protocol}, and
 * the user's identity key, sessions and pinned peers are replaced by yours — opening correctly,
 * under the real Keystore key, with nothing in the app able to tell. That is the threat model
 * {@code EncryptedKeyValueStore} is written against: an attacker with the files but not the key.
 *
 * <p>The fix is that this file never runs the cleartext migration. It was created by this branch,
 * after encryption existed, and every write to it goes through {@code put} — so cleartext in it is
 * never legitimate.
 */
@RunWith(RobolectricTestRunner.class)
public class MessageStoreIsNotAlaunderingOracleTest {

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
  private static final String LOG_KEY = String.valueOf(ProtocolIdentifier.UNENCRYPTED_MESSAGES);
  private static final String STORE_KEY = String.valueOf(ProtocolIdentifier.PROTOCOL_STORE);

  private Context context;
  private SharedPreferences messageFile;

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
    context.getSharedPreferences("protocol", Context.MODE_PRIVATE).edit().clear().commit();
    messageFile = context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE);
    messageFile.edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    final Account account = SignalProtocolMain.getInstance().getAccount();

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Bob", "Jones", "bobAddress", 3, false));
    account.setContactList(contacts);
    final ArrayList<StorageMessage> messages = new ArrayList<>();
    messages.add(new StorageMessage(StorageMessage.chatLogKey("bobAddress", 3), "bobAddress", "me",
        Instant.now(), "a real message"));
    account.setUnencryptedMessages(messages);
    helper().storeAllInformationInSharedPreferences(account);
    assertNotNull("precondition: the log must be stored in its own file",
        messageFile.getString(LOG_KEY, null));
  }

  /** The attack: plant a foreign cleartext value and see whether the app seals it. */
  @Test
  public void aplantedCleartextValueIsNeverSealed() {
    // An attacker with the data directory. The log key stays PRESENT but is replaced with
    // cleartext, which matters twice: it is what makes anything build this store at all
    // (readMessageLog only reaches it when the key is there), and it is what leaves no decryptable
    // envelope behind to trip the migration's own anti-laundering guard.
    //
    // The first version of this test simply removed the log key, so nothing ever constructed the
    // store and the test passed against the vulnerable code. A control caught that.
    messageFile.edit().clear()
        .putString(LOG_KEY, "[]")
        .putString(STORE_KEY, "{\"attacker\":\"identity store of my choosing\"}")
        .commit();

    // Anything that reads the log builds that store.
    final Account loaded = helper().getAccountFromSharedPreferences();
    if (loaded != null) {
      try {
        loaded.getUnencryptedMessages();
      } catch (final RuntimeException expected) {
        // Refusing to read is fine. Sealing is not.
      }
    }

    assertEquals("the app sealed an attacker's cleartext value under the real master key. That "
            + "value now opens in the account's file too - the AAD binds the key name, not the "
            + "file - so it can be copied there and will load as the user's identity store.",
        "{\"attacker\":\"identity store of my choosing\"}", messageFile.getString(STORE_KEY, null));
  }

  /** And a key that does not belong in this file is refused outright. */
  @Test
  public void aforeignKeyInTheLogsFileIsRefused() {
    // Log key left in place, so the store is actually built; see the note above.
    messageFile.edit().putString(STORE_KEY, "anything at all").commit();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("the account itself must still load - the account file is untouched", loaded);
    try {
      loaded.getUnencryptedMessages();
    } catch (final RuntimeException expected) {
      // The log's store refuses to come up, which is what should happen.
    }

    assertEquals("a foreign key must be left exactly as found, not sealed", "anything at all",
        messageFile.getString(STORE_KEY, null));
  }

  /** The legitimate interrupted write still works: an envelope with no marker reads back. */
  @Test
  public void anenvelopeWithNoMarkerIsStillReadable() {
    final String sealedLog = messageFile.getString(LOG_KEY, null);
    assertNotNull(sealedLog);
    // Exactly what a kill between put's value commit and its marker commit leaves behind.
    messageFile.edit().clear().putString(LOG_KEY, sealedLog).commit();
    assertNull("precondition: the marker must be gone",
        messageFile.getString("__kryptey_storage_schema", null));

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull(loaded);
    assertEquals("a legitimate interrupted write must still read back - refusing cleartext must "
        + "not also refuse this", 1, loaded.getUnencryptedMessages().size());
    assertTrue(loaded.getUnencryptedMessages().get(0).getUnencryptedMessage()
        .equals("a real message"));
  }
}
