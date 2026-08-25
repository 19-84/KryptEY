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

/**
 * A pin created by processing a bundle must still be there after the next raise.
 *
 * <p>The write that records it was one of thirteen {@code storeAllAccountInformationInSharedPreferences}
 * call sites a sweep found could be deleted with the whole suite staying green. This is the one with
 * the sharpest consequence, so it is closed first of those remaining.
 *
 * <p>Trust-on-first-use is only "first" if the app remembers the first. {@code reloadAccount} runs on
 * every {@code setInputView} — a theme flip, night mode, a rotation the messenger can provoke — and
 * replaces the account with what is on disk. If the pin never got there, the store has no identity
 * for that address afterwards, so the *next* bundle for it is a clean first sighting and is accepted
 * silently. No warning fires, because nothing was displaced: from the app's point of view this is
 * simply the first key it has ever seen for that contact.
 *
 * <p>That is the substitution the whole trust model exists to refuse, reached without breaking any of
 * it — the attacker does not have to defeat the pin, only to wait for the pin to be forgotten and
 * arrive second.
 */
@RunWith(RobolectricTestRunner.class)
public class PinnedIdentitySurvivesTheNextRaiseTest {

  private Context context;
  private SignalProtocolAddress peerAddress;

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

  private IdentityKey pinned() {
    return SignalProtocolMain.getInstance().getAccount().getSignalProtocolStore()
        .getIdentityKeyStore().getIdentity(peerAddress);
  }

  @Test
  public void apinFromAprocessedBundleIsStillThereAfterTheNextRaise() throws Exception {
    // A peer with no storage of its own, standing in for whoever supplied the bundle.
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    final String bundle = SignalProtocolMain.exportOwnKeyBundle();

    SignalProtocolMain.getInstance().setAccount(victim);
    raiseTheKeyboardAgain();

    SignalProtocolMain.addContact("Real", "Peer", peerAddress.getName(),
        peerAddress.getDeviceId());
    assertTrue("precondition: the bundle must pin",
        SignalProtocolMain.processPreKeyResponseMessage(EnvelopeCodec.fromWire(bundle),
            peerAddress));
    final IdentityKey beforeTheRaise = pinned();
    assertNotNull("precondition: an identity must be pinned in memory", beforeTheRaise);

    raiseTheKeyboardAgain();

    assertNotNull("the pin must survive the next raise. Without it the store has no identity for "
            + "this address, so the NEXT bundle to arrive is a clean first sighting and is accepted "
            + "in silence - no warning fires because nothing was displaced. The attacker does not "
            + "have to defeat trust-on-first-use, only to arrive after it was forgotten",
        pinned());
    assertEquals("and it must be the same key, not merely some key", beforeTheRaise, pinned());
  }
}
