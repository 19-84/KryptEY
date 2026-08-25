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
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code removeContact} asks whether an address name is ambiguous AFTER it has already pruned the
 * contact it is deleting out of the list.
 *
 * <p>The legacy arm of {@link StorageMessage#belongsTo} is gated on {@code hasExactlyOneContactNamed},
 * which counts rows in {@code Account.contactList}. {@code SignalProtocolMain.removeContact} calls
 * {@code mAccount.setContactList(newContacts)} first and {@code mAccount.removeAllUnencryptedMessages}
 * afterwards, so that count is taken against a list the contact has already left. Every legacy
 * message decision on the delete path is therefore made against the wrong list, and it is wrong in
 * both directions:
 *
 * <ul>
 *   <li>Deleting an impostor that shares an address name makes the name look unambiguous, so the
 *       GENUINE contact's pre-upgrade history is deleted. That is verbatim the HIGH that f3e7baf
 *       says it fixed - "deleting the impostor took the genuine contact's history with it" - still
 *       live for every message written before the device id was part of the key, which on an
 *       upgrading install is the entire history.
 *   <li>Deleting a contact that is alone under its name makes the name look ambiguous, so its own
 *       pre-upgrade plaintext is NOT deleted. It survives the one action the user has for erasing
 *       it, and with no contact row left it can never be reached or removed again.
 * </ul>
 *
 * <p>Both are one reorder apart. The fixture writes messages the way the pre-upgrade code wrote
 * them - {@code contactUUID} set to {@code signalProtocolAddress.getName()} - which is exactly the
 * shape the legacy arm exists to serve.
 */
public class LegacyChatLogDeletionTest {

  private static final String LEGACY_TEXT = "the spare key is under the third pot";

  private Account victim;
  private String peerName;
  private int peerDeviceId;
  private Contact genuineContact;

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

    genuineContact = new Contact("Bob", "Jones", peerName, peerDeviceId, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineContact);
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

  /** Whether the plaintext is anywhere in the account's store, contact row or no contact row. */
  private boolean survivesInStore(final String text) {
    for (final StorageMessage message : victim.getUnencryptedMessages()) {
      if (text.equals(message.getUnencryptedMessage())) return true;
    }
    return false;
  }

  /**
   * Deleting an impostor at the same address name must not destroy the genuine contact's
   * pre-upgrade history.
   *
   * <p>The sequence is the one the app's own duplicate-name warning produces. The user upgrades
   * with one Bob and a history filed under Bob's address name. The messenger pins its own key at
   * (bobsName, otherDevice) - permitted by design, warned about - and a second row appears. While
   * both rows stand the legacy history is correctly withheld from both, because nothing can say
   * which row it belonged to. The user then does what the warning steers them to and deletes the
   * impostor, at which point the name is unambiguous again and the history belongs to the one
   * remaining Bob.
   *
   * <p>Instead it is gone: {@code removeAllUnencryptedMessages} runs after the list has been
   * pruned, sees one contact named bobsName, concludes the name is unambiguous, and hands the
   * genuine contact's messages to the impostor's delete.
   */
  @Test
  public void deletingAnImpostorMustNotDestroyTheGenuineContactsLegacyHistory() {
    writeLegacyMessage(LEGACY_TEXT);
    assertTrue("fixture: with one contact the legacy log is unambiguous and readable",
        logContains(logOf(genuineContact), LEGACY_TEXT));

    final int impostorDeviceId = (peerDeviceId % ProtocolAddresses.MAX_DEVICE_ID) + 1;
    assertNotEquals("fixture: the impostor must differ only in device id",
        peerDeviceId, impostorDeviceId);
    final Contact impostor = new Contact("Bob", "Jones", peerName, impostorDeviceId, false);
    victim.getContactList().add(impostor);

    assertFalse("fixture: while both rows stand the legacy log is deliberately withheld",
        logContains(logOf(genuineContact), LEGACY_TEXT));

    SignalProtocolMain.removeContactFromContactListAndProtocol(impostor);

    assertEquals("fixture: only the genuine contact may remain", 1, victim.getContactList().size());
    assertTrue("deleting the impostor destroyed the genuine contact's pre-upgrade history: "
        + victim.getUnencryptedMessages(), survivesInStore(LEGACY_TEXT));
    assertTrue("and it must be readable again now the name is unambiguous",
        logContains(logOf(genuineContact), LEGACY_TEXT));
  }

  /**
   * Deleting a contact must take its pre-upgrade plaintext with it.
   *
   * <p>Deleting the contact is the only thing a user can do to erase a conversation, and the whole
   * point of {@code removeAllUnencryptedMessages}. Because the ambiguity question is asked after
   * the row has already gone, a contact that was alone under its name reads as ambiguous at exactly
   * the moment it is deleted, so the legacy arm never matches and nothing is removed. The plaintext
   * stays in the account's store with no contact row pointing at it - unreachable from the UI, and
   * so never deletable again.
   */
  @Test
  public void deletingTheOnlyContactMustNotLeaveItsLegacyPlaintextBehind() {
    writeLegacyMessage(LEGACY_TEXT);
    assertTrue("fixture: the message must start out attributed to this contact",
        logContains(logOf(genuineContact), LEGACY_TEXT));

    SignalProtocolMain.removeContactFromContactListAndProtocol(genuineContact);

    assertTrue("fixture: the contact must actually be gone", victim.getContactList().isEmpty());
    assertFalse("the deleted contact's plaintext survived the delete, with no row left to reach "
        + "it from: " + victim.getUnencryptedMessages(), survivesInStore(LEGACY_TEXT));
  }
}
