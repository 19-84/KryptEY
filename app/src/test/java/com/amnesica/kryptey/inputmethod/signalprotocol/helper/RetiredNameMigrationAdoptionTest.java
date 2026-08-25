package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * "The contact list the pre-upgrade binary wrote" is not a contact list the messenger has been kept
 * away from - it is the one it has had the whole life of the install to shape.
 *
 * <p>A legacy retirement records a bare address NAME. The migration turns that into an address by
 * asking which single contact row - or failing that, which single pin - bears the name. Both
 * answers are things one ordinary invite puts there: the attacker chooses its own address name
 * freely (it is the peer-supplied name in the envelope it sends), so it picks the address name of
 * the contact whose display name it wants to reuse, at any device id it likes, under any display
 * name the user will accept. Nothing warns: the display names differ, and no contact exists at that
 * exact address.
 *
 * <p>Moving the question from read time to load time therefore does not remove the messenger from
 * it. It fixes its answer permanently: the attacker's own address is written into the retirement,
 * and from then on the duplicate-display-name warning is suppressed for the attacker's address -
 * which is the door the retirement record exists to keep shut ("the attacker's OWN fresh address
 * carrying this contact's name, where nothing is pinned and trust-on-first-use accepts silently").
 */
public class RetiredNameMigrationAdoptionTest {

  private static final String SHARED_NAME = "bob-uuid";

  private Account account;
  private SignalProtocolAddress genuineBob;
  private SignalProtocolAddress attacker;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    genuineBob = ProtocolAddresses.of(SHARED_NAME, 5);
    attacker = ProtocolAddresses.of(SHARED_NAME, 9);
  }

  private void pin(final SignalProtocolAddress address) {
    account.getSignalProtocolStore().getIdentityKeyStore()
        .saveIdentity(address, IdentityKeyPair.generate().getPublicKey());
  }

  private void retireLegacy() {
    final LinkedList<String[]> retired = new LinkedList<>();
    // What the pre-upgrade binary wrote when the user deleted Bob: the bare address name.
    retired.add(new String[] {"Bob", "Jones", SHARED_NAME});
    account.setRetiredDisplayNames(retired);
  }

  /**
   * A contact row the messenger planted before the upgrade is taken as the address a deleted
   * contact's name was retired from.
   *
   * <p>Bob was deleted, so his row is gone and his pin is not. The only row left bearing his
   * address name is the attacker's, added under an unrelated display name that warned about
   * nothing. The migration reads it as the answer.
   */
  @Test
  public void aplantedContactRowMustNotBecomeTheAddressAnameWasRetiredFrom() {
    pin(genuineBob);     // deletion deliberately keeps it
    pin(attacker);       // one accepted invite, under any display name
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(new Contact("Carol", "Smith", SHARED_NAME, 9, false));
    account.setContactList(contacts);
    retireLegacy();

    assertTrue("precondition: before the migration the retirement warns about this address, "
            + "because a bare name matches no rendered key",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", attacker));

    LegacyKeyMigration.apply(account);

    assertNotEquals("the attacker's own address must not be recorded as the address Bob's name was "
            + "retired from", ProtocolAddresses.key(attacker),
        account.getRetiredDisplayNames().get(0)[2]);
    assertTrue("the migration switched the duplicate-name warning off for the attacker's address: "
            + "a name it never held is now recorded as retired FROM it, so re-adding \"Bob Jones\" "
            + "there is silent", SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", attacker));
  }

  /**
   * The pin arm, reached exactly as its javadoc describes - no row bears the name - with the
   * genuine pin gone the way the app's own advice makes it go: compare, mismatch, reject.
   */
  @Test
  public void aplantedPinMustNotBecomeTheAddressAnameWasRetiredFrom() {
    final Contact bob = new Contact("Bob", "Jones", SHARED_NAME, 5, false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    account.setContactList(contacts);
    pin(genuineBob);
    pin(attacker);

    // The user compared safety numbers, they did not match, and they rejected the key. That is the
    // one path that removes a pin, and it is the path the app steers a suspicious user down.
    SignalProtocolMain.rejectContactKey(bob);
    account.setContactList(new ArrayList<>());   // and then deleted the contact
    retireLegacy();

    assertTrue("precondition: the retirement warns before the migration",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", attacker));

    LegacyKeyMigration.apply(account);

    assertNotEquals("a pin the attacker obtained with one invite must not be read as the address "
            + "Bob's name was retired from", ProtocolAddresses.key(attacker),
        account.getRetiredDisplayNames().get(0)[2]);
    assertTrue("the warning is now suppressed for the attacker's address",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones", attacker));
  }
}
