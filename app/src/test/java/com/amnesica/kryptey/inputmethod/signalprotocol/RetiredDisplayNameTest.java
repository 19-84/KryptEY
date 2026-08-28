package com.amnesica.kryptey.inputmethod.signalprotocol;

import com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;

/**
 * Deleting a contact must not erase the duplicate-name warning.
 *
 * <p>{@code removeContact} keeps the pinned key, and spends twenty lines arguing that this makes
 * deletion fail closed. Measured, it closes only the SAME-address door: a substituted bundle for
 * that address is still refused.
 *
 * <p>The attacker uses the other door. Its own fresh address, carrying the deleted contact's name -
 * nothing is pinned there, so trust-on-first-use accepts it silently, and with the name gone from
 * the contact list {@code hasContactWithSameDisplayName} returns false and the duplicate warning
 * has nothing to fire on. A warned attack became an unwarned one.
 *
 * <p>That is reachable without any crafted Unicode and without the user doing anything unusual:
 * deletion is one tap with no confirmation, and a hostile messenger can drive a user to it by
 * replaying any message until decryption fails often enough that starting over looks sensible.
 *
 * <p>{@code rejectedAddresses} was deliberately made to outlive {@code removeIdentity} on exactly
 * this reasoning. The display name got no such treatment.
 */
public class RetiredDisplayNameTest {

  private Account me;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    me = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of("peer-uuid", 7);

    final Contact bob = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    me.setContactList(contacts);

    // A pinned key at Bob's address.
    //
    // This fixture had none, which made areAddAtTheSameAddressDoesNotWarn assert the suppression in
    // precisely the state where its own stated justification - "deletion keeps the pin, so a re-add
    // there is provably the same identity" - does not hold. The test passed because the code did
    // not check the premise either. Both now do.
    me.getSignalProtocolStore().getIdentityKeyStore().saveIdentity(peerAddress,
        org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey());
      // This file deletes contacts and asserts they are gone. A deletion whose write does
    // not land is rolled back so the user can retry, so the fixture has to say it writes.
    TestStores.writesLand();
}

  private SignalProtocolAddress elsewhere() {
    return ProtocolAddresses.of("attacker-uuid", 9);
  }

  @Test
  public void adeletedNameStillTriggersTheDuplicateWarning() {
    assertTrue("precondition: the live contact must warn",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", elsewhere()));

    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));
    assertEquals("precondition: the contact must actually be gone", 0, me.getContactList().size());

    assertTrue("an attacker offering a deleted contact's name at its OWN address must still raise "
            + "the duplicate warning - nothing is pinned there, so nothing else will",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", elsewhere()));
  }

  /** The fold applies to retired names too, or the warning is trivially dodged. */
  @Test
  public void adeletedNameIsMatchedThroughTheSameFold() {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));

    assertTrue("case must not dodge it",
        SignalProtocolMain.hasContactWithSameDisplayName("bob", "jones", elsewhere()));
    assertTrue("nor a zero-width character",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob​", "Jones", elsewhere()));
    assertTrue("nor a homoglyph the fold already covers",
        SignalProtocolMain.hasContactWithSameDisplayName("Bοb", "Jones", elsewhere()));
  }

  /** An unrelated name must not warn, or the control cries wolf on everything. */
  @Test
  public void anunrelatedNameDoesNotWarn() {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));

    assertFalse("a name that was never used must not warn",
        SignalProtocolMain.hasContactWithSameDisplayName("Carol", "Smith", elsewhere()));
  }

  /** Bounded, because this is a warning aid rather than a security record. */
  @Test
  public void theListIsBoundedAndKeepsTheMostRecent() {
    for (int i = 0; i < 150; i++) {
      me.retireDisplayName("Person" + i, "Surname" + i, "uuid-" + i);
    }

    assertTrue("the list must be bounded, and held " + me.getRetiredDisplayNames().size(),
        me.getRetiredDisplayNames().size() <= 100);
    assertTrue("the most recent deletion must survive",
        SignalProtocolMain.hasRetiredDisplayName("Person149", "Surname149"));
    assertFalse("and the oldest must have been dropped",
        SignalProtocolMain.hasRetiredDisplayName("Person0", "Surname0"));
  }

  /**
   * It must survive a reload, or it is not a defence.
   *
   * <p>{@code reloadAccount} runs on every {@code setInputView}, so an in-memory-only list is empty
   * by the time the attacker's invite arrives - which is after the user has put the keyboard away
   * and picked it up again. This is the same failure the display-tag secret had.
   */
  @Test
  public void itSurvivesSerialisation() throws Exception {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));
    assertTrue(SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));

    final String json = com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil
        .toJson(me.getRetiredDisplayNames());
    assertTrue("the retired names must serialise to something non-trivial: " + json,
        json != null && json.contains("Bob"));
  }

  /**
   * A re-add at the SAME address must not warn.
   *
   * <p>Deletion keeps the pin, so a re-add there is provably the same identity - a substituted
   * bundle for that address is still refused. Warning is a false alarm, and it was the commonest
   * firing of this control: the app's own decryption-failure advice tells users to ask for a fresh
   * invite, which sends them round exactly this loop.
   *
   * <p>It matters more than an ordinary false positive because the banner it reused says "You
   * already have a contact called X ... Both now appear in your list" - two things the user can see
   * are untrue. Habituation is the documented failure mode of this whole control, and a warning
   * that is provably wrong in its commonest firing is worse than the gap it closes.
   */
  @Test
  public void areAddAtTheSameAddressDoesNotWarn() {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));

    assertNotNull("precondition: the pin must survive the deletion - it is the entire reason this "
            + "re-add is provably the same identity, and without it the suppression is a hole",
        me.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    assertFalse("re-adding the same person at the same address must not warn - the surviving pin "
            + "already proves it is them",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));
  }

  /** But the same name at a DIFFERENT address is the attack, and must still warn. */
  @Test
  public void thesameNameAtAdifferentAddressStillWarns() {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));

    assertTrue("an attacker offering the deleted name at its own address must still warn",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", elsewhere()));
  }

  /**
   * The bound counts DISTINCT names, so it cannot be exhausted by repetition.
   *
   * <p>Without de-duplication, exactly 100 delete-and-re-add cycles of one unrelated contact evict
   * the name an attacker cares about. That is user work rather than attacker work, which caps the
   * severity - but it is the loop the app's own failure advice creates.
   */
  @Test
  public void repeatedDeletionsOfOneNameDoNotEvictAnother() {
    SignalProtocolMain.removeContactFromContactListAndProtocol(me.getContactList().get(0));
    assertTrue(SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));

    for (int i = 0; i < 250; i++) {
      me.retireDisplayName("Churn", "Contact", "churn-uuid");
    }

    assertEquals("repeating one name must not consume the bound", 2,
        me.getRetiredDisplayNames().size());
    assertTrue("the name an attacker would reuse must survive the churn",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));
  }

  /** And the limit itself is a decision, not an incidental constant. */
  @Test
  public void theboundIsWhatItIsDocumentedToBe() {
    for (int i = 0; i < 150; i++) {
      me.retireDisplayName("Person" + i, "Surname" + i, "uuid-" + i);
    }
    assertEquals("the bound on remembered names is a product decision", 100,
        me.getRetiredDisplayNames().size());
  }
}
