package com.amnesica.kryptey.inputmethod.signalprotocol.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.Account;
import com.amnesica.kryptey.inputmethod.signalprotocol.MessageEnvelope;
import com.amnesica.kryptey.inputmethod.signalprotocol.SignalProtocolMain;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * What restoring an older copy of the store actually costs, measured rather than described.
 *
 * <p>REVIVAL.md carries this as a known-deferred defect in prose: "restoring an old
 * {@code protocol.xml} presents envelopes that verify perfectly, rewinding the ratchet and resetting
 * {@code trustedKeys}. Needs a monotonic counter the attacker cannot rewind; not solvable at the
 * storage layer." That is a decision to live with something, and living with something described
 * rather than measured is how the size of it drifts — which is exactly what happened to the bundle
 * replay entry beside it, whose stated consequence turned out to be wrong in two ways once anyone
 * ran it.
 *
 * <p>So this pins the behaviour as it stands. It does not assert a rollback is refused; it asserts
 * what a rollback takes and what it leaves, so that a change in either direction fails a test. Note
 * what an attacker needs to do it at all: write access to the app's private storage, which is a
 * strictly larger capability than the messenger has in this threat model. That is why it is
 * deferred rather than urgent, and the tests below are the reason that judgement can be checked
 * instead of trusted.
 */
@RunWith(RobolectricTestRunner.class)
public class StoreRollbackTest {

  private Context context;
  private SharedPreferences preferences;
  private Account victim;
  private SignalProtocolAddress peerAddress;
  private Contact bob;

  private StorageHelper.CryptoBoxFactory box() {
    return (ctx, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected javax.crypto.SecretKey key() {
        return KEY;
      }
    };
  }

  private static final javax.crypto.SecretKey KEY =
      new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();

    SignalProtocolMain.resetForTest();
    SignalProtocolMain.testIsRunning = true;

    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    victim = SignalProtocolMain.getInstance().getAccount();
    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    bob = new Contact("Bob", "Jones", peerAddress.getName(), peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    victim.setContactList(contacts);
  }

  /**
   * The chat log's own file.
   *
   * <p>The store is two files now: the account in {@code protocol}, the message log in
   * {@code protocol_messages}, so that raising the keyboard does not rewrite megabytes of history.
   * An attacker copying "the store" copies both, so this test does too - restoring only one of them
   * is a different attack, pinned separately below.
   */
  private SharedPreferences messagePreferences() {
    return context.getSharedPreferences("protocol_messages", Context.MODE_PRIVATE);
  }

  /** Everything the store holds, as an attacker with file access would copy it. */
  private Map<String, String> snapshot() {
    final Map<String, String> copy = new HashMap<>();
    for (final Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      copy.put("protocol/" + entry.getKey(), String.valueOf(entry.getValue()));
    }
    for (final Map.Entry<String, ?> entry : messagePreferences().getAll().entrySet()) {
      copy.put("messages/" + entry.getKey(), String.valueOf(entry.getValue()));
    }
    return copy;
  }

  private void restore(final Map<String, String> snapshot) {
    final SharedPreferences.Editor account = preferences.edit();
    final SharedPreferences.Editor messages = messagePreferences().edit();
    account.clear();
    messages.clear();
    for (final Map.Entry<String, String> entry : snapshot.entrySet()) {
      if (entry.getKey().startsWith("protocol/")) {
        account.putString(entry.getKey().substring("protocol/".length()), entry.getValue());
      } else {
        messages.putString(entry.getKey().substring("messages/".length()), entry.getValue());
      }
    }
    account.commit();
    messages.commit();
  }

  private Account reload() {
    final Account loaded =
        new StorageHelper(context, box()).getAccountFromSharedPreferences();
    assertNotNull("precondition: the store must reload", loaded);
    SignalProtocolMain.getInstance().setAccount(loaded);
    return loaded;
  }

  /**
   * A rollback un-verifies a contact the user had compared.
   *
   * <p>This is the half that matters. The verified badge is the one indicator the whole trust model
   * surfaces, and an attacker who can rewind the store takes it away — the user sees an unverified
   * contact and, following the app's own advice, compares the number again. It does NOT hand the
   * attacker a green badge it never earned, which is the direction that would be far worse.
   */
  @Test
  public void arollbackTakesTheVerifiedBadgeAwayRatherThanGrantingOne() throws Exception {
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);
    final Map<String, String> before = snapshot();

