package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * The retired-name list is bounded by NAME, and the address it was retired from still scopes the
 * one thing that address is allowed to decide.
 *
 * <p>Two shapes were tried and each bought its own attack, because two different questions are
 * being asked of one list. The BOUND asks how many names are remembered, and the reader matches on
 * the name — so an entry per address let a messenger that drives deletions mint entries for free by
 * varying the address, pressing a real name out of a hundred-entry list without the user ever
 * typing the impostor's name. The SUPPRESSION asks whether this exact address is one the name was
 * deleted from, because deletion keeps the pin — so collapsing to the name alone made deleting an
 * impostor "Bob" evict the genuine "Bob", and the impostor's next invite arrived silent.
 *
 * <p>One entry per folded name carrying its addresses as a set answers both, and this pins both
 * halves. The end-to-end test below is the one that matters: it is the sequence the app's own
 * advice produces, and under either earlier shape it ends in silence.
 */
public class RetiredNameBoundCountsNamesTest {

  private Account victim;
  private Account genuineBob;
  private Account impostorBob;

  private SignalProtocolAddress genuineAddress;
  private SignalProtocolAddress impostorAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    genuineBob = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    impostorBob = SignalProtocolMain.getInstance().getAccount();

    genuineAddress = ProtocolAddresses.of(genuineBob.getSignalProtocolAddress().getName(),
        genuineBob.getDeviceId());
    impostorAddress = ProtocolAddresses.of(impostorBob.getSignalProtocolAddress().getName(),
        impostorBob.getDeviceId());

