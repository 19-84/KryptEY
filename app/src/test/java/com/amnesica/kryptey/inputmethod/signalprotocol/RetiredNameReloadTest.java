package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.amnesica.kryptey.inputmethod.signalprotocol.chat.Contact;
import com.amnesica.kryptey.inputmethod.signalprotocol.encoding.EnvelopeCodec;
import com.amnesica.kryptey.inputmethod.signalprotocol.helper.StorageHelper;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.CryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.storage.GcmCryptoBox;
import com.amnesica.kryptey.inputmethod.signalprotocol.util.ProtocolAddresses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.signal.libsignal.protocol.SignalProtocolAddress;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A retired display name is stored with the address it was retired from. Reload drops the address.
 *
 * <p>{@code Account.retireDisplayName} writes {@code {first, last, addressName}} and
 * {@code hasRetiredDisplayName} uses the third element to suppress the warning for a re-add at the
 * SAME address — deletion keeps the pin, so a re-add there is provably the same identity and
 * warning is a false alarm. That suppression is a deliberate fix, argued at length: the app's own
 * decryption-failure advice sends users round the delete-and-re-invite loop, so this is the
 * commonest firing of the control, and a warning that is provably wrong where it fires most is
 * exactly the habituation failure the whole design argues against.
 *
 * <p>{@code StorageHelper.getAccountFromSharedPreferences} reconstructs each entry as a
 * <em>two</em>-element array. The address is dropped on the way back in, so after any reload the
 * {@code retired.length > 2} test in {@code hasRetiredDisplayName} can never pass and the
 * suppression is dead. {@code reloadAccount} runs on every {@code setInputView}, so in production
 * the list is essentially always in the reloaded shape and the fix never applies at all.
 *
 * <p>The existing coverage cannot see this: {@code RetiredDisplayNameTest} asserts the suppression
 * in memory, and its serialisation test only checks that {@code JsonUtil.toJson} produces text
 * containing "Bob" — it never reads the value back through the storage helper.
 */
@RunWith(RobolectricTestRunner.class)
public class RetiredNameReloadTest {

  private Context context;
  private SecretKey key;
  private SignalProtocolAddress peerAddress;
  private String peerBundle;

  private static SecretKey newKey() {
    try {
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Before
  public void setUp() throws Exception {
    SignalProtocolMain.resetForTest();

    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();

    final CryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> box));

    SignalProtocolMain.testIsRunning = true;

    // The peer's bundle has to be minted before the victim's account is on disk, or
    // initialize(null) reloads the victim instead of creating a fresh identity.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(context);
  }

  @After
  public void tearDown() {
    SignalProtocolMain.resetForTest();
  }

  /** Pins the peer, adds them as a contact, then deletes the contact. */
  private void pinAndThenDeleteBob() throws Exception {
    final Account me = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("precondition: the account must load with a real storage helper", me);

    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact bob = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(bob);
    me.setContactList(contacts);

    SignalProtocolMain.removeContactFromContactListAndProtocol(bob);
  }

  /** Reload from disk the way {@code setInputView} does. */
  private Account reloaded() {
    SignalProtocolMain.reloadAccount(context);
    return SignalProtocolMain.getInstance().getAccount();
  }

  /**
   * Re-adding the deleted contact at the SAME address must still not warn after a reload.
   *
   * <p>The pin survived deletion and survived the reload, so the identity is still proven; the
   * warning would be telling the user something the app can see is untrue.
   */
  @Test
  public void areAddAtTheSameAddressStillDoesNotWarnAfterAreload() throws Exception {
    pinAndThenDeleteBob();

    assertFalse("precondition: in memory the suppression works",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));

    final Account after = reloaded();
    assertNotNull(after);
    assertTrue("precondition: the retired name itself must survive the reload",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));
    assertNotNull("precondition: deletion must have kept the pin",
        after.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));

    assertFalse("after a reload the retired entry has lost the address it was retired from, so "
            + "re-adding the same person at the same pinned address raises the duplicate warning "
            + "the fix exists to suppress",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));
  }

  /** The same name at a different address must still warn - the fix must not overshoot. */
  @Test
  public void thesameNameAtAdifferentAddressStillWarnsAfterAreload() throws Exception {
    pinAndThenDeleteBob();
    reloaded();

    assertTrue("an attacker offering the deleted name at its own address must still warn",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones",
            ProtocolAddresses.of("attacker-uuid", 9)));
  }

  /**
   * EVERY address a name was deleted from survives the reload, not just the first.
   *
   * <p>One entry now carries the whole set, and a loader reading a fixed three elements would keep
   * the oldest and drop the rest on the next {@code setInputView} — which is every time the
   * keyboard is raised. The in-memory set would have been a nicety and the suppression would have
   * gone back to answering for one address, silently, exactly the way the address itself was
   * dropped before this file existed.
   */
  @Test
  public void everyAddressOnAretiredNameSurvivesAreload() throws Exception {
    pinAndThenDeleteBob();
    final SignalProtocolAddress second = ProtocolAddresses.of("attacker-uuid", 9);

    final Account before = SignalProtocolMain.getInstance().getAccount();
    before.retireDisplayName("Bob", "Jones", ProtocolAddresses.key(second));
    assertEquals("fixture: both deletions share one entry", 1,
        before.getRetiredDisplayNames().size());
    assertEquals("fixture: that entry holds both addresses", 4,
        before.getRetiredDisplayNames().getFirst().length);
    storeTheAccount(before);

    final Account after = reloaded();
    assertEquals("both addresses must come back, or the set is in-memory only and the reload "
            + "quietly re-widens the warning", 4,
        after.getRetiredDisplayNames().getFirst().length);

    assertNotNull("precondition: the pin at the first address still stands",
        after.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
    assertTrue("a name deleted at two addresses warns at either of them, and that must survive "
            + "the reload too",
        SignalProtocolMain.hasContactWithSameDisplayName("Bob", "Jones", peerAddress));
  }

  /** Writes the account through a helper holding this test's key. */
  private void storeTheAccount(final Account account) {
    new StorageHelper(context, (c, hasExistingData) -> new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    }).storeAllInformationInSharedPreferences(account);
  }

  /**
   * De-duplication is by (name, address), so losing the address also loses the de-duplication.
   *
   * <p>{@code retireDisplayName} removes a prior entry only when {@code entry.length > 2}. Reloaded
   * entries are length 2, so nothing is ever removed and each delete-and-re-add cycle appends
   * another copy — which is precisely what {@code repeatedDeletionsOfOneNameDoNotEvictAnother} was
   * written to prevent, since the list is bounded and the oldest entry is the one an attacker wants
   * evicted.
   */
  @Test
  public void repeatedlyDeletingOneNameStillDoesNotGrowTheListAfterAreload() throws Exception {
    pinAndThenDeleteBob();
    final Account after = reloaded();

    assertEquals("precondition: one deletion, one entry", 1,
        after.getRetiredDisplayNames().size());

    for (int i = 0; i < 20; i++) {
      // The key production writes, which is now the FULL address. Passing the address name here
      // would be re-retiring a DIFFERENT address, and appending would be the correct answer to it.
      after.retireDisplayName("Bob", "Jones", ProtocolAddresses.key(peerAddress));
    }

    assertEquals("re-retiring one name at one address must not consume the bound: repeating it "
            + "evicts the name an attacker would reuse", 1,
        after.getRetiredDisplayNames().size());
  }
}
