package com.amnesica.kryptey.inputmethod.signalprotocol.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.ProtocolIdentifier;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.JsonUtil;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The migration decides "already migrated?" by INSPECTING the stored value, and the stored value is
 * the messenger's.
 *
 * <p>{@code LegacyKeyMigration} skips any chat-log key containing U+001F, on the argument that a
 * bare address name can never contain one - {@code requireDisplaySafeName} refuses it on the wire.
 * That argument covers the wire and not the disk. 0.1.5 validated nothing, which
 * {@code StoredContactNameValidationTest} already establishes for the contact list; the chat log is
 * written from the same peer-supplied name, so a legacy entry keyed
 * {@code bobName + U+001F + bobDeviceId} is something an upgrading install can be holding, planted
 * with one ordinary invite before the upgrade.
 *
 * <p>That string is byte-for-byte {@code StorageMessage.chatLogKey(bobName, bobDeviceId)}. The
 * migration reads it as an address it has already rendered and leaves it alone, so after the
 * upgrade every word the attacker sent is filed under the genuine contact's key - rendered inside
 * their conversation, under their name, their tag and their badge, which is the substitution
 * {@code StorageMessage}'s own javadoc says the re-keying removed.
 */
public class LegacySeparatorSmugglingTest {

  private static final String ATTACKER_WORDS = "meet me at the usual place, alone";
  private static final String PLACEHOLDER = "PLACEHOLDERNAME";

  private Account account;
  private Contact genuineBob;
  private String craftedName;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;
    SignalProtocolMain.initialize(null);
    account = SignalProtocolMain.getInstance().getAccount();

