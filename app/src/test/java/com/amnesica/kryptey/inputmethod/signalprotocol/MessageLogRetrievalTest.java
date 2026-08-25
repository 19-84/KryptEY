package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.exceptions.UnknownContactException;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading a contact's message log, and the guards around it.
 *
 * <p>The retrieval is a conjunction ({@code account != null && contact != null}) wrapping a filter
 * whose empty result throws. Mutation testing found all three parts weakenable with the suite
 * green: nothing had ever asked for a log with no account, for a contact with no messages, or
 * checked that one contact's messages do not leak into another's list.
 *
 * <p>That last one is not merely tidiness. The log is keyed on the address <em>name</em> only, while
 * contacts and sessions key on name plus device id — so two contacts that differ only by device id
 * share a log, and a test that never has two contacts cannot see it.
 */
public class MessageLogRetrievalTest {

  private Account account;
  private Contact alice;
  private Contact bob;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    account.setUnencryptedMessages(new ArrayList<>());

    alice = new Contact("Alice", "Smith", "alice-uuid", 7, false);
    bob = new Contact("Bob", "Jones", "bob-uuid", 9, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(alice);
    contacts.add(bob);
    account.setContactList(contacts);
  }

  private void log(final Contact contact, final String text) {
    account.addUnencryptedMessage(contact, new StorageMessage(
        // The key production writes: the full address. A bare name is the pre-upgrade format, and
        // it is re-keyed once at load rather than matched here.
        StorageMessage.chatLogKey(contact.getSignalProtocolAddressName(), contact.getDeviceId()),
        contact.getSignalProtocolAddressName(),
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L), text));
  }

  @Test
  public void aContactsOwnMessagesAreReturned() throws Exception {
    log(alice, "first");
    log(alice, "second");

    final List<StorageMessage> messages = SignalProtocolMain.getUnencryptedMessagesList(alice);

    assertNotNull(messages);
    assertEquals(2, messages.size());
  }

  /** The scoping that matters: one contact's log must not include another's. */
  @Test
  public void oneContactsMessagesDoNotAppearInAnothersLog() throws Exception {
    log(alice, "for alice");
    log(bob, "for bob");

    final List<StorageMessage> alicesLog = SignalProtocolMain.getUnencryptedMessagesList(alice);

    assertEquals("Alice's log must hold only Alice's message", 1, alicesLog.size());
    assertEquals("for alice", alicesLog.get(0).getUnencryptedMessage());
  }

  /** A contact with no messages is an exception, not an empty list the caller must special-case. */
  @Test
  public void aContactWithNoMessagesRaisesUnknownContact() {
    log(alice, "only alice has messages");

    assertThrows(UnknownContactException.class,
        () -> SignalProtocolMain.getUnencryptedMessagesList(bob));
  }

  @Test
  public void anEmptyStoreRaisesForEveryContact() {
    assertThrows(UnknownContactException.class,
        () -> SignalProtocolMain.getUnencryptedMessagesList(alice));
  }

  /** Both arms of the guard: no account, and no contact. */
  @Test
  public void retrievalIsSafeWithNoAccountOrNoContact() throws Exception {
    log(alice, "something");

    assertNull("a null contact must yield nothing, not a crash",
        SignalProtocolMain.getUnencryptedMessagesList(null));

    SignalProtocolMain.getInstance().setAccount(null);
    assertNull("no account must yield nothing",
        SignalProtocolMain.getUnencryptedMessagesList(alice));
  }

  // ------------------------------------------------------------------ encrypt

  /** Both arms of encrypt's guard. */
  @Test
  public void encryptingIsSafeWithEitherArgumentMissing() {
    final SignalProtocolAddress address = ProtocolAddresses.of("alice-uuid", 7);

    assertNull(SignalProtocolMain.encryptMessage(null, address));
    assertNull("a message with nowhere to go must not be encrypted",
        SignalProtocolMain.encryptMessage("hello", null));
    assertNull(SignalProtocolMain.encryptMessage(null, null));
  }
}
