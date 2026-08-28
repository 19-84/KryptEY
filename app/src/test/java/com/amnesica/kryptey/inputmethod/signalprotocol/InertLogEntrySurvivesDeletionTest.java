package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.LegacyKeyMigration;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What deleting a contact does <em>not</em> erase, measured rather than described.
 *
 * <p>The help tells the user: "Note that if you delete the contact, the message history will be
 * deleted too." For every message this version writes that is true. For one class of pre-upgrade
 * entry it is not, and the gap is a deliberate trade rather than an oversight — which is exactly why
 * it needs a test rather than a sentence.
 *
 * <p>The chat log used to be keyed by a bare address name. The load-time migration re-keys each
 * entry onto the full rendered address of the contact that owns it, and when the name identifies no
 * single contact — the impostor case, two rows sharing an address name — it cannot attribute the
 * entry and <b>keeps</b> it. Deleting instead was tried and rejected on strong grounds: no reader
 * matches a bare name any more, so an un-re-keyed entry is invisible to every contact including the
 * attacker's, and deleting it turned a safety measure into a destruction primitive where one
 * ordinary invite sent before the upgrade was enough to have a genuine conversation erased with no
 * prompt and no way back.
 *
 * <p>The consequence nobody had written down is what this pins: {@code removeAllUnencryptedMessages}
 * matches on the full rendered key alone, so it cannot reach such an entry either. The user deletes
 * every contact that could possibly relate to the conversation, and the plaintext stays in the
 * store. It is unreachable — no screen can show it — but it is there, and no user action erases it.
 *
 * <p>Both halves are asserted, because the trade is only defensible if both hold: the entry must be
 * invisible (or the attacker inherits a conversation) <em>and</em> it must be understood to persist
 * (or the retention promise is simply wrong). Recorded in REVIVAL.md as a known-deferred defect
 * whose fix is a "clear all history" action rather than a smarter per-contact delete — a per-contact
 * delete cannot attribute what the migration could not.
 */
@RunWith(RobolectricTestRunner.class)
public class InertLogEntrySurvivesDeletionTest {

  private static final String SHARED_NAME = "sharedAddressName";
  private static final String SECRET = "the meeting is at nine";

  private Account account;
  private Contact genuine;
  private Contact impostor;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    // Two rows under one address name, at different device ids: the case the migration cannot
    // attribute. This is reachable without any attacker - a reinstall arrives at a fresh device id.
    genuine = new Contact("Bob", "Jones", SHARED_NAME, 1, false);
    impostor = new Contact("Bob", "Jones", SHARED_NAME, 2, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuine);
    contacts.add(impostor);
    account.setContactList(contacts);

    // A pre-upgrade entry: keyed by the bare address name, with no device id in it.
    final ArrayList<StorageMessage> log = new ArrayList<>();
    log.add(new StorageMessage(SHARED_NAME, SHARED_NAME, "me", Instant.now(), SECRET));
    account.setUnencryptedMessages(log);
      // These tests delete contacts and assert the deletion happened. A deletion whose
    // write does not land is now rolled back in memory so the user can retry, so the
    // fixture has to say that its store writes.
    TestStores.writesLand();
}

  private long entriesHolding(final String text) {
    final List<StorageMessage> log = account.getUnencryptedMessages();
    if (log == null) return 0;
    return log.stream().filter(m -> text.equals(m.getUnencryptedMessage())).count();
  }

  /** The premise: the migration cannot attribute this entry, and keeps it. */
  @Test
  public void themigrationKeepsAnEntryItCannotAttribute() {
    LegacyKeyMigration.apply(account);

    assertEquals("precondition: an ambiguous legacy entry must be KEPT - deleting it was rejected "
        + "as a destruction primitive", 1, entriesHolding(SECRET));
    assertEquals("and it must still carry its bare, un-re-keyed name", SHARED_NAME,
        account.getUnencryptedMessages().get(0).getContactUUID());
    assertTrue("which is not a rendered address key",
        SHARED_NAME.indexOf(ProtocolAddresses.SEPARATOR) < 0);
  }

  /**
   * It is invisible through the production read path — which is what makes keeping it safe.
   *
   * <p>If this ever fails, keeping the entry stops being defensible: whichever row is asked would
   * inherit a conversation it never had, and the attacker's row is one of the two that can ask.
   */
  @Test
  public void theinertEntryIsInvisibleToBothContacts() throws Exception {
    LegacyKeyMigration.apply(account);

    for (final Contact contact : new Contact[] {genuine, impostor}) {
      long leaked = 0;
      try {
        final List<StorageMessage> visible =
            SignalProtocolMain.getUnencryptedMessagesList(contact);
        if (visible != null) {
          leaked = visible.stream().filter(m -> SECRET.equals(m.getUnencryptedMessage())).count();
        }
      } catch (com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException
          notFound) {
        // The read path reports "no messages for this contact" by throwing rather than returning an
        // empty list. That IS the invisibility this test is asserting, so it counts as zero rather
        // than as an error - a distinction worth stating, because catching it silently is also how
        // a test like this becomes vacuous.
        leaked = 0;
      }
      assertEquals("an un-attributed legacy entry must be visible to NO contact - handing it to "
          + "either row gives one of them a conversation it never had, and one of them may be an "
          + "impostor sharing the address name", 0, leaked);
    }
  }

  /**
   * And deleting every contact does not erase it. This is the gap.
   *
   * <p>{@code removeAllUnencryptedMessages} matches {@code belongsTo}, which compares the full
   * rendered address and nothing else — the bare-name arm was removed after two HIGH findings. So
   * the one action a user has for erasing a conversation cannot reach this entry, and the help's
   * promise that deleting a contact deletes its history does not hold for it.
   */
  @Test
  public void deletingEveryContactLeavesThePlaintextInTheStore() {
    LegacyKeyMigration.apply(account);
    assertEquals("precondition", 1, entriesHolding(SECRET));

    SignalProtocolMain.removeContactFromContactListAndProtocol(genuine);
    SignalProtocolMain.removeContactFromContactListAndProtocol(impostor);

    assertNotNull(account.getContactList());
    assertEquals("precondition: both contacts must actually be gone", 0,
        account.getContactList().size());
    assertEquals("the user has deleted every contact that could relate to this conversation and "
            + "the plaintext is still in the store. It is unreachable rather than exposed - no "
            + "screen can show it - but the help says deleting a contact deletes its history, and "
            + "for this entry that is not true. The fix is a clear-all action: a per-contact delete "
            + "cannot attribute what the migration could not", 1, entriesHolding(SECRET));
  }
}