    SignalProtocolMain.verifyContact(bob);
    assertTrue("precondition: the contact must be verified after comparing",
        SignalProtocolMain.isContactKeyTrustworthy(victim.getContactList().get(0)));
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);

    restore(before);
    final Account rolledBack = reload();

    assertFalse("a rollback removes a verification the user performed - the badge goes back to "
            + "unverified, which is the safe direction",
        SignalProtocolMain.isContactKeyTrustworthy(rolledBack.getContactList().get(0)));
  }

  /**
   * And the pin itself survives, so the rollback does not open a trust-on-first-use window.
   *
   * <p>This is the assertion worth watching. If a rollback ever cleared the pinned identity, the
   * next bundle at that address would be a clean first sighting and the attacker would have turned
   * file access into a silent key substitution. The pin is written in the same snapshot, so it comes
   * back with everything else.
   */
  @Test
  public void arollbackDoesNotClearThePinnedIdentity() throws Exception {
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);
    final Map<String, String> before = snapshot();
    final var pinned =
        victim.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);
    assertNotNull("precondition: a key must be pinned", pinned);

    SignalProtocolMain.verifyContact(bob);
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);

    restore(before);
    final Account rolledBack = reload();

    assertEquals("the pinned key must come back with the rolled-back store, or file access becomes "
            + "a silent substitution: the next bundle would be a clean first sighting", pinned,
        rolledBack.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }

  /**
   * Rolling back only the account file leaves the log alone, and the orphans it makes are inert.
   *
   * <p>A new option for the attacker, created by splitting the store into two files, so it is
   * pinned rather than assumed away. Restoring an old {@code protocol} while leaving
   * {@code protocol_messages} current keeps every message the user has received since the snapshot
   * — the history is <em>not</em> rewound — while the contact list goes back. Entries belonging to
   * a contact the rolled-back list no longer holds are then unreachable: {@code belongsTo} compares
   * the full rendered address, so they match nobody, including the attacker. Inert, not
   * misattributed, which is the same disposition the legacy migration settled on for the same
   * reason.
   */
  @Test
  public void arollbackOfTheAccountFileAloneLeavesTheChatLogWhereItIs() throws Exception {
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);
    final Map<String, String> before = snapshot();

    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("after the snapshot",
        peerAddress);
    assertNotNull(sent);
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);
    final int afterSending = victim.getUnencryptedMessages().size();
    assertTrue("precondition: the send must be logged", afterSending > 0);

    // Only the account's own file goes back.
    final SharedPreferences.Editor account = preferences.edit();
    account.clear();
    for (final Map.Entry<String, String> entry : before.entrySet()) {
      if (entry.getKey().startsWith("protocol/")) {
        account.putString(entry.getKey().substring("protocol/".length()), entry.getValue());
      }
    }
    account.commit();

    final Account rolledBack = reload();
    assertEquals("the log lives in its own file, so rolling back the account file does not rewind "
            + "it - the messages are still there", afterSending,
        rolledBack.getUnencryptedMessages().size());
  }

  /**
   * A rollback rewinds the chat log, which is the visible cost to the user.
   *
   * <p>Recorded because it is what someone would actually notice, and because it bounds the damage:
   * messages written after the snapshot are gone, not silently altered.
   */
  @Test
  public void arollbackRewindsTheChatLog() throws Exception {
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);
    final Map<String, String> before = snapshot();

    final MessageEnvelope sent = SignalProtocolMain.encryptMessage("after the snapshot",
        peerAddress);
    assertNotNull(sent);
    assertTrue("precondition: the send must be logged",
        victim.getUnencryptedMessages().size() > 0);
    new StorageHelper(context, box()).storeAllInformationInSharedPreferences(victim);

    restore(before);
    final Account rolledBack = reload();

    assertEquals("messages written after the snapshot are gone - the cost is losing history, not "
        + "history being altered", 0, rolledBack.getUnencryptedMessages().size());
  }
}
