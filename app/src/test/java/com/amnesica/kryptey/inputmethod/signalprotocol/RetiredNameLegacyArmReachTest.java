package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertFalse;
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
import java.util.LinkedList;

/**
 * The retired-name migration arm is reachable, and it re-opens the exact door the address-scoping
 * fix closed.
 *
 * <p>The arm is {@code excluding.getName().equals(retired[2])}, accepting an entry that carries a
 * bare address NAME rather than a rendered address. Its safety argument, written out beside it, is:
 * "the attacker's variant of this is a fresh address where nothing is pinned, so this arm is never
 * reached for it. Reaching it needs a surviving pin at the address being added, which is exactly the
 * 'provably the same identity' case."
 *
 * <p>The second sentence is the false one. A surviving pin at the address being added does not mean
 * the address is the one the name was retired from - it only means <em>something</em> was once
 * pinned there. And the attacker does not have to use a fresh address: it chooses its own address
 * name freely, so it picks the deleted contact's, and it gets a pin at its chosen device id the
 * ordinary way - one invite the user accepts under any name at all. Deletion then deliberately
 * KEEPS that pin, which is the property the rest of this file is built on.
 *
 * <p>So the sequence is the loop the app's own advice produces, run twice:
 *
 * <ol>
 *   <li>Bob Jones is deleted from {@code (bobsName, 5)}. On a store written before the record held a
 *       rendered address that leaves {@code {"Bob", "Jones", "bobsName"}} - the entry this arm
 *       exists to serve.
 *   <li>The messenger invites from {@code (bobsName, 6)} under any name it likes. The user accepts;
 *       a key is pinned there. It then replays a message until decryption fails and the user follows
 *       the advice and deletes that contact. The pin survives, by design.
 *   <li>The messenger invites from {@code (bobsName, 6)} again, this time as "Bob Jones".
 * </ol>
 *
 * <p>At step 3 the pin requirement is satisfied by the attacker's OWN key, and
 * {@code excluding.getName()} is {@code bobsName}, which is what the legacy entry holds. The
 * duplicate-name warning - which this codebase calls "the only control covering the case the pin
 * cannot" - is suppressed. Without the arm the same call warns.
 *
 * <p>The defect is not the comparison but the data: a legacy entry records no device id, so the arm
 * cannot tell "re-added at the address the pin survived at" from "added at a different device id
 * under the same name". Those are the legitimate case and the attack, and they are the same string.
 */
public class RetiredNameLegacyArmReachTest {

  private Account victim;
  private Account attacker;
  private String peerName;
  private SignalProtocolAddress retiredFrom;
  private SignalProtocolAddress attackerAddress;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    final Account realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    peerName = realPeer.getSignalProtocolAddress().getName();
    retiredFrom = ProtocolAddresses.of(peerName, realPeer.getDeviceId());
    // The attacker's own address name is its own to choose, so it chooses this one.
    attackerAddress = ProtocolAddresses.of(peerName,
        (realPeer.getDeviceId() % ProtocolAddresses.MAX_DEVICE_ID) + 1);

    SignalProtocolMain.getInstance().setAccount(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue("fixture: the genuine peer pins", SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), retiredFrom));

    final Contact bob = new Contact("Bob", "Jones", peerName, realPeer.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);
    SignalProtocolMain.removeContactFromContactListAndProtocol(bob);

    // The retirement as an older store holds it, and as StorageHelper reloads it: three elements,
    // the third a bare address NAME. This is precisely the shape the migration arm was added for.
    final LinkedList<String[]> legacyShape = new LinkedList<>();
    legacyShape.add(new String[] {"Bob", "Jones", peerName});
    victim.setRetiredDisplayNames(legacyShape);

    // What the first load after the upgrade does with that shape. Legacy records only ever enter
    // the app through a load, so this is where they are re-keyed - there is no longer a reader that
    // matches a bare name, because the question "does this name identify one contact?" has a sound
    // answer only here.
    com.amnesica.kryptey.inputmethod.signalprotocol.helper.LegacyKeyMigration.apply(victim);
  }

  /**
   * The arm is gone, and with it the suppression it provided.
   *
   * <p>This asserted the suppression when the arm existed. The sibling test below is why it does
   * not any more: the arm could not tell the legitimate re-add from the attack, because a legacy
   * entry records no device id and the two are literally the same string. Blanking the address at
   * migration costs this false alarm and closes that.
   */
  @Test
  public void there_addTheArmSuppressedNowWarnsInstead() {
    assertNotNull("fixture: deletion keeps the pin",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(retiredFrom));
    assertTrue("with no way to tell this from the attack the sibling test describes, the warning "
            + "is the safe side - a false alarm on one re-add per pre-upgrade retirement",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", retiredFrom));
  }

  /**
   * ...and being unable to tell that apart from the attack, it suppresses the attack too.
   *
   * <p>Note what is NOT required: no key collision, no name trickery, no U+001F, and nothing the
   * attacker has to guess. The address name is in every envelope the messenger relays and the
   * device id beside it is one byte the attacker writes.
   */
  @Test
  public void butitAlsoSuppressesAnAddAtAnAddressTheAttackerMerelyGotPinnedAt() throws Exception {
    assertNull("precondition: nothing has been pinned at the attacker's address yet",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(attackerAddress));
    assertTrue("precondition: with no pin there, the warning fires as the address-scoping fix "
            + "intended",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", attackerAddress));

    // Step 2: one invite the user accepts under any name at all, then the delete the app's own
    // decryption-failure advice produces. The pin deliberately outlives the contact.
    SignalProtocolMain.getInstance().setAccount(attacker);
    final MessageEnvelope forged = SignalProtocolMain.getPreKeyResponseMessage();
    forged.setSignalProtocolAddressName(attackerAddress.getName());
    forged.setDeviceId(attackerAddress.getDeviceId());
    final String wire = EnvelopeCodec.toWire(forged);
    SignalProtocolMain.getInstance().setAccount(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(wire), attackerAddress));
    assertNotNull("fixture: the attacker's own key is now pinned at its own address",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(attackerAddress));

    // Step 3: the same address comes back as "Bob Jones".
    assertTrue("the duplicate-name warning is suppressed at an address whose only pin is the "
            + "attacker's own, because a legacy retirement records no device id and the migration "
            + "arm compares what is left",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", attackerAddress));
  }
}
