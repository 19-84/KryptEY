package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * The three decisions the one-time key migration makes, each of which had no test.
 *
 * <p>Measured by mutation after the migration was written: keeping ambiguous chat-log entries
 * instead of dropping them, and keeping an unidentifiable retirement's bare name instead of
 * blanking it, both survived the whole suite. Those are the two choices that decide whether the
 * design is safer than the read-time matching it replaced, so they are exactly the ones that must
 * not be silently reversible.
 */
@RunWith(RobolectricTestRunner.class)
public class LegacyKeyMigrationTest {

  private Account account;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of("peer-uuid", 7);
  }

  private void addContact(final String first, final String name, final int deviceId) {
    final ArrayList<Contact> contacts = new ArrayList<>(account.getContactList());
    contacts.add(new Contact(first, "Jones", name, deviceId, false));
    account.setContactList(contacts);
  }

  private void writeLegacyMessage(final String addressName, final String text) {
    account.getUnencryptedMessages().add(new StorageMessage(addressName, addressName,
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L), text));
  }

  /**
   * An entry no single contact can claim is deleted, not left in place.
   *
   * <p>Leaving it is what the read-time gate did, and it is how the whole conversation ended up
   * inheritable: an unattributed message has no owner, so whichever row survived the next deletion
   * took it. It also cannot be erased by the user, because erasing a conversation means deleting
   * the contact and no contact owns it. Unrecoverable history is the honest cost of the upgrade;
   * plaintext that can later be handed to an impostor is not a cost, it is the defect.
   */
  @Test
  public void anambiguousLegacyEntryIsDroppedRatherThanOrphaned() {
    addContact("Bob", "peer-uuid", 7);
    addContact("Bob", "peer-uuid", 8);   // same address name, different device
    writeLegacyMessage("peer-uuid", "the whole conversation");
    assertEquals("precondition: the entry must be present before the migration", 1,
        account.getUnencryptedMessages().size());

    LegacyKeyMigration.apply(account);

    assertEquals("an entry no single contact can claim must be deleted, not left for whichever "
        + "row survives the next deletion", 0, account.getUnencryptedMessages().size());
  }

  /** And an entry exactly one contact can claim is re-keyed rather than dropped. */
  @Test
  public void anunambiguousLegacyEntryIsReKeyedRatherThanDropped() {
    addContact("Bob", "peer-uuid", 7);
    writeLegacyMessage("peer-uuid", "worth keeping");

    LegacyKeyMigration.apply(account);

    assertEquals("precondition: the entry must survive", 1,
        account.getUnencryptedMessages().size());
    assertEquals("and must now be filed under the full address",
        StorageMessage.chatLogKey("peer-uuid", 7),
        account.getUnencryptedMessages().get(0).getContactUUID());
  }

  /**
   * A retirement whose address cannot be identified is blanked, so the warning fires.
   *
   * <p>An entry that cannot say which address a name was retired from must not be able to suppress
   * a warning about that name. Keeping the bare name there would leave the reader comparing a name
   * again, which is the whole thing being removed - and it would suppress on the attacker's address
   * as readily as the genuine one.
   */
  @Test
  public void aretirementWhoseAddressCannotBeIdentifiedIsBlanked() {
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", "an-address-nothing-knows-about"});
    account.setRetiredDisplayNames(retired);

    LegacyKeyMigration.apply(account);

    assertEquals("the entry must remain, so the name is still remembered", 1,
        account.getRetiredDisplayNames().size());
    assertEquals("but its address must be blanked, which leaves the warning ON - a false alarm on "
        + "a legitimate re-add is the safe side of this, silence on an impersonation is not",
        "", account.getRetiredDisplayNames().get(0)[2]);
  }

  /** A retirement whose address survives as a pin is re-keyed, so a legitimate re-add is quiet. */
  @Test
  public void aretirementIdentifiedByAsurvivingPinIsReKeyed() {
    account.getSignalProtocolStore().getIdentityKeyStore().saveIdentity(peerAddress,
        org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey());
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", peerAddress.getName()});
    account.setRetiredDisplayNames(retired);

    LegacyKeyMigration.apply(account);

    assertEquals("deletion keeps the pin, so the identity store can still name the address the "
            + "contact row no longer does", ProtocolAddresses.key(peerAddress),
        account.getRetiredDisplayNames().get(0)[2]);
  }

  /**
   * Running the migration again changes nothing, whatever the contact list has become.
   *
   * <p>This started out asserting that the marker prevents a second run, and that test passed with
   * the marker check removed - so it was measuring nothing. The reason is worth keeping: a re-keyed
   * entry contains the separator, and the migration skips those, so a second pass has nothing to
   * act on however the messenger has edited the contact list since. <b>Idempotence is the property
   * that makes the load-time answer safe</b>, not the marker; the marker only avoids rescanning the
   * whole log on every {@code setInputView}, which happens a great deal.
   *
   * <p>So this asserts idempotence directly, against the state the original test was worried about:
   * a rival row added after the migration ran.
   */
  @Test
  public void themigrationIsIdempotentEvenAfterTheContactListChanges() {
    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    addContact("Bob", "peer-uuid", 7);
    writeLegacyMessage("peer-uuid", "worth keeping");

    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(account);

    final Account firstLoad =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull(firstLoad);
    assertEquals("precondition: the first load must migrate", 1,
        firstLoad.getUnencryptedMessages().size());
    final String afterFirst = firstLoad.getUnencryptedMessages().get(0).getContactUUID();
    assertNotEquals("precondition: it must have been re-keyed", "peer-uuid", afterFirst);

    // The messenger now adds a rival row and the account is written back.
    final ArrayList<Contact> withRival = new ArrayList<>(firstLoad.getContactList());
    withRival.add(new Contact("Bob", "Jones", "peer-uuid", 8, false));
    firstLoad.setContactList(withRival);
    new StorageHelper(context, workingBox()).storeAllInformationInSharedPreferences(firstLoad);

    final Account secondLoad =
        new StorageHelper(context, workingBox()).getAccountFromSharedPreferences();
    assertNotNull(secondLoad);

    // Directly, not via the marker: this must hold even if the migration runs again.
    LegacyKeyMigration.apply(secondLoad);
    LegacyKeyMigration.apply(secondLoad);

    assertEquals("a re-keyed entry must survive any number of further passes, whatever the contact "
            + "list has become - that is what makes doing this at load time safe", 1,
        secondLoad.getUnencryptedMessages().size());
    assertEquals("and must keep the owner it was given when the answer was still sound",
        afterFirst, secondLoad.getUnencryptedMessages().get(0).getContactUUID());
  }

  /** A working box with a fixed key, as StorageHelperTest uses, so this test is about migration. */
  private StorageHelper.CryptoBoxFactory workingBox() {
    return (ctx, hasExistingData)
        -> new com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox() {
          @Override
          protected javax.crypto.SecretKey key() {
            return KEY;
          }
        };
  }

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
}
