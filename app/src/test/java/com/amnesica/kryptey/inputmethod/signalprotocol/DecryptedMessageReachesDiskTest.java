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
 * A decrypted message must be on disk before the next raise, not only in memory.
 *
 * <p>{@code decrypt}'s final write was the last of the thirteen unasserted durable writes. An earlier
 * attempt at it failed for reasons that were all harness: this suite drives two "devices" through one
 * singleton and one {@code SharedPreferences}, and making the peer send twice ran into artefacts of
 * that rather than anything about the code — including one that was correct behaviour, since libsignal
 * keeps sending {@code PreKeySignalMessage}s against a consumed one-time pre-key until it gets a reply.
 *
 * <p>So this asks the smallest question that still bites: one message, one reload, is it in the log?
 * {@code reloadAccount} runs on every {@code setInputView}, so without that write the conversation the
 * user can scroll back through is not the conversation that happened — and the message log is the only
 * record they have that a message ever arrived.
 */
@RunWith(RobolectricTestRunner.class)
public class DecryptedMessageReachesDiskTest {

  private Context context;
  private SignalProtocolAddress peerAddress;

  @Before
  public void setUp() {
    SignalProtocolMain.resetForTest();
    context = RuntimeEnvironment.getApplication();
    final SharedPreferences preferences =
        context.getSharedPreferences("protocol", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> new GcmCryptoBox() {
          @Override
          protected SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }
        }));
    SignalProtocolMain.testIsRunning = true;
    assertTrue("precondition: the account must be created and persisted",
        SignalProtocolMain.initialize(context));
  }

  @After
  public void tearDown() {
    SignalProtocolMain.setStorageHelperFactoryForTest(null);
    SignalProtocolMain.resetForTest();
  }

  @Test
  public void adecryptedMessageIsInTheLogAfterTheNextRaise() throws Exception {
    final Account victim = SignalProtocolMain.getInstance().getAccount();
    final SignalProtocolAddress victimAddress = ProtocolAddresses.of(
        victim.getSignalProtocolAddress().getName(), victim.getDeviceId());
    // Exported with the victim's storage live, so the private halves it mints reach disk.
    final String victimBundle = SignalProtocolMain.exportOwnKeyBundle();

    // The peer, with no storage of its own.
    SignalProtocolMain.getInstance().setStorageHelperForTest(null);
    SignalProtocolMain.initialize(null);
    final Account peer = SignalProtocolMain.getInstance().getAccount();
    peerAddress = ProtocolAddresses.of(peer.getSignalProtocolAddress().getName(),
        peer.getDeviceId());
    assertTrue("precondition: the peer must build a session from the victim's bundle",
        SignalProtocolMain.processPreKeyResponseMessage(
            EnvelopeCodec.fromWire(victimBundle), victimAddress));
    final String wire = EnvelopeCodec.toWire(
        SignalProtocolMain.encryptMessage("a message that must be kept", victimAddress));

    // Back to the victim, storage restored.
    SignalProtocolMain.getInstance().setAccount(victim);
    SignalProtocolMain.setStorageHelperFactoryForTest(
        ctx -> new StorageHelper(ctx, (c, hasExistingData) -> new GcmCryptoBox() {
          @Override
          protected SecretKey key() {
            return new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
          }
        }));
    SignalProtocolMain.reloadAccount(context);

    SignalProtocolMain.addContact("Real", "Peer", peerAddress.getName(), peerAddress.getDeviceId());
    assertEquals("precondition: the message must decrypt", "a message that must be kept",
        SignalProtocolMain.decryptMessage(EnvelopeCodec.fromWire(wire), peerAddress));

    SignalProtocolMain.reloadAccount(context);
    assertNotNull("precondition: the account must reload",
        SignalProtocolMain.getInstance().getAccount());

    Contact stored = null;
    for (final Contact contact : SignalProtocolMain.getContactList()) {
      if (contact.getSignalProtocolAddress().equals(peerAddress)) stored = contact;
    }
    assertNotNull("precondition: the contact must still be there", stored);

    final java.util.List<StorageMessage> log =
        SignalProtocolMain.getUnencryptedMessagesList(stored);
    assertNotNull("the chat log must survive the raise", log);
    boolean found = false;
    for (final StorageMessage message : log) {
      if ("a message that must be kept".equals(message.getUnencryptedMessage())) found = true;
    }
    assertTrue("the decrypted message must be on disk before the next raise. reloadAccount runs on "
        + "every setInputView, so without that write the conversation the user can scroll back "
        + "through is not the conversation that happened - and the log is the only record they have "
        + "that the message ever arrived", found);
  }
}
