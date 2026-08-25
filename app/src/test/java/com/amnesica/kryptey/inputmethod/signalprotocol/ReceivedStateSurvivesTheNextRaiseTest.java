package com.amnesica.kryptey.inputmethod.signalprotocol;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.signal.libsignal.protocol.IdentityKey;
import com.amnesica.kryptey.inputmethod.signalprotocol.chat.StorageMessage;


/**
 * What a received message leaves on disk, and what an added contact does.
 *
 * <p>Two of the four remaining unasserted durable writes, checked for duplication first — the last
 * one examined turned out to be a duplicate of its neighbour, which is why the survivor list is no
 * longer read as a coverage claim without that check. These two are not: {@code
 * createAndAddContactToList} holds the only write for an added contact, and {@code decrypt}'s final
 * persist is distinct from the substitution arm above it.
 *
 * <p>Only the contact half is asserted here. The receive-side persist — the one that saves the
 * ratchet advance a decrypt performs — is still open, and the obstacle is the harness rather than the
 * property: this suite drives two "devices" through one singleton and one {@code SharedPreferences},
 * swapping the storage helper in and out, and every attempt to make the peer send twice ran into
 * artefacts of that arrangement rather than anything about the code. One of them was instructive —
 * without a reply, libsignal correctly keeps sending {@code PreKeySignalMessage}s against a one-time
 * pre-key the receiver has already consumed, so the failure was right and the scenario was wrong.
 *
 * <p>Left undone rather than forced. A test that passes because its scenario was bent until it did
 * is worth less than none, and this branch has already recorded three of those.
 */
@RunWith(RobolectricTestRunner.class)
public class ReceivedStateSurvivesTheNextRaiseTest {

  private Context context;
  private SignalProtocolAddress peerAddress;
  private Account victim;
  private Account peer;
  /** Captured while the victim's storage is live, so the Kyber private half reaches disk. */
  private String victimBundle;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    final SecretKey key = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
    final CryptoBox box = new GcmCryptoBox() {
      @Override
      protected SecretKey key() {
        return key;
      }
    };
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> box));
    SignalProtocolMain.testIsRunning = true;
    assertTrue("precondition: the account must be created and persisted",
        SignalProtocolMain.initialize(context));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
  }

  private void raiseTheKeyboardAgain() {
    SignalProtocolMain.reloadAccount(context);
    assertNotNull("precondition: the account must reload from disk",
        SignalProtocolMain.getInstance().getAccount());
  }

  private SignalProtocolAddress victimAddress() {
    return ProtocolAddresses.of(victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
  }

  /** A peer with no storage of its own, which the victim then pins. */
  private void createThePeerAndPinIt() throws Exception {
    victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(victim);
    raiseTheKeyboardAgain();
    // Exported HERE, with the victim's storage helper installed. Taken with the helper nulled, the
    // Kyber pre-key this mints stays in memory and the next reload drops it - which is the defect a
    // review round found in production, reproduced by accident in a test harness.
    victimBundle = SignalProtocolMain.exportOwnKeyBundle();
    SignalProtocolMain.addContact("Real", "Peer", peerAddress.getName(), peerAddress.getDeviceId());
    assertTrue("precondition: the bundle must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bundle),
            peerAddress));
  }

  /**
   * A contact the user added is still there after the next raise.
   *
   * <p>Added on its own, with no bundle processed afterwards — which is both the minimal scenario
   * and the only one that tests anything. The first version of this pinned a bundle immediately
   * after adding, and the persist inside that path wrote the contact too, so deleting the contact's
   * own write changed nothing observable and the mutant survived. That is the third duplicated
   * write found on this branch by trying to kill one.
   *
   * <p>Losing it is not a cosmetic loss: the contact list is how a recipient is chosen, and an
   * address with no row is one the strip treats as unknown — it opens the add-contact screen, where
   * the next bundle for that address is pinned as a first sighting.
   */
  @Test
  public void acontactAddedByTheUserIsStillThereAfterTheNextRaise() throws Exception {
    final SignalProtocolAddress added = ProtocolAddresses.of(
        "a1b2c3d4-0000-4000-8000-000000000001", 3);
    SignalProtocolMain.addContact("Added", "Contact", added.getName(), added.getDeviceId());

    boolean beforeTheRaise = false;
    for (final Contact contact : SignalProtocolMain.getContactList()) {
      if (contact.getSignalProtocolAddress().equals(added)) beforeTheRaise = true;
    }
    assertTrue("precondition: the contact must be in the list in memory", beforeTheRaise);

    raiseTheKeyboardAgain();

    boolean found = false;
    for (final Contact contact : SignalProtocolMain.getContactList()) {
      if (contact.getSignalProtocolAddress().equals(added)) found = true;
    }
    assertTrue("the contact must survive the next raise. reloadAccount replaces the list with what "
        + "is on disk, and an address with no row is one the strip treats as unknown - it opens the "
        + "add-contact screen, and the next bundle for that address is pinned as a first sighting",
        found);
  }
}
