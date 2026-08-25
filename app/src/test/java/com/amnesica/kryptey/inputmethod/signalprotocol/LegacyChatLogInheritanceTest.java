package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ambiguity gate is asked at read time, so its answer moves under the messages it governs.
 *
 * <p>{@code hasExactlyOneContactNamed} is not a property of a message. It is a property of the
 * contact list AT THE MOMENT SOMEBODY ASKS, and the contact list changes. A legacy, name-keyed
 * message that is correctly withheld from two rivals today is handed to whichever of them is left
 * tomorrow - and which one is left is a choice the messenger can drive, because the app's own
 * advice for any decryption failure is "delete the contact and ask for a new invite", and a
 * decryption failure is one replayed message away.
 *
 * <p>So the sequence is:
 *
 * <ol>
 *   <li>Upgrading user has Bob at {@code (bobsName, 5)} and a history filed under the address NAME
 *       alone, which on an upgrading install is the whole history.
 *   <li>The messenger introduces a second contact whose ADDRESS name is also {@code bobsName} - its
 *       own account name, which it chooses freely and which no control looks at - under any display
 *       name at all, so the duplicate-name warning has nothing to fire on. The legacy history now
 *       correctly belongs to nobody.
 *   <li>The messenger replays one of Bob's messages until the user follows the app's advice and
 *       deletes Bob. The delete is careful not to destroy the history, because the name is
 *       ambiguous while both rows stand - that is the fix from the last round.
 *   <li>One row is left, the name is unambiguous again, and every word the user ever exchanged
 *       with Bob is now rendered inside the attacker's conversation.
 * </ol>
 *
 * <p>This is the same outcome as the two HIGHs before it - the genuine contact's conversation
 * rendered under an impostor's row - reached without any key collision at all, purely by moving the
 * contact list between the write and the read.
 *
 * <p>Note that the two orderings are in direct opposition and no third one exists.
 * {@code LegacyChatLogDeletionTest} requires that deleting the impostor LEAVES the history (asked
 * before the prune); this requires that deleting the genuine contact does not LEAVE it for the
 * impostor (asked after the prune). The delete cannot tell the two apart - that is what "ambiguous"
 * means - so an ordering cannot satisfy both. Attribution has to be decided once, at a moment when
 * the answer is stable, not recomputed on every read.
 */
public class LegacyChatLogInheritanceTest {

  private static final String LEGACY_TEXT = "the spare key is under the third pot";

  private Account victim;
  private String peerName;
  private int peerDeviceId;
  private Contact genuineContact;
  private Contact impostor;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();

    peerName = peer.getSignalProtocolAddress().getName();
    peerDeviceId = peer.getDeviceId();

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue("fixture: the genuine peer must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(peerBundle),
            ProtocolAddresses.of(peerName, peerDeviceId)));

    final int impostorDeviceId = (peerDeviceId % ProtocolAddresses.MAX_DEVICE_ID) + 1;
    assertNotEquals("fixture: the impostor must differ only in device id",
        peerDeviceId, impostorDeviceId);

    genuineContact = new Contact("Bob", "Jones", peerName, peerDeviceId, false);
    // A completely unrelated display name, so nothing warns about this row at all - the collision
    // is in the ADDRESS name, which the user never sees and no control compares.
    impostor = new Contact("Carol", "Smith", peerName, impostorDeviceId, false);

    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineContact);
    contacts.add(impostor);
    victim.setContactList(contacts);
  }

  /** A message as the pre-upgrade code wrote it: filed under the address NAME alone. */
  private void writeLegacyMessage(final String text) {
    victim.getUnencryptedMessages().add(new StorageMessage(peerName, peerName,
        victim.getSignalProtocolAddress().getName(), Instant.now(), text));
  }

  private static boolean logContains(final List<StorageMessage> log, final String text) {
    if (log == null) return false;
    for (final StorageMessage message : log) {
      if (text.equals(message.getUnencryptedMessage())) return true;
    }
    return false;
  }

  private List<StorageMessage> logOf(final Contact contact) {
    try {
      return SignalProtocolMain.getUnencryptedMessagesList(contact);
    } catch (Exception e) {
      // an empty log raises rather than returning nothing
      return new ArrayList<>();
    }
  }

  /**
   * Deleting the genuine contact must not hand its pre-upgrade history to the impostor.
   *
   * <p>Nothing about these two rows is shared except a string the user never sees. Different
   * display names, different device ids, different pinned keys, different address tags. The
   * messages were written for one of them, at a time when the other did not exist.
   */
  @Test
  public void deletingTheGenuineContactMustNotHandItsLegacyHistoryToTheImpostor() {
    writeLegacyMessage(LEGACY_TEXT);

    assertFalse("fixture: while both rows stand the legacy log is deliberately withheld from the "
        + "genuine contact", logContains(logOf(genuineContact), LEGACY_TEXT));
    assertFalse("fixture: and from the impostor",
        logContains(logOf(impostor), LEGACY_TEXT));

    // The app's own advice for any decryption failure, which the messenger induces by replaying
    // one message.
    SignalProtocolMain.removeContactFromContactListAndProtocol(genuineContact);

    assertEquals("fixture: only the impostor may remain", 1, victim.getContactList().size());

    assertFalse("the deleted contact's entire pre-upgrade conversation is now rendered inside the "
        + "impostor's row: " + logOf(impostor), logContains(logOf(impostor), LEGACY_TEXT));
  }

}
