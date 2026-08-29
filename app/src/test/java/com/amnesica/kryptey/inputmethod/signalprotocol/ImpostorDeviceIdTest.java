package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * An address is a NAME and a DEVICE ID. The chat log only remembers the name.
 *
 * <p>Every trust record in this app is keyed by the full address: the pin, the pending change, the
 * rejection, the out-of-band mark, and the display tag. The message log is not. {@code
 * StorageMessage.contactUUID} is set from {@code signalProtocolAddress.getName()} alone, and
 * {@code getUnencryptedMessagesList} filters on {@code getSignalProtocolAddressName()} alone — so
 * two contacts that share an address name and differ only in device id share one conversation.
 *
 * <p>The device id is chosen by whoever writes the envelope, and the victim's contact's address
 * name is public — it is in every envelope the messenger relays. So an attacker can present a
 * bundle at {@code (bobsName, someOtherDevice)}. That is a fresh address, so nothing is pinned
 * there and trust-on-first-use accepts it; the design's answer to that is the duplicate-name
 * warning plus the address tag, which is explicitly a warning rather than a block ("Both now appear
 * in your list"). The user is told they will be able to tell the two rows apart.
 *
 * <p>They cannot tell the two conversations apart, because there is only one. What the impostor
 * sends is rendered inside the genuine contact's chat history, and what the user typed to the
 * genuine contact is rendered inside the impostor's.
 */
public class ImpostorDeviceIdTest {

  private Account victim;
  private Account realPeer;
  private Account impostor;

  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress impostorAddress;

  private Contact realPeerContact;
  private Contact impostorContact;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    impostor = SignalProtocolMain.getInstance().getAccount();

    final String peerName = realPeer.getSignalProtocolAddress().getName();
    peerAddress = ProtocolAddresses.of(peerName, realPeer.getDeviceId());
    // Same name, a device id the attacker picks. Both are inside libsignal's [1,127], so the wire
    // codec accepts both without complaint.
    impostorAddress = ProtocolAddresses.of(peerName, (realPeer.getDeviceId() % 127) + 1);
    assertNotEquals("fixture: the two addresses must differ", peerAddress, impostorAddress);

    // --- the genuine peer is pinned, and holds a session in both directions ---
    activate(realPeer);
    final String genuineBundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuineBundle), peerAddress));

    realPeerContact = new Contact("Bob", "Jones", peerName, realPeer.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(realPeerContact);
    victim.setContactList(contacts);
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** The impostor's own bundle, re-labelled with the genuine peer's name and its own device id. */
  private String forgedBundleAtBobsName() throws Exception {
    activate(impostor);
    final MessageEnvelope envelope = SignalProtocolMain.getPreKeyResponseMessage();
    envelope.setSignalProtocolAddressName(impostorAddress.getName());
    envelope.setDeviceId(impostorAddress.getDeviceId());
    final String wire = EnvelopeCodec.toWire(envelope);
    activate(victim);
    return wire;
  }

  /**
   * Walks the whole attack: the impostor pins at Bob's name under another device id, the user is
   * warned and adds it anyway (which the design permits), and the impostor then sends a message.
   */
  private String impostorSendsAMessage(final String plaintext) throws Exception {
    // 1. the forged bundle survives the codec and is a clean first sighting at a new address
    final String forged = forgedBundleAtBobsName();
    assertTrue("the forged bundle must be accepted - the address is genuinely unpinned",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(forged),
            impostorAddress));

    // 2. the design's mitigation does fire: the user is warned about the duplicate name
    assertTrue("precondition: the duplicate-name warning is the control that covers this door",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", impostorAddress));

    // 3. ...and it is a warning, not a block, so the second row is added
    impostorContact = new Contact("Bob", "Jones", impostorAddress.getName(),
        impostorAddress.getDeviceId(), false);
    victim.getContactList().add(impostorContact);

    // 4. the impostor establishes a session to the victim and sends
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    activate(impostor);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final MessageEnvelope outbound = SignalProtocolMain.encryptMessage(plaintext, victimAddress);
    assertNotNull("fixture: the impostor must be able to encrypt", outbound);
    final String wire = EnvelopeCodec.toWire(outbound);

    // 5. the victim decrypts it, against the impostor's address
    activate(victim);
    final String decrypted =
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), impostorAddress);
    assertEquals("fixture: the victim must recover the impostor's text", plaintext, decrypted);
    return decrypted;
  }

  private static boolean logContains(final List<StorageMessage> log, final String text) {
    for (final StorageMessage message : log) {
      if (text.equals(message.getUnencryptedMessage())) return true;
    }
    return false;
  }

  /**
   * The address tag is the thing the user is told to compare, and it does distinguish the two rows.
   * Establishing that first is what makes the failure below about the chat log rather than about
   * the contact list.
   */
  @Test
  public void theTwoRowsAreDistinguishableInTheContactList() {
    final Contact other = new Contact("Bob", "Jones", impostorAddress.getName(),
        impostorAddress.getDeviceId(), false);
    assertNotEquals("the address tag must separate two rows that differ only by device id",
        SignalProtocolMain.displayTagFor(realPeerContact),
        SignalProtocolMain.displayTagFor(other));
  }

  /**
   * The impostor's message must not appear in the genuine contact's conversation.
   *
   * <p>This is the whole value of keeping two rows rather than one: the user was warned, chose to
   * keep both, and is entitled to read them separately. Merging them means the impostor's words are
   * rendered under the name, tag and verified badge of the person it is impersonating — the
   * substitution the pin refused, achieved without touching a key.
   */
  @Test
  public void animpostorsMessageMustNotLandInTheGenuineContactsChatLog() throws Exception {
    final String hostile = "new account, send the code to this number";
    impostorSendsAMessage(hostile);

    // A genuine exchange too, so the log below is non-empty for reasons unrelated to the impostor
    // and this asserts separation rather than emptiness.
    assertNotNull(SignalProtocolMain.encryptMessage("see you at six", peerAddress));

    final List<StorageMessage> genuineLog =
        SignalProtocolMain.getUnencryptedMessagesList(realPeerContact);
    assertTrue("fixture: the genuine conversation must be in this log",
        logContains(genuineLog, "see you at six"));
    assertFalse("the impostor's message is being rendered inside the genuine contact's chat log: "
            + genuineLog, logContains(genuineLog, hostile));
  }

  /**
   * And the other direction: what the user typed to the genuine contact must not be shown in the
   * impostor's conversation.
   */
  @Test
  public void whatTheUserTypedToTheGenuinePeerMustNotAppearInTheImpostorsChatLog()
      throws Exception {
    impostorSendsAMessage("hello");

    final String priv = "the spare key is under the third pot";
    assertNotNull(SignalProtocolMain.encryptMessage(priv, peerAddress));

    final List<StorageMessage> impostorLog =
        SignalProtocolMain.getUnencryptedMessagesList(impostorContact);
    assertFalse("a message addressed to the genuine peer is being rendered in the impostor's "
            + "conversation: " + impostorLog, logContains(impostorLog, priv));
  }

  /**
   * Deleting the impostor must not take the genuine contact's history with it.
   *
   * <p>Deleting the row is the action the duplicate warning is steering the user towards, so it has
   * to be safe.
   */
  @Test
  public void deletingTheImpostorMustNotEraseTheGenuineContactsHistory() throws Exception {
    impostorSendsAMessage("hello");

    final String kept = "see you at six";
    assertNotNull(SignalProtocolMain.encryptMessage(kept, peerAddress));
    assertTrue("fixture: the message must be in the genuine log to start with",
        logContains(SignalProtocolMain.getUnencryptedMessagesList(realPeerContact), kept));

    // A store whose write lands, and the return value asserted.
    //
    // Without one this deletion rolls back: removeContact restores the contact list, the message
    // list and the session wholesale when the write fails, so the log this test reads afterwards is
    // the copy taken BEFORE the sweep ran. The assertion then holds whether the sweep is scoped or
    // deletes everything - which is the one thing this test exists to tell apart. Measured by a
    // reviewer: widening removeAllUnencryptedMessages to a total wipe was caught by three other
    // tests and not by this one, whose whole subject it is.
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();
    assertTrue("precondition: the deletion must actually reach disk, or the sweep under test never "
            + "runs and the assertion below reads a rolled-back copy of the log",
        SignalProtocolMain.removeContactFromContactListAndProtocol(impostorContact));

    final List<StorageMessage> after = new ArrayList<>();
    try {
      after.addAll(SignalProtocolMain.getUnencryptedMessagesList(realPeerContact));
    } catch (Exception e) {
      // an empty log raises rather than returning nothing; that is itself the failure
    }
    assertTrue("deleting the impostor erased the genuine contact's conversation: " + after,
        logContains(after, kept));
  }
}
