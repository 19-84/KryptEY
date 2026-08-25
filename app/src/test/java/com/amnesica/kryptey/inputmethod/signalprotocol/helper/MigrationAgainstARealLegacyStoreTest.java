package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * The key migration, run over the committed 0.1.5-shaped store rather than a constructed one.
 *
 * <p>Every other test of this migration builds its legacy state by hand, which means they all test
 * the shape their author had in mind. The fixtures under {@code src/test/resources/fixtures} are the
 * real thing — written by the previous version, kept to prove a Jackson upgrade can still read them
 * — and they carry both properties this migration exists for, neither of which anyone had noticed:
 * {@code contacts.json} holds {@code deviceId 7296}, out of libsignal's range because 0.1.5 minted
 * ids with {@code nextInt(10000)}, and {@code messages.json} holds a {@code contactUUID} that is a
 * bare address name with no device id in it at all.
 *
 * <p>So the upgrade path can be exercised against the bytes it will actually meet. That is worth
 * more than another synthetic fixture, because the two ways this migration has already gone wrong
 * were both about what real data looks like: a stored address name the wire would have refused, and
 * a contact list shaped by the messenger before the upgrade ran.
 */
public class MigrationAgainstARealLegacyStoreTest {

  private Account account;

  private static String fixture(final String name) throws IOException {
    try (InputStream in = MigrationAgainstARealLegacyStoreTest.class.getClassLoader()
        .getResourceAsStream("fixtures/" + name)) {
      assertNotNull("missing fixture: fixtures/" + name, in);
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    // Through the same two calls StorageHelper makes when it loads a store off disk.
    account.setContactList(JsonUtil.convertContactsList(
        JsonUtil.fromJson(fixture("contacts.json"), ArrayList.class)));
    account.setUnencryptedMessages(JsonUtil.convertUnencryptedMessagesList(
        JsonUtil.fromJson(fixture("messages.json"), ArrayList.class)));
  }

  /** The fixtures really are pre-upgrade, or everything below is about nothing. */
  @Test
  public void thefixtureIsALegacyStore() {
    assertEquals("precondition: one contact", 1, account.getContactList().size());
    assertEquals("precondition: one message", 1, account.getUnencryptedMessages().size());

    final String key = account.getUnencryptedMessages().get(0).getContactUUID();
    assertFalse("the fixture's chat-log key must be a bare address name - that is the shape this "
            + "migration exists for, and regenerating the fixtures destroys it",
        key.indexOf(ProtocolAddresses.SEPARATOR) >= 0);
    assertEquals("and it must be the contact's address name",
        account.getContactList().get(0).getSignalProtocolAddressName(), key);
  }

  /**
   * The real legacy message is attributed to the real legacy contact.
   *
   * <p>Both halves matter and both come from the fixture rather than from this test: the contact's
   * stored device id is out of range and is folded on read, and the message's key has no device id
   * at all. The migration has to end with them agreeing.
   */
  @Test
  public void therealLegacyMessageIsReKeyedOntoTheRealLegacyContact() {
    LegacyKeyMigration.apply(account);

    final Contact contact = account.getContactList().get(0);
    final StorageMessage message = account.getUnencryptedMessages().get(0);

    assertEquals("the message must now be filed under the contact's full address",
        StorageMessage.chatLogKey(contact.getSignalProtocolAddressName(), contact.getDeviceId()),
        message.getContactUUID());
    assertTrue("and the contact's device id must have been folded into libsignal's range on the "
            + "way in, or the two would never agree",
        contact.getDeviceId() <= ProtocolAddresses.MAX_DEVICE_ID);
    assertTrue("which is not what the fixture holds on disk", 7296 > ProtocolAddresses.MAX_DEVICE_ID);
  }

  /** And the message is then readable through the production path, which is the point of all this. */
  @Test
  public void thereKeyedMessageIsReadableThroughTheProductionPath() throws Exception {
    LegacyKeyMigration.apply(account);

    final java.util.List<StorageMessage> log =
        SignalProtocolMain.getUnencryptedMessagesList(account.getContactList().get(0));

    assertNotNull("a migrated legacy log must be retrievable, not merely re-keyed in place", log);
    assertEquals(1, log.size());
    assertEquals("fixture message", log.get(0).getUnencryptedMessage());
  }
}