    // The reader is static and answers for whichever account is loaded; initialize() left the last
    // one active. A test asking the victim's questions against the impostor's account passes for
    // reasons that have nothing to do with what it claims.
    activate(victim);
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** Pins that peer's real bundle at its own address, the way an accepted invite does. */
  private void acceptInviteFrom(final Account peer, final SignalProtocolAddress address)
      throws Exception {
    activate(peer);
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue("fixture: the invite must be accepted",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bundle), address));
  }

  /** Adds a contact under that name at that address and then deletes it, as the user would. */
  private void addAndDelete(final String first, final String last,
                            final SignalProtocolAddress address) {
    final Contact contact =
        new Contact(first, last, address.getName(), address.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    victim.setContactList(contacts);
    SignalProtocolMain.removeContactFromContactListAndProtocol(contact);
  }

  /**
   * Deleting an impostor must not silence the impostor.
   *
   * <p>The whole sequence, driven by the app's own advice. The user deletes the genuine Bob. An
   * impostor invites as "Bob Jones" from its own address, and the warning fires — that is the
   * control working. The user heeds it and deletes the impostor, which is exactly what the warning
   * asks for. The impostor invites again from the same address.
   *
   * <p>Under name-only keying the impostor's own deletion evicted the genuine entry, so the second
   * invite arrived with nothing to say. Under an address-in-the-set suppression the merged entry
   * would list the impostor's address and suppress on it. Both end in a row that looks healthy,
   * carrying the name of somebody the user knows, at an address they were warned about once and
   * cannot be warned about again.
   */
  @Test
  public void deletingAnimpostorDoesNotSilenceItsNextInvite() throws Exception {
    acceptInviteFrom(genuineBob, genuineAddress);
    addAndDelete("Bob", "Jones", genuineAddress);

    acceptInviteFrom(impostorBob, impostorAddress);
    assertTrue("precondition: an impostor reusing a deleted contact's name must be warned about",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", impostorAddress));

    // The user does what the warning asks: removes the contact they were warned about.
    addAndDelete("Bob", "Jones", impostorAddress);
    assertNotNull("precondition: deletion keeps the pin, which is what the suppression is "
            + "justified by - without it this test would pass for the wrong reason",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(impostorAddress));

    assertTrue("heeding the warning must not be what switches it off: a name the user deleted at "
            + "ANOTHER address is still a name coming back, and the impostor's second invite is "
            + "the one arriving at a user who has already been told once",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", impostorAddress));
  }

  /**
   * And the false alarm the suppression exists for still does not fire, however often it recurs.
   *
   * <p>Delete-and-re-add at one address is the commonest firing of this control, because the app's
   * decryption-failure advice sends users round that loop. The set stays a single address across
   * any number of cycles, so the suppression keeps applying — a control that is provably wrong
   * where it fires most is the habituation failure this design argues against.
   */
  @Test
  public void repeatedCyclesAtOneAddressStayQuiet() throws Exception {
    acceptInviteFrom(genuineBob, genuineAddress);
    for (int i = 0; i < 5; i++) {
      addAndDelete("Bob", "Jones", genuineAddress);
    }

    assertNotNull("precondition: the pin must survive for the suppression to be allowed",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(genuineAddress));
    assertFalse("re-adding at the one address the name was ever deleted from must stay quiet",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", genuineAddress));
  }

  /**
   * A name varied by address cannot press another name out of the bound.
   *
   * <p>Entries are minted by deletion, and deletion is attacker-drivable: replay a message until
   * decryption fails often enough that starting over looks sensible, and the app's own advice does
   * the rest. With an entry per address the attacker chooses a fresh address each cycle and each
   * one costs a slot; a hundred of them and the name the user actually needs remembered is gone.
   */
  @Test
  public void varyingTheAddressUnderOneNameCannotEvictAnotherName() {
    victim.retireDisplayName("Alice", "Baker", ProtocolAddresses.key(genuineAddress));

    for (int i = 1; i <= 300; i++) {
      victim.retireDisplayName("Bob", "Jones",
          ProtocolAddresses.key(ProtocolAddresses.of("attacker-" + i, 1)));
    }

    assertEquals("one entry per folded name: three hundred addresses under one name must occupy "
            + "one slot, or the bound counts the attacker's work instead of the user's", 2,
        victim.getRetiredDisplayNames().size());
    assertTrue("the name the user deleted must still be remembered after the flood",
        SignalProtocolMain.hasRetiredDisplayName("Alice", "Baker"));
  }

  /**
   * And one name's address set is bounded too, in the direction that fails safe.
   *
   * <p>Nothing else caps it: the entry is written on every deletion and read back whole. Dropping
   * the oldest address loses silence rather than a warning — an address no longer listed is one the
   * suppression no longer applies to. The attacker cannot use the trim to manufacture silence
   * either, since suppression needs a set of exactly one address and merging never shrinks one.
   */
  @Test
  public void oneNamesAddressSetIsBounded() {
    for (int i = 1; i <= 300; i++) {
      victim.retireDisplayName("Bob", "Jones",
          ProtocolAddresses.key(ProtocolAddresses.of("attacker-" + i, 1)));
    }

    final String[] entry = victim.getRetiredDisplayNames().getFirst();
    assertTrue("a single name's address set grew without limit: " + (entry.length - 2)
            + " addresses on one entry, written to disk on every save",
        entry.length - 2 <= Account.RETIRED_ADDRESSES_PER_NAME);
    assertTrue("...and it must still hold addresses at all", entry.length > 2);
  }

  /**
   * Repeating one address cannot crowd the others out of a name's set.
   *
   * <p>The de-duplication in the merge is what this asserts, and a review round found it was the
   * only thing standing between the user and the attack the whole shape was built to close, with
   * nothing testing it. Without it: the messenger drives eight delete-and-re-invite cycles at its
   * OWN address - one replayed message each, the app's advice does the rest - the set fills with
   * eight copies of that one address, the genuine address is trimmed out of the far end, and every
   * address in the set is then the impostor's, so the impostor is suppressed permanently.
   *
   * <p>Every other test here uses distinct addresses and so cannot see it, including the one that
   * repeats a single address: a set of five identical addresses is still all-equal, so it is
   * suppressed either way. This one needs a second address present to have something to lose.
   */
  @Test
  public void repeatingOneAddressCannotCrowdOutAnother() throws Exception {
    acceptInviteFrom(genuineBob, genuineAddress);
    addAndDelete("Bob", "Jones", genuineAddress);

    acceptInviteFrom(impostorBob, impostorAddress);
    for (int i = 0; i < 10; i++) {
      addAndDelete("Bob", "Jones", impostorAddress);
    }

    assertEquals("repeats of one address must not consume the set: two addresses were deleted "
            + "from, so the entry holds two", 4,
        victim.getRetiredDisplayNames().getFirst().length);
    assertNotNull("precondition: the pin the suppression is justified by still stands at the "
            + "impostor's address, so nothing but the set decides this",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(impostorAddress));
    assertTrue("driving deletions at one address must not buy silence at it",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", impostorAddress));
  }

  /**
   * The trim keeps the newest addresses and drops the oldest.
   *
   * <p>Asserted on the entry rather than through the reader, because the reader cannot see it: a
   * set holding two or more distinct addresses warns at every address, so a trim in either
   * direction leaves the same answer to every question the reader can be asked. A test that read
   * this through {@code hasContactWithSameDisplayName} passed with the trim reversed AND with the
   * trim deleted, while its own message claimed to be pinning the direction.
   *
   * <p>Nothing security-relevant turns on the direction today - see
   * {@code RETIRED_ADDRESSES_PER_NAME}, where it is the size the trim leaves that matters - so this
   * pins the writer and the loader agreeing, which is a real property: they drop different ends and
   * a reload changes which addresses a name is remembered at.
   */
  @Test
  public void thetrimDropsTheOldestAddress() throws Exception {
    victim.retireDisplayName("Bob", "Jones", ProtocolAddresses.key(genuineAddress));
    for (int i = 1; i <= Account.RETIRED_ADDRESSES_PER_NAME; i++) {
      victim.retireDisplayName("Bob", "Jones",
          ProtocolAddresses.key(ProtocolAddresses.of("attacker-" + i, 1)));
    }

    final String[] entry = victim.getRetiredDisplayNames().getFirst();
    final java.util.List<String> addresses =
        java.util.Arrays.asList(entry).subList(2, entry.length);

    assertEquals("the set is full", Account.RETIRED_ADDRESSES_PER_NAME, addresses.size());
    assertFalse("the oldest address is the one dropped",
        addresses.contains(ProtocolAddresses.key(genuineAddress)));
    assertTrue("...and the newest is kept",
        addresses.contains(ProtocolAddresses.key(
            ProtocolAddresses.of("attacker-" + Account.RETIRED_ADDRESSES_PER_NAME, 1))));
  }
}
