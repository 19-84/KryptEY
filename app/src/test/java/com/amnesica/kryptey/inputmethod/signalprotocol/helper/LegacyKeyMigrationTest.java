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
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L), text, false));
  }

  /**
   * An entry no single contact can claim is deleted, not left in place.
   *
   * <p>This asserted the opposite one round ago, and the reasoning that changed it is worth keeping.
   * Deleting was chosen because an unattributed entry could still be matched by a bare-name reader
   * and handed to whichever row survived a deletion. No reader does that any more - {@code belongsTo}
   * compares the full rendered address and nothing produces a bare name to match - so the premise
   * expired when the read arms did. What was left was a destruction primitive: one ordinary invite
   * sent before the upgrade, bearing an existing contact's address name at another device id,
   * passes both add-path checks silently and has the genuine conversation erased at the next load,
   * with no prompt and no way back.
   *
   * <p>So it is kept — and made inert rather than assumed to be. "Left exactly as it was" is what
   * this used to assert, and a review round showed the difference matters: the bare key is inert
   * only when {@code chatLogKey} cannot produce it, and a 0.1.5 store was never held to the wire's
   * name check, so a peer-chosen address name can BE a rendered key. Two pre-upgrade invites under
   * such a name make {@code soleContactNamed} ambiguous, the entry lands in this arm, and kept
   * verbatim it is matched by the genuine contact it names. The separator prefix makes it
   * unproducible; the original key is still readable one character in.
   */
  @Test
  public void anambiguousLegacyEntryIsKeptAndMadeInert() {
    addContact("Bob", "peer-uuid", 7);
    addContact("Bob", "peer-uuid", 8);   // same address name, different device
    writeLegacyMessage("peer-uuid", "the whole conversation");
    assertEquals("precondition: the entry must be present before the migration", 1,
        account.getUnencryptedMessages().size());

    LegacyKeyMigration.apply(account);

    assertEquals("an entry no single contact can claim must be KEPT - nothing can read it, and "
        + "deleting it is a destruction primitive one pre-upgrade invite away", 1,
        account.getUnencryptedMessages().size());
    assertEquals("and neutralised rather than left matchable: the prefix is a character no "
            + "chatLogKey output can begin with, because a contact's address name is never empty",
        com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses.SEPARATOR
            + "peer-uuid",
        account.getUnencryptedMessages().get(0).getContactUUID());
    assertEquals("with the original key still readable, so a later version could attribute it",
        "peer-uuid", account.getUnencryptedMessages().get(0).getContactUUID().substring(1));
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

  /**
   * EVERY address on a retirement is blanked, not just the first.
   *
   * <p>Not reachable in production - the migration is gated on a schema flag and runs on the first
   * load after the upgrade, when no entry can hold a second address yet. Written because the loop
   * was indexed at element two while the record it walks now carries a set, and a migration written
   * to a shape the record no longer has is one that comes back wrong the next time the shape
   * changes. What it would have done if reached is migrate most of the very thing it refuses to
   * trust: a bare name identifies no address, and that is as true of the third element as the
   * first.
   */
  @Test
  public void everyAddressOnAretirementIsBlanked() {
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", "first-address", "second-address", "third-address"});
    account.setRetiredDisplayNames(retired);

    LegacyKeyMigration.apply(account);

    final String[] entry = account.getRetiredDisplayNames().get(0);
    assertEquals("the entry keeps its shape", 5, entry.length);
    for (int i = 2; i < entry.length; i++) {
      assertEquals("address element " + i + " must be blanked too", "", entry[i]);
    }
  }

  /**
   * A retirement is blanked even when a pin bearing its name survives.
   *
   * <p>This too asserted the opposite one round ago. Identifying the address from the name - by
   * contact row or by surviving pin - was reachable: the messenger chooses its own address name, so
   * it plants a row or gets a pin bearing the victim's address name at another device id, and once
   * the victim is deleted the attacker's is the only thing left bearing that name. The migration
   * would then write the ATTACKER's address into the victim's retirement and suppress the duplicate
   * warning for it permanently. Moving the question to load time froze the messenger's answer
   * rather than removing it.
   */
  @Test
  public void aretirementIsBlankedEvenWhenApinBearingItsNameSurvives() {
    account.getSignalProtocolStore().getIdentityKeyStore().saveIdentity(peerAddress,
        org.signal.libsignal.protocol.IdentityKeyPair.generate().getPublicKey());
    final LinkedList<String[]> retired = new LinkedList<>();
    retired.add(new String[] {"Bob", "Jones", peerAddress.getName()});
    account.setRetiredDisplayNames(retired);

    LegacyKeyMigration.apply(account);

    assertEquals("a bare name does not identify an address, and no moment exists at which it does "
            + "- so the address is dropped and the warning stays on", "",
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
  public void themigrationRunsOnceBecauseTheMarkerSaysSo() {
    final Context context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    addContact("Bob", "peer-uuid", 7);
    writeLegacyMessage("peer-uuid", "worth keeping");

    final StorageHelper helper = new StorageHelper(context, workingBox());
    helper.storeAllInformationInSharedPreferences(account);
    // Stand in for a store the pre-upgrade binary wrote: no marker. Every store this code writes
    // carries one, which is what makes the migration a one-shot.
    preferences.edit().remove(com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier
        .KEY_SCHEMA_MIGRATED.toString()).commit();

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

    // NOT by applying the transformation again. It is not content-idempotent and must not be:
    // deciding "already re-keyed?" from the shape of the key was a guess about a value the
    // messenger wrote, and a 0.1.5 store could hold an address name that renders exactly like a
    // migrated key. The marker is what makes this run once, because it is a fact about the store
    // written by this app rather than a value supplied to it.

    assertEquals("the second load must not migrate again, whatever the contact list has become", 1,
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
