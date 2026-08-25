package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Ambiguity is not an accident of the user's contact list - it is one invite away.
 *
 * <p>The attacker chooses its own address name; it is the peer-supplied name in the envelope it
 * sends. Choosing the address name of a contact the user already has, at a different device id and
 * under an unrelated display name, warns about nothing: display names differ, so the duplicate-name
 * check is silent, and no contact exists at that exact address, so
 * {@code existingContactAtSameAddress} is silent too. The row simply appears.
 *
 * <p>Do that before the upgrade and the first load afterwards finds two rows bearing the name every
 * legacy chat-log entry is keyed by, declares the entries unattributable, and DELETES them. The
 * user is not asked and is not told; the next thing they see is an empty conversation. Under the
 * read-time matching this replaced, the same planted row only withheld the history, which came back
 * when the row went away.
 *
 * <p>The stated reason for deleting rather than orphaning is that unattributable plaintext "can
 * later be handed to whichever contact survives". That was true while a reader matched bare names.
 * It is not true after this commit: {@code belongsTo} compares the full rendered address and
 * nothing produces a bare name to match, so an un-re-keyed entry is inert - invisible to every row,
 * including the attacker's. What deletion buys over leaving it is that the plaintext is gone from
 * disk; what it costs is that the messenger can destroy any conversation it likes, permanently, at
 * a moment of its choosing.
 */
public class PlantedRivalRowDestroysHistoryTest {

  private static final String HISTORY = "everything we have ever said to each other";

  private Account account;
  private Contact genuineBob;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    genuineBob = new Contact("Bob", "Jones", "bob-uuid", 5, false);
  }

  private boolean bobsLogHolds(final String text) {
    try {
      for (final StorageMessage message
          : SignalProtocolMain.getUnencryptedMessagesList(genuineBob)) {
        if (text.equals(message.getUnencryptedMessage())) return true;
      }
    } catch (Exception ignored) {
      // no messages for this contact
    }
    return false;
  }

  /** With no rival row, the history is re-keyed and kept: the fixture works. */
  @Test
  public void withoutTheplantedRowTheHistorySurvives() {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    account.setContactList(contacts);
    account.getUnencryptedMessages().add(new StorageMessage("bob-uuid", "bob-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        HISTORY));

    LegacyKeyMigration.apply(account);

    assertTrue("control: an unambiguous entry is re-keyed and kept", bobsLogHolds(HISTORY));
  }

  /** One extra row, added by one ordinary invite, and the same history is gone. */
  @Test
  public void aplantedRivalRowMustNotCostTheUserTheirHistory() {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    // The messenger's row: Bob's address name, any other device id, an unrelated display name.
    // Nothing in the add path warns about this combination.
    contacts.add(new Contact("Carol", "Smith", "bob-uuid", 9, false));
    account.setContactList(contacts);
    account.getUnencryptedMessages().add(new StorageMessage("bob-uuid", "bob-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        HISTORY));

    assertFalse("precondition: no display-name warning stands between the attacker and this row",
        SignalProtocolMain.hasContactWithSameDisplayName("Carol", "Smith",
            com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses
                .of("bob-uuid", 9)));

    LegacyKeyMigration.apply(account);

    assertEquals("the messenger destroyed the user's conversation with Bob by adding one contact "
            + "row before the upgrade - silently, irreversibly, and at a moment the user never "
            + "sees", 1, account.getUnencryptedMessages().size());
    assertEquals(HISTORY, account.getUnencryptedMessages().get(0).getUnencryptedMessage());
  }
}
