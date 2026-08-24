package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The cheapest way past the entire trust model: don't fight the pin, go around it.
 *
 * <p>Every control built so far defends <em>one address</em>. Substituting a key for a pinned
 * contact is refused, recorded, and warned about. But a messenger does not have to do that. It can
 * fabricate a reinstall story — "phone died, here's my new invite" — and attach a bundle at a
 * <b>different</b> address it controls. Adding that is a clean first sighting: no pin to conflict
 * with, no identity change, no warning anywhere. And because the contact list rendered names only,
 * the result was two rows both reading "Alice", indistinguishable to the user.
 *
 * <p>What makes the story credible is that it is <em>true behaviour</em> for a genuine reinstall —
 * a fresh install really does mint a new address (see {@link AddressingPremiseTest}), so a real
 * reinstalled peer really does arrive as a second contact. The app therefore cannot refuse this.
 * It can only make it visible.
 */
public class DuplicateContactNameTest {

  private Account victim;
  private Account realAlice;
  private Account attacker;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realAlice = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setAccount(victim);
  }

  private static SignalProtocolAddress addressOf(final Account a) {
    return ProtocolAddresses.of(a.getSignalProtocolAddress().getName(), a.getDeviceId());
  }

  private Contact addAs(final String first, final String last, final Account peer) {
    final Contact c = new Contact(first, last, peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId(), false);
    final ArrayList<Contact> list = victim.getContactList() == null
        ? new ArrayList<>() : new ArrayList<>(victim.getContactList());
    list.add(c);
    victim.setContactList(list);
    return c;
  }

  @Test
  public void aSecondContactWithTheSameNameIsFlagged() {
    addAs("Alice", "Smith", realAlice);

    assertTrue("adding a second Alice at another address must be noticed",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith", addressOf(attacker)));
  }

  /** The genuine contact must not flag itself, or every re-add would cry wolf. */
  @Test
  public void aContactDoesNotFlagItself() {
    addAs("Alice", "Smith", realAlice);

    assertFalse("the existing Alice must not count as her own duplicate",
        SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith", addressOf(realAlice)));
  }

  @Test
  public void differentNamesAreNotFlagged() {
    addAs("Alice", "Smith", realAlice);

    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Smith",
        addressOf(attacker)));
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Jones",
        addressOf(attacker)));
  }

  @Test
  public void anEmptyContactListFlagsNothing() {
    victim.setContactList(new ArrayList<>());
    assertFalse(SignalProtocolMain.hasContactWithSameDisplayName("Alice", "Smith",
        addressOf(attacker)));
  }

  // ------------------------------------------------------------- address tags

  /**
   * The tag has to actually distinguish the two, or flagging them achieves nothing — the user still
   * cannot tell which row is which.
   */
  @Test
  public void theAddressTagDistinguishesTwoContactsWithOneName() {
    final Contact genuine = addAs("Alice", "Smith", realAlice);
    final Contact impostor = addAs("Alice", "Smith", attacker);

    assertEquals("Alice", genuine.getFirstName());
    assertEquals(genuine.getFirstName(), impostor.getFirstName());
    assertNotEquals("two contacts under one name must not share an address tag",
        genuine.getAddressTag(), impostor.getAddressTag());
  }

  /** Same address, same tag - it identifies the address, so it must be stable for one. */
  @Test
  public void theAddressTagIsStableForOneAddress() {
    final Contact first = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), realAlice.getDeviceId(), false);
    final Contact again = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), realAlice.getDeviceId(), true);

    assertEquals(first.getAddressTag(), again.getAddressTag());
  }

  /**
   * The tag must survive rejecting and re-pinning a key. It identifies the address, not the
   * identity — if it moved when a key was legitimately replaced it would read as a change when
   * nothing about who the contact is had moved.
   */
  @Test
  public void theAddressTagSurvivesRejectingAndRePinningTheKey() throws Exception {
    final Contact alice = addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress address = addressOf(realAlice);

    SignalProtocolMain.getInstance().setAccount(attacker);
    final String forged = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(forged), address);

    final String before = alice.getAddressTag();
    SignalProtocolMain.rejectContactKey(alice);

    SignalProtocolMain.getInstance().setAccount(realAlice);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), address));

    assertEquals("the tag names the address, so re-keying must not move it",
        before, alice.getAddressTag());
  }

  /**
   * The address NAME must be part of the tag too. Device ids fold into [1,127], so two unrelated
   * peers colliding on one is routine - and the impersonation case is precisely a second contact at
   * an address the attacker chose, which they can pick to match. A tag built from the device id
   * alone would render the two rows identical again.
   *
   * <p>The earlier distinguishing test could not catch this: its two accounts had different random
   * device ids, so the tags differed on that alone and a name-blind tag still passed.
   */
  @Test
  public void theTagSeparatesTwoAddressesThatShareADeviceId() {
    final Contact genuine = new Contact("Alice", "Smith",
        realAlice.getSignalProtocolAddress().getName(), 42, false);
    final Contact impostor = new Contact("Alice", "Smith",
        attacker.getSignalProtocolAddress().getName(), 42, false);

    assertEquals("the device ids collide, as they routinely can",
        genuine.getDeviceId(), impostor.getDeviceId());
    assertNotEquals("a colliding device id must not make two peers look like one contact",
        genuine.getAddressTag(), impostor.getAddressTag());
  }

  /** The device id is part of the address, so it has to be part of the tag. */
  @Test
  public void theTagSeparatesTwoDevicesOfOneName() {
    final String name = realAlice.getSignalProtocolAddress().getName();
    final Contact deviceOne = new Contact("Alice", "Smith", name, 11, false);
    final Contact deviceTwo = new Contact("Alice", "Smith", name, 22, false);

    assertNotEquals("same name, different device must be visibly different",
        deviceOne.getAddressTag(), deviceTwo.getAddressTag());
  }

  // ------------------------------------------------- confusable / invisible names

  /**
   * Names that <em>render</em> identically must compare identically.
   *
   * <p>NFKC + trim + lowercase was not enough, and was documented as if it were. NFKC does not fold
   * scripts — Cyrillic А, Greek Α and Latin A are three characters that draw the same glyph and none
   * folds to another — and it does not strip format characters, so a zero-width space or a
   * right-to-left override survives it and renders as nothing.
   *
   * <p>The consequence was worse than a missed toast: the contact-row tag is gated on this same
   * comparison, so one invisible character suppressed the warning <em>and</em> removed the tag from
   * both rows. Two entries reading identically, with nothing anywhere to tell them apart.
   *
   * <p>The user types this text because they copy the name out of the invite — which the attacker
   * wrote.
   */
  @Test
  public void namesThatRenderIdenticallyAreTreatedAsDuplicates() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    final String[] dodges = {
        "\u0410lice",       // Cyrillic А
        "Ali\u0441e",       // Cyrillic с
        "\u0391lice",       // Greek Α
        "Alice\u200B",      // zero-width space
        "Ali\u00ADce",      // soft hyphen
        "\u200EAlice",      // left-to-right mark
        "Alice\u2060",      // word joiner
        "\uFEFFAlice",      // BOM
        "  ALICE  ",         // whitespace + case, which the old folding did catch
    };
    for (final String dodge : dodges) {
      assertTrue("\"" + dodge + "\" dodged the duplicate check",
          SignalProtocolMain.hasContactWithSameDisplayName(dodge, "Smith", elsewhere));
    }
  }

  /** The folding must not be so aggressive that genuinely different names collide. */
  @Test
  public void genuinelyDifferentNamesSurviveTheFolding() {
    addAs("Alice", "Smith", realAlice);
    final SignalProtocolAddress elsewhere = ProtocolAddresses.of("attacker-uuid", 7);

    for (final String[] other : new String[][] {
        {"Alicia", "Smith"}, {"Alice", "Smyth"}, {"Bob", "Smith"}, {"Al", "Smith"}}) {
      assertFalse(other[0] + " " + other[1] + " must not be treated as a duplicate of Alice Smith",
          SignalProtocolMain.hasContactWithSameDisplayName(other[0], other[1], elsewhere));
    }
  }

  // --------------------------------------------------------- one address, one identity

  /**
   * A second contact row at an address already in use is never legitimate, and needs no name
   * trickery to create.
   *
   * <p>An attacker already present as one contact sends a bundle from that same address with a
   * "this is Alice, I reinstalled" story. Same key, so no pin conflict and no identity change; a
   * different name, so the duplicate-name check does not fire; and the address matches the contact
   * being added, so the name loop skips the only row that would have matched. Nothing warned.
   *
   * <p>Unlike the name check this one is exact — it compares addresses, which a peer cannot dodge.
   */
  @Test
  public void asecondContactAtAnAddressAlreadyInUseIsFlagged() {
    addAs("Bob", "Jones", attacker);
    final SignalProtocolAddress attackerAddress = addressOf(attacker);

    final Contact clash =
        SignalProtocolMain.existingContactAtSameAddress(attackerAddress, "Alice", "Smith");

    assertNotNull("a second name at one address must be flagged", clash);
    assertEquals("Bob", clash.getFirstName());
  }

  /** Re-adding the same contact under the same name is not a clash. */
  @Test
  public void reAddingTheSameContactAtItsOwnAddressIsNotFlagged() {
    addAs("Bob", "Jones", attacker);

    assertNull("the same person under the same name is a re-add, not a second identity",
        SignalProtocolMain.existingContactAtSameAddress(addressOf(attacker), "Bob", "Jones"));
  }

  /** And a genuinely new address is not a clash. */
  @Test
  public void aContactAtAFreshAddressIsNotFlagged() {
    addAs("Bob", "Jones", attacker);

    assertNull(SignalProtocolMain.existingContactAtSameAddress(
        addressOf(realAlice), "Alice", "Smith"));
    assertNull(SignalProtocolMain.existingContactAtSameAddress(null, "Alice", "Smith"));
  }

  // ------------------------------------------------------------------ tag width

  /**
   * The tag must not be grindable. It was 40 bits, hashed over an address the peer chooses freely,
   * against an adversary that knows the address it wants to collide with — minutes on a GPU, and a
   * couple of core-hours for the trailing bits a user is least likely to read.
   */
  @Test
  public void theAddressTagIsWideEnoughToResistGrinding() {
    final String tag = new Contact("A", "B", "peer-uuid", 7, false).getAddressTag();
    final String hex = tag.replace("#", "").replace("-", "");

    assertEquals("the tag must carry 96 bits; 40 is grindable by the adversary in the model",
        24, hex.length());
    assertTrue("the tag must be hex", hex.matches("[0-9a-f]+"));
  }
}
