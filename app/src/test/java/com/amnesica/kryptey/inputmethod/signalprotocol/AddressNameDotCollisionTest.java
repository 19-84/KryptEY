package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * The chat-log key and the address name live in one namespace, and the messenger writes the address
 * name.
 *
 * <p>{@code ProtocolAddresses.key} renders a full address as {@code name.deviceId}, and
 * {@link StorageMessage#belongsTo} compares {@code contactUUID} against that string in its first
 * arm and against the bare address NAME in its legacy arm. Nothing keeps the two apart. An address
 * name is peer-supplied free text out of the pasted envelope - {@code MessageEnvelope} carries it
 * verbatim, {@code addContact} validates only the display name the user types - so an attacker may
 * choose an address name that is already a rendered address.
 *
 * <p>Give the attacker's own bundle the address name {@code bobsName.bobsDeviceId}. It is a fresh,
 * unpinned address, so it is accepted; it collides with no existing contact, so the same-address
 * refusal does not fire; and it may carry any display name at all, so the duplicate-name warning
 * need not fire either. Every message already filed for the genuine Bob carries
 * {@code contactUUID = "bobsName.bobsDeviceId"}, which is now literally the attacker's address name
 * - so the legacy arm matches, and the genuine conversation is rendered inside the attacker's.
 *
 * <p>The ambiguity gate does not help: it counts contacts sharing an ADDRESS NAME, and these two
 * address names are different strings. It answers "exactly one", which is the answer that turns the
 * legacy arm on.
 *
 * <p>The address name is the thing the fix's own reasoning calls public - "it travels in every
 * envelope the messenger relays" - and the device id beside it is in the same envelope. The
 * attacker needs no other knowledge.
 */
public class AddressNameDotCollisionTest {

  private Account victim;
  private SignalProtocolAddress peerAddress;
  private SignalProtocolAddress collidingAddress;
  private Contact genuineContact;
  private Contact attackerContact;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account attacker = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    // Everything here is in the envelope the messenger relayed: the name and the device id.
    final String craftedName = peerAddress.getName() + "." + peerAddress.getDeviceId();
    collidingAddress = ProtocolAddresses.of(craftedName, 9);

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(attacker);
    final String attackerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);

    assertTrue("fixture: the genuine peer pins",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(peerBundle),
            peerAddress));
    // A clean first sighting at an address nobody has ever used. Nothing refuses this.
    assertTrue("the crafted address is unpinned, so trust-on-first-use accepts it",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(attackerBundle),
            collidingAddress));

    genuineContact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    // Under a completely unrelated display name, so the duplicate-name warning has nothing to say.
    attackerContact = new Contact("Carol", "Smith", collidingAddress.getName(),
        collidingAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineContact);
    contacts.add(attackerContact);
    victim.setContactList(contacts);
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
      return new ArrayList<>();
    }
  }

  /**
   * A contact whose address NAME is a rendered address must not inherit that address's chat log.
   *
   * <p>The user is looking at a row called Carol. What is rendered under it is every word they ever
   * exchanged with Bob. Nothing about the two contacts is shared: different names, different
   * addresses, different pinned keys, different address tags. The only thing that connects them is
   * that one contact's key string parses as the other contact's name.
   */
  @Test
  public void acontactWhoseNameIsARenderedAddressMustNotInheritThatAddressesLog() throws Exception {
    final String priv = "the spare key is under the third pot";
    assertNotNull("fixture: the victim must be able to write to the genuine peer",
        SignalProtocolMain.encryptMessage(priv, peerAddress));

    assertTrue("fixture: the message must be in the genuine contact's log",
        logContains(logOf(genuineContact), priv));

    assertEquals("fixture: the two address names must be different strings, so nothing about "
            + "this is a same-address collision",
        false, genuineContact.getSignalProtocolAddressName()
            .equals(attackerContact.getSignalProtocolAddressName()));
    assertNotNull("fixture: one contact bears that name, which is the state the deleted legacy "
            + "arm would have treated as attributable",
        victim.soleContactNamed(attackerContact.getSignalProtocolAddressName()));

    assertFalse("the genuine contact's conversation is being rendered inside the attacker's: "
        + logOf(attackerContact), logContains(logOf(attackerContact), priv));
  }

  /**
   * And it must not be able to delete it either.
   *
   * <p>{@code removeAllUnencryptedMessages} runs the same predicate, so deleting the attacker's row
   * - which is what a user does when they realise they added someone they should not have - takes
   * the genuine contact's whole conversation with it.
   */
  @Test
  public void deletingSuchAContactMustNotEraseTheOtherContactsHistory() throws Exception {
    final String kept = "see you at six";
    assertNotNull(SignalProtocolMain.encryptMessage(kept, peerAddress));
    assertTrue("fixture: the message must start in the genuine contact's log",
        logContains(logOf(genuineContact), kept));

    victim.removeAllUnencryptedMessages(attackerContact);

    assertTrue("deleting the attacker's row erased the genuine contact's conversation",
        logContains(logOf(genuineContact), kept));
  }
}
