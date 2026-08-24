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
 * Trust decisions must reach disk, not just memory.
 *
 * <p>Every other test of this class runs {@code initialize(null)}, which leaves
 * {@code mStorageHelper} null - so every
 * {@code storeAllAccountInformationInSharedPreferences()} call inside verify, dismiss, reject,
 * delete and the send rollback is a silent no-op. The trust LOGIC is well covered; the half that
 * decides whether the result survives the keyboard being dismissed was executed by nothing, and a
 * mutation deleting those persist calls survived the whole suite.
 *
 * <p>That gap matters here more than it would elsewhere. {@code reloadAccount} runs on every
 * {@code setInputView}, so anything held only in memory is gone the next time the user raises the
 * keyboard - which is exactly the interval an attacker is working in. The display-tag secret and
 * the retired display names both had this failure, and both took a review round to find.
 */
@RunWith(RobolectricTestRunner.class)
public class TrustPersistsTest {

  private Context context;
  private SecretKey key;
  private SignalProtocolAddress peerAddress;

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
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    key = newKey();

    // A working in-process box standing in for the Keystore-backed one, which has no JVM
    // implementation - a real context alone would not make the persisted half reachable.
    final CryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> box));

    SignalProtocolMain.testIsRunning = true;

    // Both peer bundles are minted BEFORE the victim exists.
    //
    // Not tidiness: once the victim's account is on disk, initialize(null) no longer creates a
    // fresh identity - it takes the "protocol data already exists" branch and reloads the victim,
    // so what came back was the victim's own bundle. The attacker's bundle has to be made while
    // there is nothing stored to reload.
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    peerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(null);
    attackerBundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.initialize(context);
  }

  private String peerBundle;
  private String attackerBundle;

  @After
  public void tearDown() {
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
  }

  /** Pins a peer and returns the contact, with everything persisted. */
  private Contact pinnedPeer() throws Exception {
    final Account me = SignalProtocolMain.getInstance().getAccount();
    assertNotNull("precondition: the account must load with a real storage helper", me);

    assertTrue(SignalProtocolMain.processPreKeyResponseMessage(
        EnvelopeCodec.fromWire(peerBundle), peerAddress));

    final Contact contact = new Contact("Bob", "Jones", peerAddress.getName(),
        peerAddress.getDeviceId(), false);
    final ArrayList<Contact> contacts = new ArrayList<>();
    contacts.add(contact);
    me.setContactList(contacts);
    return contact;
  }

  /** Reload from disk the way setInputView does, and hand back what came out. */
  private Account reloaded() {
    SignalProtocolMain.reloadAccount(context);
    return SignalProtocolMain.getInstance().getAccount();
  }

  @Test
  public void averifiedBadgeSurvivesTheKeyboardBeingDismissed() throws Exception {
    final Contact contact = pinnedPeer();
    assertTrue(SignalProtocolMain.verifyContact(contact));

    final Account after = reloaded();
    assertNotNull("the account must reload", after);
    assertEquals("the contact must still be there", 1, after.getContactList().size());
    assertTrue("a verification held only in memory is gone the next time the keyboard is raised",
        after.getContactList().get(0).isVerified());
  }

  @Test
  public void adeletedContactStaysDeletedAndItsNameStaysRetired() throws Exception {
    final Contact contact = pinnedPeer();
    SignalProtocolMain.getInstance().getAccount().setContactList(
        new ArrayList<>(java.util.List.of(contact)));
    SignalProtocolMain.removeContactFromContactListAndProtocol(contact);

    final Account after = reloaded();
    assertEquals("the deletion must survive", 0, after.getContactList().size());
    assertTrue("and the retired name with it - it is the only warning left once the contact is "
            + "gone, and reloadAccount runs on every setInputView",
        SignalProtocolMain.hasRetiredDisplayName("Bob", "Jones"));
  }

  /** A substitution recorded in memory must still be pending after a reload. */
  @Test
  public void apendingIdentityChangeSurvivesAreload() throws Exception {
    pinnedPeer();

    assertFalse("the attacker's bundle must be refused",
        SignalProtocolMain.importOutOfBandKeyBundle(attackerBundle, peerAddress));
    assertTrue(SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));

    reloaded();
    assertTrue("a pending change held only in memory would clear itself every time the user put "
            + "the keyboard away", SignalProtocolMain.hasUnacceptedIdentityChange(peerAddress));
  }

  /** And the pinned key itself, which is the thing everything else rests on. */
  @Test
  public void thepinnedKeySurvivesAreload() throws Exception {
    pinnedPeer();
    final var pinned = SignalProtocolMain.getInstance().getAccount()
        .getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress);
    assertNotNull(pinned);

    final Account after = reloaded();
    assertEquals("the pin must be the same key after a reload", pinned,
        after.getSignalProtocolStore().getIdentityKeyStore().getIdentity(peerAddress));
  }
}
