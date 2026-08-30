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

  /**
   * A key that does not belong in this file makes the store refuse to come up.
   *
   * <p>Asserting the refusal, not the absence of a seal — and the difference is the whole test. An
   * earlier version only checked that the planted value had not been sealed, which
   * {@code requireEncryptedOnly} never does under any mutation, including being gutted to an empty
   * method. It also kept the sealed marker from {@code setUp}, so {@code needsMigration()} was
   * false and the old vulnerable code would not have sealed anything either. It passed against
   * everything and discriminated nothing.
   *
   * <p>What must hold is that the store is refused: with the check gone, {@code get} falls through
   * its cleartext branch and hands back whatever the attacker wrote as the user's chat log.
   */
  @Test
  public void aforeignKeyInTheLogsFileMakesTheStoreRefuseToOpen() {
    // The sealed log and marker from setUp stay, so the store is genuinely built and the only
    // thing wrong with the file is the extra key.
    messageFile.edit().putString(STORE_KEY, "anything at all").commit();

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("the account itself must still load - the account file is untouched", loaded);

    try {
      loaded.getUnencryptedMessages();
      throw new AssertionError("a file carrying a key that cannot legitimately be there was opened "
          + "anyway. Whatever else is in it is then read as the user's own data.");
    } catch (final com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException
        expected) {
      // Refusing is the point.
    }

    assertEquals("and the foreign value must be left exactly as found, not sealed",
        "anything at all", messageFile.getString(STORE_KEY, null));
  }

  /**
   * Cleartext under the log's OWN key must refuse the store, not be read as the user's history.
   *
   * <p>{@code requireEncryptedOnly} makes three refusals and only the first was enforced. Both
   * existing cases above plant a value under {@code PROTOCOL_STORE} - a key that may not be in this
   * file at all - so they trip the unexpected-key check and return before the other two are
   * reached. A sweep deleted the cleartext and undecryptable arms with the unexpected-key arm left
   * intact: neither test failed. This is the case nobody had constructed.
   *
   * <p>The attacker plants a chat log of their own authorship under the one key that legitimately
   * belongs here, having emptied the file first so no marker and no envelope remain. With the
   * refusal gone the store comes up; {@code get} finds no marker, sees the value is not an
   * envelope, finds no readable envelope beside it to object about - and returns it. The planted
   * messages are then the user's history, attributed to the user's own contacts, with nothing to
   * distinguish them from messages they actually received.
   *
   * <p>Planted as real serialized {@link StorageMessage}s rather than as {@code "[]"} on purpose: a
   * value that fails to parse would make the mutant fail at the deserializer for an unrelated
   * reason, and the case would then be evidence of nothing. This one parses.
   */
  @Test
  public void cleartextUnderThelogsOwnKeyMakesTheStoreRefuseToOpen() {
    final ArrayList<StorageMessage> planted = new ArrayList<>();
    planted.add(new StorageMessage(StorageMessage.chatLogKey("bobAddress", 3), "bobAddress", "me",
        Instant.now(), "a message the user never received"));
    final String plantedJson =
        com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil.toJson(planted);
    assertTrue("precondition: the planted log must be well-formed, or the mutant would fail at the "
        + "deserializer rather than on the guard under test", plantedJson.startsWith("["));

    // Emptied first, which is what makes this the attack rather than a corrupted file: no marker to
    // vouch for a mix, and no surviving envelope for the read-side guard to object about.
    messageFile.edit().clear().putString(LOG_KEY, plantedJson).commit();
    assertNull("precondition: no marker, so nothing downstream refuses on the marker's behalf",
        messageFile.getString("__kryptey_storage_schema", null));

    final Account loaded = helper().getAccountFromSharedPreferences();
    assertNotNull("the account itself must still load - the account file is untouched", loaded);

    try {
      final ArrayList<StorageMessage> read = loaded.getUnencryptedMessages();
      throw new AssertionError("cleartext under the log's own key was opened and returned as the "
          + "user's chat log: " + read.size() + " message(s), the first reading '"
          + (read.isEmpty() ? "" : read.get(0).getUnencryptedMessage()) + "'. This file has never "
          + "legitimately held cleartext - every write to it goes through put.");
    } catch (final com.amnesica.kryptey.inputmethod.signalprotocol.ChatLogUnavailableException
        expected) {
      // Refusing is the point.
    }

    assertEquals("and the planted value must be left exactly as found, not sealed on the "
        + "attacker's behalf", plantedJson, messageFile.getString(LOG_KEY, null));
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
