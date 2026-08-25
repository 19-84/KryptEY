package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.BinaryEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The U+001F separation is an envelope-parser property, and contacts do not all come from the
 * envelope parser.
 *
 * <p>{@code ProtocolAddresses.SEPARATOR}'s javadoc states the premise exactly:
 * "{@code BinaryEnvelope.requireDisplaySafeName} refuses any sender name outside printable ASCII,
 * on every envelope, in both directions. A character below 0x20 therefore cannot occur in any
 * address name this app will accept, so a rendered key and a bare name live in provably disjoint
 * spaces."
 *
 * <p>The second sentence does not follow from the first. Every contact the CURRENT code creates
 * comes from a decoded envelope, so it is validated - but the contact list is also read back off
 * disk on every {@code setInputView}, through {@code JsonUtil.convertContactsList}, and nothing on
 * that path asks the question. KryptEY 0.1.5 - the version this branch exists to upgrade from - had
 * no validation of any kind: its {@code MessageEnvelope} was Jackson-deserialised straight out of
 * the wire text and {@code createAndAddContactToList} checked only that the name was non-null. So
 * an address name containing a byte below 0x20 is something an upgrading install can already be
 * holding, planted by the messenger before the upgrade with a single ordinary invite.
 *
 * <p>The value to plant is not secret: it is Bob's address name, a U+001F, and Bob's device id -
 * every part of which travels in the envelopes the messenger relays. After the upgrade the crafted
 * name is literally the string every message filed for Bob carries as its {@code contactUUID}, the
 * legacy arm compares bare names, and the ambiguity gate answers "exactly one" because no other
 * contact shares that name. The attacker's row therefore renders - and can delete - Bob's entire
 * post-upgrade conversation. That is the Round B finding, restored, against a store the validation
 * never covered.
 *
 * <p>The fixture builds the stored JSON as text, in the exact shape {@code JsonUtil.toJson} emits,
 * and loads it through the two production calls {@code StorageHelper.getAccountFromSharedPreferences}
 * makes. Nothing in the test constructs a {@code Contact} carrying the crafted name directly.
 */
public class StoredContactNameValidationTest {

  private static final String SECRET = "the spare key is under the third pot";
  private static final String PLACEHOLDER = "PLACEHOLDERNAME";

  private Account victim;
  private SignalProtocolAddress peerAddress;
  private Contact genuineContact;
  private String craftedName;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();

    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());

    SignalProtocolMain.getInstance().setAccount(peer);
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue("fixture: the genuine peer must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(peerBundle),
            peerAddress));

    genuineContact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineContact);
    victim.setContactList(contacts);

    // Both halves are in the envelopes the messenger relays.
    craftedName = peerAddress.getName() + ((char) 0x1f) + peerAddress.getDeviceId();
  }

  /**
   * The stored contact list as a 0.1.5 install could legitimately be holding it: the exact JSON
   * {@code JsonUtil.toJson} writes, with an address name no current envelope could carry.
   */
  private Contact loadCraftedContactFromStoredJson() throws IOException {
    final ArrayList<Contact> shape = new ArrayList<>();
    shape.add(new Contact("Carol", "Smith", PLACEHOLDER, 9, false));
    final String stored = JsonUtil.toJson(shape)
        // The JSON escape for U+001F. Jackson emits it for any control character, and accepts it.
        .replace(PLACEHOLDER, peerAddress.getName() + "\\u001F" + peerAddress.getDeviceId());

    @SuppressWarnings("unchecked")
    final ArrayList<Contact> raw =
        (ArrayList<Contact>) JsonUtil.fromJson(stored, ProtocolIdentifier.CONTACTS.className);
    return JsonUtil.convertContactsList(raw).get(0);
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

  /** The premise, stated as an assertion: no envelope can carry this name. */
  @Test
  public void thewireRefusesTheCraftedName() {
    assertThrows("requireDisplaySafeName must refuse a name holding U+001F", IOException.class,
        () -> BinaryEnvelope.encode(
            new MessageEnvelope(new byte[] {1, 2, 3}, 3, craftedName, 9)));
  }

  /**
   * ...and the store does not, so the disjointness the key relies on does not hold.
   *
   * <p>The user is looking at a row called Carol. What is rendered under it is every word they have
   * exchanged with Bob since the upgrade. The two contacts share no display name, no address, no
   * pinned key and no address tag; the only thing connecting them is that one row's stored name is
   * byte-for-byte the other row's chat-log key.
   */
  @Test
  public void acontactLoadedFromStorageMustNotCarryANameTheWireWouldRefuse() throws Exception {
    assertNotNull("fixture: the victim must be able to write to the genuine peer",
        SignalProtocolMain.encryptMessage(SECRET, peerAddress));
    assertTrue("fixture: the message must be in the genuine contact's log",
        logContains(logOf(genuineContact), SECRET));

    final Contact loaded = loadCraftedContactFromStoredJson();
    assertEquals("fixture: the load path must have preserved the stored name verbatim",
        craftedName, loaded.getSignalProtocolAddressName());
    victim.getContactList().add(loaded);

    // A control row with an ordinary name, so the assertion below cannot pass merely because every
    // contact sees every message.
    final Contact bystander = new Contact("Dave", "Brown", "an-ordinary-address-name", 11, false);
    victim.getContactList().add(bystander);
    assertFalse("control: an ordinary contact must see nothing",
        logContains(logOf(bystander), SECRET));

    assertNotNull("fixture: one contact bears the crafted name - the state the deleted legacy arm "
            + "would have treated as attributable",
        victim.soleContactNamed(craftedName));

    assertFalse("the genuine contact's conversation is being rendered inside the attacker's row, "
        + "because a stored address name was never held to the validation the key's disjointness "
        + "argument depends on: " + logOf(loaded), logContains(logOf(loaded), SECRET));
  }
}
