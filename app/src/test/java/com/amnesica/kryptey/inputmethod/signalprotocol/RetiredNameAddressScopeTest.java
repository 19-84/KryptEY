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

/**
 * The retired-name warning is suppressed on a claim about a surviving pin that it never checks.
 *
 * <p>{@code hasRetiredDisplayName(first, last, excluding)} skips an entry when
 * {@code excluding.getName().equals(retired[2])}. The justification, written out in both
 * {@code Account.retireDisplayName} and {@code RetiredDisplayNameTest}, is that "deletion keeps the
 * pin, so a re-add at the SAME address is provably the same identity - a substituted bundle for
 * that address is still refused".
 *
 * <p>An address is a name AND a device id. Everything that actually enforces the claim - the pin,
 * the pending change, the rejection, the display tag - is keyed by both. This suppression is keyed
 * by the name alone, and never asks whether a pin survives at the address being re-added.
 *
 * <p>So an attacker that reuses the deleted contact's address NAME with any other device id lands
 * on an address where nothing has ever been pinned, and the warning that exists precisely to cover
 * "the attacker's own address carrying a deleted contact's name" is suppressed by the guard meant
 * to remove its false alarms. The device id is one byte the attacker writes into the envelope, and
 * the address name is public - it is in every envelope the messenger relays.
 */
public class RetiredNameAddressScopeTest {

  private Account victim;
  private Account realPeer;
  private Account attacker;

  private SignalProtocolAddress peerAddress;
  /** Same name, a device id the attacker picks: an address with no history at all. */
  private SignalProtocolAddress sameNameOtherDevice;

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    realPeer = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.initialize(null);
    attacker = SignalProtocolMain.getInstance().getAccount();

    final String peerName = realPeer.getSignalProtocolAddress().getName();
    peerAddress = ProtocolAddresses.of(peerName, realPeer.getDeviceId());
    sameNameOtherDevice = ProtocolAddresses.of(peerName, (realPeer.getDeviceId() % 127) + 1);

    activate(realPeer);
    final String genuine = SignalProtocolMain.exportOwnKeyBundle();
    activate(victim);
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(genuine), peerAddress));

    final Contact bob = new Contact("Bob", "Jones", peerName, realPeer.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    // The premise of the whole retired-name mechanism: the user deletes the contact. The codebase
    // treats this as attacker-drivable - replay any message until decryption fails often enough
    // that starting over looks sensible.
    // The deletion must land, or Bob stays a live contact and hasContactWithSameDisplayName
    // answers from the live row - so the retired-name arm this class is named for is never
    // consulted, and a mutant removing it goes unnoticed here.
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();
    assertTrue("fixture: the deletion must reach disk, or the retirement is not what answers",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));
  }

  private void activate(final Account account) {
    SignalProtocolMain.getInstance().setAccount(account);
  }

  /** The suppression's own premise, stated as an assertion. */
  @Test
  public void theSuppressionsPremiseHoldsAtTheAddressItWasRetiredFrom() {
    assertNotNull("deletion must keep the pin - that is what makes a re-add here provably the "
            + "same identity",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertFalse("re-adding at the address the pin survived at must not warn",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));
  }

  /**
   * ...and does not hold one device id away, where nothing was ever pinned.
   */
  @Test
  public void adeletedNameOfferedAtTheSameNameButAnotherDeviceMustStillWarn() {
    assertNull("precondition: nothing has ever been pinned at this address",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(sameNameOtherDevice));

    assertTrue("the retired-name warning is suppressed at an address that carries no pin at all, "
            + "because the suppression compares address NAMES while every trust record is keyed by "
            + "name and device id",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", sameNameOtherDevice));
  }

  /**
   * The same defect at the EXACT same address, reached without touching the device id.
   *
   * <p>{@code rejectContactKey} removes the pin. Deleting the contact afterwards retires the name.
   * A re-add at that address is then suppressed on the grounds that "deletion keeps the pin" — and
   * the pin is gone, removed by the user's own mismatch report. The suppression never asks.
   *
   * <p>This is the sequence the app's own advice produces: compare, find a mismatch, reject, delete,
   * ask for a fresh invite. Whatever bundle arrives next is a silent trust-on-first-use at an
   * address the user has already reported an impersonation attempt at.
   */
  @Test
  public void anameRetiredFromAnaddressWhosePinWasRejectedMustStillWarn() throws Exception {
    // Re-create the contact so it can be rejected, then delete it again.
    final Contact bob = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);

    assertTrue("fixture: there must be a pin to reject", SignalProtocolMain.rejectContactKey(bob));
    // The deletion must land, or Bob stays a live contact and hasContactWithSameDisplayName
    // answers from the live row - so the retired-name arm this class is named for is never
    // consulted, and a mutant removing it goes unnoticed here.
    com.amnesica.kryptey.inputmethod.signalprotocol.storage.TestStores.writesLand();
    assertTrue("fixture: the deletion must reach disk, or the retirement is not what answers",
        SignalProtocolMain.removeContactFromContactListAndProtocol(bob));

    assertNull("precondition: the rejection removed the pin the suppression is justified by",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    assertTrue("the duplicate-name warning is suppressed at an address whose pin the user threw "
            + "away: the suppression asserts a surviving pin without checking for one",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));
  }

  /**
   * And the attack it enables end to end: the attacker's bundle is pinned at that address with no
   * warning ever having fired.
   */
  @Test
  public void theattackersBundleIsPinnedAtthatAddressUnwarned() throws Exception {
    activate(attacker);
    final MessageEnvelope forged = SignalProtocolMain.getPreKeyResponseMessage();
    forged.setSignalProtocolAddressName(sameNameOtherDevice.getName());
    forged.setDeviceId(sameNameOtherDevice.getDeviceId());
    final String wire = EnvelopeCodec.toWire(forged);
    activate(victim);

    final boolean warned = SignalProtocolMain.hasContactWithSameDisplayName(
        "Bob", "Jones", sameNameOtherDevice)
        || SignalProtocolMain.wasKeyRejected(sameNameOtherDevice)
        || SignalProtocolMain.hasUnacceptedIdentityChange(sameNameOtherDevice);

    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(wire), sameNameOtherDevice));
    assertNotNull("fixture: the attacker's key is now pinned",
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(sameNameOtherDevice));

    assertTrue("an attacker's bundle carrying a deleted contact's name was accepted with no "
            + "warning of any kind: the one control covering this door was suppressed",
        warned);
  }
}