    genuineBob = new Contact("Bob", "Jones", "bob-uuid", 5, false);
    // Both halves travel in every envelope the messenger relays.
    craftedName = "bob-uuid" + ProtocolAddresses.SEPARATOR + "5";
  }

  /**
   * A contact row as a 0.1.5 install could legitimately be holding it: the exact JSON
   * {@code JsonUtil.toJson} writes, carrying an address name no current envelope could have carried.
   * Nothing here constructs the crafted name through a path the current code controls.
   */
  private Contact attackerRowFromStoredJson() throws Exception {
    return attackerRowFromStoredJson(9);
  }

  private Contact attackerRowFromStoredJson(final int deviceId) throws Exception {
    final ArrayList<Contact> shape = new ArrayList<>();
    shape.add(new Contact("Carol", "Smith", PLACEHOLDER, deviceId, false));
    final String stored = JsonUtil.toJson(shape).replace(PLACEHOLDER, "bob-uuid\\u001F5");
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

  /** The premise, as an assertion: the crafted legacy key IS the genuine contact's key. */
  @Test
  public void thecraftedLegacyNameIsByteForByteTheGenuineContactsChatLogKey() {
    assertEquals(StorageMessage.chatLogKey("bob-uuid", 5), craftedName);
  }

  /**
   * The migration must not take the messenger's word for it that an entry is already re-keyed.
   *
   * <p>The one-time pass is the only moment the app can distinguish "written under the old schema"
   * from "written under the new one", and it knows which it is from the marker it just consulted -
   * not from the shape of a value a peer chose.
   */
  @Test
  public void alegacyEntryWhoseBareNameHoldsTheSeparatorIsNotAdoptedIntoTheGenuineLog()
      throws Exception {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    contacts.add(attackerRowFromStoredJson());
    // A row with an ordinary name, so nothing below can pass merely because every row sees
    // every message.
    final Contact bystander = new Contact("Dave", "Brown", "dave-uuid", 11, false);
    contacts.add(bystander);
    account.setContactList(contacts);

    // Exactly what 0.1.5 wrote for a message received from that peer: the chat log keyed by the
    // peer's address name.
    account.getUnencryptedMessages().add(new StorageMessage(craftedName, craftedName,
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        ATTACKER_WORDS, false));

    LegacyKeyMigration.apply(account);

    assertFalse("control: an ordinary row must see nothing",
        logContains(logOf(bystander), ATTACKER_WORDS));
    assertFalse("the attacker's pre-upgrade words are being rendered inside the genuine contact's "
            + "conversation, because the migration read a peer-chosen stored name as an address it "
            + "had already rendered: " + logOf(genuineBob),
        logContains(logOf(genuineBob), ATTACKER_WORDS));
  }


  /**
   * And the arm that declines to attribute must leave the entry genuinely inert.
   *
   * <p>The single-row case above is re-keyed onto the attacker. This is its intersection with the
   * other half of the design: {@code soleContactNamed} returns null when **two** rows bear the
   * name, not only when none does — so two pre-upgrade invites under the crafted name, at different
   * device ids, land the entry in the keep-don't-delete arm, where it used to be left verbatim.
   *
   * <p>Verbatim is not inert here. The crafted key IS Bob's rendered key, so
   * {@code belongsTo("bob-uuid", 5)} matches it and the attacker's pre-upgrade plaintext is
   * rendered inside Bob's conversation, under Bob's name, tag and badge. That is the substitution
   * the re-keying removes, reached through the arm that declines to re-key — and neither existing
   * test covers the intersection: one puts a single crafted row in the list, the other uses a
   * shared name containing no separator.
   *
   * <p>Two pre-upgrade invites is the standard pretext this codebase already names: the second
   * arrives as "my phone died, here is my new invite". 0.1.5 warned about neither.
   */
  @Test
  public void anambiguousLegacyEntryIsNeutralisedRatherThanLeftMatchable() throws Exception {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    // TWO rows bearing the crafted name, which is what makes soleContactNamed answer null.
    contacts.add(attackerRowFromStoredJson(9));
    contacts.add(attackerRowFromStoredJson(10));
    final Contact bystander = new Contact("Dave", "Brown", "dave-uuid", 11, false);
    contacts.add(bystander);
    account.setContactList(contacts);

    account.getUnencryptedMessages().add(new StorageMessage(craftedName, craftedName,
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        ATTACKER_WORDS, false));

    LegacyKeyMigration.apply(account);

    assertFalse("control: an ordinary row must see nothing",
        logContains(logOf(bystander), ATTACKER_WORDS));
    assertFalse("the entry the migration declined to attribute is still matched by the genuine "
            + "contact, because the crafted key IS their rendered key. Kept is not the same as "
            + "inert: " + logOf(genuineBob),
        logContains(logOf(genuineBob), ATTACKER_WORDS));
    assertFalse("and not by either attacker row either",
        logContains(logOf(contacts.get(1)), ATTACKER_WORDS)
            || logContains(logOf(contacts.get(2)), ATTACKER_WORDS));

    // Kept, not deleted - the whole reason this arm exists. Deleting would be a destruction
    // primitive: one ordinary pre-upgrade invite is enough to have a genuine conversation classed
    // ambiguous and erased.
    assertEquals("the entry must still be in the log, just unattributable", 1,
        account.getUnencryptedMessages().size());
    assertEquals("and its original key must still be readable, one character in, so a later "
            + "version could attribute it", craftedName,
        account.getUnencryptedMessages().get(0).getContactUUID().substring(1));
  }

  /**
   * And the same entry keyed by an ordinary bare name IS re-keyed - so the assertion above is
   * measuring the separator test, not the migration failing to run at all.
   */
  @Test
  public void anordinaryLegacyEntryIsStillReKeyedInTheSameFixture() {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    account.setContactList(contacts);
    account.getUnencryptedMessages().add(new StorageMessage("bob-uuid", "bob-uuid",
        account.getSignalProtocolAddress().getName(), Instant.ofEpochSecond(1_700_000_000L),
        "ordinary", false));

    LegacyKeyMigration.apply(account);

    assertTrue("an ordinary bare name must be re-keyed onto the full address",
        logContains(logOf(genuineBob), "ordinary"));
    assertEquals(StorageMessage.chatLogKey("bob-uuid", 5),
        account.getUnencryptedMessages().get(0).getContactUUID());
  }

  /**
   * A second migration pass must not re-attribute an entry the first pass already placed.
   *
   * <p>The migration's "runs once" is enforced by the schema marker, and the marker travels in the
   * account batch while the log is committed first — so "log re-keyed, marker missing" is a state
   * the write order deliberately produces, and this file's own fixture is what makes it dangerous.
   * On the second pass the key being examined is a RENDERED one, and the crafted attacker row's
   * address name is byte-for-byte a rendered key: {@code soleContactNamed} finds it, and the
   * genuine contact's history moves into the impostor's conversation.
   *
   * <p>Measured before the fix: pass one placed the entry with Bob, pass two moved it to the
   * attacker's row, and {@code belongsTo(Bob)} went false. The fix records the answer on the entry
   * rather than inferring it — not from the key's shape, which is the hole this file exists for.
   */
  @Test
  public void asecondPassDoesNotMoveAnentryTheFirstPassPlaced() throws Exception {
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(genuineBob);
    contacts.add(attackerRowFromStoredJson());
    account.setContactList(contacts);

    // Keyed by the bare name and never asked, which is how 0.1.5 wrote it.
    account.getUnencryptedMessages().add(new StorageMessage("bob-uuid", "bob-uuid", "me",
        java.time.Instant.ofEpochMilli(1000), ATTACKER_WORDS, false));

    LegacyKeyMigration.apply(account);
    assertTrue("precondition: the first pass must place it with the genuine contact",
        logContains(logOf(genuineBob), ATTACKER_WORDS));

    LegacyKeyMigration.apply(account);

    assertTrue("a second pass must leave it where the first pass put it. The marker cannot make "
            + "that true: it is written in the account batch, and the log is committed first",
        logContains(logOf(genuineBob), ATTACKER_WORDS));
    assertTrue("and it must not have moved into the row whose address name is a rendered key",
        !logContains(logOf(attackerRowFromStoredJson()), ATTACKER_WORDS));
  }
}
